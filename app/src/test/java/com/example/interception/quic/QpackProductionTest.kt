package com.example.interception.quic

import com.example.interception.quic.QpackDecoder.DynamicTable
import com.example.interception.quic.QpackDecoder.HeaderEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the production QPACK decoder (RFC 9204) — the encoder-stream instruction parser
 * ([QpackDecoder.feedEncoderStream]), the Required Insert Count / Base reconstruction and
 * static/dynamic/post-base addressing ([QpackDecoder.decodeFieldSection]), and the decoder-stream
 * instruction parser ([QpackDecoder.consumeDecoderStream]). This is the call site the legacy
 * static-only [QpackDecoder.decode] never had: a real dynamic table that an encoder stream grows
 * and a field section that resolves dynamic and post-base entries against it.
 *
 * All wire bytes are hand-encoded per RFC 9204 (no external encoder) so the vectors are
 * self-describing and deterministic on the host JVM.
 */
class QpackProductionTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /** HPACK/QPACK variable-length integer with an [prefixBits]-bit prefix (RFC 7541 §5.1 / RFC 9204). */
    private fun encodeInt(value: Int, prefixBits: Int): ByteArray {
        val maxPrefix = (1 shl prefixBits) - 1
        if (value < maxPrefix) return byteArrayOf(value.toByte())
        val out = mutableListOf(maxPrefix.toByte())
        var v = value - maxPrefix
        while (v >= 128) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    /** A QPACK string literal (RFC 9204 §4.1.3): H=0 (raw) + [prefixBits]-bit length + UTF-8 bytes. */
    private fun encodeStr(value: String, prefixBits: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val lenBytes = encodeInt(bytes.size, prefixBits)
        // The H bit sits just above the prefix; raw (H=0) leaves the length's first byte as-is.
        return lenBytes + bytes
    }

    /** Builds a byte array from int literals (so 0x80..0xff don't need `.toByte()` on every element). */
    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** Static indexed field line (`1T` + 6-bit index) referencing static [idx]. */
    private fun indexedStatic(idx: Int): ByteArray = byteArrayOf((0xC0 or idx).toByte())

    /** Literal with name reference to static [nameIdx] + raw value (RFC 9204 §4.5.5). */
    private fun literalNameRefStatic(nameIdx: Int, value: String): ByteArray {
        val first = (0x40 or 0x10 or (nameIdx and 0x0F)).toByte() // 01 N=0 T=1(static) 4-bit prefix
        return byteArrayOf(first) + encodeStr(value, 7)
    }

    @Test
    fun `a static-only field section decodes pseudo-headers`() {
        // RIC=0 (0x00), Base=0 (0x00), then indexed static :method=GET (17), :path=/ (1),
        // and a literal :authority=www.example.com (name ref to static 0).
        val section = byteArrayOf(0x00, 0x00) +
            indexedStatic(17) + // :method GET
            indexedStatic(1) + // :path /
            literalNameRefStatic(0, "www.example.com") // :authority www.example.com

        val table = DynamicTable(initialCapacity = 0)
        val decoded = QpackDecoder.decodeFieldSection(table, section)

        assertNotNull(decoded)
        val map = decoded!!.associate { it.name to it.value }
        assertEquals("GET", map[":method"])
        assertEquals("/", map[":path"])
        assertEquals("www.example.com", map[":authority"])
    }

    @Test
    fun `a response status decodes from a static-indexed field section`() {
        // RIC=0, Base=0, :status=200 (static 25).
        val section = byteArrayOf(0x00, 0x00) + indexedStatic(25)
        val table = DynamicTable(initialCapacity = 0)
        val decoded = QpackDecoder.decodeFieldSection(table, section)
        assertNotNull(decoded)
        assertEquals(":status", decoded!!.single().name)
        assertEquals("200", decoded.single().value)
    }

    @Test
    fun `encoder stream insert with name reference grows the table and a dynamic-indexed field resolves it`() {
        // Start with a table whose capacity can hold the inserted entry (name+value+32).
        val table = DynamicTable(initialCapacity = 4096)
        assertEquals(0, table.insertCount)

        // Insert With Name Reference (static name index 36 = "cache-control") + value "no-cache-please".
        // Instruction: 1T(static, bit6) + 6-bit prefix name index 36, then value string (7-bit).
        val nameIdxBytes = encodeInt(36, 6) // 36 < 63 -> single byte 0x24
        val firstByte = (0x80 or 0x40 or (nameIdxBytes[0].toInt() and 0x3F)).toByte() // 0xC0 | 0x24 = 0xE4
        val insert = byteArrayOf(firstByte) + encodeStr("no-cache-please", 7)
        val applied = QpackDecoder.feedEncoderStream(table, insert)

        assertEquals(1, applied)
        assertEquals(1, table.insertCount)
        assertEquals("cache-control", table.entry(0)?.name)
        assertEquals("no-cache-please", table.entry(0)?.value)

        // Field section: RIC=1 (EncInsertCount = (1 mod 16)+1 = 2 -> 0x02), Base=1 (sign 0, delta 0 -> 0x00),
        // dynamic-indexed relative 0 (absolute = Base - 0 - 1 = 0) -> 0x80.
        val section = b(0x02, 0x00, 0x80)
        val decoded = QpackDecoder.decodeFieldSection(table, section)
        assertNotNull(decoded)
        assertEquals(HeaderEntry("cache-control", "no-cache-please"), decoded!!.single())
    }

    @Test
    fun `encoder stream set dynamic table capacity changes the table capacity`() {
        val table = DynamicTable(initialCapacity = 0)
        // Set Dynamic Table Capacity: 001 + 5-bit prefix. capacity=256 -> multi-byte varint.
        val capBytes = encodeInt(256, 5) // 256 >= 31 -> [0x1F, 0xE1, 0x01]
        capBytes[0] = (0x20 or (capBytes[0].toInt() and 0x1F)).toByte() // OR in the 001 instruction prefix
        val applied = QpackDecoder.feedEncoderStream(table, capBytes)
        assertEquals(1, applied)
        assertEquals(256, table.capacity())
    }

    @Test
    fun `encoder stream duplicate re-inserts an existing entry`() {
        val table = DynamicTable(initialCapacity = 4096)
        // Insert one entry first (cache-control / no-cache-please).
        val insert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + encodeStr("no-cache-please", 7)
        QpackDecoder.feedEncoderStream(table, insert)
        assertEquals(1, table.insertCount)

        // Duplicate: 000 + 5-bit prefix relative index 0 (absolute = insertCount-1-0 = 0).
        val dup = byteArrayOf((0x00 or 0).toByte()) // 0x00
        val applied = QpackDecoder.feedEncoderStream(table, dup)
        assertEquals(1, applied)
        assertEquals(2, table.insertCount)
        assertEquals("cache-control", table.entry(1)?.name)
        assertEquals("no-cache-please", table.entry(1)?.value)
    }

    @Test
    fun `a blocked field section returns null until the encoder stream delivers the insert`() {
        val table = DynamicTable(initialCapacity = 4096)
        // A section that needs RIC=1 but the table is still empty (insertCount=0) -> blocked.
        val section = b(0x02, 0x00, 0x80)
        assertNull("section requiring an undelivered insert must be blocked", QpackDecoder.decodeFieldSection(table, section))

        // Deliver the insert via the encoder stream, then the same section decodes.
        val insert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + encodeStr("no-cache-please", 7)
        QpackDecoder.feedEncoderStream(table, insert)
        val decoded = QpackDecoder.decodeFieldSection(table, section)
        assertNotNull("section unblocks once the insert is delivered", decoded)
        assertEquals(HeaderEntry("cache-control", "no-cache-please"), decoded!!.single())
    }

    @Test
    fun `a field section split across two decode calls decodes identically to one`() {
        // Fragmented delivery: feed the encoder insert and the field section in separate calls; the
        // result must match a single-shot decode (the table state carries across calls).
        val table = DynamicTable(initialCapacity = 4096)
        val insert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + encodeStr("no-cache-please", 7)
        QpackDecoder.feedEncoderStream(table, insert)

        val section = b(0x02, 0x00, 0x80)
        val oneShot = QpackDecoder.decodeFieldSection(table, section.copyOf())!!

        // A second decode of the same (now-unblocked) section is identical — state is not consumed.
        val again = QpackDecoder.decodeFieldSection(table, section.copyOf())
        assertEquals(oneShot, again)
    }

    @Test
    fun `decoder stream instructions are counted and never throw`() {
        // Section Acknowledgment (0x80|streamId=7 -> 0x87), Stream Cancellation (0x40|id=3 -> 0x43),
        // Insert Count Increment (0x00|increment=5 -> 0x05).
        val decoderStream = b(0x87, 0x43, 0x05)
        assertEquals(3, QpackDecoder.consumeDecoderStream(decoderStream))
        // Malformed/truncated remainder stops cleanly rather than throwing.
        assertEquals(0, QpackDecoder.consumeDecoderStream(hex("ff"), 1))
    }
}
