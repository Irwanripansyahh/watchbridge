package com.watchbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchbridge.MainActivity
import com.watchbridge.R
import com.watchbridge.WatchBridgeApp
import com.watchbridge.ancs.AncsSessionManager
import com.watchbridge.ble.BleAdvertiser
import com.watchbridge.ble.BleConnectionManager
import com.watchbridge.ble.BondManager
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.notification.AncsNotificationPipeline
import com.watchbridge.notification.AppNameResolver
import com.watchbridge.notification.CallNotificationHandler
import com.watchbridge.notification.NotificationActionReceiver
import com.watchbridge.notification.NotificationRenderer
import com.watchbridge.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the entire BLE + ANCS lifecycle.
 */
class WatchBridgeService : Service() {

    companion object {
        private const val TAG = "WatchBridgeService"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "WatchBridge::BleConnection"

        const val ACTION_START = "com.watchbridge.service.START"
        const val ACTION_STOP = "com.watchbridge.service.STOP"
        const val ACTION_CONNECT_BONDED = "com.watchbridge.service.CONNECT_BONDED"
        const val ACTION_START_ADVERTISING = "com.watchbridge.service.START_ADVERTISING"

        var connectionManager: BleConnectionManager? = null
            private set
        var advertiser: BleAdvertiser? = null
            private set
        var sessionManager: AncsSessionManager? = null
            private set
        var stateMachine: ConnectionStateMachine? = null
            private set
        var bondManager: BondManager? = null
            private set
        var pipeline: AncsNotificationPipeline? = null
            private set
        var settingsManager: SettingsManager? = null
            private set
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var actionReceiver: NotificationActionReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        val settings = SettingsManager(this)
        val connMgr = BleConnectionManager(this)
        val bondMgr = BondManager(this)
        val adv = BleAdvertiser(this)
        val sessMgr = AncsSessionManager()
        val sm = ConnectionStateMachine(connMgr, bondMgr)

        val renderer = NotificationRenderer(this)
        val callHandler = CallNotificationHandler(this)
        val appNameResolver = AppNameResolver(this)
        val pipe = AncsNotificationPipeline(
            context = this,
            connectionManager = connMgr,
            sessionManager = sessMgr,
            renderer = renderer,
            callHandler = callHandler,
            appNameResolver = appNameResolver,
            settings = settings
        )

        connMgr.setAncsCallbacks(
            onNotificationSource = { data ->
                sessMgr.processNotificationEvent(data)
            },
            onDataSource = { data ->
                val result = sessMgr.processDataSourceFragment(data)
                if (result != null) {
                    pipe.handleDataSourceResponse(result)
                }
            }
        )

        sm.onDisconnected = {
            pipe.onDisconnected()
            sessMgr.onDisconnected()
            updateNotification("Reconnecting...")
        }
        sm.onSessionReset = {
            sessMgr.resetSession()
        }

        connectionManager = connMgr
        sessionManager = sessMgr
        stateMachine = sm
        bondManager = bondMgr
        pipeline = pipe
        settingsManager = settings
        advertiser = adv

        bondMgr.register()

        val receiver = NotificationActionReceiver()
        registerReceiver(
            receiver,
            IntentFilter("com.watchbridge.ACTION_PERFORM"),
            RECEIVER_NOT_EXPORTED
        )
        actionReceiver = receiver

        pipe.start()
        sm.start()

        serviceScope.launch {
            sm.state.collect { state ->
                val statusText = when (state) {
                    ConnectionStateMachine.State.IDLE -> "Not connected"
                    ConnectionStateMachine.State.ADVERTISING -> "Waiting for iPhone..."
                    ConnectionStateMachine.State.CONNECTING -> "Connecting..."
                    ConnectionStateMachine.State.CONNECTED -> "Bonding & discovering..."
                    ConnectionStateMachine.State.READY -> "Connected to iPhone"
                    ConnectionStateMachine.State.DISCONNECTED -> "Disconnected"
                    ConnectionStateMachine.State.WAITING_TO_RECONNECT -> "Waiting to reconnect..."
                    ConnectionStateMachine.State.RECONNECTING -> "Reconnecting..."
                    ConnectionStateMachine.State.FAILED -> "Connection failed"
                }
                updateNotification(statusText)

                when (state) {
                    ConnectionStateMachine.State.READY ->
                        sessMgr.updateState(AncsSessionManager.SessionState.ACTIVE)
                    ConnectionStateMachine.State.RECONNECTING ->
                        sessMgr.updateState(AncsSessionManager.SessionState.RECONNECTING)
                    ConnectionStateMachine.State.DISCONNECTED ->
                        sessMgr.updateState(AncsSessionManager.SessionState.DISCONNECTED)
                    else -> {}
                }
            }
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        when (intent?.action) {
            ACTION_CONNECT_BONDED -> {
                stateMachine?.autoConnectToBonded()
            }
            ACTION_START_ADVERTISING -> {
                val adv = advertiser
                val sm = stateMachine
                if (adv != null && sm != null) {
                    sm.startAdvertisingForPairing(adv)
                } else {
                    Log.e(TAG, "Cannot start advertising: advertiser=$adv, stateMachine=$sm")
                }
            }
            ACTION_STOP -> {
                stateMachine?.disconnect()
                advertiser?.close()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")

        advertiser?.close()
        stateMachine?.disconnect()
        bondManager?.unregister()

        try {
            actionReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) { }

        releaseWakeLock()
        serviceScope.cancel()

        connectionManager = null
        sessionManager = null
        stateMachine = null
        bondManager = null
        pipeline = null
        settingsManager = null
        advertiser = null
        isRunning = false

        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String = "Active"): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WatchBridgeApp.SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
