package com.watchbridge.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.watchbridge.R
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent
import com.watchbridge.call.WatchCalls
import com.watchbridge.ui.IncomingCallActivity
import com.watchbridge.ui.OngoingCallActivity

/**
 * Special notification handling for incoming calls and active calls.
 * Incoming calls get full-screen-style high-priority notifications with accept/reject.
 * Active calls get an ongoing notification with hang-up (best-effort, iOS 13+).
 */
class CallNotificationHandler(
    private val context: Context,
    private val notificationRenderer: NotificationRenderer
) {

    companion object {
        private const val TAG = "CallNotificationHandler"
        private const val INCOMING_CALL_NOTIF_ID = 900

        /** How long the watch's own phone app gets to start ringing before the fallback. */
        private const val WATCH_RING_WAIT_MS = 3000L
        private const val ACTIVE_CALL_NOTIF_ID = 901
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending "the watch's phone app never rang" fallback for the current call. */
    private var watchRingFallback: Runnable? = null

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Show an incoming call notification using CallStyle for the native Wear OS call UI.
     */
    fun showIncomingCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        val callerName = attrs?.title ?: "Unknown Caller"

        if (WatchCalls.isEnabled(context)) {
            handOverToWatchPhoneApp(event, callerName)
            return
        }

        notificationRenderer.wakeScreen()

        val caller = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val declineIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_NEGATIVE)
        val answerIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_POSITIVE)

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, INCOMING_CALL_NOTIF_ID,
            Intent(context, IncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("call_uid", event.notificationUid.toInt())
                putExtra("caller_name", callerName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent)
            )

        notificationManager.notify(INCOMING_CALL_NOTIF_ID, builder.build())
        Log.i(TAG, "Showing incoming call from: $callerName")
    }

    /**
     * "Answer calls on watch": the watch's own phone app rings and carries the audio, so
     * WatchBridge shows no call screen at all (and doesn't buzz or wake the screen either).
     * Only if that app never starts ringing — the watch isn't connected as the phone's call
     * audio — is the call shown, as a plain notification, so it isn't missed.
     */
    private fun handOverToWatchPhoneApp(event: AncsNotificationEvent, callerName: String) {
        Log.i(TAG, "Incoming call from $callerName: leaving it to the watch's phone app")
        cancelWatchRingFallback()
        val fallback = Runnable {
            watchRingFallback = null
            if (WatchCalls.isRinging(context) || WatchCalls.isInCall(context)) return@Runnable
            Log.i(TAG, "The watch's phone app didn't ring; showing the call as a notification")
            showCallNotificationFallback(event, callerName)
        }
        watchRingFallback = fallback
        mainHandler.postDelayed(fallback, WATCH_RING_WAIT_MS)
    }

    /** A plain notification (no full-screen call screen): answer on the phone, or decline. */
    private fun showCallNotificationFallback(event: AncsNotificationEvent, callerName: String) {
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_call_accept, "Answer on phone",
                createCallActionIntent(event.notificationUid, AncsConstants.ACTION_POSITIVE)
            )
            .addAction(
                R.drawable.ic_call_decline, "Decline",
                createCallActionIntent(event.notificationUid, AncsConstants.ACTION_NEGATIVE)
            )
        notificationManager.notify(INCOMING_CALL_NOTIF_ID, builder.build())
    }

    private fun cancelWatchRingFallback() {
        watchRingFallback?.let { mainHandler.removeCallbacks(it) }
        watchRingFallback = null
    }

    /**
     * Show an active call notification using CallStyle for the native Wear OS call UI.
     */
    fun showActiveCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        // The watch's own phone app has the call (and its in-call screen): no screen of ours
        if (WatchCalls.isEnabled(context)) {
            Log.i(TAG, "Active call left to the watch's phone app")
            return
        }
        notificationRenderer.wakeScreen()

        val callerName = attrs?.title ?: "Active Call"

        val caller = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val hangUpIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_NEGATIVE)

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, ACTIVE_CALL_NOTIF_ID,
            Intent(context, OngoingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("call_uid", event.notificationUid.toInt())
                putExtra("caller_name", callerName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(caller, hangUpIntent)
            )

        notificationManager.notify(ACTIVE_CALL_NOTIF_ID, builder.build())
        Log.i(TAG, "Showing active call: $callerName")
    }

    /**
     * Cancel the incoming call notification (called when call is answered, rejected, or ends).
     */
    fun cancelIncomingCall() {
        cancelWatchRingFallback()
        notificationManager.cancel(INCOMING_CALL_NOTIF_ID)
        context.sendBroadcast(
            Intent("com.watchbridge.CALL_DISMISSED").setPackage(context.packageName)
        )
    }

    /**
     * Cancel the active call notification.
     */
    fun cancelActiveCall() {
        notificationManager.cancel(ACTIVE_CALL_NOTIF_ID)
        context.sendBroadcast(
            Intent("com.watchbridge.CALL_DISMISSED").setPackage(context.packageName)
        )
    }

    fun isCallCategory(categoryId: Byte): Boolean =
        categoryId == AncsConstants.CATEGORY_INCOMING_CALL ||
            categoryId == AncsConstants.CATEGORY_ACTIVE_CALL

    private fun createCallActionIntent(
        notificationUid: UInt,
        actionId: Byte
    ): PendingIntent {
        val intent = Intent("com.watchbridge.ACTION_PERFORM").apply {
            setPackage(context.packageName)
            putExtra("notification_uid", notificationUid.toInt())
            putExtra("action_id", actionId)
            putExtra("is_call", true)
        }
        val requestCode = (notificationUid.toInt() * 10) + actionId + 100
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
