package com.watchbridge.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds ANCS Control Point command packets.
 */
object AncsActionHandler {

    /**
     * Build a GetNotificationAttributes command.
     *
     * Format:
     *   Byte 0: CommandID = 0
     *   Bytes 1-4: NotificationUID (uint32 LE)
     *   Remaining: List of [AttributeID, MaxLength(2 bytes LE)] pairs
     */
    fun buildGetNotificationAttributes(
        notificationUid: UInt,
        requestTitle: Boolean = true,
        requestSubtitle: Boolean = true,
        requestMessage: Boolean = true,
        requestDate: Boolean = true,
        requestAppIdentifier: Boolean = true,
        requestActionLabels: Boolean = true,
        maxLength: Int = AncsConstants.MAX_ATTRIBUTE_LENGTH
    ): ByteArray {
        val attrs = mutableListOf<ByteArray>()

        // AppIdentifier has no max length parameter
        if (requestAppIdentifier) {
            attrs.add(byteArrayOf(AncsConstants.ATTR_APP_IDENTIFIER))
        }

        // Attributes with max length
        fun addWithLength(attrId: Byte) {
            val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(attrId)
            buf.putShort(maxLength.toShort())
            attrs.add(buf.array())
        }

        if (requestTitle) addWithLength(AncsConstants.ATTR_TITLE)
        if (requestSubtitle) addWithLength(AncsConstants.ATTR_SUBTITLE)
        if (requestMessage) addWithLength(AncsConstants.ATTR_MESSAGE)
        if (requestDate) attrs.add(byteArrayOf(AncsConstants.ATTR_DATE))
        if (requestActionLabels) {
            addWithLength(AncsConstants.ATTR_POSITIVE_ACTION_LABEL)
            addWithLength(AncsConstants.ATTR_NEGATIVE_ACTION_LABEL)
        }

        val attrBytes = attrs.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        header.put(AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        header.putInt(notificationUid.toInt())

        return header.array() + attrBytes
    }

    /**
     * Build a GetAppAttributes command.
     *
     * Format:
     *   Byte 0: CommandID = 1
     *   Bytes 1+: App identifier (null-terminated UTF-8)
     *   Then: AttributeIDs to request
     */
    fun buildGetAppAttributes(appIdentifier: String): ByteArray {
        val appBytes = appIdentifier.toByteArray(Charsets.UTF_8) + 0.toByte()
        return byteArrayOf(AncsConstants.COMMAND_GET_APP_ATTRIBUTES) +
            appBytes +
            byteArrayOf(AncsConstants.APP_ATTR_DISPLAY_NAME)
    }

    /**
     * Build a PerformNotificationAction command.
     *
     * Format:
     *   Byte 0: CommandID = 2
     *   Bytes 1-4: NotificationUID (uint32 LE)
     *   Byte 5: ActionID (0=Positive, 1=Negative)
     */
    fun buildPerformAction(notificationUid: UInt, actionId: Byte): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(AncsConstants.COMMAND_PERFORM_NOTIFICATION_ACTION)
        buf.putInt(notificationUid.toInt())
        buf.put(actionId)
        return buf.array()
    }

    fun buildPositiveAction(notificationUid: UInt): ByteArray =
        buildPerformAction(notificationUid, AncsConstants.ACTION_POSITIVE)

    fun buildNegativeAction(notificationUid: UInt): ByteArray =
        buildPerformAction(notificationUid, AncsConstants.ACTION_NEGATIVE)
}
