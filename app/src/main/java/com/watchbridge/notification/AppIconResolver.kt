package com.watchbridge.notification

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.ConnectivityManager
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone

/**
 * Resolves iOS bundle identifiers to the app's real icon.
 *
 * ANCS only tells us the bundle ID ("net.whatsapp.WhatsApp"), never the icon. Popular apps
 * have their icon shipped in the APK (assets/app_icons, from scripts/fetch_app_icons.py),
 * so they work offline from the very first notification. Any other app is looked up on the
 * App Store (iTunes Lookup API) and its artwork downloaded.
 *
 * Downloaded icons are cached on disk so each app is only fetched once. Lookups that fail
 * are remembered for a while so an offline watch doesn't hit the network on every notification.
 */
class AppIconResolver(private val context: Context) {

    companion object {
        private const val TAG = "AppIconResolver"
        private const val PREFS_NAME = "watchbridge_app_icon_misses"
        /** Downloaded icons, under filesDir. */
        private const val ICON_DIR = "app_icons"

        /** Icons shipped in the APK, under assets/. */
        private const val BUNDLED_ICON_DIR = "app_icons"

        private const val LOOKUP_URL = "https://itunes.apple.com/lookup"
        private const val ICON_SIZE_PX = 128
        private const val NETWORK_TIMEOUT_MS = 5000

        /** Retry an app the App Store doesn't know about after a day. */
        private const val NOT_FOUND_RETRY_MS = 24 * 60 * 60 * 1000L

        /** Retry after a network error (e.g. Wi-Fi was off) much sooner. */
        private const val NETWORK_ERROR_RETRY_MS = 10 * 60 * 1000L

        /** "…/100x100bb.jpg" → lets us ask the CDN for a PNG at the size we want. */
        private val ARTWORK_SIZE_SUFFIX = Regex("""/\d+x\d+bb\.(jpg|png|webp)$""")
    }

    private val iconDir = File(context.filesDir, ICON_DIR)
    private val missPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = LruCache<String, Bitmap>(40)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Icons shipped in the APK, keyed by lowercase bundle ID → asset file name. */
    private val bundledIcons: Map<String, String> by lazy {
        context.assets.list(BUNDLED_ICON_DIR).orEmpty()
            .associateBy { it.substringBeforeLast('.').lowercase(Locale.ROOT) }
    }

    /** Downloads in progress, so a burst of notifications from one app fetches its icon once. */
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    /**
     * Get the icon for a bundle identifier, downloading it on first use.
     * Returns null if the icon is unavailable (offline, not on the App Store, ...).
     *
     * Cancelling the caller (e.g. via a timeout) does not cancel the download, so the
     * icon is still cached for the next notification from that app.
     */
    suspend fun getIcon(bundleId: String): Bitmap? {
        memoryCache.get(bundleId)?.let { return it }

        withContext(Dispatchers.IO) {
            readBundled(bundleId) ?: readFromDisk(bundleId)
        }?.let { return it }

        if (System.currentTimeMillis() < missPrefs.getLong(bundleId, 0L)) return null
        if (!isNetworkAvailable()) return null

        val download = synchronized(inFlight) {
            inFlight.getOrPut(bundleId) {
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        download(bundleId)
                    } finally {
                        synchronized(inFlight) { inFlight.remove(bundleId) }
                    }
                }
            }
        }
        return download.await()
    }

    private fun readBundled(bundleId: String): Bitmap? {
        val asset = bundledIcons[bundleId.lowercase(Locale.ROOT)] ?: return null
        val raw = try {
            context.assets.open("$BUNDLED_ICON_DIR/$asset").use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Can't read bundled icon $asset", e)
            null
        }
        if (raw == null) return null

        val icon = roundCorners(scaleToIconSize(raw))
        memoryCache.put(bundleId, icon)
        return icon
    }

    private fun readFromDisk(bundleId: String): Bitmap? {
        val file = iconFile(bundleId)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        memoryCache.put(bundleId, bitmap)
        return bitmap
    }

    private fun download(bundleId: String): Bitmap? {
        try {
            for (country in storefronts()) {
                val artworkUrl = lookupArtworkUrl(bundleId, country) ?: continue
                val raw = downloadBitmap(artworkUrl) ?: continue
                val icon = roundCorners(raw)

                iconDir.mkdirs()
                iconFile(bundleId).outputStream().use {
                    icon.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                memoryCache.put(bundleId, icon)
                missPrefs.edit().remove(bundleId).apply()
                Log.d(TAG, "Fetched icon for $bundleId (store=$country)")
                return icon
            }
            Log.d(TAG, "No App Store icon for $bundleId")
            rememberMiss(bundleId, NOT_FOUND_RETRY_MS)
        } catch (e: IOException) {
            Log.w(TAG, "Icon download failed for $bundleId: ${e.message}")
            rememberMiss(bundleId, NETWORK_ERROR_RETRY_MS)
        } catch (e: Exception) {
            // Unexpected response (bad JSON, undecodable image). Never let this
            // propagate: the notification must still be shown without an icon.
            Log.w(TAG, "Icon lookup failed for $bundleId", e)
            rememberMiss(bundleId, NOT_FOUND_RETRY_MS)
        }
        return null
    }

    /**
     * Regional apps (banks, e-wallets, ...) are often only listed in their home store,
     * so try the watch's locale and time zone region before falling back to the US store.
     */
    private fun storefronts(): List<String> {
        val localeCountry = Locale.getDefault().country
        val timeZoneCountry = try {
            android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id)
        } catch (_: IllegalArgumentException) {
            null
        }
        return listOfNotNull(localeCountry, timeZoneCountry, "us")
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.length == 2 && it.all(Char::isLetter) }
            .distinct()
    }

    private fun lookupArtworkUrl(bundleId: String, country: String): String? {
        val query = "bundleId=${URLEncoder.encode(bundleId, "UTF-8")}&country=$country"
        val json = httpGet("$LOOKUP_URL?$query")?.toString(Charsets.UTF_8) ?: return null
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val app = results.getJSONObject(0)
        val artworkUrl = app.optString("artworkUrl100")
            .ifEmpty { app.optString("artworkUrl60") }
            .ifEmpty { return null }
        return artworkUrl.replace(ARTWORK_SIZE_SUFFIX, "/${ICON_SIZE_PX}x${ICON_SIZE_PX}bb.png")
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val bytes = httpGet(url) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return scaleToIconSize(bitmap)
    }

    private fun scaleToIconSize(bitmap: Bitmap): Bitmap {
        if (bitmap.width == ICON_SIZE_PX && bitmap.height == ICON_SIZE_PX) return bitmap
        return Bitmap.createScaledBitmap(bitmap, ICON_SIZE_PX, ICON_SIZE_PX, true)
    }

    /** Returns the response body, or null for a non-200 response. Throws on network errors. */
    private fun httpGet(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** App Store artwork is a full-bleed square; mask it to the iOS rounded-square shape. */
    private fun roundCorners(source: Bitmap): Bitmap {
        val size = ICON_SIZE_PX.toFloat()
        val output = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size * 0.225f

        canvas.drawRoundRect(RectF(0f, 0f, size, size), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork != null
    }

    private fun rememberMiss(bundleId: String, retryAfterMs: Long) {
        missPrefs.edit()
            .putLong(bundleId, System.currentTimeMillis() + retryAfterMs)
            .apply()
    }

    private fun iconFile(bundleId: String): File =
        File(iconDir, bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
}
