package com.example.interception.grpc

import com.example.interception.http.Http2Parser
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the gRPC capture path: HPACK (incl. Huffman) header
 * decoding, gRPC detection from HEADERS, length-prefixed message framing,
 * and protobuf wire-format decoding.
 *
 * HPACK blocks are hand-built; Huffman-coded strings use the RFC 7541
 * Appendix B code (encoder verified against the RFC 7541 Appendix C.4
 * reference vectors).
 */
class GrpcParserTests {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun headersFrame(streamId: Int, block: ByteArray, flags: Int = 0x04): Http2Parser.H2Frame =
        Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, streamId, block)

    // ── HPACK / Huffman ────────────────────────────────────────────────

    @Test
    fun `RFC 7541 C4-1 huffman header block decodes`() {
        // C.4.1 first request: 8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff
        val block = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")
        val headers = Http2Parser.decodeHeaders(headersFrame(1, block), block)
        assertEquals("GET", headers.pseudoHeaders[":method"])
        assertEquals("http", headers.pseudoHeaders[":scheme"])
        assertEquals("/", headers.pseudoHeaders[":path"])
        assertEquals("www.example.com", headers.pseudoHeaders[":authority"])
    }

    @Test
    fun `dynamic table resolves incremental-indexing references`() {
        // Re-using the C.4.1 block inserts :authority=www.example.com at
        // dynamic index 62; 0xbe is an indexed reference to it (RFC C.4.2).
        val state = Http2Parser.HpackDecoder()
        val block1 = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")
        Http2Parser.decodeHeaders(headersFrame(1, block1), block1, state)
        val block2 = hex("828684be") // GET, http, /, idx 62
        val second = Http2Parser.decodeHeaders(headersFrame(3, block2), block2, state)
        assertEquals("www.example.com", second.pseudoHeaders[":authority"])
    }

    // ── GrpcDetector ───────────────────────────────────────────────────

    @Test
    fun `grpc content-type variants match`() {
        assertTrue(GrpcDetector.isGrpcContentType("application/grpc"))
        assertTrue(GrpcDetector.isGrpcContentType("application/grpc+proto"))
        assertTrue(GrpcDetector.isGrpcContentType("application/grpc+json"))
        assertTrue(GrpcDetector.isGrpcContentType("Application/GRPC"))
        assertFalse(GrpcDetector.isGrpcContentType("application/json"))
        assertFalse(GrpcDetector.isGrpcContentType(null))
    }

    // HEADERS block: 0x83 :method POST | 0x86 :scheme http |
    // 0x41 :authority=huff("grpc.example.com") | 0x44 :path=huff("/my.Service/GetUser") |
    // 0x5f content-type=huff("application/grpc")
    private val grpcHeadersBlock = hex(
        "8386" +
            "418c" + "9acac8b97c8e9ae82ae43d3f" +
            "448e" + "629f4bee2d9dcc42b188a9e082d9" +
            "5f8b" + "1d75d0620d263d4c4d6564"
    )

    @Test
    fun `grpc call extracted from HEADERS frame`() {
        val headers = Http2Parser.decodeHeaders(headersFrame(3, grpcHeadersBlock), grpcHeadersBlock)
        val call = GrpcDetector.callFromHeaders(headers)
        assertNotNull(call)
        call!!
        assertEquals(3, call.streamId)
        assertEquals("/my.Service/GetUser", call.grpcMethod)
        assertEquals("grpc.example.com", call.authority)
        assertEquals("POST", call.httpMethod)
        assertEquals("my.Service", call.service)
        assertEquals("GetUser", call.rpcName)
        assertEquals("GetUserRequest", call.messageType)
        assertEquals("proto", call.encoding)
        assertEquals("http://grpc.example.com/my.Service/GetUser", call.url)
    }

    @Test
    fun `non-grpc headers do not produce a call`() {
        val block = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff") // plain GET, no content-type
        val headers = Http2Parser.decodeHeaders(headersFrame(1, block), block)
        assertNull(GrpcDetector.callFromHeaders(headers))
    }

    @Test
    fun `grpc-status parsed from trailers`() {
        // literal-without-indexing, literal name "grpc-status", value "0"
        val block = hex("00") + byteArrayOf(0x0b) + "grpc-status".toByteArray() + byteArrayOf(0x01) + "0".toByteArray()
        val headers = Http2Parser.decodeHeaders(headersFrame(3, block, flags = 0x05), block)
        assertEquals(0, GrpcDetector.grpcStatusFromTrailers(headers))
    }

    // ── gRPC message framing ───────────────────────────────────────────

    // protobuf payload: field1 varint 42 | field2 "alice" | field3 {1: 1}
    private val protoPayload = hex("082a" + "1205" + "616c696365" + "1a020801")

    private fun grpcFrame(payload: ByteArray, compressed: Boolean = false): ByteArray =
        byteArrayOf(if (compressed) 1 else 0) +
            byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte()
            ) + payload

    @Test
    fun `grpc framing parses single and back-to-back messages`() {
        val single = grpcFrame(protoPayload)
        val (used1, msgs1) = GrpcDetector.parseMessages(single)
        assertEquals(single.size, used1)
        assertEquals(1, msgs1.size)
        assertFalse(msgs1[0].compressed)
        assertEquals(protoPayload.size, msgs1[0].length)
        assertArrayEquals(protoPayload, msgs1[0].payload)

        val other = hex("0801")
        val double = single + grpcFrame(other)
        val (used2, msgs2) = GrpcDetector.parseMessages(double)
        assertEquals(double.size, used2)
        assertEquals(2, msgs2.size)
        assertArrayEquals(other, msgs2[1].payload)
    }

    @Test
    fun `grpc framing leaves truncated message unconsumed`() {
        val full = grpcFrame(protoPayload)
        val partial = full.copyOf(full.size - 3) // cut 3 bytes off the payload
        val (used, msgs) = GrpcDetector.parseMessages(partial)
        assertEquals(0, used)
        assertTrue(msgs.isEmpty())

        // ...but a complete message followed by a truncated one consumes the first
        val withTail = full + full.copyOf(7) // header + 2 payload bytes
        val (used2, msgs2) = GrpcDetector.parseMessages(withTail)
        assertEquals(full.size, used2)
        assertEquals(1, msgs2.size)
    }

    @Test
    fun `grpc framing rejects garbage`() {
        assertFalse(GrpcDetector.looksLikeGrpcFraming(hex("ffffffffffff")))  // bad flag
        assertFalse(GrpcDetector.looksLikeGrpcFraming(hex("007fffffff")))    // absurd length
        assertFalse(GrpcDetector.looksLikeGrpcFraming(ByteArray(3)))         // too short
        assertTrue(GrpcDetector.looksLikeGrpcFraming(grpcFrame(protoPayload)))
    }

    // ── ProtobufDecoder ────────────────────────────────────────────────

    @Test
    fun `protobuf decodes varint string and nested message`() {
        val fields = ProtobufDecoder.decode(protoPayload)
        assertNotNull(fields)
        fields!!
        assertEquals(3, fields.size)

        assertEquals(1, fields[0].number)
        assertEquals(ProtobufDecoder.WIRE_VARINT, fields[0].wireType)
        assertEquals(42L, (fields[0].value as ProtobufDecoder.ProtoValue.Varint).value)

        assertEquals(2, fields[1].number)
        assertEquals("alice", (fields[1].value as ProtobufDecoder.ProtoValue.Text).value)

        assertEquals(3, fields[2].number)
        val nested = (fields[2].value as ProtobufDecoder.ProtoValue.Nested).fields
        assertEquals(1, nested.size)
        assertEquals(1L, (nested[0].value as ProtobufDecoder.ProtoValue.Varint).value)
    }

    @Test
    fun `protobuf decodes fixed32 fixed64 and multibyte varints`() {
        // field 5 fixed32 = 0x01020304 LE bytes 04 03 02 01 | field 6 fixed64 = 1 | field 7 varint 300
        val bytes = hex("2d04030201" + "310100000000000000" + "38ac02")
        val fields = ProtobufDecoder.decode(bytes)
        assertNotNull(fields)
        fields!!
        assertEquals(0x01020304L, (fields[0].value as ProtobufDecoder.ProtoValue.Fixed32).value)
        assertEquals(1L, (fields[1].value as ProtobufDecoder.ProtoValue.Fixed64).value)
        assertEquals(300L, (fields[2].value as ProtobufDecoder.ProtoValue.Varint).value)
    }

    @Test
    fun `protobuf rejects non-messages`() {
        assertNull(ProtobufDecoder.decode(hex("ff")))         // truncated varint tag
        assertNull(ProtobufDecoder.decode(hex("08")))         // tag with missing value
        assertNull(ProtobufDecoder.decode(hex("0e00")))       // wire type 6 is invalid
        assertNull(ProtobufDecoder.decode(hex("1205")))       // length-delimited, missing payload
        // A bare group-start tag is surfaced as an opaque group marker, not a crash:
        val group = ProtobufDecoder.decode(hex("0b"))
        assertEquals(1, group!!.size)
        assertTrue(group[0].value is ProtobufDecoder.ProtoValue.Group)
    }

    @Test
    fun `protobuf debug string renders decode_raw style`() {
        val text = ProtobufDecoder.decodeToString(protoPayload)
        assertNotNull(text)
        text!!
        assertTrue(text.contains("1: 42"))
        assertTrue(text.contains("2: \"alice\""))
        assertTrue(text.contains("3 {"))
    }

    // ── end-to-end: frames → headers → call → framed protobuf ─────────

    @Test
    fun `h2 frames flow through the full grpc pipeline`() {
        // HEADERS(stream 3) + DATA(stream 3, one gRPC message) concatenated
        val hBlock = grpcHeadersBlock
        val hFrame = byteArrayOf(
            0, 0, hBlock.size.toByte(),
            Http2Parser.FRAME_HEADERS.toByte(), 0x04, // END_HEADERS
            0, 0, 0, 3
        ) + hBlock
        val dPayload = grpcFrame(protoPayload)
        val dFrame = byteArrayOf(
            0, 0, dPayload.size.toByte(),
            Http2Parser.FRAME_DATA.toByte(), 0x01, // END_STREAM
            0, 0, 0, 3
        ) + dPayload

        val frames = Http2Parser.parseFrames(hFrame + dFrame)
        assertEquals(2, frames.size)
        assertEquals(Http2Parser.FRAME_HEADERS, frames[0].type)
        assertEquals(Http2Parser.FRAME_DATA, frames[1].type)

        val headers = Http2Parser.decodeHeaders(frames[0], frames[0].payload)
        val call = GrpcDetector.callFromHeaders(headers)
        assertNotNull(call)

        val (used, messages) = GrpcDetector.parseMessages(frames[1].payload)
        assertEquals(frames[1].payload.size, used)
        assertEquals(1, messages.size)

        val decoded = ProtobufDecoder.decodeToString(messages[0].payload)
        assertNotNull(decoded)
        assertTrue(decoded!!.contains("2: \"alice\""))
    }
}
