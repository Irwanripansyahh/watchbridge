package com.watchbridge.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchbridge.R
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent

/**
 * Special notification handling for incoming calls and active calls.
 * Incoming calls get full-screen-style high-priority notifications with accept/reject.
 * Active calls get an ongoing notification with hang-up (best-effort, iOS 13+).
 */
class CallNotificationHandler(private val context: Context) {

    companion object {
        private const val TAG = "CallNotificationHandler"
        private const val INCOMING_CALL_NOTIF_ID = 900
        private const val ACTIVE_CALL_NOTIF_ID = 901
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Show an incoming call notification with accept/reject actions.
     */
    fun showIncomingCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        val callerName = attrs?.title ?: "Unknown Caller"

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Incoming Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))

        // Accept action
        if (event.hasPositiveAction) {
            val label = attrs?.positiveActionLabel ?: "Accept"
            val acceptIntent = createCallActionIntent(
                event.notificationUid,
                AncsConstants.ACTION_POSITIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher, label, acceptIntent
                ).build()
            )
        }

        // Reject action
        if (event.hasNegativeAction) {
            val label = attrs?.negativeActionLabel ?: "Decline"
            val rejectIntent = createCallActionIntent(
                event.notificationUid,
                AncsConstants.ACTION_NEGATIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher, label, rejectIntent
                ).build()
            )
        }

        notificationManager.notify(INCOMING_CALL_NOTIF_ID, builder.build())
        Log.i(TAG, "Showing incoming call from: $callerName")
    }

    /**
     * Show an active call notification with hang-up action (best-effort, Category 12).
     */
    fun showActiveCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        val callerName = attrs?.title ?: "Active Call"

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("On Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)

        // Hang-up action (negative action on active call)
        if (event.hasNegativeAction) {
            val label = attrs?.negativeActionLabel ?: "Hang Up"
            val hangUpIntent = createCallActionIntent(
                event.notificationUid,
                AncsConstants.ACTION_NEGATIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher, label, hangUpIntent
                ).build()
            )
        }

        notificationManager.notify(ACTIVE_CALL_NOTIF_ID, builder.build())
        Log.i(TAG, "Showing active call: $callerName")
    }

    /**
     * Cancel the incoming call notification (called when call is answered, rejected, or ends).
     */
    fun cancelIncomingCall() {
        notificationManager.cancel(INCOMING_CALL_NOTIF_ID)
    }

    /**
     * Cancel the active call notification.
     */
    fun cancelActiveCall() {
        notificationManager.cancel(ACTIVE_CALL_NOTIF_ID)
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
