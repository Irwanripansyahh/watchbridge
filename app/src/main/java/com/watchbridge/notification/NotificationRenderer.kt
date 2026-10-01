package com.watchbridge.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.watchbridge.R
import com.watchbridge.ancs.AncsAttributeParser
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ancs.AncsNotificationEvent
import com.watchbridge.settings.SettingsManager

/**
 * Renders ANCS notifications as Wear OS native notifications.
 * Maps ANCS categories to appropriate channels, icons, and priorities.
 * Handles notification creation, update, and removal.
 *
 * - Notifications are grouped per iPhone app (bundle ID), so ten WhatsApp messages stack
 *   into one WhatsApp group instead of ten loose cards.
 * - Chats (Social category with a sender and a message) use MessagingStyle: every message
 *   of one conversation shares a single notification in the native Wear OS chat layout.
 */
class NotificationRenderer(
    private val context: Context,
    private val settings: SettingsManager
) {

    companion object {
        private const val TAG = "NotificationRenderer"
        // Offset notification IDs to avoid collision with service notification (ID=1)
        private const val NOTIFICATION_ID_OFFSET = 1000
        private const val GROUP_PREFIX = "ios:"

        /** MessagingStyle only keeps the latest 25 messages anyway. */
        private const val MAX_MESSAGES_SHOWN = 25
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** One chat thread; all its ANCS notifications share a single Android notification. */
    private class Conversation(
        val key: String,
        val notifId: Int,
        val group: AppGroup,
        /** Group chat name, or null for a 1:1 chat. */
        val title: String?
    ) {
        val messages = LinkedHashMap<UInt, ChatMessage>()
        lateinit var latestEvent: AncsNotificationEvent
        lateinit var latestAttrs: AncsAttributeParser.NotificationAttributes
    }

    private class ChatMessage(val sender: String, val text: String, val timestamp: Long)

    /** Notifications of one iPhone app, stacked under a group summary. */
    private class AppGroup(val key: String, val summaryId: Int) {
        /** Android notification IDs currently in the group. */
        val members = mutableSetOf<Int>()
        var appName: String = ""
        var appIcon: Bitmap? = null
        var categoryId: Byte = AncsConstants.CATEGORY_OTHER
    }

    // Called from the pipeline's coroutines and BLE callbacks, so all state is behind a lock
    private val lock = Any()
    private val groups = mutableMapOf<String, AppGroup>()
    private val conversations = mutableMapOf<String, Conversation>()
    private val uidToConversation = mutableMapOf<UInt, Conversation>()

    /** Standalone (non-chat) notifications and the app group they're in. */
    private val uidToGroup = mutableMapOf<UInt, AppGroup>()

    /**
     * Show a basic notification from a Notification Source event (before attributes are fetched).
     * Used as a placeholder until full attributes arrive.
     */
    fun showBasicNotification(event: AncsNotificationEvent) {
        // Silent: the watch should only buzz and peek once, when the real content arrives
        postPlaceholder(event, text = "Loading...", silent = true)
    }

    /**
     * The iPhone never sent this notification's content (even after retries). Still let the
     * user know something arrived, instead of leaving "Loading..." forever.
     */
    fun showUnavailableNotification(event: AncsNotificationEvent) {
        postPlaceholder(event, text = "Open your iPhone to read it", silent = false)
    }

    private fun postPlaceholder(event: AncsNotificationEvent, text: String, silent: Boolean) {
        if (event.isSilent) return

        // Under the lock, so it can't land on top of the real content rendered meanwhile
        synchronized(lock) {
            // Already showing real content (ANCS "modified" event): keep it until the update arrives
            val uid = event.notificationUid
            if (uid in uidToConversation || uid in uidToGroup) return

            val builder = NotificationCompat.Builder(context, channelFor(event.categoryId))
                .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
                .setContentTitle(event.categoryName)
                .setContentText(text)
                .setAutoCancel(true)
                .setSilent(silent)

            builder.priority = NotificationCompat.PRIORITY_HIGH

            notificationManager.notify(uidToNotifId(uid), builder.build())
        }
    }

    /**
     * Update a notification with full attributes fetched from ANCS Data Source.
     */
    fun showFullNotification(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?,
        appIcon: Bitmap?
    ) {
        synchronized(lock) {
            renderFullNotification(event, attrs, appDisplayName, appIcon)
        }
    }

    private fun renderFullNotification(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?,
        appIcon: Bitmap?
    ) {
        val uid = event.notificationUid
        val group = attrs.appIdentifier?.let { appId ->
            val group = groups.getOrPut(GROUP_PREFIX + appId) {
                AppGroup(GROUP_PREFIX + appId, stableNotifId("summary|$appId"))
            }
            group.appName = appDisplayName ?: event.categoryName
            if (appIcon != null) group.appIcon = appIcon
            group.categoryId = event.categoryId
            group
        }

        val sender = attrs.title
        val text = attrs.message
        val conversationKey = if (
            group != null &&
            event.categoryId == AncsConstants.CATEGORY_SOCIAL &&
            !sender.isNullOrEmpty() &&
            !text.isNullOrEmpty()
        ) {
            // In group chats iOS puts the sender in the title and the chat name in the subtitle
            "${group.key}|${attrs.subtitle?.takeIf { it.isNotEmpty() } ?: sender}"
        } else {
            null
        }

        // A modified notification may have moved to another conversation, or stopped being a chat
        uidToConversation[uid]?.let { existing ->
            if (existing.key != conversationKey) removeChatMessage(uid, existing)
        }
        if (conversationKey != null) {
            uidToGroup.remove(uid)?.let { leaveGroup(it, uidToNotifId(uid)) }
        }

        if (conversationKey != null && group != null && sender != null && text != null) {
            showChatMessage(event, attrs, group, conversationKey, sender, text)
        } else {
            showStandalone(event, attrs, group, appDisplayName, appIcon)
        }
        group?.let { postGroupSummary(it) }
    }

    private fun showStandalone(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        group: AppGroup?,
        appDisplayName: String?,
        appIcon: Bitmap?
    ) {
        val uid = event.notificationUid
        val notifId = uidToNotifId(uid)

        val title = buildTitle(event, attrs, appDisplayName)
        val body = buildBody(attrs)

        val builder = NotificationCompat.Builder(context, channelFor(event.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
            .setContentTitle(title)
            .setAutoCancel(true)
            .setWhen(attrs.timestampMillis ?: System.currentTimeMillis())
            .setShowWhen(true)

        // The small icon has to stay a monochrome silhouette (the system tints it),
        // so the iPhone app's real, full-color icon goes in the large icon slot.
        if (appIcon != null) {
            builder.setLargeIcon(appIcon)
        }
        if (appDisplayName != null && appDisplayName != title) {
            builder.setSubText(appDisplayName)
        }
        if (group != null) {
            builder.setGroup(group.key)
        }

        if (body.isNotEmpty()) {
            builder.setContentText(body)
            // Use BigTextStyle for longer messages
            if (body.length > 40) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        }

        builder.priority = NotificationCompat.PRIORITY_HIGH
        builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)

        val uids = listOf(uid)
        builder.setDeleteIntent(createActionIntent(notifId, "dismiss", uids, AncsConstants.ACTION_NEGATIVE))
        addActions(builder, event, attrs, notifId, positiveUids = uids, negativeUids = uids)

        notificationManager.notify(notifId, builder.build())
        if (group != null) {
            group.members += notifId
            uidToGroup[uid] = group
        }
        Log.d(TAG, "Showed notification uid=$uid: $title")
    }

    private fun showChatMessage(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        group: AppGroup,
        key: String,
        sender: String,
        text: String
    ) {
        val uid = event.notificationUid
        val conversation = conversations.getOrPut(key) {
            Conversation(
                key = key,
                notifId = stableNotifId(key),
                group = group,
                title = attrs.subtitle?.takeIf { it.isNotEmpty() }
            )
        }
        conversation.messages[uid] = ChatMessage(
            sender, text, attrs.timestampMillis ?: System.currentTimeMillis()
        )
        conversation.latestEvent = event
        conversation.latestAttrs = attrs
        uidToConversation[uid] = conversation

        // The conversation card replaces this message's "Loading..." placeholder
        notificationManager.cancel(uidToNotifId(uid))
        postConversation(conversation, alert = true)
        group.members += conversation.notifId
        Log.d(TAG, "Added uid=$uid to conversation ${conversation.key} (${conversation.messages.size} messages)")
    }

    private fun postConversation(conversation: Conversation, alert: Boolean) {
        val group = conversation.group
        val messages = conversation.messages.values
            .sortedBy { it.timestamp }
            .takeLast(MAX_MESSAGES_SHOWN)
        val latest = messages.last()

        // The app icon doubles as the sender avatar, so every chat shows which app it's from
        val avatar = group.appIcon?.let { IconCompat.createWithBitmap(it) }
        val people = mutableMapOf<String, Person>()
        fun personFor(name: String) = people.getOrPut(name) {
            Person.Builder().setName(name).setKey("${group.key}|$name").setIcon(avatar).build()
        }

        // MessagingStyle needs a "you"; iPhone notifications only ever contain incoming messages
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        conversation.title?.let {
            style.setConversationTitle(it)
            style.setGroupConversation(true)
        }
        messages.forEach { style.addMessage(it.text, it.timestamp, personFor(it.sender)) }

        val allUids = conversation.messages.keys.toList()
        val notifId = conversation.notifId
        val builder = NotificationCompat.Builder(context, channelFor(group.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(AncsConstants.CATEGORY_SOCIAL))
            .setStyle(style)
            // Shown by surfaces that don't render MessagingStyle
            .setContentTitle(conversation.title ?: latest.sender)
            .setContentText(latest.text)
            .setLargeIcon(group.appIcon)
            .setSubText(group.appName)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .setGroup(group.key)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Re-posting after a message was removed on the iPhone shouldn't buzz again
            .setOnlyAlertOnce(!alert)
            // Swiping the conversation away clears all its messages on the iPhone
            .setDeleteIntent(
                createActionIntent(notifId, "dismiss", allUids, AncsConstants.ACTION_NEGATIVE)
            )

        val latestUid = conversation.messages.entries.maxBy { it.value.timestamp }.key
        addActions(
            builder, conversation.latestEvent, conversation.latestAttrs, notifId,
            positiveUids = listOf(latestUid),
            negativeUids = allUids
        )

        notificationManager.notify(notifId, builder.build())
    }

    private fun removeChatMessage(uid: UInt, conversation: Conversation) {
        conversation.messages.remove(uid)
        uidToConversation.remove(uid)
        if (conversation.messages.isEmpty()) {
            conversations.remove(conversation.key)
            notificationManager.cancel(conversation.notifId)
            leaveGroup(conversation.group, conversation.notifId)
        } else {
            postConversation(conversation, alert = false)
        }
    }

    private fun postGroupSummary(group: AppGroup) {
        // Swiping the whole group away clears every notification in it on the iPhone
        val groupUids = uidToGroup.filterValues { it === group }.keys +
            uidToConversation.filterValues { it.group === group }.keys

        val builder = NotificationCompat.Builder(context, channelFor(group.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(group.categoryId))
            .setContentTitle(group.appName)
            .setLargeIcon(group.appIcon)
            .setGroup(group.key)
            .setGroupSummary(true)
            .setAutoCancel(true)
            // Only the children buzz; the summary just holds the stack together
            .setSilent(true)
            .setDeleteIntent(
                createActionIntent(
                    group.summaryId, "dismiss", groupUids.toList(), AncsConstants.ACTION_NEGATIVE
                )
            )

        notificationManager.notify(group.summaryId, builder.build())
    }

    /** The group object itself is kept (one per app), so membership never gets split. */
    private fun leaveGroup(group: AppGroup, notifId: Int) {
        group.members.remove(notifId)
        if (group.members.isEmpty()) {
            notificationManager.cancel(group.summaryId)
        }
    }

    /**
     * Positive/negative buttons from iOS (e.g. "Accept"/"Decline", "Clear").
     */
    private fun addActions(
        builder: NotificationCompat.Builder,
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        notifId: Int,
        positiveUids: List<UInt>,
        negativeUids: List<UInt>
    ) {
        if (event.hasPositiveAction) {
            val label = attrs.positiveActionLabel ?: "Accept"
            val intent = createActionIntent(
                notifId, "positive", positiveUids, AncsConstants.ACTION_POSITIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_launcher, label, intent).build()
            )
        }

        if (event.hasNegativeAction) {
            val label = attrs.negativeActionLabel ?: "Dismiss"
            val intent = createActionIntent(
                notifId, "negative", negativeUids, AncsConstants.ACTION_NEGATIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_launcher, label, intent).build()
            )
        }
    }

    /**
     * Remove a notification when ANCS sends a Removed event.
     */
    fun cancelNotification(notificationUid: UInt) {
        synchronized(lock) {
            uidToConversation[notificationUid]?.let { conversation ->
                removeChatMessage(notificationUid, conversation)
                return
            }

            val notifId = uidToNotifId(notificationUid)
            notificationManager.cancel(notifId)
            uidToGroup.remove(notificationUid)?.let { leaveGroup(it, notifId) }
        }
    }

    /**
     * Cancel all WatchBridge notifications (e.g., on disconnect).
     */
    fun cancelAll() {
        synchronized(lock) {
            notificationManager.cancelAll()
            groups.clear()
            conversations.clear()
            uidToConversation.clear()
            uidToGroup.clear()
        }
    }

    private fun channelFor(categoryId: Byte): String =
        NotificationChannels.channelForCategory(categoryId, vibrate = settings.isVibrationEnabled)

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
        notifId: Int,
        actionType: String,
        notificationUids: List<UInt>,
        actionId: Byte
    ): PendingIntent {
        val intent = Intent(NotificationActionReceiver.ACTION_PERFORM).apply {
            setPackage(context.packageName)
            // Keeps every notification's PendingIntent distinct (extras alone don't), without
            // affecting how the receiver's intent filter matches
            identifier = "$notifId/$actionType"
            putExtra(
                NotificationActionReceiver.EXTRA_NOTIFICATION_UIDS,
                notificationUids.map { it.toInt() }.toIntArray()
            )
            putExtra("action_id", actionId)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
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

    /** Conversation and summary IDs: negative, so they never collide with uid-based IDs. */
    private fun stableNotifId(key: String): Int = key.hashCode() or Int.MIN_VALUE
}
