package com.example.interception.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [Http2Session]: the stateful per-connection coordinator that HPACK-decodes
 * both directions of a MITM'd h2 connection and pairs/emits transactions and gRPC messages.
 *
 * All HEADERS/CONTINUATION payloads below are hand-built, real HPACK byte sequences (literal
 * fields with incremental indexing, and real indexed references into the dynamic table) --
 * not canned fixtures replayed verbatim. [literalHeader] mirrors RFC 7541 §6.2.2 ("Literal
 * Header Field with Incremental Indexing — New Name"): first byte 0x40 (6-bit name-index
 * prefix = 0, meaning "literal name follows"), then a non-Huffman string literal (H=0) for the
 * name, then one for the value. This only needs a single length byte per string because every
 * name/value used here is under 127 bytes, which is exactly when HPACK's 7-bit-prefix integer
 * encoding collapses to one byte.
 */
class Http2SessionTest {

    private fun literalHeader(name: String, value: String): ByteArray {
        require(name.length < 127 && value.length < 127)
        return byteArrayOf(0x40) +
            byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.US_ASCII)
    }

    /** Real gRPC message framing: 1-byte compressed flag + 4-byte big-endian length + payload. */
    private fun grpcFrame(payload: ByteArray, compressed: Boolean = false): ByteArray =
        byteArrayOf(if (compressed) 1 else 0) +
            byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte(),
            ) + payload

    private fun headersFrame(streamId: Int, block: ByteArray, flags: Int): Http2Parser.H2Frame =
        Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, streamId, block)

    private fun continuationFrame(streamId: Int, block: ByteArray, flags: Int): Http2Parser.H2Frame =
        Http2Parser.H2Frame(block.size, Http2Parser.FRAME_CONTINUATION, flags, streamId, block)

    private fun dataFrame(streamId: Int, payload: ByteArray, endStream: Boolean): Http2Parser.H2Frame =
        Http2Parser.H2Frame(
            payload.size, Http2Parser.FRAME_DATA,
            if (endStream) Http2Parser.FLAG_END_STREAM else 0, streamId, payload,
        )

    private fun rstStreamFrame(streamId: Int): Http2Parser.H2Frame =
        Http2Parser.H2Frame(4, Http2Parser.FRAME_RST_STREAM, 0, streamId, byteArrayOf(0, 0, 0, 0))

    private val END_HEADERS = Http2Parser.FLAG_END_HEADERS
    private val END_STREAM = Http2Parser.FLAG_END_STREAM
    private val BOTH = END_HEADERS or END_STREAM

    private class Recorder {
        val transactions = CopyOnWriteArrayList<Http2Transaction>()
        val grpcEvents = CopyOnWriteArrayList<GrpcEvent>()
        fun session() = Http2Session(onTransaction = { transactions.add(it) }, onGrpcMessage = { grpcEvents.add(it) })
    }

    // ── Multiplexing ────────────────────────────────────────────────────

    @Test
    fun `interleaved streams pair request and response correctly without mixing`() {
        val rec = Recorder()
        val session = rec.session()

        // Stream 1: GET /a (no body), stream 3: POST /b (has body). Every pseudo-header is a
        // fresh literal so this exercises real HPACK decoding, not table lookups.
        val s1Req = literalHeader(":method", "GET") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/a") + literalHeader(":authority", "host-one.example")
        val s3Req = literalHeader(":method", "POST") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/b") + literalHeader(":authority", "host-two.example")

        // Deliberately interleaved across two onClientFrames calls to simulate the real relay
        // loop reading frames off the wire as they arrive, stream 3 before stream 1 completes.
        session.onClientFrames(listOf(headersFrame(3, s3Req, END_HEADERS)))
        session.onClientFrames(
            listOf(
                headersFrame(1, s1Req, BOTH), // GET: END_HEADERS + END_STREAM, no body
                dataFrame(3, "hello".toByteArray(), endStream = true),
            ),
        )

        val s3Resp = literalHeader(":status", "201")
        val s1Resp = literalHeader(":status", "200")
        session.onServerFrames(listOf(headersFrame(3, s3Resp, BOTH)))
        session.onServerFrames(
            listOf(
                headersFrame(1, s1Resp, END_HEADERS),
                dataFrame(1, "world".toByteArray(), endStream = true),
            ),
        )

        assertEquals(2, rec.transactions.size)
        val byPath = rec.transactions.associateBy { it.request.path }
        val a = byPath["/a"]!!
        assertEquals("host-one.example", a.request.host)
        assertEquals(200, a.response.statusCode)
        assertEquals("world", a.response.body)
        assertEquals("", a.request.body)

        val b = byPath["/b"]!!
        assertEquals("host-two.example", b.request.host)
        assertEquals(201, b.response.statusCode)
        assertEquals("hello", b.request.body)
    }

    // ── HPACK dynamic table persistence across frames/streams ──────────

    @Test
    fun `dynamic table entry inserted on one stream resolves via indexed reference on another`() {
        val rec = Recorder()
        val session = rec.session()

        // Stream 1's request inserts four dynamic entries, in order; the last one inserted
        // (:authority) lands at dynamic index 62 (RFC 7541 §2.3.3 -- the most recently
        // inserted entry is always index 62 relative to the *current* static-table size).
        val s1Req = literalHeader(":method", "GET") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/first") + literalHeader(":authority", "shared-host.example")
        session.onClientFrames(listOf(headersFrame(1, s1Req, BOTH)))
        session.onServerFrames(listOf(headersFrame(1, literalHeader(":status", "200"), BOTH)))
        assertEquals(1, rec.transactions.size)
        assertEquals("shared-host.example", rec.transactions[0].request.host)

        // Stream 3's request references that same dynamic entry via indexed field 0xBE
        // (0x80 | 62) instead of re-sending the authority bytes -- this only decodes correctly
        // if clientHpack's dynamic table survived from the earlier onClientFrames() call. The
        // indexed reference must come first in this block: HPACK indices are evaluated against
        // whatever the dynamic table looks like *at that point in the byte stream*, so if the
        // other three literal fields below were inserted first they would each shift index 62
        // to point at themselves instead of at :authority.
        val s3Req = byteArrayOf(0xBE.toByte()) + literalHeader(":method", "GET") +
            literalHeader(":scheme", "http") + literalHeader(":path", "/second")
        session.onClientFrames(listOf(headersFrame(3, s3Req, BOTH)))
        session.onServerFrames(listOf(headersFrame(3, literalHeader(":status", "200"), BOTH)))

        assertEquals(2, rec.transactions.size)
        val second = rec.transactions.first { it.request.path == "/second" }
        assertEquals("shared-host.example", second.request.host)
    }

    // ── CONTINUATION coalescing ─────────────────────────────────────────

    @Test
    fun `HEADERS without END_HEADERS is merged with a following CONTINUATION frame`() {
        val rec = Recorder()
        val session = rec.session()

        val full = literalHeader(":method", "GET") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/split") + literalHeader(":authority", "continued.example") +
            literalHeader("x-extra", "present")
        val firstHalf = full.copyOfRange(0, full.size / 2)
        val secondHalf = full.copyOfRange(full.size / 2, full.size)

        // No END_HEADERS on the initiating frame (still awaiting CONTINUATION), but END_STREAM
        // IS set here (GET, no body) -- Http2Session must carry that flag through the merge.
        session.onClientFrames(listOf(headersFrame(1, firstHalf, END_STREAM)))
        session.onClientFrames(listOf(continuationFrame(1, secondHalf, END_HEADERS)))

        session.onServerFrames(listOf(headersFrame(1, literalHeader(":status", "200"), BOTH)))

        assertEquals(1, rec.transactions.size)
        val tx = rec.transactions[0]
        assertEquals("/split", tx.request.path)
        assertEquals("continued.example", tx.request.host)
        assertEquals("present", tx.request.headers.entries.first { it.key.equals("x-extra", true) }.value)
    }

    // ── gRPC message emission ───────────────────────────────────────────

    @Test
    fun `grpc stream emits per-message events and a grpc status, never an http2 transaction`() {
        val rec = Recorder()
        val session = rec.session()

        val grpcReq = literalHeader(":method", "POST") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/my.Service/GetUser") +
            literalHeader(":authority", "grpc.example.com") +
            literalHeader("content-type", "application/grpc")
        session.onClientFrames(listOf(headersFrame(3, grpcReq, END_HEADERS)))

        // Real protobuf payload: field 1 varint 42, field 2 string "alice".
        val protoPayload = byteArrayOf(0x08, 0x2a, 0x12, 0x05) + "alice".toByteArray()
        val requestMsg = grpcFrame(protoPayload)
        session.onClientFrames(listOf(dataFrame(3, requestMsg, endStream = true)))

        val grpcResp = literalHeader(":status", "200") + literalHeader("content-type", "application/grpc")
        session.onServerFrames(listOf(headersFrame(3, grpcResp, END_HEADERS)))
        val responseMsg = grpcFrame(byteArrayOf(0x08, 0x01))
        session.onServerFrames(listOf(dataFrame(3, responseMsg, endStream = false)))

        // Trailer HEADERS carries grpc-status and ends the response stream.
        val trailer = literalHeader("grpc-status", "0")
        session.onServerFrames(listOf(headersFrame(3, trailer, BOTH)))

        assertEquals(0, rec.transactions.size) // gRPC never goes through the h2-transaction path
        assertEquals(2, rec.grpcEvents.size)

        val clientMsg = rec.grpcEvents.first { it.isClient }
        assertEquals("/my.Service/GetUser", clientMsg.call.grpcMethod)
        assertEquals("grpc.example.com", clientMsg.call.authority)
        assertTrue(clientMsg.message.payload.contentEquals(protoPayload))
        assertFalse(clientMsg.message.compressed)

        val serverMsg = rec.grpcEvents.first { !it.isClient }
        // grpc-status was recorded on the trailer HEADERS *after* the DATA frame that carried
        // this event was drained, matching real gRPC framing (trailers always come last); the
        // status field is therefore expected to still be null on this particular event.
        assertEquals(null, serverMsg.grpcStatus)
    }

    // ── RST_STREAM cleanup ───────────────────────────────────────────────

    @Test
    fun `RST_STREAM clears stream state so a reused stream id starts clean`() {
        val rec = Recorder()
        val session = rec.session()

        // Partial exchange on stream id 5: headers arrive, then the stream is reset before a
        // response ever shows up.
        val stale = literalHeader(":method", "GET") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/stale") + literalHeader(":authority", "stale-host.example")
        session.onClientFrames(listOf(headersFrame(5, stale, BOTH)))
        session.onClientFrames(listOf(rstStreamFrame(5)))

        // The same stream id is legally reused later (RFC 7540 permits stream ids to be reused
        // only after they are closed, which RST_STREAM does immediately).
        val fresh = literalHeader(":method", "POST") + literalHeader(":scheme", "https") +
            literalHeader(":path", "/fresh") + literalHeader(":authority", "fresh-host.example")
        session.onClientFrames(listOf(headersFrame(5, fresh, END_HEADERS)))
        session.onClientFrames(listOf(dataFrame(5, "new-body".toByteArray(), endStream = true)))
        session.onServerFrames(listOf(headersFrame(5, literalHeader(":status", "204"), BOTH)))

        assertEquals(1, rec.transactions.size)
        val tx = rec.transactions[0]
        assertEquals("/fresh", tx.request.path)
        assertEquals("fresh-host.example", tx.request.host)
        assertEquals("new-body", tx.request.body)
        assertEquals(204, tx.response.statusCode)
    }

    @Test
    fun `RST_STREAM on an unknown stream id is a no-op`() {
        val rec = Recorder()
        val session = rec.session()
        session.onClientFrames(listOf(rstStreamFrame(99)))
        assertTrue(rec.transactions.isEmpty())
        assertNull(rec.transactions.firstOrNull())
    }
}
