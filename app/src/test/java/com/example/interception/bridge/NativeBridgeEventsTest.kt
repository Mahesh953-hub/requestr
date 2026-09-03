package com.example.interception.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip tests for [BridgeEvent] parsing against the exact JSON the Rust bridge emits.
 * Uses Robolectric so org.json.JSONObject is the real Android implementation.
 */
@RunWith(RobolectricTestRunner::class)
class NativeBridgeEventsTest {

    private inline fun <reified T : BridgeEvent> assertParses(json: String): T {
        val event = BridgeEvent.parse(json)
        assertTrue(
            "Expected ${T::class.simpleName} but got ${event::class.simpleName}: $event",
            event is T
        )
        return event as T
    }

    @Test
    fun `session_start with all fields`() {
        val e = assertParses<BridgeEvent.SessionStart>(
            """{"type":"session_start","session_id":42,"src":"10.0.0.1:12345","dst":"93.184.216.34:443","hostname":"example.com","is_tcp":true}"""
        )
        assertEquals(42L, e.sessionId)
        assertEquals("10.0.0.1:12345", e.src)
        assertEquals("93.184.216.34:443", e.dst)
        assertEquals("example.com", e.hostname)
        assertTrue(e.isTcp)
    }

    @Test
    fun `session_start with null optional fields`() {
        val e = assertParses<BridgeEvent.SessionStart>(
            """{"type":"session_start","session_id":1,"src":"10.0.0.1:1","dst":"1.2.3.4:80","is_tcp":false}"""
        )
        assertNull(e.hostname)
        assertFalse(e.isTcp)
    }

    @Test
    fun `session_start rejects missing required src`() {
        val e = BridgeEvent.parse("""{"type":"session_start","session_id":1,"dst":"1.2.3.4:80","is_tcp":true}""")
        assertTrue("Expected Unknown, got ${e::class.simpleName}", e is BridgeEvent.Unknown)
    }

    @Test
    fun `session_end round-trips`() {
        val e = assertParses<BridgeEvent.SessionEnd>(
            """{"type":"session_end","session_id":7,"tx_bytes":1024,"rx_bytes":2048,"reason":"closed"}"""
        )
        assertEquals(7L, e.sessionId)
        assertEquals(1024L, e.txBytes)
        assertEquals(2048L, e.rxBytes)
        assertEquals("closed", e.reason)
    }

    @Test
    fun `dns event round-trips`() {
        val e = assertParses<BridgeEvent.Dns>(
            """{"type":"dns","name":"example.com","address":"198.18.0.1","is_virtual":true}"""
        )
        assertEquals("example.com", e.name)
        assertEquals("198.18.0.1", e.address)
        assertTrue(e.isVirtual)
    }

    @Test
    fun `udp_policy with tee_and_drop`() {
        val e = assertParses<BridgeEvent.UdpPolicy>(
            """{"type":"udp_policy","session_id":99,"src":"10.0.0.2:5000","dst":"8.8.8.8:443","verdict":"tee_and_drop"}"""
        )
        assertEquals(BridgeEvent.UdpVerdict.TEE_AND_DROP, e.verdict)
    }

