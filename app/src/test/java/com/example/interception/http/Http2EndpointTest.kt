package com.example.interception.http

import com.example.interception.http.Http2Endpoint.ErrorCode
import com.example.interception.http.Http2Parser.H2Frame
import com.example.interception.http.Http2Parser.H2Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * S3 task 57 (gate G27): two [Http2Endpoint]s (one SERVER, one CLIENT role) talking over pipes
 * on two threads -- the connection preface, SETTINGS exchange, PING, HEADERS/CONTINUATION,
 * per-connection and per-stream flow control, RST_STREAM, GOAWAY, stream concurrency limits and
 * the malformed-message answers RFC 9113 requires. A raw byte peer covers the connection errors
 * a real [Http2Endpoint] can never emit itself (HPACK faults, oversized frames, missing preface).
 */
class Http2EndpointTest {

    // ── harness ─────────────────────────────────────────────────────────────────────────

    private class Recorder : Http2Endpoint.Listener {
        private val events = LinkedBlockingQueue<String>()
        private val seen = ArrayList<String>()
        val headers = ConcurrentHashMap<Int, H2Headers>()
        val trailers = ConcurrentHashMap<Int, H2Headers>()
        val data = ConcurrentHashMap<Int, ByteArrayOutputStream>()
        val settings = LinkedBlockingQueue<Http2Endpoint.PeerSettings>()
        var onHeadersHook: ((Int, H2Headers, Boolean) -> Unit)? = null

