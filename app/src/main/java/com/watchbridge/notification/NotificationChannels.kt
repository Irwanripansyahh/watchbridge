package com.watchbridge.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.watchbridge.ancs.AncsConstants

/**
 * Creates and manages Wear OS notification channels mapped to ANCS categories.
 * Each ANCS category gets its own channel so users can control importance/vibration per type.
 *
 * All bridged channels use IMPORTANCE_HIGH so notifications pop up as heads-up
 * on the watch face instead of silently going to the shade.
 *
 * A channel's vibration can't be changed after it's created, so the "Vibration" setting
 * switches between two families of channels: the normal ones and "_quiet" twins without
 * vibration. Only the active family exists at a time. Call channels always vibrate,
 * since a watch has no ringer.
 */
object NotificationChannels {

    const val CHANNEL_INCOMING_CALL = "ancs_incoming_call_v3"
    const val CHANNEL_MISSED_CALL = "ancs_missed_call_v3"
    const val CHANNEL_VOICEMAIL = "ancs_voicemail_v3"
    const val CHANNEL_SOCIAL = "ancs_social_v3"
    const val CHANNEL_SCHEDULE = "ancs_schedule_v3"
    const val CHANNEL_EMAIL = "ancs_email_v3"
    const val CHANNEL_NEWS = "ancs_news_v3"
    const val CHANNEL_HEALTH = "ancs_health_v3"
    const val CHANNEL_BUSINESS = "ancs_business_v3"
    const val CHANNEL_LOCATION = "ancs_location_v3"
    const val CHANNEL_ENTERTAINMENT = "ancs_entertainment_v3"
    const val CHANNEL_OTHER = "ancs_other_v3"
    const val CHANNEL_ACTIVE_CALL = "ancs_active_call_v3"

    private const val QUIET_SUFFIX = "_quiet"

    // Old channel IDs to clean up (v1 and v2)
    private val OLD_CHANNEL_IDS = listOf(
        "ancs_incoming_call", "ancs_missed_call", "ancs_voicemail",
        "ancs_social", "ancs_schedule", "ancs_email", "ancs_news",
        "ancs_health", "ancs_business", "ancs_location",
        "ancs_entertainment", "ancs_other", "ancs_active_call",
        "ancs_incoming_call_v2", "ancs_missed_call_v2", "ancs_voicemail_v2",
        "ancs_social_v2", "ancs_schedule_v2", "ancs_email_v2", "ancs_news_v2",
        "ancs_health_v2", "ancs_business_v2", "ancs_location_v2",
        "ancs_entertainment_v2", "ancs_other_v2", "ancs_active_call_v2"
    )

    private val VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200)

    private class ChannelSpec(val id: String, val name: String, val description: String)

    private val CALL_CHANNELS = listOf(
        ChannelSpec(CHANNEL_INCOMING_CALL, "Incoming Calls", "Phone incoming call alerts"),
        ChannelSpec(CHANNEL_ACTIVE_CALL, "Active Calls", "Active call controls")
    )

    /** Channels that follow the "Vibration" setting. */
    private val ALERT_CHANNELS = listOf(
        ChannelSpec(CHANNEL_MISSED_CALL, "Missed Calls", "Phone missed call alerts"),
        ChannelSpec(CHANNEL_VOICEMAIL, "Voicemail", "Phone voicemail notifications"),
        ChannelSpec(CHANNEL_SOCIAL, "Social", "Messages, social media notifications"),
        ChannelSpec(CHANNEL_SCHEDULE, "Schedule", "Calendar and reminder notifications"),
        ChannelSpec(CHANNEL_EMAIL, "Email", "Email notifications"),
        ChannelSpec(CHANNEL_NEWS, "News", "News notifications"),
        ChannelSpec(CHANNEL_HEALTH, "Health & Fitness", "Health and fitness notifications"),
        ChannelSpec(CHANNEL_BUSINESS, "Business & Finance", "Business and finance notifications"),
        ChannelSpec(CHANNEL_LOCATION, "Location", "Location-based notifications"),
        ChannelSpec(CHANNEL_ENTERTAINMENT, "Entertainment", "Entertainment notifications"),
        ChannelSpec(CHANNEL_OTHER, "Other", "Other phone notifications")
    )

    fun createAll(context: Context, vibrate: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Delete old channels so new settings take effect
        OLD_CHANNEL_IDS.forEach { nm.deleteNotificationChannel(it) }

        // Only keep the active family, so system settings don't list every category twice
        ALERT_CHANNELS.forEach { nm.deleteNotificationChannel(alertChannelId(it.id, !vibrate)) }

        val channels = CALL_CHANNELS.map { buildChannel(it.id, it, vibrate = true) } +
            ALERT_CHANNELS.map { buildChannel(alertChannelId(it.id, vibrate), it, vibrate) }

        nm.createNotificationChannels(channels)
    }

    private fun buildChannel(id: String, spec: ChannelSpec, vibrate: Boolean) =
        NotificationChannel(id, spec.name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = spec.description
            enableVibration(vibrate)
            vibrationPattern = if (vibrate) VIBRATION_PATTERN else null
        }

    private fun alertChannelId(baseId: String, vibrate: Boolean): String =
        if (vibrate) baseId else baseId + QUIET_SUFFIX

    fun channelForCategory(categoryId: Byte, vibrate: Boolean): String = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> CHANNEL_INCOMING_CALL
        AncsConstants.CATEGORY_ACTIVE_CALL -> CHANNEL_ACTIVE_CALL
        else -> alertChannelId(alertBaseChannel(categoryId), vibrate)
    }

    private fun alertBaseChannel(categoryId: Byte): String = when (categoryId) {
        AncsConstants.CATEGORY_MISSED_CALL -> CHANNEL_MISSED_CALL
        AncsConstants.CATEGORY_VOICEMAIL -> CHANNEL_VOICEMAIL
        AncsConstants.CATEGORY_SOCIAL -> CHANNEL_SOCIAL
        AncsConstants.CATEGORY_SCHEDULE -> CHANNEL_SCHEDULE
        AncsConstants.CATEGORY_EMAIL -> CHANNEL_EMAIL
        AncsConstants.CATEGORY_NEWS -> CHANNEL_NEWS
        AncsConstants.CATEGORY_HEALTH_AND_FITNESS -> CHANNEL_HEALTH
        AncsConstants.CATEGORY_BUSINESS_AND_FINANCE -> CHANNEL_BUSINESS
        AncsConstants.CATEGORY_LOCATION -> CHANNEL_LOCATION
        AncsConstants.CATEGORY_ENTERTAINMENT -> CHANNEL_ENTERTAINMENT
        else -> CHANNEL_OTHER
    }
}
