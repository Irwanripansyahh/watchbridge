package com.watchbridge.ancs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class AncsAttributeParserTest {

    private val second = 1_000_000_000L

    /** A GetNotificationAttributes response, as iOS sends it on the Data Source. */
    private fun notificationResponse(uid: Int, vararg attributes: Pair<Byte, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES.toInt())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(uid).array())
        attributes.forEach { (id, value) -> writeAttribute(out, id, value) }
        return out.toByteArray()
    }

    private fun writeAttribute(out: ByteArrayOutputStream, id: Byte, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(id.toInt())
        out.write(bytes.size and 0xFF)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes)
    }

    /** Every attribute the pipeline requests, in request order. */
    private fun chatResponse(uid: Int, sender: String, message: String) = notificationResponse(
        uid,
        AncsConstants.ATTR_APP_IDENTIFIER to "net.whatsapp.WhatsApp",
        AncsConstants.ATTR_TITLE to sender,
        AncsConstants.ATTR_SUBTITLE to "",
        AncsConstants.ATTR_MESSAGE to message,
        AncsConstants.ATTR_DATE to "20261001T091500",
        AncsConstants.ATTR_POSITIVE_ACTION_LABEL to "",
        AncsConstants.ATTR_NEGATIVE_ACTION_LABEL to "Clear"
    )

    @Test
    fun `complete response in one packet is parsed`() {
        val result = AncsAttributeParser().feedFragment(chatResponse(7, "Budi", "Halo!"))
            as AncsAttributeParser.NotificationAttributes

        assertEquals(7u, result.notificationUid)
        assertEquals("net.whatsapp.WhatsApp", result.appIdentifier)
        assertEquals("Budi", result.title)
        assertEquals("Halo!", result.message)
        assertEquals("Clear", result.negativeActionLabel)
    }

    @Test
    fun `response split at any byte waits for the rest`() {
        val response = chatResponse(42, "Budi", "Pesan yang cukup panjang untuk dipecah")

        // Including splits that fall exactly between two attributes, which used to be
        // mistaken for the end of the response
        for (split in 1 until response.size) {
            val parser = AncsAttributeParser()
            assertNull("split at $split", parser.feedFragment(response.copyOfRange(0, split), 0))

            val result = parser.feedFragment(response.copyOfRange(split, response.size), 0)
                as AncsAttributeParser.NotificationAttributes
            assertEquals("split at $split", "Pesan yang cukup panjang untuk dipecah", result.message)
        }
    }

    @Test
    fun `responses after a split one are not corrupted`() {
        val parser = AncsAttributeParser()
        val first = chatResponse(1, "Budi", "Satu")
        val second = chatResponse(2, "Siti", "Dua")

        // Split the first one right after the title, between two attributes
        val afterTitle = 5 + (3 + "net.whatsapp.WhatsApp".length) + (3 + "Budi".length)
        assertNull(parser.feedFragment(first.copyOfRange(0, afterTitle), 0))
        val one = parser.feedFragment(first.copyOfRange(afterTitle, first.size), 0)
            as AncsAttributeParser.NotificationAttributes
        val two = parser.feedFragment(second, 0) as AncsAttributeParser.NotificationAttributes

        assertEquals("Satu", one.message)
        assertEquals(2u, two.notificationUid)
        assertEquals("Dua", two.message)
    }

    @Test
    fun `half-received response that stopped arriving is dropped`() {
        val parser = AncsAttributeParser()
        val lost = chatResponse(1, "Budi", "Hilang")
        assertNull(parser.feedFragment(lost.copyOfRange(0, 10), 0))

        val result = parser.feedFragment(chatResponse(2, "Siti", "Masuk"), 2 * second)
            as AncsAttributeParser.NotificationAttributes

        assertEquals(2u, result.notificationUid)
        assertEquals("Masuk", result.message)
    }

    @Test
    fun `app attributes split after the identifier wait for the display name`() {
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_APP_ATTRIBUTES.toInt())
        out.write("net.whatsapp.WhatsApp".toByteArray())
        out.write(0)
        val header = out.toByteArray()
        writeAttribute(out, AncsConstants.APP_ATTR_DISPLAY_NAME, "WhatsApp")
        val full = out.toByteArray()

        val parser = AncsAttributeParser()
        assertNull(parser.feedFragment(header, 0))
        val result = parser.feedFragment(full.copyOfRange(header.size, full.size), 0)
            as AncsAttributeParser.AppAttributes

        assertEquals("net.whatsapp.WhatsApp", result.appIdentifier)
        assertEquals("WhatsApp", result.displayName)
    }

    @Test
    fun `date is converted to a timestamp in local time`() {
        val attributes = AncsAttributeParser.NotificationAttributes(1u, date = "20261001T091530")
        val calendar = Calendar.getInstance().apply { timeInMillis = attributes.timestampMillis!! }

        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, calendar.get(Calendar.MONTH))
        assertEquals(1, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, calendar.get(Calendar.MINUTE))
        assertEquals(30, calendar.get(Calendar.SECOND))
    }
}
