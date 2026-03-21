package com.watchbridge.notification

import com.watchbridge.R
import com.watchbridge.ancs.AncsConstants

/**
 * Maps ANCS category IDs to appropriate notification small icons.
 */
object NotificationIcons {

    fun iconForCategory(categoryId: Byte): Int = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> R.drawable.ic_notif_call
        AncsConstants.CATEGORY_ACTIVE_CALL -> R.drawable.ic_notif_call
        AncsConstants.CATEGORY_MISSED_CALL -> R.drawable.ic_notif_missed_call
        AncsConstants.CATEGORY_VOICEMAIL -> R.drawable.ic_notif_voicemail
        AncsConstants.CATEGORY_SOCIAL -> R.drawable.ic_notif_social
        AncsConstants.CATEGORY_SCHEDULE -> R.drawable.ic_notif_schedule
        AncsConstants.CATEGORY_EMAIL -> R.drawable.ic_notif_email
        AncsConstants.CATEGORY_NEWS -> R.drawable.ic_notif_news
        AncsConstants.CATEGORY_HEALTH_AND_FITNESS -> R.drawable.ic_notif_health
        AncsConstants.CATEGORY_BUSINESS_AND_FINANCE -> R.drawable.ic_notif_business
        AncsConstants.CATEGORY_LOCATION -> R.drawable.ic_notif_location
        AncsConstants.CATEGORY_ENTERTAINMENT -> R.drawable.ic_notif_entertainment
        else -> R.drawable.ic_notif_other
    }
}
