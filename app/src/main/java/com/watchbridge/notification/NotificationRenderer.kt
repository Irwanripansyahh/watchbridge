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
 * Renders ANCS notifications as Wear OS native notifications.
 * Maps ANCS categories to appropriate channels, icons, and priorities.
 * Handles notification creation, update, and removal.
 */
class NotificationRenderer(private val context: Context) {

    companion object {
        private const val TAG = "NotificationRenderer"
        // Offset notification IDs to avoid collision with service notification (ID=1)
        private const val NOTIFICATION_ID_OFFSET = 1000
        private const val GROUP_KEY_PREFIX = "watchbridge_"
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * Show a basic notification from a Notification Source event (before attributes are fetched).
     * Used as a placeholder until full attributes arrive.
     */
    fun showBasicNotification(event: AncsNotificationEvent) {
        if (event.isSilent) return

        val channelId = NotificationChannels.channelForCategory(event.categoryId)
        val notifId = uidToNotifId(event.notificationUid)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(event.categoryName)
            .setContentText("Loading...")
            .setAutoCancel(true)
            .setGroup(groupKeyForCategory(event.categoryId))
            .setOnlyAlertOnce(true) // Don't buzz again when we update with attributes

        if (event.isImportant) {
            builder.priority = NotificationCompat.PRIORITY_HIGH
        }

        notificationManager.notify(notifId, builder.build())
    }

    /**
     * Update a notification with full attributes fetched from ANCS Data Source.
     */
    fun showFullNotification(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?
    ) {
        val channelId = NotificationChannels.channelForCategory(event.categoryId)
        val notifId = uidToNotifId(event.notificationUid)

        val title = buildTitle(event, attrs, appDisplayName)
        val body = buildBody(attrs)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setGroup(groupKeyForCategory(event.categoryId))

        if (body.isNotEmpty()) {
            builder.setContentText(body)
            // Use BigTextStyle for longer messages
            if (body.length > 40) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        }

        if (event.isImportant) {
            builder.priority = NotificationCompat.PRIORITY_HIGH
        }

        // Add dismiss action via broadcast
        val dismissIntent = createActionIntent(
            event.notificationUid,
            AncsConstants.ACTION_NEGATIVE,
            "dismiss"
        )
        builder.setDeleteIntent(dismissIntent)

        // Add positive/negative action buttons if available
        if (event.hasPositiveAction) {
            val label = attrs.positiveActionLabel ?: "Accept"
            val intent = createActionIntent(
                event.notificationUid,
                AncsConstants.ACTION_POSITIVE,
                "positive"
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher, label, intent
                ).build()
            )
        }

        if (event.hasNegativeAction) {
            val label = attrs.negativeActionLabel ?: "Dismiss"
            val intent = createActionIntent(
                event.notificationUid,
                AncsConstants.ACTION_NEGATIVE,
                "negative"
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher, label, intent
                ).build()
            )
        }

        notificationManager.notify(notifId, builder.build())
        Log.d(TAG, "Showed notification uid=${event.notificationUid}: $title")
    }

    /**
     * Remove a notification when ANCS sends a Removed event.
     */
    fun cancelNotification(notificationUid: UInt) {
        notificationManager.cancel(uidToNotifId(notificationUid))
    }

    /**
     * Cancel all WatchBridge notifications (e.g., on disconnect).
     */
    fun cancelAll() {
        notificationManager.cancelAll()
    }

    private fun buildTitle(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?
    ): String {
        val title = attrs.title
        val subtitle = attrs.subtitle

        return when {
            // For calls, use category name as prefix
            event.categoryId == AncsConstants.CATEGORY_INCOMING_CALL ->
                "Incoming Call: ${title ?: "Unknown"}"
            event.categoryId == AncsConstants.CATEGORY_MISSED_CALL ->
                "Missed Call: ${title ?: "Unknown"}"
            // For other notifications, use app name + title
            !title.isNullOrEmpty() && !subtitle.isNullOrEmpty() ->
                "$title - $subtitle"
            !title.isNullOrEmpty() -> title
            appDisplayName != null -> appDisplayName
            else -> event.categoryName
        }
    }

    private fun buildBody(attrs: AncsAttributeParser.NotificationAttributes): String {
        return attrs.message ?: ""
    }

    private fun createActionIntent(
        notificationUid: UInt,
        actionId: Byte,
        actionType: String
    ): PendingIntent {
        val intent = Intent("com.watchbridge.ACTION_PERFORM").apply {
            setPackage(context.packageName)
            putExtra("notification_uid", notificationUid.toInt())
            putExtra("action_id", actionId)
        }
        val requestCode = (notificationUid.toInt() * 10) + actionId
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun uidToNotifId(uid: UInt): Int =
        (uid.toInt() and 0x7FFFFFFF) + NOTIFICATION_ID_OFFSET

    private fun groupKeyForCategory(categoryId: Byte): String =
        GROUP_KEY_PREFIX + AncsConstants.categoryName(categoryId).replace(" ", "_").lowercase()
}
