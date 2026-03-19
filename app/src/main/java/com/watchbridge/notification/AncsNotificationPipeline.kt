package com.watchbridge.notification

import android.util.Log
import com.watchbridge.ancs.AncsActionHandler
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent
import com.watchbridge.ancs.AncsSessionManager
import com.watchbridge.ble.BleConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates the full notification pipeline:
 * 1. Receives Notification Source events from AncsSessionManager
 * 2. Queues GetNotificationAttributes requests (rate-limited)
 * 3. Processes Data Source responses (attribute fragments)
 * 4. Resolves app names via AppNameResolver
 * 5. Renders notifications via NotificationRenderer / CallNotificationHandler
 * 6. Handles notification removal
 */
class AncsNotificationPipeline(
    private val connectionManager: BleConnectionManager,
    private val sessionManager: AncsSessionManager,
    private val renderer: NotificationRenderer,
    private val callHandler: CallNotificationHandler,
    private val appNameResolver: AppNameResolver
) {

    companion object {
        private const val TAG = "AncsNotifPipeline"
        private const val REQUEST_DELAY_MS = 100L // Rate limit between Control Point writes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Queue of notification UIDs waiting for attribute fetch. */
    private val attributeRequestQueue = Channel<AncsNotificationEvent>(Channel.BUFFERED)

    /** Map of UID -> event for pending attribute responses. */
    private val pendingEvents = mutableMapOf<UInt, AncsNotificationEvent>()

    /**
     * Start the pipeline. Call after ANCS session is active.
     */
    fun start() {
        // Process Notification Source events
        scope.launch {
            sessionManager.events.collect { event ->
                handleEvent(event)
            }
        }

        // Process attribute request queue with rate limiting
        scope.launch {
            for (event in attributeRequestQueue) {
                requestAttributes(event)
                delay(REQUEST_DELAY_MS)
            }
        }

        // Wire up notification action callbacks
        NotificationActionReceiver.onAction = { uid, actionId ->
            performAction(uid, actionId)
        }

        Log.i(TAG, "Notification pipeline started")
    }

    /**
     * Handle a Data Source fragment. Called by the session manager's data callback.
     */
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
                // Show placeholder immediately
                if (!callHandler.isCallCategory(event.categoryId)) {
                    renderer.showBasicNotification(event)
                }
                // Queue attribute fetch
                pendingEvents[event.notificationUid] = event
                attributeRequestQueue.trySend(event)
            }
            event.isRemoved -> {
                pendingEvents.remove(event.notificationUid)
                renderer.cancelNotification(event.notificationUid)

                // Also cancel call notifications if this was a call
                when (event.categoryId) {
                    AncsConstants.CATEGORY_INCOMING_CALL -> callHandler.cancelIncomingCall()
                    AncsConstants.CATEGORY_ACTIVE_CALL -> callHandler.cancelActiveCall()
                }
            }
        }
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

        // Resolve app name
        val appId = attrs.appIdentifier
        val appDisplayName = if (appId != null) {
            if (appNameResolver.needsRequest(appId)) {
                // Request app display name
                appNameResolver.markPending(appId)
                val cmd = AncsActionHandler.buildGetAppAttributes(appId)
                connectionManager.writeControlPoint(cmd)
            }
            appNameResolver.getDisplayName(appId)
        } else null

        // Render the full notification
        if (callHandler.isCallCategory(event.categoryId)) {
            when (event.categoryId) {
                AncsConstants.CATEGORY_INCOMING_CALL ->
                    callHandler.showIncomingCall(event, attrs)
                AncsConstants.CATEGORY_ACTIVE_CALL ->
                    callHandler.showActiveCall(event, attrs)
            }
        } else {
            renderer.showFullNotification(event, attrs, appDisplayName)
        }

        pendingEvents.remove(attrs.notificationUid)
        Log.d(TAG, "Rendered notification uid=${attrs.notificationUid}: ${attrs.title}")
    }

    private fun onAppAttributesReceived(attrs: AncsAttributeParser.AppAttributes) {
        val displayName = attrs.displayName
        if (displayName != null) {
            appNameResolver.cacheAppName(attrs.appIdentifier, displayName)
            Log.d(TAG, "Resolved app: ${attrs.appIdentifier} -> $displayName")
        }
    }

    /**
     * Perform an ANCS action (accept/reject/dismiss) from a notification button.
     */
    private fun performAction(notificationUid: UInt, actionId: Byte) {
        val command = AncsActionHandler.buildPerformAction(notificationUid, actionId)
        connectionManager.writeControlPoint(command)
        Log.i(TAG, "Performed action $actionId on uid=$notificationUid")
    }

    /**
     * Clean up on disconnect.
     */
    fun onDisconnected() {
        pendingEvents.clear()
        renderer.cancelAll()
        callHandler.cancelIncomingCall()
        callHandler.cancelActiveCall()
    }
}
