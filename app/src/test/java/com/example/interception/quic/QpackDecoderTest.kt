package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for [QpackDecoder] — specifically the v5.6 (P2) fix to [QpackDecoder]'s
 * Huffman-coded string handling (previously both the Huffman and non-Huffman branches of
 * `decodeStr` did an identical plain UTF-8 decode, silently corrupting any Huffman-coded QPACK
 * string).
 *
 * QPACK (RFC 9204 §4.1.2) mandates reusing the RFC 7541 Appendix B Huffman table unmodified, so
 * this reuses the exact same "www.example.com" Huffman encoding from RFC 7541 Appendix C.4.1
 * that [com.example.interception.grpc.GrpcParserTests] already verifies against
 * [com.example.interception.http.Http2Parser]'s HPACK decoder — proving [QpackDecoder] and
 * [Http2Parser] now decode Huffman-coded strings identically via the shared
 * [com.example.interception.http.HpackHuffman] table, as RFC 9204 requires.
 *
 * [QpackDecoder.decode] itself has no production call site yet (real QUIC stream/frame
 * demultiplexing -- reading HTTP/3 HEADERS frames off a [QuicStream] -- is still open P2 work;
 * see TODO_v5.md), so this test is what currently exercises it end-to-end and guards the bug
 * fix above from regressing before that wiring lands.
 */
class QpackDecoderTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `decodes a huffman-coded literal field line with name reference`() {
        // 2-byte required-insert-count prefix (0x0000) that decode() always skips, then one
        // "Literal Field Line With Name Reference" (RFC 9204 §4.5.4): 0x40 = static name index 0
        // (":authority"), followed by a Huffman-coded value string: 0x8c = H-bit set + 12-byte
        // length, then the RFC 7541 Appendix C.4.1 encoding of "www.example.com".
        val payload = hex("0000") + hex("40") + hex("8c") + hex("f1e3c2e5f23a6ba0ab90f4ff")

        val (_, entries) = QpackDecoder.decode(payload)

        assertEquals(1, entries.size)
        assertEquals(":authority", entries[0].name)
        assertEquals("www.example.com", entries[0].value)
    }

    @Test
    fun `decodes a plain (non-huffman) literal field line with name reference`() {
        // Same name reference (idx 0 = ":authority") but a plain (non-Huffman) 3-byte ASCII
        // value "abc": 0x03 length prefix (H-bit clear) then the raw bytes.
        val payload = hex("0000") + hex("40") + hex("03") + "abc".toByteArray(Charsets.US_ASCII)

        val (_, entries) = QpackDecoder.decode(payload)

        assertEquals(1, entries.size)
        assertEquals(":authority", entries[0].name)
        assertEquals("abc", entries[0].value)
    }

    @Test
    fun `decodes an indexed field line from the static table`() {
        // 0x80 | 17 = indexed field line referencing static index 17 (":method" = "GET").
        val payload = hex("0000") + hex("80" .let { (0x80 or 17).toString(16).padStart(2, '0') })

        val (_, entries) = QpackDecoder.decode(payload)

        assertEquals(1, entries.size)
        assertEquals(":method", entries[0].name)
        assertEquals("GET", entries[0].value)
    }
}
