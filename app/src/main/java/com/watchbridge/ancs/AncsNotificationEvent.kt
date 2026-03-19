package com.watchbridge.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed representation of an 8-byte ANCS Notification Source event.
 *
 * Format:
 *   Byte 0: EventID (Added=0, Modified=1, Removed=2)
 *   Byte 1: EventFlags (bitmask)
 *   Byte 2: CategoryID
 *   Byte 3: CategoryCount
 *   Bytes 4-7: NotificationUID (uint32 LE)
 */
data class AncsNotificationEvent(
    val eventId: Byte,
    val eventFlags: Byte,
    val categoryId: Byte,
    val categoryCount: Byte,
    val notificationUid: UInt
) {
    val isAdded: Boolean get() = eventId == AncsConstants.EVENT_ID_ADDED
    val isModified: Boolean get() = eventId == AncsConstants.EVENT_ID_MODIFIED
    val isRemoved: Boolean get() = eventId == AncsConstants.EVENT_ID_REMOVED

    val isSilent: Boolean get() = eventFlags.toInt() and AncsConstants.EVENT_FLAG_SILENT.toInt() != 0
    val isImportant: Boolean get() = eventFlags.toInt() and AncsConstants.EVENT_FLAG_IMPORTANT.toInt() != 0
    val isPreExisting: Boolean get() = eventFlags.toInt() and AncsConstants.EVENT_FLAG_PRE_EXISTING.toInt() != 0
    val hasPositiveAction: Boolean get() = eventFlags.toInt() and AncsConstants.EVENT_FLAG_POSITIVE_ACTION.toInt() != 0
    val hasNegativeAction: Boolean get() = eventFlags.toInt() and AncsConstants.EVENT_FLAG_NEGATIVE_ACTION.toInt() != 0

    val categoryName: String get() = AncsConstants.categoryName(categoryId)

    val eventName: String
        get() = when (eventId) {
            AncsConstants.EVENT_ID_ADDED -> "Added"
            AncsConstants.EVENT_ID_MODIFIED -> "Modified"
            AncsConstants.EVENT_ID_REMOVED -> "Removed"
            else -> "Unknown($eventId)"
        }

    override fun toString(): String =
        "ANCS[$eventName uid=$notificationUid cat=$categoryName count=$categoryCount flags=0x${eventFlags.toUByte().toString(16)}]"

    companion object {
        const val EVENT_SIZE = 8

        fun parse(data: ByteArray): AncsNotificationEvent? {
            if (data.size < EVENT_SIZE) return null

            val uid = ByteBuffer.wrap(data, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toUInt()

            return AncsNotificationEvent(
                eventId = data[0],
                eventFlags = data[1],
                categoryId = data[2],
                categoryCount = data[3],
                notificationUid = uid
            )
        }
    }
}
