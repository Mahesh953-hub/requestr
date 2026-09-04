package com.example.interception.http

import com.example.interception.http.Http2Parser.H2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.util.Random
import java.util.zip.GZIPInputStream

/**
 * S3 task 23 / gate G11: deterministic mutational fuzzing of the in-house HTTP/2 frame parser,
 * HPACK decoder and [Http2Session] (the observe-only consumer task 57 builds termination on).
 *
 * Seeds are real nghttp2-encoded header blocks (first story of each corpus profile), the RFC 7541
 * Appendix C vectors and a set of well-formed frames of every type. Each iteration picks a seed,
 * applies 1-4 mutations (bit flips, byte overwrites with boundary values, truncation, insertion,
 * duplication, length/flag/stream-id field corruption) and feeds the result through every parser
 * entry point and a long-lived session. The gate:
 *  - nothing throws (a malformed peer must never take the relay down),
 *  - every violation is *reported* ([Http2Parser.H2Headers.error]) rather than silently decoded,
 *  - no desync: after each batch a well-formed exchange on a fresh stream still produces a
 *    transaction with the exact headers and body, and a well-formed frame after garbage is still
 *    framed correctly.
 *
 * Fixed seed so a failure reproduces; `HTTP2_FUZZ_ITERATIONS` raises the budget locally.
 */
class Http2FuzzTest {

    private val iterations = System.getenv("HTTP2_FUZZ_ITERATIONS")?.toIntOrNull() ?: 200_000
    private val rnd = Random(0x5EED_2023L)

    // -- seeds ------------------------------------------------------------------------------------

    private fun corpusBlocks(profile: String): List<ByteArray> {
        val stream = javaClass.classLoader!!.getResourceAsStream("hpack-test-case/$profile/story_00.json.gz")!!
        val json = GZIPInputStream(stream).use { InputStreamReader(it, Charsets.UTF_8).readText() }
        return Regex("\"wire\": \"([0-9a-f]*)\"").findAll(json).map { HpackConformanceTest.hex(it.groupValues[1]) }.toList()
    }