    @Test
    fun `udp_policy rejects unknown verdict`() {
        val e = BridgeEvent.parse("""{"type":"udp_policy","session_id":1,"src":"a","dst":"b","verdict":"bogus"}""")
        assertTrue("Expected Unknown for bad verdict", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee round-trips`() {
        val rawBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val b64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
        val e = assertParses<BridgeEvent.PacketTee>(
            """{"type":"packet_tee","session_id":5,"packet_len":4,"data_base64":"$b64","is_outbound":true}"""
        )
        assertEquals(5L, e.sessionId)
        assertEquals(4, e.packetLen)
        assertTrue(e.isOutbound)
        assertTrue("exact bytes must match", rawBytes.contentEquals(e.rawFrame))
    }

    @Test
    fun `error with session_id`() {
        val e = assertParses<BridgeEvent.Error>(
            """{"type":"error","session_id":3,"code":"protect_failed","message":"VpnService.protect returned false"}"""
        )
        assertEquals(3L, e.sessionId)
        assertEquals("protect_failed", e.code)
    }

    @Test
    fun `error without session_id`() {
        val e = assertParses<BridgeEvent.Error>(
            """{"type":"error","code":"tee_write_error","message":"disk full"}"""
        )
        assertNull(e.sessionId)
    }

    @Test
    fun `unknown type returns Unknown`() {
        val e = BridgeEvent.parse("""{"type":"future_event","data":42}""")
        assertTrue(e is BridgeEvent.Unknown)
    }

    @Test
    fun `malformed JSON returns Unknown`() {
        val e = BridgeEvent.parse("{not json")
        assertTrue(e is BridgeEvent.Unknown)
    }

    @Test
    fun `UdpVerdict fromWire resolves all variants`() {
        assertEquals(BridgeEvent.UdpVerdict.ALLOW, BridgeEvent.UdpVerdict.fromWire("allow"))
        assertEquals(BridgeEvent.UdpVerdict.TEE_AND_DROP, BridgeEvent.UdpVerdict.fromWire("tee_and_drop"))
        assertEquals(BridgeEvent.UdpVerdict.TEE_AND_PASS, BridgeEvent.UdpVerdict.fromWire("tee_and_pass"))
        assertNull(BridgeEvent.UdpVerdict.fromWire("unknown"))
    }

    @Test
    fun `packet_tee with base64 data round-trips exact bytes`() {
        // This is the exact JSON format the Rust bridge produces (Base64 of raw IP frame)
        val rawBytes = byteArrayOf(0x45, 0x00, 0x00, 0x28, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01)
        val b64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
        val json = """{"type":"packet_tee","session_id":0,"data_base64":"$b64","packet_len":8,"is_outbound":true}"""
        val e = assertParses<BridgeEvent.PacketTee>(json)
        assertEquals(8, e.packetLen)
        assertTrue(e.isOutbound)
        assertTrue("exact bytes must match", rawBytes.contentEquals(e.rawFrame))
    }

    @Test
    fun `packet_tee rejects length-mismatched data`() {
        // data_base64 decodes to 3 bytes but packet_len claims 8
        val json = """{"type":"packet_tee","session_id":0,"data_base64":"AQID","packet_len":8,"is_outbound":false}"""
        val e = BridgeEvent.parse(json)
        assertTrue("Length-mismatched data must be rejected as Unknown", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee rejects missing data`() {
        val json = """{"type":"packet_tee","session_id":5,"packet_len":1500,"is_outbound":true}"""
        val e = BridgeEvent.parse(json)
        assertTrue("Missing data_base64 must be rejected as Unknown", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee rejects empty data_base64`() {
        val json = """{"type":"packet_tee","session_id":5,"packet_len":4,"data_base64":"","is_outbound":true}"""
        val e = BridgeEvent.parse(json)
        assertTrue("Empty data_base64 must be rejected as Unknown", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee rejects zero or negative packet_len`() {
        val json = """{"type":"packet_tee","session_id":5,"packet_len":0,"data_base64":"AQID","is_outbound":true}"""
        val e = BridgeEvent.parse(json)
        assertTrue("packet_len<=0 must be rejected as Unknown", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee rejects packet_len over 9000`() {
        val json = """{"type":"packet_tee","session_id":5,"packet_len":9001,"data_base64":"AQID","is_outbound":true}"""
        val e = BridgeEvent.parse(json)
        assertTrue("packet_len over MAX_TEE_FRAME_BYTES must be rejected as Unknown", e is BridgeEvent.Unknown)
    }

    @Test
    fun `packet_tee rejects oversized data_base64 before decoding`() {
        // A tiny declared packet_len with a huge base64 string must be rejected purely on the
        // length-cap check, without ever attempting to decode the (junk) payload.
        val oversized = "A".repeat(20000)
        val json = """{"type":"packet_tee","session_id":5,"packet_len":4,"data_base64":"$oversized","is_outbound":true}"""
        val e = BridgeEvent.parse(json)
        assertTrue("Oversized data_base64 must be rejected as Unknown", e is BridgeEvent.Unknown)
    }
}
