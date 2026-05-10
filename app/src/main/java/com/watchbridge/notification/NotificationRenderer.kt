package com.watchbridge.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchbridge.R
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent
import com.watchbridge.ui.NotificationPopupActivity

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
            .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
            .setContentTitle(event.categoryName)
            .setContentText("Loading...")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Don't buzz again when we update with attributes

        builder.priority = NotificationCompat.PRIORITY_HIGH

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
            .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
            .setContentTitle(title)
            .setAutoCancel(true)

        if (body.isNotEmpty()) {
            builder.setContentText(body)
            // Use BigTextStyle for longer messages
            if (body.length > 40) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        }

        builder.priority = NotificationCompat.PRIORITY_HIGH
        builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)

        // Full-screen intent: launches NotificationPopupActivity over the watch face when
        // the screen is off/locked (the common case on a watch). FSI is the only reliable
        // way to launch an activity from a foreground service on Android 14+ — direct
        // startActivity() is blocked by Background Activity Launch (BAL) restrictions
        // unless the FGS type is one of phoneCall/mediaPlayback/voip/etc.
        // (connectedDevice is not on that list.)
        if (!isCallCategory(event.categoryId) && !event.isSilent) {
            val (appName, sender, popupBody) = buildPopupFields(event, attrs, appDisplayName)
            val popupIntent = Intent(context, NotificationPopupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("app_name", appName)
                putExtra("sender", sender)
                putExtra("body", popupBody)
                putExtra("category_id", event.categoryId)
                putExtra("notification_uid", event.notificationUid.toInt())
            }
            val popupPi = PendingIntent.getActivity(
                context, notifId, popupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(popupPi, true)
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

    private fun isCallCategory(categoryId: Byte): Boolean =
        categoryId == AncsConstants.CATEGORY_INCOMING_CALL ||
            categoryId == AncsConstants.CATEGORY_ACTIVE_CALL

    private fun buildPopupFields(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?
    ): Triple<String, String, String> {
        val appName = appDisplayName ?: event.categoryName
        val sender = attrs.title?.takeIf { it.isNotEmpty() } ?: appName
        val body = attrs.message ?: ""
        return Triple(appName, sender, body)
    }

    /**
     * Remove a notification when ANCS sends a Removed event.
     */
    fun cancelNotification(notificationUid: UInt) {
        notificationManager.cancel(uidToNotifId(notificationUid))
        // Dismiss popup if it's still showing
        context.sendBroadcast(
            Intent(NotificationPopupActivity.BROADCAST_POPUP_DISMISSED).apply {
                setPackage(context.packageName)
                putExtra("notification_uid", notificationUid.toInt())
            }
        )
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

    @Suppress("DEPRECATION")
    internal fun wakeScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wasInteractive = pm.isInteractive
        Log.d(TAG, "wakeScreen() called — screen interactive=$wasInteractive")

        try {
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "watchbridge:notification_wake"
            )
            wakeLock.acquire(3000L)
            Log.d(TAG, "wakeScreen() wake lock acquired — screen now interactive=${pm.isInteractive}")
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen() wake lock failed", e)
        }

        // Vibrate — short buzz
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen() vibrate failed", e)
        }
    }

    private fun uidToNotifId(uid: UInt): Int =
        (uid.toInt() and 0x7FFFFFFF) + NOTIFICATION_ID_OFFSET
}
