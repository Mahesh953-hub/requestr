package com.example.interception.http

import com.example.interception.http.Http2Parser.H2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 task 23 / gate G11: hand-built malformed HPACK blocks and HTTP/2 frames. Every vector must
 * (a) never throw, (b) be reported through [Http2Parser.H2Headers.error] when it violates
 * RFC 7541/9113, and (c) leave the decoder usable for the next well-formed block.
 */
class Http2MalformedVectorsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(s: String) = HpackConformanceTest.hex(s.replace(" ", ""))

    private fun decode(block: ByteArray, state: Http2Parser.HpackDecoder = Http2Parser.HpackDecoder(), flags: Int = Http2Parser.FLAG_END_HEADERS) =
        Http2Parser.decodeHeaders(H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, 1, block), block, state)

    // RFC 7541 Appendix C.2.1 -- literal with indexing, "custom-key: custom-header".
    private val c21 = hex("400a 6375 7374 6f6d 2d6b 6579 0d63 7573 746f 6d2d 6865 6164 6572")
    // RFC 7541 Appendix C.4.1 -- Huffman-coded first request.
    private val c41 = hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")

    @Test
    fun `RFC 7541 appendix C vectors decode cleanly`() {
        val a = decode(c21)
        assertNull(a.error)
        assertEquals(listOf("custom-key" to "custom-header"), a.entries)
        val b = decode(c41)
        assertNull(b.error)
        assertEquals(listOf(":method" to "GET", ":scheme" to "http", ":path" to "/", ":authority" to "www.example.com"), b.entries)
    }

    @Test
    fun `indexed field with index 0 is an error`() {
        val h = decode(bytes(0x80))
        assertTrue(h.error!!, h.error!!.contains("invalid index 0"))
        assertTrue(h.entries.isEmpty())
    }

    @Test
    fun `index beyond static and dynamic tables is an error but earlier fields are kept`() {
        val h = decode(bytes(0x82, 0xBE)) // :method GET, then dynamic index 62 on an empty table
        assertEquals(listOf(":method" to "GET"), h.entries)
        assertTrue(h.error!!, h.error!!.contains("invalid index 62"))
    }

    @Test
    fun `integer overflow via endless continuation octets is bounded and reported`() {
        // 7-bit prefix all ones followed by 8 continuation bytes with the high bit set.
        val h = decode(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        assertTrue(h.error!!, h.error!!.contains("integer overflow"))
        // Exactly 2^28-1 (five continuation octets) is representable but points nowhere.
        val big = decode(bytes(0xFF, 0x80, 0xFF, 0xFF, 0xFF, 0x7F))
        assertTrue(big.error!!, big.error!!.contains("invalid index") || big.error!!.contains("overflow"))
    }

    @Test
    fun `truncated integer and truncated string literal are errors`() {
        assertTrue(decode(bytes(0xFF)).error!!.contains("truncated"))
        // Literal without indexing, new name of length 10 but only 3 octets follow.
        val h = decode(bytes(0x00, 0x0A, 0x61, 0x62, 0x63))
        assertTrue(h.error!!, h.error!!.contains("bad string literal"))
        // Name decoded, value missing entirely.
        val h2 = decode(bytes(0x00, 0x01, 0x61))
        assertTrue(h2.error!!, h2.error!!.contains("bad string literal"))
    }

    @Test
    fun `string length pointing past the block is an error`() {
        val h = decode(bytes(0x00, 0x7F, 0x80, 0x01, 0x61)) // name length 255 via continuation
        assertNotNull(h.error)
    }

    @Test
    fun `huffman EOS inside a string, long padding and non-ones padding are errors`() {
        // Huffman-coded 'a' (5 bits 00011) then EOS (30 ones): 0001 1111 1111 1111 1111 1111 1111 1111 111x
        val eos = bytes(0x00, 0x01, 0x61, 0x85, 0x1F, 0xFF, 0xFF, 0xFF, 0xF8) // name "a" raw, value huffman 5 octets
        assertTrue(decode(eos).error!!, decode(eos).error!!.contains("bad string literal"))
        // 'a' then a full octet of padding (8 ones > 7 allowed).
        val longPad = bytes(0x00, 0x01, 0x61, 0x82, 0x1F, 0xFF)
        assertNotNull(decode(longPad).error)
        // 'a' padded with zeros instead of ones.
        val zeroPad = bytes(0x00, 0x01, 0x61, 0x81, 0x18)
        assertNotNull(decode(zeroPad).error)
        // The same 'a' with correct 3-bit ones padding is fine.
        val ok = decode(bytes(0x00, 0x01, 0x61, 0x81, 0x1F))
        assertNull(ok.error)
        assertEquals(listOf("a" to "a"), ok.entries)
    }

    @Test
    fun `dynamic table size update above the acknowledged setting is an error`() {
        val state = Http2Parser.HpackDecoder()
        // 0x3F 0x80 0x20 == 31 + 4096 = 4127 > default 4096.
        val h = decode(bytes(0x3F, 0x80, 0x20, 0x82), state)
        assertTrue(h.error!!, h.error!!.contains("exceeds SETTINGS_HEADER_TABLE_SIZE 4096"))
        assertTrue(h.entries.isEmpty())
        // Raise the setting and the same update is legal.
        state.setMaxHeaderTableSize(8192)
        val ok = decode(bytes(0x3F, 0x80, 0x20, 0x82), state)
        assertNull(ok.error)
        assertEquals(listOf(":method" to "GET"), ok.entries)
    }

    @Test
    fun `dynamic table size update after a header field is an error`() {
        val h = decode(bytes(0x82, 0x20))
        assertEquals(listOf(":method" to "GET"), h.entries)
        assertTrue(h.error!!, h.error!!.contains("after a header field"))
    }

    @Test
    fun `shrinking the table evicts entries and a later reference to them fails cleanly`() {
        val state = Http2Parser.HpackDecoder()
        assertNull(decode(c21, state).error)
        assertEquals(1, state.dynamicCount)
        assertEquals(listOf("custom-key" to "custom-header"), decode(bytes(0xBE), state).entries)
        // Size update to 0 evicts everything; the following index 62 is now invalid.
        val h = decode(bytes(0x20, 0xBE), state)
        assertEquals(0, state.dynamicCount)
        assertTrue(h.error!!, h.error!!.contains("invalid index 62"))
        // SETTINGS shrink applied out of band evicts too.
        assertNull(decode(c21, state).error)
        state.setMaxHeaderTableSize(10)
        assertEquals(0, state.dynamicCount)
        assertEquals(10, state.maxHeaderTableSize)
    }

    @Test
    fun `padding and priority framing faults are reported`() {
        val padded = Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_PADDED
        assertTrue(decode(bytes(0x10, 0x82), flags = padded).error!!.contains("padding"))
        assertTrue(decode(ByteArray(0), flags = padded).error!!.contains("PADDED"))
        val prio = Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_PRIORITY
        assertTrue(decode(bytes(0x00, 0x00, 0x00, 0x01), flags = prio).error!!.contains("PRIORITY"))
        // Well-formed padding + priority around a real block still decodes.
        val ok = decode(bytes(0x02, 0x00, 0x00, 0x00, 0x00, 0x10, 0x82, 0x00, 0x00), flags = padded or prio)
        assertNull(ok.error)
        assertEquals(listOf(":method" to "GET"), ok.entries)
    }

    @Test
    fun `a faulty block does not poison the decoder for the next well-formed block`() {
        val state = Http2Parser.HpackDecoder()
        assertNotNull(decode(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F), state).error)
        assertNotNull(decode(bytes(0x00, 0x0A, 0x61), state).error)
        val ok = decode(c41, state)
        assertNull(ok.error)
        assertEquals(4, ok.entries.size)
    }

    @Test
    fun `raw literals keep UTF-8 when valid and fall back to ISO-8859-1 otherwise`() {
        val utf8 = "caf\u00e9".toByteArray(Charsets.UTF_8) // 5 octets
        val block = bytes(0x00, 0x01, 0x61, utf8.size) + utf8
        assertEquals("caf\u00e9", decode(block).entries.single().second)
        val invalid = bytes(0x00, 0x01, 0x61, 0x02, 0xC3, 0x28) // dangling lead byte
        assertEquals("\u00c3(", decode(invalid).entries.single().second)
    }

    // -- frame layer --------------------------------------------------------------------------

    private fun frame(type: Int, flags: Int, streamId: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        return bytes(len ushr 16, (len ushr 8) and 0xFF, len and 0xFF, type, flags, streamId ushr 24, (streamId ushr 16) and 0xFF, (streamId ushr 8) and 0xFF, streamId and 0xFF) + payload
    }

    @Test
    fun `reserved stream id bit is ignored and partial frames wait for more bytes`() {
        val f = frame(Http2Parser.FRAME_DATA, 0, 0x80000005.toInt(), bytes(1, 2, 3))
        val parsed = Http2Parser.parseFrames(f)
        assertEquals(1, parsed.size)
        assertEquals(5, parsed[0].streamId)
        // Header only / payload short by one byte: nothing consumed, nothing thrown.
        assertTrue(Http2Parser.parseFrames(f.copyOf(9)).isEmpty())
        assertTrue(Http2Parser.parseFrames(f.copyOf(f.size - 1)).isEmpty())
        assertTrue(Http2Parser.parseFrames(ByteArray(0)).isEmpty())
    }

    @Test
    fun `frames after a garbage frame with a valid length prefix are still recovered`() {
        val junk = frame(0x7B, 0xFF, 0x7FFFFFFF, ByteArray(40) { 0xEE.toByte() })
        val good = frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 3, c41)
        val parsed = Http2Parser.parseFrames(junk + good + junk)
        assertEquals(3, parsed.size)
        assertEquals("0x7b", Http2Parser.frameTypeName(parsed[0].type))
        assertEquals(3, parsed[1].streamId)
        assertNull(Http2Parser.decodeHeaders(parsed[1], parsed[1].payload).error)
    }

    @Test
    fun `data padding, settings and error codes never read out of bounds`() {
        val padTooBig = H2Frame(2, Http2Parser.FRAME_DATA, Http2Parser.FLAG_PADDED, 1, bytes(0xFF, 0x41))
        assertEquals(0, Http2Parser.stripDataPadding(padTooBig).size)
        val padExact = H2Frame(3, Http2Parser.FRAME_DATA, Http2Parser.FLAG_PADDED, 1, bytes(0x01, 0x41, 0x00))
        assertEquals("A", String(Http2Parser.stripDataPadding(padExact)))
        val oddSettings = H2Frame(7, Http2Parser.FRAME_SETTINGS, 0, 0, bytes(0, 4, 0, 1, 0, 0, 9))
        assertEquals(listOf(4 to 65536), Http2Parser.decodeSettings(oddSettings))
        assertEquals("?", Http2Parser.errorCodeName(bytes(0, 0)))
        assertEquals("0xdeadbeef", Http2Parser.errorCodeName(bytes(0xDE, 0xAD, 0xBE, 0xEF)))
        assertEquals("CANCEL", Http2Parser.errorCodeName(bytes(0, 0, 0, 8)))
    }
}