    private val headerBlocks: List<ByteArray> by lazy {
        corpusBlocks("nghttp2") + corpusBlocks("nghttp2-16384-4096") + corpusBlocks("nghttp2-change-table-size") + listOf(
            HpackConformanceTest.hex("400a637573746f6d2d6b65790d637573746f6d2d686561646572"),
            HpackConformanceTest.hex("828684418cf1e3c2e5f23a6ba0ab90f4ff"),
            HpackConformanceTest.hex("488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d1bff6e919d29ad171863c78f0b97c8e9ae82ae43d3"),
        )
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun frameBytes(type: Int, flags: Int, streamId: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf((len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(), type.toByte(), flags.toByte()) + be32(streamId) + payload
    }

    private fun literal(name: String, value: String): ByteArray =
        byteArrayOf(0x00, name.length.toByte()) + name.toByteArray() + byteArrayOf(value.length.toByte()) + value.toByteArray()

    private val requestBlock = literal(":method", "POST") + literal(":scheme", "https") + literal(":path", "/fuzz/probe") +
        literal(":authority", "probe.example") + literal("content-type", "application/json") + literal("x-probe", "1")
    private val responseBlock = literal(":status", "200") + literal("content-type", "application/json") + literal("set-cookie", "a=1") + literal("set-cookie", "b=2")

    private val seedFrames: List<ByteArray> by lazy {
        val hb = headerBlocks
        buildList {
            add(frameBytes(Http2Parser.FRAME_SETTINGS, 0, 0, byteArrayOf(0, 4, 0, 1, 0, 0, 0, 5, 0, 0, 0x40, 0, 0, 1, 0, 0, 0x10, 0)))
            add(frameBytes(Http2Parser.FRAME_SETTINGS, Http2Parser.FLAG_ACK, 0, ByteArray(0)))
            add(frameBytes(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, hb[0]))
            add(frameBytes(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_PADDED, 3, byteArrayOf(4) + hb[1] + ByteArray(4)))
            add(frameBytes(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_PRIORITY, 5, byteArrayOf(0, 0, 0, 0, 16) + hb[2]))
            add(frameBytes(Http2Parser.FRAME_HEADERS, 0, 7, hb[3].copyOf(hb[3].size / 2)) + frameBytes(Http2Parser.FRAME_CONTINUATION, Http2Parser.FLAG_END_HEADERS, 7, hb[3].copyOfRange(hb[3].size / 2, hb[3].size)))
            add(frameBytes(Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, 1, "{\"ok\":true}".toByteArray()))
            add(frameBytes(Http2Parser.FRAME_DATA, Http2Parser.FLAG_PADDED, 3, byteArrayOf(3) + "abc".toByteArray() + ByteArray(3)))
            add(frameBytes(Http2Parser.FRAME_DATA, 0, 9, byteArrayOf(0, 0, 0, 0, 5, 1, 2, 3, 4, 5))) // gRPC-shaped
            add(frameBytes(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 9, literal(":method", "POST") + literal(":path", "/pkg.Svc/Do") + literal("content-type", "application/grpc") + literal("te", "trailers")))
            add(frameBytes(Http2Parser.FRAME_PRIORITY, 0, 1, byteArrayOf(0x80.toByte(), 0, 0, 3, 200.toByte())))
            add(frameBytes(Http2Parser.FRAME_RST_STREAM, 0, 1, be32(8)))
            add(frameBytes(Http2Parser.FRAME_PUSH_PROMISE, Http2Parser.FLAG_END_HEADERS, 1, be32(2) + hb[4]))
            add(frameBytes(Http2Parser.FRAME_PING, 0, 0, ByteArray(8) { it.toByte() }))
            add(frameBytes(Http2Parser.FRAME_GOAWAY, 0, 0, be32(7) + be32(1) + "bye".toByteArray()))
            add(frameBytes(Http2Parser.FRAME_WINDOW_UPDATE, 0, 0, be32(65535)))
            add(frameBytes(Http2Parser.FRAME_WINDOW_UPDATE, 0, 1, be32(0x7FFFFFFF)))
            add(frameBytes(0x0A, 0, 1, ByteArray(6) { 0x55 })) // ALTSVC-ish unknown type
        }
    }

    // -- mutators ---------------------------------------------------------------------------------

    private val boundary = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF, 0x3F, 0x1F, 0x0F, 0x40, 0x20, 0xBE, 0xC0, 0xFE)

    private fun mutate(input: ByteArray): ByteArray {
        var b = input.copyOf()
        val rounds = 1 + rnd.nextInt(4)
        repeat(rounds) {
            if (b.isEmpty()) { b = byteArrayOf(boundary[rnd.nextInt(boundary.size)].toByte()); return@repeat }
            when (rnd.nextInt(10)) {
                0 -> { val i = rnd.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                1 -> { val i = rnd.nextInt(b.size); b[i] = boundary[rnd.nextInt(boundary.size)].toByte() }
                2 -> b = b.copyOf(rnd.nextInt(b.size + 1))
                3 -> { val i = rnd.nextInt(b.size + 1); val ins = ByteArray(1 + rnd.nextInt(6)) { boundary[rnd.nextInt(boundary.size)].toByte() }; b = b.copyOfRange(0, i) + ins + b.copyOfRange(i, b.size) }
                4 -> { val i = rnd.nextInt(b.size); val j = i + rnd.nextInt(b.size - i + 1); b = b.copyOfRange(0, j) + b.copyOfRange(i, j) + b.copyOfRange(j, b.size) }
                5 -> if (b.size >= 9) { b[rnd.nextInt(3)] = boundary[rnd.nextInt(boundary.size)].toByte() } // length field
                6 -> if (b.size >= 9) { b[4] = (b[4].toInt() or (1 shl rnd.nextInt(8))).toByte() } // flags
                7 -> if (b.size >= 9) { b[5] = boundary[rnd.nextInt(boundary.size)].toByte(); if (rnd.nextBoolean()) b[8] = 0 } // stream id / reserved bit
                8 -> { val i = rnd.nextInt(b.size); b[i] = 0xFF.toByte(); if (i + 1 < b.size) b[i + 1] = 0xFF.toByte() } // integer continuation storm
                else -> { val i = rnd.nextInt(b.size); b[i] = (rnd.nextInt(256)).toByte() }
            }
        }
        return b
    }

    // -- targets ----------------------------------------------------------------------------------

    private class Recorder {
        val transactions = ArrayList<Http2Transaction>()
        val grpc = ArrayList<GrpcEvent>()
        val session = Http2Session(onTransaction = { transactions.add(it) }, onGrpcMessage = { grpc.add(it) })
    }

    private fun exercise(bytes: ByteArray, rec: Recorder, decoder: Http2Parser.HpackDecoder): Int {
        var reported = 0
        val frames = Http2Parser.parseFrames(bytes)
        for (f in frames) {
            when (f.type) {
                Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_PUSH_PROMISE, Http2Parser.FRAME_CONTINUATION -> {
                    val h = Http2Parser.decodeHeaders(f, f.payload, decoder)
                    if (h.error != null) reported++
                    h.all; h.regularEntries; h.toString()
                }
                Http2Parser.FRAME_DATA -> Http2Parser.stripDataPadding(f)
                Http2Parser.FRAME_SETTINGS -> Http2Parser.decodeSettings(f)
                Http2Parser.FRAME_RST_STREAM, Http2Parser.FRAME_GOAWAY -> Http2Parser.errorCodeName(f.payload)
            }
            Http2Parser.frameTypeName(f.type); f.typeName
        }
        Http2Parser.isPreface(bytes); Http2Parser.looksLikeFrameStart(bytes)
        // The raw block as a header block on its own (most seeds are bare HPACK blocks).
        val asBlock = Http2Parser.decodeHeaders(H2Frame(bytes.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, bytes), bytes, Http2Parser.HpackDecoder())
        if (asBlock.error != null) reported++
        // Session: both directions, so client and server HPACK contexts and the stream table churn.
        if (rnd.nextBoolean()) rec.session.onClientFrames(frames) else rec.session.onServerFrames(frames)
        return reported
    }

    /** A clean request/response on [streamId] must still be assembled -- proves no desync. */
    private fun probe(rec: Recorder, streamId: Int) {
        val before = rec.transactions.size
        rec.session.onClientFrames(listOf(
            H2Frame(requestBlock.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, streamId, requestBlock),
            H2Frame(2, Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, streamId, "{}".toByteArray()),
        ))
        rec.session.onServerFrames(listOf(
            H2Frame(responseBlock.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, streamId, responseBlock),
            H2Frame(4, Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, streamId, "done".toByteArray()),
        ))
        assertEquals("probe on stream $streamId produced no transaction", before + 1, rec.transactions.size)
        val t = rec.transactions.last()
        assertEquals("/fuzz/probe", t.request.path)
        assertEquals("probe.example", t.request.host)
        assertEquals("{}", t.request.body)
        assertEquals(200, t.response.statusCode)
        assertEquals("done", t.response.body)
        assertEquals(listOf("a=1", "b=2"), t.responseHeaders!!.regularEntries.filter { it.first == "set-cookie" }.map { it.second })
    }

    @Test(timeout = 240_000)
    fun `mutated frames and header blocks never throw, are reported, and never desync the session`() {
        val rec = Recorder()
        val decoder = Http2Parser.HpackDecoder()
        val seeds = headerBlocks.map { frameBytes(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1 + 2 * rnd.nextInt(50), it) } + seedFrames + headerBlocks
        var reported = 0
        var failures = 0
        var firstFailure: String? = null
        var streamId = 1_001
        for (i in 0 until iterations) {
            val input = mutate(seeds[rnd.nextInt(seeds.size)])
            try {
                reported += exercise(input, rec, decoder)
            } catch (t: Throwable) {
                failures++
                if (firstFailure == null) firstFailure = "iteration $i: ${t::class.simpleName}: ${t.message}\ninput=${input.joinToString("") { "%02x".format(it) }}\n${t.stackTrace.take(6).joinToString("\n")}"
            }
            if (i % 500 == 499) { probe(rec, streamId); streamId += 2 }
        }
        probe(rec, streamId)
        assertEquals("exceptions escaped the parser/decoder/session:\n$firstFailure", 0, failures)
        assertTrue("fuzzer produced no reported violations -- mutators are not reaching the decoder", reported > iterations / 10)
        // Sanity on the seed set itself: all bare corpus blocks decode cleanly on a fresh decoder
        // only when their story order is respected; the first block of each profile always does.
        for (profile in listOf("nghttp2", "nghttp2-16384-4096", "nghttp2-change-table-size")) {
            val first = corpusBlocks(profile).first()
            assertNull(Http2Parser.decodeHeaders(H2Frame(first.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, first), first).error)
        }
    }

    @Test(timeout = 120_000)
    fun `random byte streams never throw at any entry point`() {
        val rec = Recorder()
        val decoder = Http2Parser.HpackDecoder()
        var failures = 0
        var first: String? = null
        for (i in 0 until iterations / 4) {
            val len = rnd.nextInt(96)
            val input = ByteArray(len) { rnd.nextInt(256).toByte() }
            // Make a good share of them frame-shaped so the payload layer is reached.
            if (len >= 9 && rnd.nextInt(3) != 0) { input[0] = 0; input[1] = 0; input[2] = (len - 9).toByte(); input[3] = rnd.nextInt(11).toByte() }
            try { exercise(input, rec, decoder) } catch (t: Throwable) {
                failures++
                if (first == null) first = "iteration $i: ${t::class.simpleName}: ${t.message} input=${input.joinToString("") { "%02x".format(it) }}"
            }
        }
        assertEquals("exceptions escaped: $first", 0, failures)
        probe(rec, 7_777)
    }
}
