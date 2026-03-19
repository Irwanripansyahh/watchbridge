package com.watchbridge.ancs

import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) protocol constants.
 * Reference: Apple ANCS Specification
 */
object AncsConstants {

    // --- ANCS Service UUID ---
    val ANCS_SERVICE_UUID: UUID =
        UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

    // --- Characteristic UUIDs ---
    /** Notifiable. iPhone -> Watch: 8-byte notification events. */
    val NOTIFICATION_SOURCE_UUID: UUID =
        UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")

    /** Writeable with response. Watch -> iPhone: commands. */
    val CONTROL_POINT_UUID: UUID =
        UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")

    /** Notifiable. iPhone -> Watch: attribute data responses. */
    val DATA_SOURCE_UUID: UUID =
        UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Standard CCCD for enabling notifications. */
    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- EventID (Notification Source byte 0) ---
    const val EVENT_ID_ADDED: Byte = 0
    const val EVENT_ID_MODIFIED: Byte = 1
    const val EVENT_ID_REMOVED: Byte = 2

    // --- EventFlags (Notification Source byte 1, bitmask) ---
    const val EVENT_FLAG_SILENT: Byte = (1 shl 0).toByte()
    const val EVENT_FLAG_IMPORTANT: Byte = (1 shl 1).toByte()
    const val EVENT_FLAG_PRE_EXISTING: Byte = (1 shl 2).toByte()
    const val EVENT_FLAG_POSITIVE_ACTION: Byte = (1 shl 3).toByte()
    const val EVENT_FLAG_NEGATIVE_ACTION: Byte = (1 shl 4).toByte()

    // --- CategoryID (Notification Source byte 2) ---
    const val CATEGORY_OTHER: Byte = 0
    const val CATEGORY_INCOMING_CALL: Byte = 1
    const val CATEGORY_MISSED_CALL: Byte = 2
    const val CATEGORY_VOICEMAIL: Byte = 3
    const val CATEGORY_SOCIAL: Byte = 4
    const val CATEGORY_SCHEDULE: Byte = 5
    const val CATEGORY_EMAIL: Byte = 6
    const val CATEGORY_NEWS: Byte = 7
    const val CATEGORY_HEALTH_AND_FITNESS: Byte = 8
    const val CATEGORY_BUSINESS_AND_FINANCE: Byte = 9
    const val CATEGORY_LOCATION: Byte = 10
    const val CATEGORY_ENTERTAINMENT: Byte = 11
    const val CATEGORY_ACTIVE_CALL: Byte = 12 // Undocumented, iOS 13+

    fun categoryName(id: Byte): String = when (id) {
        CATEGORY_OTHER -> "Other"
        CATEGORY_INCOMING_CALL -> "Incoming Call"
        CATEGORY_MISSED_CALL -> "Missed Call"
        CATEGORY_VOICEMAIL -> "Voicemail"
        CATEGORY_SOCIAL -> "Social"
        CATEGORY_SCHEDULE -> "Schedule"
        CATEGORY_EMAIL -> "Email"
        CATEGORY_NEWS -> "News"
        CATEGORY_HEALTH_AND_FITNESS -> "Health & Fitness"
        CATEGORY_BUSINESS_AND_FINANCE -> "Business & Finance"
        CATEGORY_LOCATION -> "Location"
        CATEGORY_ENTERTAINMENT -> "Entertainment"
        CATEGORY_ACTIVE_CALL -> "Active Call"
        else -> "Unknown($id)"
    }

    // --- CommandID (Control Point byte 0) ---
    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES: Byte = 0
    const val COMMAND_GET_APP_ATTRIBUTES: Byte = 1
    const val COMMAND_PERFORM_NOTIFICATION_ACTION: Byte = 2

    // --- Notification AttributeID ---
    const val ATTR_APP_IDENTIFIER: Byte = 0
    const val ATTR_TITLE: Byte = 1
    const val ATTR_SUBTITLE: Byte = 2
    const val ATTR_MESSAGE: Byte = 3
    const val ATTR_MESSAGE_SIZE: Byte = 4
    const val ATTR_DATE: Byte = 5
    const val ATTR_POSITIVE_ACTION_LABEL: Byte = 6
    const val ATTR_NEGATIVE_ACTION_LABEL: Byte = 7

    // --- App AttributeID ---
    const val APP_ATTR_DISPLAY_NAME: Byte = 0

    // --- ActionID ---
    const val ACTION_POSITIVE: Byte = 0
    const val ACTION_NEGATIVE: Byte = 1

    /** Max length to request for string attributes. */
    const val MAX_ATTRIBUTE_LENGTH: Int = 256
}
