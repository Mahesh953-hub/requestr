package com.example.interception.http

import com.example.interception.grpc.GrpcDetector
import com.example.interception.grpc.ProtobufDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * S3 task 23 / gate G11 companion: the WebSocket, SSE, gRPC-framing and protobuf decoders that sit
 * behind the HTTP/2 (and HTTP/1.1) parsers must survive malformed input the same way -- no
 * exception escapes, and a well-formed message after garbage is still decoded. Deterministic seed.
 */
class ProtocolFramingFuzzTest {

    private val iterations = System.getenv("HTTP2_FUZZ_ITERATIONS")?.toIntOrNull()?.div(4) ?: 50_000
    private val rnd = Random(0x77_5EEDL)

    private fun wsFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, mask: Boolean = false): ByteArray {
        val head = ArrayList<Byte>()
        head += ((if (fin) 0x80 else 0) or opcode).toByte()
        val m = if (mask) 0x80 else 0
        when {
            payload.size < 126 -> head += (m or payload.size).toByte()
            payload.size < 65536 -> { head += (m or 126).toByte(); head += (payload.size ushr 8).toByte(); head += payload.size.toByte() }
            else -> { head += (m or 127).toByte(); repeat(4) { head += 0 }; head += (payload.size ushr 24).toByte(); head += (payload.size ushr 16).toByte(); head += (payload.size ushr 8).toByte(); head += payload.size.toByte() }
        }
        val key = byteArrayOf(1, 2, 3, 4)
        if (mask) key.forEach { head += it }
        val body = if (mask) ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() } else payload
        return head.toByteArray() + body
    }

    private fun grpcMessage(payload: ByteArray, compressed: Boolean = false) =
        byteArrayOf(if (compressed) 1 else 0, (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload

    private val proto = byteArrayOf(0x08, 0x96.toByte(), 0x01, 0x12, 0x05, 'a'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte())

    private val seeds: List<ByteArray> by lazy {
        listOf(
            wsFrame(1, "hello".toByteArray()),
            wsFrame(1, "hello".toByteArray(), mask = true),
            wsFrame(2, ByteArray(300) { it.toByte() }),
            wsFrame(1, "frag".toByteArray(), fin = false) + wsFrame(0, "ment".toByteArray()),
            wsFrame(8, byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray()),
            wsFrame(9, ByteArray(0)) + wsFrame(10, ByteArray(0)),
            grpcMessage(proto), grpcMessage(proto, compressed = true) + grpcMessage(ByteArray(0)),
            proto, byteArrayOf(0x0A, 0x02, 0x08, 0x01, 0x1A, 0x00),
            "event: deploy\ndata: {\"id\":1}\nid: 7\nretry: 100\n\ndata: second\n\n".toByteArray(),
            ":comment\r\ndata: a\r\ndata: b\r\n\r\n".toByteArray(),
        )
    }

    private fun mutate(input: ByteArray): ByteArray {
        var b = input.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            if (b.isEmpty()) { b = byteArrayOf(rnd.nextInt(256).toByte()); return@repeat }
            when (rnd.nextInt(6)) {
                0 -> { val i = rnd.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                1 -> { val i = rnd.nextInt(b.size); b[i] = listOf(0x00, 0x7F, 0x80, 0xFF, 0x7E, 0x7D, 0xFE, 0xFD)[rnd.nextInt(8)].toByte() }
                2 -> b = b.copyOf(rnd.nextInt(b.size + 1))
                3 -> { val i = rnd.nextInt(b.size + 1); b = b.copyOfRange(0, i) + ByteArray(1 + rnd.nextInt(8)) { rnd.nextInt(256).toByte() } + b.copyOfRange(i, b.size) }
                4 -> { val i = rnd.nextInt(b.size); b[i] = 0xFF.toByte() }
                else -> { val i = rnd.nextInt(b.size); b[i] = rnd.nextInt(256).toByte() }
            }
        }
        return b
    }

    private fun exercise(bytes: ByteArray, assembler: WsMessageAssembler) {
        val (_, ws) = WebSocketParser.parseFrames(bytes)
        for (f in ws) { WebSocketParser.describe(f); assembler.offer(f) }
        WebSocketParser.tryParseFrame(bytes)
        val (_, msgs) = GrpcDetector.parseMessages(bytes)
        for (m in msgs) ProtobufDecoder.decodeToString(m.payload)
        GrpcDetector.looksLikeGrpcFraming(bytes)
        ProtobufDecoder.decode(bytes)
        ProtobufDecoder.decodeToString(bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        SseParser.parseEvents(text)
        SseParser.parseEvents(text, lastSeenBlock = rnd.nextInt(4) - 1)
    }

    @Test(timeout = 120_000)
    fun `websocket, gRPC framing, protobuf and SSE decoders never throw and recover after garbage`() {
        val assembler = WsMessageAssembler()
        var failures = 0
        var first: String? = null
        for (i in 0 until iterations) {
            val input = if (rnd.nextInt(5) == 0) ByteArray(rnd.nextInt(64)) { rnd.nextInt(256).toByte() } else mutate(seeds[rnd.nextInt(seeds.size)])
            try { exercise(input, assembler) } catch (t: Throwable) {
                failures++
                if (first == null) first = "iteration $i: ${t::class.simpleName}: ${t.message}\ninput=${input.joinToString("") { "%02x".format(it) }}\n${t.stackTrace.take(6).joinToString("\n")}"
            }
        }
        println("ProtocolFramingFuzzTest: $iterations iterations, $failures escaped exceptions")
        assertEquals("exceptions escaped: $first", 0, failures)

        // Recovery: well-formed input right after the campaign decodes exactly.
        val (consumed, frames) = WebSocketParser.parseFrames(wsFrame(1, "still fine".toByteArray(), mask = true))
        assertEquals(1, frames.size)
        assertEquals("still fine", String(frames[0].payload))
        assertTrue(consumed > 0)
        val (_, grpc) = GrpcDetector.parseMessages(grpcMessage(proto))
        assertEquals(1, grpc.size)
        assertTrue(ProtobufDecoder.decodeToString(grpc[0].payload)!!.contains("alice"))
        val (events, _) = SseParser.parseEvents("event: ok\ndata: yes\n\n")
        assertEquals("yes", events.single().data)
        assertEquals("ok", events.single().event)
    }
}
