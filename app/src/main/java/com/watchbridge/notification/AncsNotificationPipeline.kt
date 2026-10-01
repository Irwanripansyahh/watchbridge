package com.watchbridge.notification

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.watchbridge.ancs.AncsActionHandler
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent
import com.watchbridge.ancs.AncsSessionManager
import com.watchbridge.ble.BleConnectionManager
import com.watchbridge.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the full notification pipeline with filtering, DND respect,
 * and category-based settings.
 */
class AncsNotificationPipeline(
    private val context: Context,
    private val connectionManager: BleConnectionManager,
    private val sessionManager: AncsSessionManager,
    private val renderer: NotificationRenderer,
    private val callHandler: CallNotificationHandler,
    private val appNameResolver: AppNameResolver,
    private val appIconResolver: AppIconResolver,
    private val settings: SettingsManager
) {

    companion object {
        private const val TAG = "AncsNotifPipeline"
        private const val REQUEST_DELAY_MS = 100L

        /** How long a first-time app's notification waits for its icon to download. */
        private const val ICON_WAIT_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val attributeRequestQueue = Channel<AncsNotificationEvent>(Channel.BUFFERED)
    private val pendingEvents = ConcurrentHashMap<UInt, AncsNotificationEvent>()

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun start() {
        scope.launch {
            sessionManager.events.collect { event ->
                handleEvent(event)
            }
        }

        scope.launch {
            for (event in attributeRequestQueue) {
                requestAttributes(event)
                delay(REQUEST_DELAY_MS)
            }
        }

        NotificationActionReceiver.onAction = { uid, actionId ->
            performAction(uid, actionId)
        }

        Log.i(TAG, "Notification pipeline started")
    }

    fun handleDataSourceResponse(result: Any?) {
        when (result) {
            is AncsAttributeParser.NotificationAttributes -> {
                onNotificationAttributesReceived(result)
            }
            is AncsAttributeParser.AppAttributes -> {
                onAppAttributesReceived(result)
            }
        }
    }

    private fun handleEvent(event: AncsNotificationEvent) {
        when {
            event.isAdded || event.isModified -> {
                // Apply filters
                if (!shouldShowNotification(event)) {
                    Log.d(TAG, "Filtered out: uid=${event.notificationUid} cat=${event.categoryName}")
                    return
                }

                // Show placeholder immediately (except calls)
                if (!callHandler.isCallCategory(event.categoryId)) {
                    renderer.showBasicNotification(event)
                }
                pendingEvents[event.notificationUid] = event
                attributeRequestQueue.trySend(event)
            }
            event.isRemoved -> {
                pendingEvents.remove(event.notificationUid)
                renderer.cancelNotification(event.notificationUid)

                when (event.categoryId) {
                    AncsConstants.CATEGORY_INCOMING_CALL -> callHandler.cancelIncomingCall()
                    AncsConstants.CATEGORY_ACTIVE_CALL -> callHandler.cancelActiveCall()
                }
            }
        }
    }

    /**
     * Check if a notification should be shown based on current settings.
     */
    private fun shouldShowNotification(event: AncsNotificationEvent): Boolean {
        // Respect DND / Theater mode
        if (settings.respectDnd.value && isDndActive()) {
            // Always let calls through in DND
            if (event.categoryId != AncsConstants.CATEGORY_INCOMING_CALL) {
                return false
            }
        }

        // Filter silent notifications
        if (event.isSilent && !settings.showSilent.value) {
            return false
        }

        // Filter pre-existing notifications
        if (event.isPreExisting && !settings.showPreExisting.value) {
            return false
        }

        // Check category filter
        if (!settings.isCategoryEnabled(event.categoryId)) {
            return false
        }

        return true
    }

    private fun isDndActive(): Boolean {
        val filter = notificationManager.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun requestAttributes(event: AncsNotificationEvent) {
        val command = AncsActionHandler.buildGetNotificationAttributes(
            notificationUid = event.notificationUid
        )
        connectionManager.writeControlPoint(command)
        Log.d(TAG, "Requested attributes for uid=${event.notificationUid}")
    }

    private fun onNotificationAttributesReceived(attrs: AncsAttributeParser.NotificationAttributes) {
        val event = pendingEvents[attrs.notificationUid]
        if (event == null) {
            Log.w(TAG, "Received attributes for unknown uid=${attrs.notificationUid}")
            return
        }

        val appId = attrs.appIdentifier
        val appDisplayName = if (appId != null) {
            if (appNameResolver.needsRequest(appId)) {
                appNameResolver.markPending(appId)
                val cmd = AncsActionHandler.buildGetAppAttributes(appId)
                connectionManager.writeControlPoint(cmd)
            }
            appNameResolver.getDisplayName(appId)
        } else null

        if (callHandler.isCallCategory(event.categoryId)) {
            when (event.categoryId) {
                AncsConstants.CATEGORY_INCOMING_CALL ->
                    callHandler.showIncomingCall(event, attrs)
                AncsConstants.CATEGORY_ACTIVE_CALL ->
                    callHandler.showActiveCall(event, attrs)
            }
            pendingEvents.remove(attrs.notificationUid)
            Log.d(TAG, "Rendered call uid=${attrs.notificationUid}: ${attrs.title}")
            return
        }

        scope.launch {
            val appIcon = appId?.let { resolveAppIcon(it) }

            // iOS may have removed or modified the notification while we waited for the icon
            if (!pendingEvents.remove(attrs.notificationUid, event)) return@launch

            renderer.showFullNotification(event, attrs, appDisplayName, appIcon)
            Log.d(TAG, "Rendered notification uid=${attrs.notificationUid}: ${attrs.title}")
        }
    }

    /**
     * Cached icons come back immediately. An app we haven't seen before gets a short grace
     * period to download; if that runs out we show the notification without an icon and the
     * download keeps going in the background for next time.
     */
    private suspend fun resolveAppIcon(appId: String): Bitmap? =
        withTimeoutOrNull(ICON_WAIT_MS) { appIconResolver.getIcon(appId) }

    private fun onAppAttributesReceived(attrs: AncsAttributeParser.AppAttributes) {
        val displayName = attrs.displayName
        if (displayName != null) {
            appNameResolver.cacheAppName(attrs.appIdentifier, displayName)
            Log.d(TAG, "Resolved app: ${attrs.appIdentifier} -> $displayName")
        }
    }

    private fun performAction(notificationUid: UInt, actionId: Byte) {
        val command = AncsActionHandler.buildPerformAction(notificationUid, actionId)
        connectionManager.writeControlPoint(command)
        Log.i(TAG, "Performed action $actionId on uid=$notificationUid")
    }

    fun onDisconnected() {
        pendingEvents.clear()
        renderer.cancelAll()
        callHandler.cancelIncomingCall()
        callHandler.cancelActiveCall()
    }
}