        override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
            if (trailers) this.trailers[streamId] = headers else this.headers[streamId] = headers
            events.put("HEADERS $streamId end=$endStream trailers=$trailers")
            onHeadersHook?.invoke(streamId, headers, endStream)
        }
        override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
            this.data.getOrPut(streamId) { ByteArrayOutputStream() }.write(data)
            if (endStream) events.put("DATA_END $streamId")
        }
        override fun onRstStream(streamId: Int, errorCode: Int) { events.put("RST $streamId $errorCode") }
        override fun onGoAway(lastStreamId: Int, errorCode: Int) { events.put("GOAWAY $lastStreamId $errorCode") }
        override fun onStreamClosed(streamId: Int) { events.put("CLOSED $streamId") }
        override fun onPeerSettings(settings: Http2Endpoint.PeerSettings) { this.settings.put(settings) }

        /** First event (already seen or arriving within 5 s) that starts with [prefix]. */
        fun expect(prefix: String): String {
            synchronized(seen) { seen.firstOrNull { it.startsWith(prefix) }?.let { seen.remove(it); return it } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) fail("timed out waiting for '$prefix'; seen=$seen")
                val e = events.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
                if (e.startsWith(prefix)) return e
                synchronized(seen) { seen += e }
            }
        }

        fun none(prefix: String, waitMs: Long = 200): Boolean {
            val e = events.poll(waitMs, TimeUnit.MILLISECONDS) ?: return synchronized(seen) { seen.none { it.startsWith(prefix) } }
            synchronized(seen) { seen += e }
            return synchronized(seen) { seen.none { it.startsWith(prefix) } }
        }
    }

    private class Link(serverMaxStreams: Int = 128) {
        val clientToServer = PipedOutputStream()
        val serverToClient = PipedOutputStream()
        val serverListener = Recorder()
        val clientListener = Recorder()
        val serverRead = CopyOnWriteArrayList<H2Frame>()
        val serverWrote = CopyOnWriteArrayList<H2Frame>()
        val clientRead = CopyOnWriteArrayList<H2Frame>()
        val server = Http2Endpoint(
            PipedInputStream(clientToServer, 1 shl 20), serverToClient, Http2Endpoint.Role.SERVER, serverListener, "srv",
            onFrameRead = { serverRead += it }, onFrameWritten = { serverWrote += it }, maxConcurrentStreams = serverMaxStreams,
        )
        val client = Http2Endpoint(
            PipedInputStream(serverToClient, 1 shl 20), clientToServer, Http2Endpoint.Role.CLIENT, clientListener, "cli",
            onFrameRead = { clientRead += it },
        )
        var serverError: Throwable? = null
        var clientError: Throwable? = null
        val done = CountDownLatch(2)
        val serverThread = Thread {
            try { server.start(); server.run() } catch (t: Throwable) { serverError = t } finally { done.countDown() }
        }
        val clientThread = Thread {
            try { client.run() } catch (t: Throwable) { clientError = t } finally { done.countDown() }
        }

        /** The client preface is written here, before any test-thread frame can interleave. */
        fun start(): Link { serverThread.start(); client.start(); clientThread.start(); return this }

        fun finish() {
            client.close(); server.close()
            assertTrue("relay threads did not end", done.await(5, TimeUnit.SECONDS))
        }
    }

    private var link: Link? = null

    @After fun tearDown() { link?.finish() }

    private fun open(serverMaxStreams: Int = 128): Link = Link(serverMaxStreams).start().also { link = it }

    private val request = listOf(":method" to "GET", ":scheme" to "https", ":authority" to "example.com", ":path" to "/")

    // ── handshake, PING, settings ────────────────────────────────────────────────────────

    @Test
    fun `both sides exchange and acknowledge SETTINGS and neither allows server push`() {
        val l = open()
        val fromClient = l.serverListener.settings.poll(5, TimeUnit.SECONDS)!!
        val fromServer = l.clientListener.settings.poll(5, TimeUnit.SECONDS)!!
        assertFalse(fromClient.enablePush)
        assertFalse(fromServer.enablePush)
        assertEquals(128, fromServer.maxConcurrentStreams)
        assertEquals(16_384, fromServer.maxFrameSize)
        // Each side ACKs the other's SETTINGS exactly once.
        waitUntil { l.serverWrote.any { it.type == Http2Parser.FRAME_SETTINGS && (it.flags and Http2Parser.FLAG_ACK) != 0 } }
        waitUntil { l.clientRead.any { it.type == Http2Parser.FRAME_SETTINGS && (it.flags and Http2Parser.FLAG_ACK) != 0 } }
        assertEquals(1, l.serverWrote.count { it.type == Http2Parser.FRAME_SETTINGS && (it.flags and Http2Parser.FLAG_ACK) != 0 })
        assertEquals(1, l.serverRead.count { it.type == Http2Parser.FRAME_SETTINGS && (it.flags and Http2Parser.FLAG_ACK) != 0 })
    }

    @Test
    fun `PING is answered with an ACK carrying the same payload`() {
        val l = open()
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        l.client.writePing(payload)
        waitUntil { l.clientRead.any { it.type == Http2Parser.FRAME_PING && (it.flags and Http2Parser.FLAG_ACK) != 0 } }
        val ack = l.clientRead.first { it.type == Http2Parser.FRAME_PING && (it.flags and Http2Parser.FLAG_ACK) != 0 }
        assertEquals(payload.toList(), ack.payload.toList())
        // A PING ACK is not answered again.
        assertEquals(1, l.serverRead.count { it.type == Http2Parser.FRAME_PING })
    }

    // ── streams ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `request and response headers and data cross the connection with END_STREAM`() {
        val l = open()
        l.serverListener.onHeadersHook = { id, _, end ->
            if (end) {
                l.server.writeHeaders(id, listOf(":status" to "200", "content-type" to "text/plain"), endStream = false)
                l.server.writeData(id, "pong".toByteArray(), endStream = true)
            }
        }
        assertTrue(l.client.writeHeaders(1, request + ("x-req" to "1"), endStream = true))
        assertEquals("HEADERS 1 end=true trailers=false", l.serverListener.expect("HEADERS 1"))
        assertEquals(request + ("x-req" to "1"), l.serverListener.headers[1]!!.entries)
        assertEquals("HEADERS 1 end=false trailers=false", l.clientListener.expect("HEADERS 1"))
        assertEquals(listOf(":status" to "200", "content-type" to "text/plain"), l.clientListener.headers[1]!!.entries)
        l.clientListener.expect("DATA_END 1")
        assertEquals("pong", String(l.clientListener.data[1]!!.toByteArray()))
        l.clientListener.expect("CLOSED 1")
        l.serverListener.expect("CLOSED 1")
        assertFalse(l.client.isStreamOpen(1))
        assertFalse(l.server.isStreamOpen(1))
    }

    @Test
    fun `trailers are delivered as a second header block and must end the stream`() {
        val l = open()
        assertTrue(l.client.writeHeaders(1, request + ("te" to "trailers"), endStream = false))
        assertTrue(l.client.writeData(1, "body".toByteArray(), endStream = false))
        assertTrue(l.client.writeHeaders(1, listOf("grpc-status" to "0"), endStream = true))
        l.serverListener.expect("HEADERS 1 end=false trailers=false")
        assertEquals("HEADERS 1 end=true trailers=true", l.serverListener.expect("HEADERS 1 end=true"))
        assertEquals(listOf("grpc-status" to "0"), l.serverListener.trailers[1]!!.entries)
        assertEquals("body", String(l.serverListener.data[1]!!.toByteArray()))

        // Trailers without END_STREAM are a malformed message -> RST_STREAM(PROTOCOL_ERROR).
        assertTrue(l.client.writeHeaders(3, request, endStream = false))
        assertTrue(l.client.writeHeaders(3, listOf("x-t" to "1"), endStream = false))
        assertEquals("RST 3 ${ErrorCode.PROTOCOL_ERROR}", l.clientListener.expect("RST 3"))
    }

    @Test
    fun `a header block larger than MAX_FRAME_SIZE is split into HEADERS plus CONTINUATION and reassembled`() {
        val l = open()
        val big = request + (1..40).map { "x-h$it" to "v".repeat(1000) + it }
        assertTrue(l.client.writeHeaders(1, big, endStream = true))
        l.serverListener.expect("HEADERS 1")
        assertEquals(big, l.serverListener.headers[1]!!.entries)
        waitUntil { l.serverRead.any { it.type == Http2Parser.FRAME_CONTINUATION } }
        val headersFrame = l.serverRead.first { it.type == Http2Parser.FRAME_HEADERS && it.streamId == 1 }
        assertEquals(0, headersFrame.flags and Http2Parser.FLAG_END_HEADERS)
        assertTrue(headersFrame.length <= 16_384)
        assertTrue(l.serverRead.last { it.type == Http2Parser.FRAME_CONTINUATION }.endHeaders)
    }

    @Test
    fun `client to server DATA beyond the initial window is paced by WINDOW_UPDATE`() {
        val l = open()
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        assertTrue(l.client.writeHeaders(1, request, endStream = false))
        assertTrue(l.client.writeData(1, payload, endStream = true))
        l.serverListener.expect("DATA_END 1")
        assertEquals(payload.toList(), l.serverListener.data[1]!!.toByteArray().toList())
        assertTrue(l.clientRead.any { it.type == Http2Parser.FRAME_WINDOW_UPDATE && it.streamId == 0 })
        assertTrue(l.clientRead.any { it.type == Http2Parser.FRAME_WINDOW_UPDATE && it.streamId == 1 })
        assertTrue(l.serverRead.filter { it.type == Http2Parser.FRAME_DATA }.all { it.length <= 16_384 })
    }

    @Test
    fun `server to client DATA beyond the initial window is paced by WINDOW_UPDATE`() {
        val l = open()
        val payload = ByteArray(200_000) { (it % 13).toByte() }
        l.serverListener.onHeadersHook = { id, _, _ ->
            Thread {
                l.server.writeHeaders(id, listOf(":status" to "200"), endStream = false)
                l.server.writeData(id, payload, endStream = true)
            }.start()
        }
        assertTrue(l.client.writeHeaders(1, request, endStream = true))
        l.clientListener.expect("DATA_END 1")
        assertEquals(payload.size, l.clientListener.data[1]!!.size())
        assertEquals(payload.toList(), l.clientListener.data[1]!!.toByteArray().toList())
        assertTrue(l.serverRead.any { it.type == Http2Parser.FRAME_WINDOW_UPDATE })
    }

    @Test
    fun `a writer parked on a closed window fails with FLOW_CONTROL and the stream is reset`() {
        val l = open()
        // A server that never reads (no WINDOW_UPDATEs) -- simulate with a client-side flow
        // control whose timeout is tiny: reserve past the window must throw quickly.
        val fc = Http2FlowControl(waitTimeoutMs = 200)
        val out = PipedOutputStream()
        val sink = PipedInputStream(out, 1 shl 20)
        val lone = Http2Endpoint(PipedInputStream(PipedOutputStream(), 16), out, Http2Endpoint.Role.CLIENT, Recorder(), "lone", flowControl = fc)
        lone.start()
        assertTrue(lone.writeHeaders(1, request, endStream = false))
        try {
            lone.writeData(1, ByteArray(70_000), endStream = true)
            fail("expected FlowControlException")
        } catch (_: Http2FlowControl.FlowControlException) {
        }
        val written = ByteArray(sink.available()).also { sink.read(it) }
        val frames = Http2Parser.parseFrames(written.copyOfRange(Http2Endpoint.PREFACE.size, written.size))
        assertEquals(65_535, frames.filter { it.type == Http2Parser.FRAME_DATA }.sumOf { it.length })
        val rst = frames.last()
        assertEquals(Http2Parser.FRAME_RST_STREAM, rst.type)
        assertEquals(ErrorCode.FLOW_CONTROL_ERROR, Http2Endpoint.readInt(rst.payload, 0))
        assertFalse(lone.isStreamOpen(1))
        lone.close()
        l.finish(); link = null
    }

    @Test
    fun `RST_STREAM from either side closes the stream and is reported`() {
        val l = open()
        assertTrue(l.client.writeHeaders(1, request, endStream = false))
        l.serverListener.expect("HEADERS 1")
        l.client.writeRstStream(1, ErrorCode.CANCEL)
        assertEquals("RST 1 ${ErrorCode.CANCEL}", l.serverListener.expect("RST 1"))
        waitUntil { !l.server.isStreamOpen(1) }
        // DATA arriving for the reset stream is dropped silently (not a connection error).
        assertTrue(l.client.writeHeaders(3, request, endStream = false))
        l.serverListener.expect("HEADERS 3")
        l.server.writeRstStream(3, ErrorCode.REFUSED_STREAM)
        assertEquals("RST 3 ${ErrorCode.REFUSED_STREAM}", l.clientListener.expect("RST 3"))
        assertNull(l.serverError)
    }

    @Test
    fun `GOAWAY stops new streams and refuses streams above last-stream-id`() {
        val l = open()
        assertTrue(l.client.writeHeaders(1, request, endStream = true))
        l.serverListener.expect("HEADERS 1")
        l.server.writeGoAway(ErrorCode.NO_ERROR, "bye")
        assertEquals("GOAWAY 1 ${ErrorCode.NO_ERROR}", l.clientListener.expect("GOAWAY"))
        // goAwayReceived is recorded before the listener runs, so this is deterministic.
        assertFalse(l.client.writeHeaders(3, request, endStream = true))
        // Only the first GOAWAY is written.
        l.server.writeGoAway(ErrorCode.INTERNAL_ERROR)
        assertEquals(1, l.serverWrote.count { it.type == Http2Parser.FRAME_GOAWAY })
    }

    @Test
    fun `streams beyond MAX_CONCURRENT_STREAMS are refused not killed`() {
        val l = open(serverMaxStreams = 1)
        assertTrue(l.client.writeHeaders(1, request, endStream = false))
        l.serverListener.expect("HEADERS 1")
        assertTrue(l.client.writeHeaders(3, request, endStream = false))
        assertEquals("RST 3 ${ErrorCode.REFUSED_STREAM}", l.clientListener.expect("RST 3"))
        assertTrue(l.server.isStreamOpen(1))
        assertNull(l.serverError)
        // After stream 1 ends, stream 5 is accepted.
        assertTrue(l.client.writeData(1, ByteArray(0), endStream = true))
        l.serverListener.expect("DATA_END 1")
        l.server.writeHeaders(1, listOf(":status" to "204"), endStream = true)
        l.clientListener.expect("CLOSED 1")
        waitUntil { !l.server.isStreamOpen(1) }
        assertTrue(l.client.writeHeaders(5, request, endStream = false))
        l.serverListener.expect("HEADERS 5")
    }

    @Test
    fun `a malformed request is answered with RST_STREAM PROTOCOL_ERROR and the connection lives on`() {
        val l = open()
        assertTrue(l.client.writeHeaders(1, listOf("accept" to "*/*") + request, endStream = true))
        assertEquals("RST 1 ${ErrorCode.PROTOCOL_ERROR}", l.clientListener.expect("RST 1"))
        assertTrue(l.serverListener.none("HEADERS 1"))
        assertTrue(l.client.writeHeaders(3, request, endStream = true))
        l.serverListener.expect("HEADERS 3")
        assertNull(l.serverError)
    }

    // ── raw peer: connection errors ──────────────────────────────────────────────────────

    private class RawServer {
        val toServer = PipedOutputStream()
        val fromServer = PipedInputStream(1 shl 20)
        private val captured = ByteArrayOutputStream()
        val listener = Recorder()
        val server: Http2Endpoint
        var error: Http2Endpoint.Http2ConnectionException? = null
        private val done = CountDownLatch(1)
        init {
            val out = PipedOutputStream(fromServer)
            server = Http2Endpoint(PipedInputStream(toServer, 1 shl 20), out, Http2Endpoint.Role.SERVER, listener, "raw")
            Thread {
                try { server.start(); server.run() } catch (e: Http2Endpoint.Http2ConnectionException) { error = e } catch (_: Throwable) {} finally { done.countDown() }
            }.start()
            Thread {
                val buf = ByteArray(8192)
                try { while (true) { val n = fromServer.read(buf); if (n < 0) break; synchronized(captured) { captured.write(buf, 0, n) } } } catch (_: Throwable) {}
            }.start()
        }

        fun send(bytes: ByteArray) { runCatching { toServer.write(bytes); toServer.flush() } }

        fun awaitEnd(): List<H2Frame> {
            // PipedInputStream only wakes a parked reader on writer close, not on reader close.
            runCatching { toServer.close() }
            assertTrue("server did not end", done.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            return Http2Parser.parseFrames(synchronized(captured) { captured.toByteArray() })
        }

        fun goAwayCode(frames: List<H2Frame>): Int? = frames.lastOrNull { it.type == Http2Parser.FRAME_GOAWAY }?.let { Http2Endpoint.readInt(it.payload, 4) }
    }

    private fun frame(type: Int, flags: Int, streamId: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = (payload.size ushr 16).toByte(); out[1] = (payload.size ushr 8).toByte(); out[2] = payload.size.toByte()
        out[3] = type.toByte(); out[4] = flags.toByte()
        out[5] = (streamId ushr 24).toByte(); out[6] = (streamId ushr 16).toByte(); out[7] = (streamId ushr 8).toByte(); out[8] = streamId.toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }

    private val emptySettings = frame(Http2Parser.FRAME_SETTINGS, 0, 0, ByteArray(0))

    private fun rawConnectionError(vararg afterPreface: ByteArray): Pair<Int?, RawServer> {
        val raw = RawServer()
        raw.send(Http2Endpoint.PREFACE)
        for (b in afterPreface) raw.send(b)
        val frames = raw.awaitEnd()
        return raw.goAwayCode(frames) to raw
    }

    @Test
    fun `a missing connection preface is a PROTOCOL_ERROR`() {
        val raw = RawServer()
        raw.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val frames = raw.awaitEnd()
        assertEquals(ErrorCode.PROTOCOL_ERROR, raw.goAwayCode(frames))
        assertEquals(ErrorCode.PROTOCOL_ERROR, raw.error?.errorCode)
    }

    @Test
    fun `the first frame after the preface must be SETTINGS`() {
        val (code, raw) = rawConnectionError(frame(Http2Parser.FRAME_PING, 0, 0, ByteArray(8)))
        assertEquals(ErrorCode.PROTOCOL_ERROR, code)
        assertEquals(ErrorCode.PROTOCOL_ERROR, raw.error?.errorCode)
    }

    @Test
    fun `an HPACK decoding fault is a COMPRESSION_ERROR`() {
        // 0x80 = indexed representation with index 0, never valid (RFC 7541 §6.1).
        val (code, _) = rawConnectionError(emptySettings, frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_END_STREAM, 1, byteArrayOf(0x80.toByte())))
        assertEquals(ErrorCode.COMPRESSION_ERROR, code)
    }

    @Test
    fun `a frame longer than MAX_FRAME_SIZE is a FRAME_SIZE_ERROR before it is buffered`() {
        val (code, _) = rawConnectionError(emptySettings, frame(Http2Parser.FRAME_DATA, 0, 1, ByteArray(0)).also { it[0] = 0x01; it[1] = 0; it[2] = 0 })
        assertEquals(ErrorCode.FRAME_SIZE_ERROR, code)
    }

    @Test
    fun `SETTINGS payload not a multiple of 6 and SETTINGS ACK with payload are FRAME_SIZE_ERRORs`() {
        assertEquals(ErrorCode.FRAME_SIZE_ERROR, rawConnectionError(frame(Http2Parser.FRAME_SETTINGS, 0, 0, ByteArray(5))).first)
        assertEquals(ErrorCode.FRAME_SIZE_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_SETTINGS, Http2Parser.FLAG_ACK, 0, ByteArray(6))).first)
    }

    @Test
    fun `invalid SETTINGS values are rejected with the RFC error codes`() {
        fun setting(id: Int, value: Long) = byteArrayOf((id ushr 8).toByte(), id.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(frame(Http2Parser.FRAME_SETTINGS, 0, 0, setting(Http2Parser.SETTINGS_ENABLE_PUSH, 2))).first)
        assertEquals(ErrorCode.FLOW_CONTROL_ERROR, rawConnectionError(frame(Http2Parser.FRAME_SETTINGS, 0, 0, setting(Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE, 0x8000_0000L))).first)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(frame(Http2Parser.FRAME_SETTINGS, 0, 0, setting(Http2Parser.SETTINGS_MAX_FRAME_SIZE, 1000))).first)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(frame(Http2Parser.FRAME_SETTINGS, 0, 1, ByteArray(0))).first)
    }

    @Test
    fun `WINDOW_UPDATE of zero on the connection and DATA on an idle stream are PROTOCOL_ERRORs`() {
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_WINDOW_UPDATE, 0, 0, ByteArray(4))).first)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_DATA, 0, 1, "x".toByteArray())).first)
        assertEquals(ErrorCode.FRAME_SIZE_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_WINDOW_UPDATE, 0, 0, ByteArray(3))).first)
    }

    @Test
    fun `even-numbered client streams PUSH_PROMISE and RST_STREAM on idle streams are PROTOCOL_ERRORs`() {
        val headersBlock = HpackEncoder().encode(request)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 2, headersBlock)).first)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_PUSH_PROMISE, Http2Parser.FLAG_END_HEADERS, 1, ByteArray(4))).first)
        assertEquals(ErrorCode.PROTOCOL_ERROR, rawConnectionError(emptySettings, frame(Http2Parser.FRAME_RST_STREAM, 0, 1, ByteArray(4))).first)
    }

    @Test
    fun `a CONTINUATION interleaved with another frame is a PROTOCOL_ERROR`() {
        val headersBlock = HpackEncoder().encode(request)
        val (code, _) = rawConnectionError(
            emptySettings,
            frame(Http2Parser.FRAME_HEADERS, 0, 1, headersBlock.copyOfRange(0, 4)),
            frame(Http2Parser.FRAME_PING, 0, 0, ByteArray(8)),
        )
        assertEquals(ErrorCode.PROTOCOL_ERROR, code)
    }

    @Test
    fun `PRIORITY and unknown frame types are accepted and ignored`() {
        val raw = RawServer()
        raw.send(Http2Endpoint.PREFACE)
        raw.send(emptySettings)
        raw.send(frame(Http2Parser.FRAME_PRIORITY, 0, 1, ByteArray(5)))
        raw.send(frame(0xAB, 0, 0, byteArrayOf(1, 2, 3)))
        raw.send(frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_END_STREAM, 1, HpackEncoder().encode(request)))
        raw.listener.expect("HEADERS 1")
        assertNull(raw.error)
        raw.server.close()
        raw.awaitEnd()
    }

    @Test
    fun `padded HEADERS with PRIORITY and a CONTINUATION reassemble to the same header list`() {
        val raw = RawServer()
        val block = HpackEncoder().encode(request + ("x-a" to "1"))
        val split = 5
        val first = ByteArrayOutputStream().apply {
            write(3) // pad length
            write(ByteArray(5)) // stream dependency + weight
            write(block, 0, split)
            write(ByteArray(3)) // padding
        }.toByteArray()
        raw.send(Http2Endpoint.PREFACE)
        raw.send(emptySettings)
        raw.send(frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_PADDED or Http2Parser.FLAG_PRIORITY or Http2Parser.FLAG_END_STREAM, 1, first))
        raw.send(frame(Http2Parser.FRAME_CONTINUATION, Http2Parser.FLAG_END_HEADERS, 1, block.copyOfRange(split, block.size)))
        assertEquals("HEADERS 1 end=true trailers=false", raw.listener.expect("HEADERS 1"))
        assertEquals(request + ("x-a" to "1"), raw.listener.headers[1]!!.entries)
        raw.server.close()
        raw.awaitEnd()
    }

    @Test
    fun `DATA totalling more than the initial window is credited back with WINDOW_UPDATEs as it is consumed`() {
        val raw = RawServer()
        raw.send(Http2Endpoint.PREFACE)
        raw.send(emptySettings)
        raw.send(frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, HpackEncoder().encode(request)))
        raw.listener.expect("HEADERS 1")
        // 5 x 16 KiB = 81,920 > 65,535: legal only because the receiver re-opens its window
        // (half-window batching) as each frame is handed to the listener.
        repeat(4) { raw.send(frame(Http2Parser.FRAME_DATA, 0, 1, ByteArray(16_384))) }
        raw.send(frame(Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, 1, ByteArray(16_384)))
        raw.listener.expect("DATA_END 1")
        assertEquals(5 * 16_384, raw.listener.data[1]!!.size())
        assertNull(raw.error)
        raw.server.close()
        val frames = raw.awaitEnd()
        assertTrue(frames.any { it.type == Http2Parser.FRAME_WINDOW_UPDATE && it.streamId == 0 })
        assertTrue(frames.any { it.type == Http2Parser.FRAME_WINDOW_UPDATE && it.streamId == 1 })
        assertNull(raw.goAwayCode(frames))
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within $timeoutMs ms")
            Thread.sleep(10)
        }
    }

    @Suppress("unused")
    private fun assertFrameTypes(frames: List<H2Frame>, vararg types: Int) {
        assertNotNull(frames)
        assertEquals(types.toList(), frames.map { it.type })
    }
}
