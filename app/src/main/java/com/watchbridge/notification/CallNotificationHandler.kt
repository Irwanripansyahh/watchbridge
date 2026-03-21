package com.watchbridge.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.watchbridge.R
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent

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
        private const val ACTIVE_CALL_NOTIF_ID = 901
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Show an incoming call notification using CallStyle for the native Wear OS call UI.
     */
    fun showIncomingCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        notificationRenderer.wakeScreen()

        val callerName = attrs?.title ?: "Unknown Caller"

        val caller = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val declineIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_NEGATIVE)
        val answerIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_POSITIVE)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent)
            )

        notificationManager.notify(INCOMING_CALL_NOTIF_ID, builder.build())
        Log.i(TAG, "Showing incoming call from: $callerName")
    }

    /**
     * Show an active call notification using CallStyle for the native Wear OS call UI.
     */
    fun showActiveCall(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes?
    ) {
        notificationRenderer.wakeScreen()

        val callerName = attrs?.title ?: "Active Call"

        val caller = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val hangUpIntent = createCallActionIntent(event.notificationUid, AncsConstants.ACTION_NEGATIVE)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)
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
