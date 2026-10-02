package com.watchbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchbridge.MainActivity
import com.watchbridge.R
import com.watchbridge.WatchBridgeApp
import com.watchbridge.ams.AmsMediaManager
import com.watchbridge.ams.MediaSessionBridge
import com.watchbridge.ancs.AncsSessionManager
import com.watchbridge.ble.BleAdvertiser
import com.watchbridge.ble.BleConnectionManager
import com.watchbridge.ble.BondManager
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.notification.AncsNotificationPipeline
import com.watchbridge.notification.AppIconResolver
import com.watchbridge.notification.AppNameResolver
import com.watchbridge.notification.CallNotificationHandler
import com.watchbridge.notification.NotificationActionReceiver
import com.watchbridge.notification.NotificationRenderer
import com.watchbridge.settings.SettingsManager
import com.watchbridge.tile.ConnectionTileService
import com.watchbridge.tile.MediaTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the entire BLE + ANCS lifecycle.
 */
class WatchBridgeService : Service() {

    companion object {
        private const val TAG = "WatchBridgeService"
        private const val NOTIFICATION_ID = 1
        private const val BLUETOOTH_OFF_NOTIFICATION_ID = 2
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
        var mediaManager: AmsMediaManager? = null
            private set
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var actionReceiver: NotificationActionReceiver? = null
    private var mediaSessionBridge: MediaSessionBridge? = null

    /** Pauses reconnecting while the watch's Bluetooth is off, resumes when it's back on. */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> stateMachine?.onBluetoothStateChanged(enabled = false)
                BluetoothAdapter.STATE_ON -> stateMachine?.onBluetoothStateChanged(enabled = true)
            }
        }
    }

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

        val renderer = NotificationRenderer(this, settings)
        val callHandler = CallNotificationHandler(this, renderer)
        val appNameResolver = AppNameResolver(this)
        val appIconResolver = AppIconResolver(this)
        val pipe = AncsNotificationPipeline(
            context = this,
            connectionManager = connMgr,
            sessionManager = sessMgr,
            renderer = renderer,
            callHandler = callHandler,
            appNameResolver = appNameResolver,
            appIconResolver = appIconResolver,
            settings = settings
        )

        val media = AmsMediaManager(connMgr)
        connMgr.setAmsCallbacks(
            onReady = media::onSubscribed,
            onRemoteCommands = media::onRemoteCommandsUpdate,
            onEntityUpdate = media::onEntityUpdate
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
            media.reset()
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
        mediaManager = media
        advertiser = adv

        bondMgr.register()

        val receiver = NotificationActionReceiver()
        registerReceiver(
            receiver,
            IntentFilter(NotificationActionReceiver.ACTION_PERFORM),
            RECEIVER_NOT_EXPORTED
        )
        actionReceiver = receiver

        pipe.start()
        sm.start()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // Bluetooth may already be off when the service starts (e.g. at boot)
        sm.onBluetoothStateChanged(enabled = bondMgr.isBluetoothEnabled())

        serviceScope.launch {
            sm.state.collect { state ->
                val statusText = when (state) {
                    ConnectionStateMachine.State.IDLE -> "Not connected"
                    ConnectionStateMachine.State.ADVERTISING -> "Waiting for phone..."
                    ConnectionStateMachine.State.CONNECTING -> "Connecting..."
                    ConnectionStateMachine.State.CONNECTED -> "Bonding & discovering..."
                    ConnectionStateMachine.State.READY -> "Connected to phone"
                    ConnectionStateMachine.State.DISCONNECTED -> "Disconnected"
                    ConnectionStateMachine.State.WAITING_TO_RECONNECT -> "Waiting to reconnect..."
                    ConnectionStateMachine.State.RECONNECTING -> "Reconnecting..."
                    ConnectionStateMachine.State.WAITING_FOR_PHONE -> "Waiting for phone (out of range)"
                    ConnectionStateMachine.State.BLUETOOTH_OFF -> "Bluetooth is off"
                    ConnectionStateMachine.State.FAILED -> "Not paired"
                }
                updateNotification(statusText)
                ConnectionTileService.requestUpdate(this@WatchBridgeService)
                showBluetoothOffAlert(state == ConnectionStateMachine.State.BLUETOOTH_OFF)

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

        // The phone's now playing, as a media session the watch's own media controls can use
        val sessionBridge = MediaSessionBridge(this, media)
        mediaSessionBridge = sessionBridge
        serviceScope.launch(Dispatchers.Main) {
            media.state.collect { sessionBridge.update(it) }
        }

        // The connection tile shows the iPhone's battery
        serviceScope.launch {
            connMgr.phoneBattery.collect { ConnectionTileService.requestUpdate(this@WatchBridgeService) }
        }
        serviceScope.launch {
            connMgr.phoneDeviceName.collect { ConnectionTileService.requestUpdate(this@WatchBridgeService) }
        }

        // Refresh the media tile when what it shows changes (not on every position tick)
        serviceScope.launch {
            media.state
                .map {
                    listOf(
                        it.available, it.playerName, it.title, it.artist, it.isPlaying,
                        it.supportedCommands, it.volume?.let { volume -> (volume * 100).toInt() }
                    )
                }
                .distinctUntilChanged()
                .collect { MediaTileService.requestUpdate(this@WatchBridgeService) }
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
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) { }
        showBluetoothOffAlert(false)

        releaseWakeLock()
        serviceScope.cancel()
        mediaSessionBridge?.release()
        mediaSessionBridge = null

        connectionManager = null
        sessionManager = null
        stateMachine = null
        bondManager = null
        pipeline = null
        settingsManager = null
        mediaManager = null
        advertiser = null
        isRunning = false
        ConnectionTileService.requestUpdate(this)
        MediaTileService.requestUpdate(this)

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

    /**
     * A notification while the watch's Bluetooth is off, since nothing arrives from the
     * iPhone meanwhile. Posted once per time Bluetooth goes off; removed when it's back on.
     */
    private fun showBluetoothOffAlert(show: Boolean) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!show) {
            nm.cancel(BLUETOOTH_OFF_NOTIFICATION_ID)
            return
        }

        val openApp = PendingIntent.getActivity(
            this, BLUETOOTH_OFF_NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, WatchBridgeApp.CONNECTION_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_disabled)
            .setContentTitle("Bluetooth is off")
            .setContentText("Turn on Bluetooth to get your phone's notifications")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(BLUETOOTH_OFF_NOTIFICATION_ID, notification)
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
