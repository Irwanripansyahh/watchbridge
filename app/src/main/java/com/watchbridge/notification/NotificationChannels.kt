package com.watchbridge.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.watchbridge.ancs.AncsConstants

/**
 * Creates and manages Wear OS notification channels mapped to ANCS categories.
 * Each ANCS category gets its own channel so users can control importance/vibration per type.
 */
object NotificationChannels {

    const val CHANNEL_INCOMING_CALL = "ancs_incoming_call"
    const val CHANNEL_MISSED_CALL = "ancs_missed_call"
    const val CHANNEL_VOICEMAIL = "ancs_voicemail"
    const val CHANNEL_SOCIAL = "ancs_social"
    const val CHANNEL_SCHEDULE = "ancs_schedule"
    const val CHANNEL_EMAIL = "ancs_email"
    const val CHANNEL_NEWS = "ancs_news"
    const val CHANNEL_HEALTH = "ancs_health"
    const val CHANNEL_BUSINESS = "ancs_business"
    const val CHANNEL_LOCATION = "ancs_location"
    const val CHANNEL_ENTERTAINMENT = "ancs_entertainment"
    const val CHANNEL_OTHER = "ancs_other"
    const val CHANNEL_ACTIVE_CALL = "ancs_active_call"

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val channels = listOf(
            NotificationChannel(
                CHANNEL_INCOMING_CALL, "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "iPhone incoming call alerts" },

            NotificationChannel(
                CHANNEL_MISSED_CALL, "Missed Calls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "iPhone missed call alerts" },

            NotificationChannel(
                CHANNEL_VOICEMAIL, "Voicemail",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "iPhone voicemail notifications" },

            NotificationChannel(
                CHANNEL_SOCIAL, "Social",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Messages, social media notifications" },

            NotificationChannel(
                CHANNEL_SCHEDULE, "Schedule",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Calendar and reminder notifications" },

            NotificationChannel(
                CHANNEL_EMAIL, "Email",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Email notifications" },

            NotificationChannel(
                CHANNEL_NEWS, "News",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "News notifications" },

            NotificationChannel(
                CHANNEL_HEALTH, "Health & Fitness",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Health and fitness notifications" },

            NotificationChannel(
                CHANNEL_BUSINESS, "Business & Finance",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Business and finance notifications" },

            NotificationChannel(
                CHANNEL_LOCATION, "Location",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Location-based notifications" },

            NotificationChannel(
                CHANNEL_ENTERTAINMENT, "Entertainment",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Entertainment notifications" },

            NotificationChannel(
                CHANNEL_OTHER, "Other",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Other iPhone notifications" },

            NotificationChannel(
                CHANNEL_ACTIVE_CALL, "Active Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Active call controls" }
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
