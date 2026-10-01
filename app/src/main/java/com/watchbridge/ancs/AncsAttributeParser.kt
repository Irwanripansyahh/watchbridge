package com.watchbridge.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses fragmented Data Source responses into structured notification attributes.
 *
 * Data Source responses can span multiple BLE packets. This parser accumulates
 * fragments and emits a complete result when all requested attributes are received.
 *
 * A packet can end exactly between two attributes, so "the buffer parses cleanly" doesn't
 * mean the response is done: completion is decided by the set of attributes that were
 * requested ([expectedNotificationAttributes]).
 *
 * Response format:
 *   Byte 0: CommandID (0 = GetNotificationAttributes response)
 *   Bytes 1-4: NotificationUID (uint32 LE)
 *   Then repeating: [AttributeID (1 byte)] [Length (2 bytes LE)] [Value (Length bytes)]
 */
class AncsAttributeParser(
    private val expectedNotificationAttributes: Set<Byte> =
        AncsActionHandler.DEFAULT_NOTIFICATION_ATTRIBUTES
) {

    companion object {
        /** Fragments of one response arrive back to back; a gap this long means the rest was lost. */
        private const val STALE_RESPONSE_NANOS = 1_000_000_000L

        /** Far above any real response (a few attributes of at most 256 bytes each). */
        private const val MAX_RESPONSE_BYTES = 8 * 1024
    }

    private var buffer = ByteArray(0)
    private var expectedCommandId: Byte? = null
    private var lastFragmentNanos = 0L

    data class NotificationAttributes(
        val notificationUid: UInt,
        val appIdentifier: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val message: String? = null,
        val messageSize: String? = null,
        val date: String? = null,
        val positiveActionLabel: String? = null,
        val negativeActionLabel: String? = null
    ) {
        /**
         * When the notification was posted on the iPhone, in epoch millis. ANCS sends it as
         * "yyyyMMdd'T'HHmmSS" in the iPhone's local time, which matches the watch's time zone.
         */
        val timestampMillis: Long?
            get() = date?.let {
                try {
                    SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(it)?.time
                } catch (_: ParseException) {
                    null
                }
            }
    }

    data class AppAttributes(
        val appIdentifier: String,
        val displayName: String? = null
    )

    /**
     * Feed a Data Source fragment into the parser.
     * Returns parsed attributes if the response is complete, null if more fragments needed.
     */
    fun feedFragment(data: ByteArray, nowNanos: Long = System.nanoTime()): Any? {
        // A half-received response would otherwise swallow the next response into it
        if (buffer.isNotEmpty() && nowNanos - lastFragmentNanos > STALE_RESPONSE_NANOS) {
            reset()
        }
        lastFragmentNanos = nowNanos

        buffer += data
        if (buffer.size > MAX_RESPONSE_BYTES) {
            reset()
            return null
        }

        if (buffer.isEmpty()) return null

        return when (buffer[0]) {
            AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES -> tryParseNotificationAttributes()
            AncsConstants.COMMAND_GET_APP_ATTRIBUTES -> tryParseAppAttributes()
            else -> {
                reset()
                null
            }
        }
    }

    fun reset() {
        buffer = ByteArray(0)
        expectedCommandId = null
    }

    private fun tryParseNotificationAttributes(): NotificationAttributes? {
        // Minimum: CommandID(1) + UID(4) = 5 bytes before first attribute
        if (buffer.size < 5) return null

        val uid = ByteBuffer.wrap(buffer, 1, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toUInt()

        val attrs = mutableMapOf<Byte, String>()
        var offset = 5

        while (offset < buffer.size) {
            // Need at least: AttributeID(1) + Length(2) = 3 bytes
            if (offset + 3 > buffer.size) return null // Need more data

            val attrId = buffer[offset]
            val attrLen = ByteBuffer.wrap(buffer, offset + 1, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF

            offset += 3

            if (offset + attrLen > buffer.size) return null // Need more data

            val value = String(buffer, offset, attrLen, Charsets.UTF_8)
            attrs[attrId] = value
            offset += attrLen
        }

        // The packet ended between two attributes, but more are still to come
        if (!attrs.keys.containsAll(expectedNotificationAttributes)) return null

        // All attributes parsed successfully
        val result = NotificationAttributes(
            notificationUid = uid,
            appIdentifier = attrs[AncsConstants.ATTR_APP_IDENTIFIER],
            title = attrs[AncsConstants.ATTR_TITLE],
            subtitle = attrs[AncsConstants.ATTR_SUBTITLE],
            message = attrs[AncsConstants.ATTR_MESSAGE],
            messageSize = attrs[AncsConstants.ATTR_MESSAGE_SIZE],
            date = attrs[AncsConstants.ATTR_DATE],
            positiveActionLabel = attrs[AncsConstants.ATTR_POSITIVE_ACTION_LABEL],
            negativeActionLabel = attrs[AncsConstants.ATTR_NEGATIVE_ACTION_LABEL]
        )
        reset()
        return result
    }

    private fun tryParseAppAttributes(): AppAttributes? {
        // CommandID(1) then null-terminated app identifier, then attributes
        if (buffer.size < 2) return null

        // Find null terminator for app identifier
        val nullIndex = buffer.indexOf(0.toByte(), fromIndex = 1)
        if (nullIndex < 0) return null // Need more data

        val appId = String(buffer, 1, nullIndex - 1, Charsets.UTF_8)
        var offset = nullIndex + 1

        var displayName: String? = null
        var hasDisplayName = false

        while (offset < buffer.size) {
            if (offset + 3 > buffer.size) return null

            val attrId = buffer[offset]
            val attrLen = ByteBuffer.wrap(buffer, offset + 1, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF

            offset += 3

            if (offset + attrLen > buffer.size) return null

            val value = String(buffer, offset, attrLen, Charsets.UTF_8)
            if (attrId == AncsConstants.APP_ATTR_DISPLAY_NAME) {
                displayName = value.ifEmpty { null }
                hasDisplayName = true
            }
            offset += attrLen
        }

        // The packet ended right after the app identifier; the name is still to come
        if (!hasDisplayName) return null

        val result = AppAttributes(appIdentifier = appId, displayName = displayName)
        reset()
        return result
    }

    private fun ByteArray.indexOf(byte: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }
}
