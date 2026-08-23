package com.example.interception.quic

import com.example.interception.quic.Http3StreamParser.Event
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies [Http3StreamParser] — the HTTP/3 stream demultiplexer (RFC 9114) that feeds the production
 * [QpackDecoder]. Covers the four mandated scenarios from the v6.0 plan (Task 11): a control-stream
 * SETTINGS frame sets the QPACK dynamic-table capacity; an encoder-stream insert unblocks a previously
 * blocked request HEADERS; a request HEADERS decodes its pseudo-headers; DATA frames route to the
 * request vs response buffer by direction; and a reserved grease frame is skipped by length so the
 * following frame still parses. Plus an incremental split-delivery case exercising the per-stream
 * buffer tail.
 *
 * All wire bytes are hand-encoded per RFC 9114 / RFC 9204 (no external encoder) so the vectors are
 * self-describing and deterministic on the host JVM.
 */
class Http3StreamParserTest {

    // ── Wire encoders ───────────────────────────────────────────────────────────────────

    /** QUIC variable-length integer (RFC 9000 §16): 2-bit length class prefix. */
    private fun encodeQuicVarint(value: Long): ByteArray {
        require(value >= 0) { "varint must be non-negative" }
        return when {
            value <= 0x3F -> byteArrayOf(value.toByte())
            value <= 0x3FFF -> byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toByte())
            value <= 0x3FFFFFFF -> byteArrayOf(
                (0x80 or (value shr 24).toInt()).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )
            else -> ByteArray(8) { i ->
                if (i == 0) (0xC0 or (value shr 56).toInt()).toByte()
                else ((value shr ((7 - i) * 8)) and 0xFF).toByte()
            }
        }
    }

    /** An HTTP/3 frame: type varint + length varint + payload (RFC 9114 §7.1). */
    private fun h3Frame(type: Int, payload: ByteArray): ByteArray =
        encodeQuicVarint(type.toLong()) + encodeQuicVarint(payload.size.toLong()) + payload

    /** A SETTINGS identifier/value pair (varint id + varint value). */
    private fun setting(id: Int, value: Int): ByteArray =
        encodeQuicVarint(id.toLong()) + encodeQuicVarint(value.toLong())

    /** HPACK/QPACK prefix integer (RFC 7541 §5.1 / RFC 9204 §4.1.1) with an [prefixBits]-bit prefix. */
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
        return encodeInt(bytes.size, prefixBits) + bytes
    }

    /** Builds a byte array from int literals (so 0x80..0xff don't need `.toByte()` per element). */
    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** Static indexed field line (`1T` + 6-bit index) referencing static [idx]. */
    private fun indexedStatic(idx: Int): ByteArray = byteArrayOf((0xC0 or idx).toByte())

    /** Literal with name reference to static [nameIdx] + raw value (RFC 9204 §4.5.5). */
    private fun literalNameRefStatic(nameIdx: Int, value: String): ByteArray {
        val first = (0x40 or 0x10 or (nameIdx and 0x0F)).toByte() // 01 N=0 T=1(static) 4-bit prefix
        return byteArrayOf(first) + encodeStr(value, 7)
    }

    // Stream IDs (RFC 9000 §2.1): bit 0 = initiator (0 client), bit 1 = unidirectional (1).
    private val controlStream = 2L // client-initiated unidirectional
    private val encoderStream = 6L // client-initiated unidirectional
    private val requestStream = 0L // client-initiated bidirectional

    @Test
    fun `control stream SETTINGS sets the QPACK dynamic table capacity`() {
        val parser = Http3StreamParser()
        val settingsPayload = setting(0x01, 4096) + setting(0x07, 100) // QPACK_MAX_TABLE_CAPACITY, BLOCKED_STREAMS
        val controlBytes = b(0x00) + h3Frame(FRAME_SETTINGS, settingsPayload) // 0x00 = CONTROL stream-type prefix

        val events = parser.feed(controlStream, controlBytes, isOutbound = true)

        val settings = events.filterIsInstance<Event.Settings>().single()
        assertEquals(4096, settings.maxTableCapacity)
        assertEquals(100, settings.blockedStreams)
        // The capacity is applied to the parser's shared dynamic table and cached for inspection.
        assertEquals(4096, parser.maxTableCapacity)
        assertEquals(4096, parser.table.capacity())
        assertEquals(100, parser.blockedStreams)
    }

    @Test
    fun `a request HEADERS frame decodes method path and authority pseudo-headers`() {
        val parser = Http3StreamParser()
        // RIC=0 (0x00), Base=0 (0x00), then static-indexed :method=GET (17), :path=/ (1), and a
        // literal :authority=www.example.com (name ref to static 0).
        val section = b(0x00, 0x00) +
            indexedStatic(17) + // :method GET
            indexedStatic(1) + // :path /
            literalNameRefStatic(0, "www.example.com") // :authority www.example.com
        val requestBytes = h3Frame(FRAME_HEADERS, section)

        val events = parser.feed(requestStream, requestBytes, isOutbound = true)

        val headers = events.filterIsInstance<Event.RequestHeaders>().single()
        assertEquals(requestStream, headers.streamId)
        assertEquals("GET", headers.headers[":method"])
        assertEquals("/", headers.headers[":path"])
        assertEquals("www.example.com", headers.headers[":authority"])
    }

    @Test
    fun `an encoder stream insert unblocks a previously blocked request HEADERS`() {
        val parser = Http3StreamParser()

        // 1. Control-stream SETTINGS sets a QPACK capacity so a dynamic-table entry can be referenced.
        val settings = setting(0x01, 4096) + setting(0x07, 100)
        parser.feed(controlStream, b(0x00) + h3Frame(FRAME_SETTINGS, settings), isOutbound = true)

        // 2. Request HEADERS referencing a dynamic entry (Required Insert Count = 1) BEFORE the
        //    encoder stream has delivered the insert -> the section is blocked and yields no event.
        //    RIC enc=2 (req=1), Base=1, dynamic-indexed relative 0 (absolute = Base - 0 - 1 = 0).
        val section = b(0x02, 0x00, 0x80)
        val blocked = parser.feed(requestStream, h3Frame(FRAME_HEADERS, section), isOutbound = true)
        assertTrue("a blocked HEADERS yields no RequestHeaders event yet",
            blocked.none { it is Event.RequestHeaders })

        // 3. The QPACK encoder stream (stream-type prefix 0x02) delivers the insert: Insert With Name
        //    Reference to static 36 (cache-control) + value "no-cache-please".
        val encoderInsert = byteArrayOf((0x80 or 0x40 or 36).toByte()) + encodeStr("no-cache-please", 7)
        val unblocked = parser.feed(encoderStream, b(0x02) + encoderInsert, isOutbound = true)

        val headers = unblocked.filterIsInstance<Event.RequestHeaders>().single()
        assertEquals(requestStream, headers.streamId)
        assertEquals(1, headers.headers.size)
        assertEquals("no-cache-please", headers.headers["cache-control"])
    }

    @Test
    fun `DATA frames route to request or response by direction`() {
        val parser = Http3StreamParser()

        // Outbound (client->server) DATA on the request stream -> RequestData.
        val reqEvents = parser.feed(requestStream, h3Frame(FRAME_DATA, "hello".toByteArray()), isOutbound = true)
        val req = reqEvents.filterIsInstance<Event.RequestData>().single()
        assertEquals(requestStream, req.streamId)
        assertEquals("hello", String(req.bytes))

        // Inbound (server->client) DATA on the same bidirectional stream -> ResponseData.
        val respEvents = parser.feed(requestStream, h3Frame(FRAME_DATA, "world".toByteArray()), isOutbound = false)
        val resp = respEvents.filterIsInstance<Event.ResponseData>().single()
        assertEquals(requestStream, resp.streamId)
        assertEquals("world", String(resp.bytes))
    }

    @Test
    fun `a grease frame is skipped by length and the following frame still parses`() {
        val parser = Http3StreamParser()
        // 0x21 = reserved grease frame type (0x1f*0 + 0x21, RFC 9114 §7.2.8); silently discarded.
        val grease = h3Frame(0x21, "grease-bytes".toByteArray())
        // A response HEADERS immediately after: :status=200 (static 25).
        val section = b(0x00, 0x00) + indexedStatic(25)
        val headers = h3Frame(FRAME_HEADERS, section)

        val events = parser.feed(requestStream, grease + headers, isOutbound = false)

        // No event for the grease frame; the HEADERS frame parses as a response.
        val resp = events.filterIsInstance<Event.ResponseHeaders>().single()
        assertEquals(requestStream, resp.streamId)
        assertEquals("200", resp.status)
    }

    @Test
    fun `a frame split across two deliveries buffers its tail and then parses`() {
        val parser = Http3StreamParser()
        val section = b(0x00, 0x00) + indexedStatic(25) // :status 200
        val frame = h3Frame(FRAME_HEADERS, section)
        val split = frame.size / 2

        val first = parser.feed(requestStream, frame.copyOfRange(0, split), isOutbound = false)
        assertTrue("a partial frame yields no event", first.isEmpty())

        val second = parser.feed(requestStream, frame.copyOfRange(split, frame.size), isOutbound = false)
        val resp = second.filterIsInstance<Event.ResponseHeaders>().single()
        assertEquals("200", resp.status)
    }

    private companion object {
        // HTTP/3 frame types (RFC 9114 §7.2).
        const val FRAME_DATA = 0x00
        const val FRAME_HEADERS = 0x01
        const val FRAME_SETTINGS = 0x04
    }
}
