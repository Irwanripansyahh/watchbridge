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

        /** Ask again if iOS hasn't answered an attribute request by then. */
        private const val ATTRIBUTE_TIMEOUT_MS = 4000L
        private const val MAX_ATTRIBUTE_RETRIES = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Unlimited: a burst of notifications (e.g. pre-existing ones on connect) must never be
    // dropped, or they'd never be requested
    private val attributeRequestQueue = Channel<AncsNotificationEvent>(Channel.UNLIMITED)
    private val pendingEvents = ConcurrentHashMap<UInt, AncsNotificationEvent>()

    /** UIDs whose attributes haven't arrived yet → retries used so far. */
    private val awaitingAttributes = ConcurrentHashMap<UInt, Int>()

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

                // Nothing is shown until the content arrives (usually well under a second):
                // a "Loading..." placeholder could get stuck if the content never came.
                pendingEvents[event.notificationUid] = event
                awaitingAttributes[event.notificationUid] = 0
                attributeRequestQueue.trySend(event)
            }
            event.isRemoved -> {
                pendingEvents.remove(event.notificationUid)
                awaitingAttributes.remove(event.notificationUid)
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

        // A lost or garbled response would mean the notification never shows up: ask again
        scope.launch {
            delay(ATTRIBUTE_TIMEOUT_MS)
            val uid = event.notificationUid
            // Removed, superseded by a newer event, or already rendered
            if (pendingEvents[uid] !== event) return@launch
            val retries = awaitingAttributes[uid] ?: return@launch

            if (retries < MAX_ATTRIBUTE_RETRIES) {
                Log.w(TAG, "No attributes for uid=$uid after ${ATTRIBUTE_TIMEOUT_MS}ms, retrying")
                awaitingAttributes[uid] = retries + 1
                attributeRequestQueue.trySend(event)
            } else {
                Log.e(TAG, "Giving up on attributes for uid=$uid")
                awaitingAttributes.remove(uid)
                if (!pendingEvents.remove(uid, event)) return@launch
                when (event.categoryId) {
                    AncsConstants.CATEGORY_INCOMING_CALL -> callHandler.showIncomingCall(event, null)
                    AncsConstants.CATEGORY_ACTIVE_CALL -> callHandler.showActiveCall(event, null)
                    else -> renderer.showUnavailableNotification(event)
                }
            }
        }
    }

    private fun onNotificationAttributesReceived(attrs: AncsAttributeParser.NotificationAttributes) {
        awaitingAttributes.remove(attrs.notificationUid)
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

    /**
     * Notifications stay on the watch until the user clears them (here or on the iPhone),
     * even while the iPhone is out of range. Only call screens go: their state is unknown now.
     */
    fun onDisconnected() {
        pendingEvents.clear()
        awaitingAttributes.clear()
        callHandler.cancelIncomingCall()
        callHandler.cancelActiveCall()
    }
}
