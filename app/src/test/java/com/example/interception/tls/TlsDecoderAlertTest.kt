package com.example.interception.tls

import org.junit.Assert.*
import org.junit.Test

class TlsDecoderAlertTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `fatal handshake_failure alert parses correctly`() {
        val record = hex("15 03 03 00 02 02 28")
        val alert = TlsDecoder.parseAlert(record)
        assertNotNull(alert)
        assertTrue(alert!!.isFatal)
        assertEquals("fatal", alert.levelName)
        assertEquals("handshake_failure", alert.descriptionName)
        assertEquals(40, alert.description)
    }

    @Test
    fun `warning close_notify is not fatal`() {
        val record = hex("15 03 03 00 02 01 00")
        val alert = TlsDecoder.parseAlert(record)
        assertNotNull(alert)
        assertFalse(alert!!.isFatal)
        assertEquals("warning", alert.levelName)
        assertEquals("close_notify", alert.descriptionName)
    }

    @Test
    fun `unmapped description code reports unknown(N) honestly`() {
        val record = hex("15 03 03 00 02 02 FE") // 0xFE = 254, unmapped
        val alert = TlsDecoder.parseAlert(record)
        assertNotNull(alert)
        assertEquals("unknown(254)", alert!!.descriptionName)
    }

    @Test
    fun `handshake record type 22 is not an alert`() {
        val record = hex("16 03 03 00 02 01 01")
        assertNull(TlsDecoder.parseAlert(record))
    }

    @Test
    fun `findAlertInRecords locates the second record in a two-record buffer`() {
        // First record: a tiny fake handshake record (type 22, 2-byte body)
        val handshake = hex("16 03 03 00 02 01 01")
        // Second record: fatal unknown_ca alert
        val alert = hex("15 03 03 00 02 02 30")
        val combined = handshake + alert

        val found = TlsDecoder.findAlertInRecords(combined)
        assertNotNull(found)
        assertEquals("unknown_ca", found!!.descriptionName)
        assertTrue(found.isFatal)
    }
}
