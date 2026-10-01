package com.watchbridge.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates from this fork's GitHub Releases: check the latest release,
 * download its APK and hand it to the system installer.
 *
 * Installing needs the "Install unknown apps" permission. Wear OS often has no screen for
 * it, so the install scripts grant it over adb ([installPermissionAdbCommand]).
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    /** owner/name of the GitHub repo whose releases carry the APK. */
    const val GITHUB_REPO = "Irwanripansyahh/watchbridge"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private const val NETWORK_TIMEOUT_MS = 15_000
    private const val FAST_NETWORK_TIMEOUT_MS = 10_000

    internal const val ACTION_INSTALL_STATUS = "com.watchbridge.update.INSTALL_STATUS"

    data class Release(val version: String, val apkUrl: String, val apkSizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data object Installing : State
        /** WatchBridge isn't allowed to install apps yet. */
        data class NeedsInstallPermission(val release: Release) : State
        /** [release] is set when retrying should download it again. */
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    fun checkForUpdate(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        job = scope.launch {
            _state.value = State.Checking
            _state.value = try {
                val release = withFastNetwork(appContext) { network -> fetchLatestRelease(network) }
                val installed = installedVersion(appContext)
                when {
                    release == null -> State.Failed("No release with an APK on GitHub yet")
                    isNewerVersion(release.version, installed) -> State.Available(release)
                    else -> State.UpToDate(installed)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed", e)
                State.Failed("Can't reach GitHub. Connect the watch to Wi-Fi and try again.")
            } catch (e: JSONException) {
                Log.w(TAG, "Unexpected GitHub response", e)
                State.Failed("Unexpected response from GitHub")
            }
        }
    }

    fun downloadAndInstall(context: Context, release: Release) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        if (!canInstallUpdates(appContext)) {
            _state.value = State.NeedsInstallPermission(release)
            return
        }

        job = scope.launch {
            try {
                val apk = withFastNetwork(appContext) { network -> download(appContext, release, network) }
                val problem = checkApk(appContext, apk)
                if (problem != null) {
                    apk.delete()
                    _state.value = State.Failed(problem)
                    return@launch
                }
                _state.value = State.Installing
                install(appContext, apk)
            } catch (e: IOException) {
                Log.w(TAG, "Update download/install failed", e)
                _state.value = State.Failed("Download failed. Check the watch's Wi-Fi and try again.", release)
            }
        }
    }

    /** Whether WatchBridge may install apps ("Install unknown apps"), needed for updates. */
    fun canInstallUpdates(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Opens the system "Install unknown apps" screen for WatchBridge. Returns false if the
     * watch doesn't have one (common on Wear OS); see [installPermissionAdbCommand].
     */
    fun openInstallPermissionSettings(context: Context): Boolean =
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    /** Grants "Install unknown apps" from a computer when the watch has no screen for it. */
    fun installPermissionAdbCommand(context: Context): String =
        "adb shell appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow"

    private fun fetchLatestRelease(network: Network?): Release? {
        val connection = open(LATEST_RELEASE_URL, network)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WatchBridge")
            // No release published yet
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP ${connection.responseCode}")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return null

            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                apkUrl = apk.getString("browser_download_url"),
                apkSizeBytes = apk.optLong("size")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun download(context: Context, release: Release, network: Network?): File {
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "watchbridge-update.apk")
        val connection = open(release.apkUrl, network)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes

            _state.value = State.Downloading(release, 0f)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Downloading(release, percent / 100f)
                        }
                    }
                }
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Returns why the APK can't be installed over this app, or null if it can. Catching a
     * signing key mismatch here gives a clear message instead of a generic installer error.
     */
    @Suppress("DEPRECATION")
    private fun checkApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val update = pm.getPackageArchiveInfo(apk.path, flags)
            ?: return "The downloaded file isn't a valid APK"
        if (update.packageName != context.packageName) {
            return "The release APK is for a different app (${update.packageName})"
        }

        val installedSigners = pm.getPackageInfo(context.packageName, flags)
            .signingInfo?.apkContentsSigners?.toSet()
        val updateSigners = update.signingInfo?.apkContentsSigners?.toSet()
        if (installedSigners != null && updateSigners != null && installedSigners != updateSigners) {
            return "This WatchBridge is signed with a different key (e.g. a debug build). " +
                "Uninstall it and install the release APK once; updates work from then on."
        }
        return null
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once WatchBridge installed itself, later updates can skip the confirmation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("watchbridge.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }

            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
            // Mutable: the installer adds the result extras to this intent
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, statusIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutable
            )
            session.commit(pendingIntent.intentSender)
        }
        Log.i(TAG, "Install session $sessionId committed")
    }

    /** Result of the install session, delivered by [UpdateInstallReceiver]. */
    internal fun onInstallStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's "Install update?" confirmation
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually not seen: the update restarts the app
                Log.i(TAG, "Update installed")
                _state.value = State.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _state.value = State.Idle
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed: status=$status $message")
                _state.value = State.Failed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage on the watch"
                        PackageInstaller.STATUS_FAILURE_CONFLICT,
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "This version can't be installed over the current one. Uninstall and install the release APK."
                        else -> "Install failed${message?.let { ": $it" } ?: ""}"
                    }
                )
            }
        }
    }

    /**
     * Wear OS keeps Wi-Fi off and may only have a slow Bluetooth link. Asking for an unmetered
     * network brings Wi-Fi up (if the watch knows one) while [block] runs; if none shows up,
     * fall back to whatever network is active.
     */
    private suspend fun <T> withFastNetwork(context: Context, block: suspend (Network?) -> T): T {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val available = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onUnavailable() {
                available.complete(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()

        cm.requestNetwork(request, callback, FAST_NETWORK_TIMEOUT_MS)
        try {
            return block(available.await())
        } finally {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Already released after onUnavailable
            }
        }
    }

    private fun open(url: String, network: Network?): HttpURLConnection {
        val connection = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        return connection
    }
}
