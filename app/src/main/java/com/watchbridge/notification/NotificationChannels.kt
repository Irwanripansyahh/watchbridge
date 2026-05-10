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

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Delete old channels so new settings take effect
        OLD_CHANNEL_IDS.forEach { nm.deleteNotificationChannel(it) }

        val channels = listOf(
            NotificationChannel(
                CHANNEL_INCOMING_CALL, "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "iPhone incoming call alerts"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_MISSED_CALL, "Missed Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "iPhone missed call alerts"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_VOICEMAIL, "Voicemail",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "iPhone voicemail notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_SOCIAL, "Social",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages, social media notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_SCHEDULE, "Schedule",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Calendar and reminder notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_EMAIL, "Email",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Email notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_NEWS, "News",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "News notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_HEALTH, "Health & Fitness",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Health and fitness notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_BUSINESS, "Business & Finance",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Business and finance notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_LOCATION, "Location",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Location-based notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_ENTERTAINMENT, "Entertainment",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Entertainment notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_OTHER, "Other",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Other iPhone notifications"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },

            NotificationChannel(
                CHANNEL_ACTIVE_CALL, "Active Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active call controls"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            }
        )

        nm.createNotificationChannels(channels)
    }

    fun channelForCategory(categoryId: Byte): String = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> CHANNEL_INCOMING_CALL
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
        AncsConstants.CATEGORY_ACTIVE_CALL -> CHANNEL_ACTIVE_CALL
        else -> CHANNEL_OTHER
    }
}
