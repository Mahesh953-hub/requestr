package com.example.interception

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.HeaderKind
import com.example.data.local.MessageDirection
import com.example.data.local.RequestrDatabase
import com.example.data.persistence.PersistenceHealth
import com.example.data.persistence.PersistenceQueue
import com.example.interception.http.Http2Parser
import com.example.interception.persist.ProtocolRows
import com.example.interception.tcp.TcpReassembler
import com.example.interception.tcp.TcpStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 20 on the tun path: [CaptureEngine] writes WebSocket messages, SSE events and gRPC
 * call/messages as v20 child rows through the real [PersistenceQueue] into an in-memory Room
 * database, hanging off one parent transaction per upgrade / response / call.
 *
 * Client bytes arrive as genuine IPv4+TCP packets through [CaptureEngine.ingest]. The tun fd only
 * ever carries the client side, so server bytes are injected into the reassembled stream's
 * `serverToClient` buffer (the same buffer the pump's return path fills) and then a bare ACK
 * triggers the engine's inspection pass exactly as a real packet would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CaptureEngineProtocolChildrenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue
    private lateinit var engine: CaptureEngine

    private val client = "10.0.0.5"
    private val server = "93.184.216.34"
    private val cPort = 51000

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1), capacity = 256)
        engine = CaptureEngine(context, db, sessionId = 1L, persistence = queue, protocolChildren = true)
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
    }

    // -- packet plumbing -----------------------------------------------------------------------------

    private fun ipv4Tcp(sPort: Int, seq: Long, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val totalLen = 40 + payload.size
        val buf = ByteArray(totalLen)
        buf[0] = ((4 shl 4) or 5).toByte()
        buf[2] = (totalLen shr 8).toByte()
        buf[3] = totalLen.toByte()
        buf[8] = 64
        buf[9] = 6
        client.split(".").map { it.toInt().toByte() }.forEachIndexed { i, b -> buf[12 + i] = b }
        server.split(".").map { it.toInt().toByte() }.forEachIndexed { i, b -> buf[16 + i] = b }
        val off = 20
        buf[off] = (cPort shr 8).toByte(); buf[off + 1] = cPort.toByte()
        buf[off + 2] = (sPort shr 8).toByte(); buf[off + 3] = sPort.toByte()
        buf[off + 4] = (seq shr 24).toByte(); buf[off + 5] = (seq shr 16).toByte(); buf[off + 6] = (seq shr 8).toByte(); buf[off + 7] = seq.toByte()
        buf[off + 12] = (5 shl 4).toByte()
        buf[off + 13] = flags.toByte()
        System.arraycopy(payload, 0, buf, off + 20, payload.size)
        return buf
    }

    private inner class Connection(val sPort: Int) {
        private var seq = 100L
        private var serverSeq = 500L
        val key = TcpStream.keyFrom(client, cPort, server, sPort)

        init {
            engine.ingest(ipv4Tcp(sPort, seq, TcpReassembler.FLAG_SYN), 40, 4)
            engine.ingest(ipv4Tcp(sPort, seq, TcpReassembler.FLAG_SYN or TcpReassembler.FLAG_ACK), 40, 4)
            seq++
            engine.ingest(ipv4Tcp(sPort, seq, TcpReassembler.FLAG_ACK), 40, 4)
        }

        fun clientSends(bytes: ByteArray) {
            engine.ingest(ipv4Tcp(sPort, seq, TcpReassembler.FLAG_ACK or TcpReassembler.FLAG_PSH, bytes), 40 + bytes.size, 4)
            seq += bytes.size
        }

        fun serverSends(bytes: ByteArray) {
            val stream = reassembler().streams[key] ?: throw AssertionError("stream $key not tracked")
            stream.serverToClient.write(serverSeq, bytes, 0, bytes.size)
            serverSeq += bytes.size
            // A bare ACK from the client makes the engine re-inspect both buffers.
            engine.ingest(ipv4Tcp(sPort, seq, TcpReassembler.FLAG_ACK), 40, 4)
        }

        private fun reassembler(): TcpReassembler {
            val f = CaptureEngine::class.java.getDeclaredField("reassembler")
            f.isAccessible = true
            return f.get(engine) as TcpReassembler
        }
    }

    private fun wsTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size < 126)
        return byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload
    }

    private fun wsMaskedTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size < 126)
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        return byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask + masked
    }

    // -- WebSocket -----------------------------------------------------------------------------------

    @Test
    fun `websocket upgrade over the tun path persists one 101 parent and ordered message children`() = runBlocking {
        val c = Connection(80)
        c.clientSends(
            ("GET /chat HTTP/1.1\r\nHost: ws.example\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
        )
        c.serverSends(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n").toByteArray(),
        )
        c.clientSends(wsMaskedTextFrame("hello"))
        c.serverSends(wsTextFrame("hi there"))
        c.clientSends(wsMaskedTextFrame("bye"))
        queue.awaitIdle()

        val rows = db.capturedTrafficDao().getBySession(1L)
        assertEquals("only the upgrade exchange is a transaction row", 1, rows.size)
        val parent = rows.single()
        assertEquals(101, parent.statusCode)
        assertEquals("GET", parent.method)
        val headerRows = db.transactionHeaderDao().forTraffic(parent.id)
        assertEquals(
            listOf("Host", "Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version"),
            headerRows.filter { it.kind == HeaderKind.REQUEST }.sortedBy { it.ordinal }.map { it.name },
        )
        assertEquals("websocket", headerRows.first { it.kind == HeaderKind.RESPONSE && it.name == "Upgrade" }.value)

        val messages = db.webSocketMessageDao().forTraffic(parent.id)
        assertEquals(listOf(0, 1, 2), messages.map { it.frameIndex })
        assertEquals(listOf("hello", "hi there", "bye"), messages.map { it.payloadText })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.CLIENT), messages.map { it.direction })
        assertTrue(messages.all { it.isText && it.connectionId == c.key && !it.payloadTruncated })
        assertEquals(3, db.webSocketMessageDao().count())
        assertEquals(0L, PersistenceHealth.snapshot().orphanedPatches)
    }

    @Test
    fun `protocolChildren off keeps the legacy flat WS rows on the tun path`() = runBlocking {
        engine.protocolChildren = false
        val c = Connection(80)
        c.clientSends("GET /chat HTTP/1.1\r\nHost: ws.example\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
        c.serverSends("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
        c.clientSends(wsMaskedTextFrame("hello"))
        c.serverSends(wsTextFrame("hi"))
        queue.awaitIdle()

        assertEquals(0, db.webSocketMessageDao().count())
        val rows = db.capturedTrafficDao().getBySession(1L).sortedBy { it.id }
        assertEquals(listOf("GET", "WS→", "WS←"), rows.map { it.method })
        assertEquals("hello", rows[1].requestBody)
        assertEquals("hi", rows[2].responseBody)
    }

    // -- SSE -----------------------------------------------------------------------------------------

    @Test
    fun `text event-stream response persists sse_events children of the carrying transaction`() = runBlocking {
        val c = Connection(80)
        c.clientSends("GET /events HTTP/1.1\r\nHost: sse.example\r\nAccept: text/event-stream\r\n\r\n".toByteArray())
        val body = "event: tick\nid: 1\ndata: one\n\ndata: two\ndata: lines\n\n: keepalive\n\nretry: 2500\ndata: three\n\n"
        c.serverSends(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray(),
        )
        queue.awaitIdle()

        val parent = db.capturedTrafficDao().getBySession(1L).single()
        assertEquals(200, parent.statusCode)
        val events = db.sseEventDao().forTraffic(parent.id)
        assertEquals(listOf(0, 1, 2), events.map { it.eventIndex })
        assertEquals(listOf("tick", null, null), events.map { it.eventName })
        assertEquals(listOf("one", "two\nlines", "three"), events.map { it.data })
        assertEquals("1", events[0].eventId)
        assertEquals(2_500L, events[2].retryMs)
    }

    // -- gRPC over h2c -------------------------------------------------------------------------------

    private fun literalHeader(name: String, value: String): ByteArray {
        require(name.length < 127 && value.length < 127)
        return byteArrayOf(0x40) +
            byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.US_ASCII)
    }

    private fun h2Frame(type: Int, flags: Int, streamId: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
            type.toByte(), flags.toByte(),
            (streamId ushr 24).toByte(), (streamId ushr 16).toByte(), (streamId ushr 8).toByte(), streamId.toByte(),
        ) + payload

    private fun grpcMessage(payload: ByteArray): ByteArray =
        byteArrayOf(0) + byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        ) + payload

    @Test
    fun `h2c grpc call persists one parent, grpc_calls upsert, ordered messages and frame timeline`() = runBlocking {
        val c = Connection(8080)
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray()
        val settings = h2Frame(Http2Parser.FRAME_SETTINGS, 0, 0, ByteArray(0))
        val requestBlock = literalHeader(":method", "POST") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/my.Service/GetUser") + literalHeader(":authority", "grpc.example:8080") +
            literalHeader("content-type", "application/grpc") + literalHeader("te", "trailers")
        val protoRequest = byteArrayOf(0x08, 0x2a, 0x12, 0x05) + "alice".toByteArray()

        c.clientSends(preface + settings + h2Frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, requestBlock))
        queue.awaitIdle()
        // The parent exists as soon as the call is identified -- before any message or response.
        val early = db.capturedTrafficDao().getBySession(1L).single()
        assertEquals("gRPC", early.protocol)
        assertNull(early.statusCode)
        assertEquals("http://grpc.example:8080/my.Service/GetUser", early.url)
        assertFalse(ProtocolRows.hasSyntheticGrpcHeader(early))
        assertNull(db.grpcCallDao().forTraffic(early.id)!!.grpcStatus)

        c.clientSends(h2Frame(Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, 1, grpcMessage(protoRequest)))
        c.serverSends(
            settings +
                h2Frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 1, literalHeader(":status", "200") + literalHeader("content-type", "application/grpc")) +
                h2Frame(Http2Parser.FRAME_DATA, 0, 1, grpcMessage(byteArrayOf(0x08, 0x01))) +
                h2Frame(Http2Parser.FRAME_DATA, 0, 1, grpcMessage(byteArrayOf(0x08, 0x02))) +
                h2Frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or Http2Parser.FLAG_END_STREAM, 1, literalHeader("grpc-status", "0") + literalHeader("grpc-message", "ok%20done")),
        )
        queue.awaitIdle()

        val rows = db.capturedTrafficDao().getBySession(1L)
        assertEquals("no flat per-message rows", 1, rows.size)
        val parent = rows.single()
        assertEquals(200, parent.statusCode)
        assertEquals("gRPC", parent.protocol)
        assertEquals(protoRequest.size + 5L, parent.requestSizeBytes)
        assertEquals(2 * (2 + 5L), parent.responseSizeBytes)

        val call = db.grpcCallDao().forTraffic(parent.id)!!
        assertEquals(0, call.grpcStatus)
        assertEquals("ok done", call.grpcMessage)
        assertEquals("my.Service", call.service)
        assertEquals("GetUser", call.method)
        assertEquals(1, db.grpcCallDao().count())

        val messages = db.grpcMessageDao().forTraffic(parent.id)
        assertEquals(listOf(0, 1, 2), messages.map { it.messageIndex })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.SERVER), messages.map { it.direction })
        assertTrue(messages[0].payload!!.contentEquals(protoRequest))
        assertTrue(messages.all { it.streamId == 1 })

        val headers = db.transactionHeaderDao().forTraffic(parent.id)
        assertEquals(listOf(":method", ":scheme", ":path", ":authority"), headers.filter { it.kind == HeaderKind.REQUEST_PSEUDO }.sortedBy { it.ordinal }.map { it.name })
        assertEquals(listOf(":status"), headers.filter { it.kind == HeaderKind.RESPONSE_PSEUDO }.map { it.name })
        assertEquals(listOf("grpc-status", "grpc-message"), headers.filter { it.kind == HeaderKind.RESPONSE_TRAILER }.sortedBy { it.ordinal }.map { it.name })

        val frames = db.http2StreamEventDao().forTraffic(parent.id)
        // client HEADERS, client DATA, server HEADERS, DATA, DATA, trailer HEADERS (stream 0 SETTINGS are not stream events)
        assertEquals(listOf(1, 0, 1, 0, 0, 1), frames.map { it.frameType })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.SERVER, MessageDirection.SERVER, MessageDirection.SERVER), frames.map { it.direction })
        assertEquals("END_STREAM END_HEADERS", frames.last().note)
        assertTrue(frames.all { it.connectionId == c.key })
    }

    @Test
    fun `RST_STREAM on the tun path closes the grpc call with a null status`() = runBlocking {
        val c = Connection(8080)
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray()
        val requestBlock = literalHeader(":method", "POST") + literalHeader(":scheme", "http") +
            literalHeader(":path", "/my.Service/Watch") + literalHeader(":authority", "grpc.example:8080") +
            literalHeader("content-type", "application/grpc")
        c.clientSends(preface + h2Frame(Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS, 3, requestBlock))
        c.clientSends(h2Frame(Http2Parser.FRAME_DATA, 0, 3, grpcMessage(byteArrayOf(0x08, 0x01))))
        c.clientSends(h2Frame(Http2Parser.FRAME_RST_STREAM, 0, 3, byteArrayOf(0, 0, 0, 8)))
        queue.awaitIdle()

        val parent = db.capturedTrafficDao().getBySession(1L).single()
        assertNull(parent.statusCode)
        assertNull(db.grpcCallDao().forTraffic(parent.id)!!.grpcStatus)
        assertEquals(1, db.grpcMessageDao().countForTraffic(parent.id))
        assertEquals("RST_STREAM CANCEL", db.http2StreamEventDao().forTraffic(parent.id).last().note)
    }
}
