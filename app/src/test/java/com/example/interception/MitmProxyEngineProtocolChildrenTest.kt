package com.example.interception

import com.example.data.local.HeaderKind
import com.example.data.local.MessageDirection
import com.example.data.local.WebSocketMessageEntity
import com.example.data.models.HttpMethod
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.http.GrpcCallEnd
import com.example.interception.http.GrpcCallStart
import com.example.interception.http.GrpcEvent
import com.example.interception.http.Http2Parser
import com.example.interception.http.Http2Session
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.http.WebSocketParser
import com.example.interception.persist.ProtocolRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S3 task 20: with a [com.example.data.persistence.CaptureSink] wired, [MitmProxyEngine] persists
 * WebSocket messages and gRPC messages as v20 child rows under ONE parent transaction instead of
 * the pre-v20 flattened `WS→`/`WS←` and `x-grpc-*` rows. The gRPC half drives a real
 * [Http2Session] (hand-built HPACK, real gRPC length-prefix framing) into the engine's
 * call-start / message / call-end handlers exactly as the h2 relay wires them.
 */
class MitmProxyEngineProtocolChildrenTest {

    private fun request() = ParsedHttpMessage(
        isRequest = true,
        method = HttpMethod.GET,
        url = "https://ws.example/chat",
        path = "/chat",
        host = "ws.example",
        headers = mapOf("upgrade" to "websocket", "set-cookie" to "b=2"),
        headerLines = listOf("Upgrade" to "websocket", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2"),
    )

    private fun response(status: Int = 101) = ParsedHttpMessage(
        isRequest = false,
        method = null,
        statusCode = status,
        headers = mapOf("upgrade" to "websocket"),
        headerLines = listOf("Upgrade" to "websocket"),
        trailers = listOf("X-Checksum" to "abc"),
    )

    private fun textFrame(text: String) = WebSocketParser.WsFrame(
        opcode = WebSocketParser.OP_TEXT, isMasked = false, payload = text.toByteArray(Charsets.UTF_8), totalLength = text.length + 2,
    )

    private fun binaryFrame(bytes: ByteArray) = WebSocketParser.WsFrame(
        opcode = WebSocketParser.OP_BINARY, isMasked = false, payload = bytes, totalLength = bytes.size + 2,
    )

    // -- emitTransaction / header rows ---------------------------------------------------------------

    @Test
    fun `emitTransaction with a sink returns the row handle and writes ordered duplicate-preserving header rows with trailers`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)

        val handle = engine.emitTransaction(
            request(), response(), 42L, "ws.example:443", logRequests = true, logResponses = true,
            headers = ProtocolRows.headerRows(request(), response(), logRequests = true, logResponses = true),
        )

        val row = sink.row(handle ?: throw AssertionError("sink path must return a handle"))
        assertEquals(101, row.entity.statusCode)
        val requestRows = row.headers.filter { it.kind == HeaderKind.REQUEST }
        // Wire order and raw casing survive; the duplicate Set-Cookie the map collapsed is kept.
        assertEquals(listOf("Upgrade", "Set-Cookie", "Set-Cookie"), requestRows.map { it.name })
        assertEquals(listOf("a=1", "b=2"), requestRows.filter { it.name == "Set-Cookie" }.map { it.value })
        assertEquals(listOf(0, 1, 2), requestRows.map { it.ordinal })
        assertEquals(listOf("X-Checksum" to "abc"), row.headers.filter { it.kind == HeaderKind.RESPONSE_TRAILER }.map { it.name to it.value })
        assertTrue(row.headers.all { it.trafficId == row.id })
    }

    @Test
    fun `emitTransaction without a sink still delivers through the legacy callback and returns null`() {
        var delivered = 0
        val engine = MitmProxyEngine(onDecryptedTransaction = { delivered++ })
        val handle = engine.emitTransaction(request(), response(200), 1L, "ws.example:443", logRequests = true, logResponses = true)
        assertNull(handle)
        assertEquals(1, delivered)
    }

    @Test
    fun `response logging off suppresses response header rows but keeps request rows`() {
        val rows = ProtocolRows.headerRows(request(), response(), logRequests = true, logResponses = false)
        assertTrue(rows.none { it.kind == HeaderKind.RESPONSE || it.kind == HeaderKind.RESPONSE_TRAILER })
        assertEquals(3, rows.count { it.kind == HeaderKind.REQUEST })
    }

    // -- WebSocket -----------------------------------------------------------------------------------

    @Test
    fun `websocket frames become interleaved child rows under the 101 upgrade row and no flat rows`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        val state = MitmProxyEngine.ConnectionProtocolState().apply { wsUrl = "wss://ws.example/chat" }
        state.wsParent = engine.emitTransaction(request(), response(), 1L, "ws.example:443", logRequests = true, logResponses = true)

        engine.emitWsFrame(textFrame("hello"), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(textFrame("hi there"), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(binaryFrame(byteArrayOf(0, 1, 2, 3, 0x7f)), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)

        assertEquals("only the upgrade row is a transaction", 1, sink.rows.size)
        val parentId = sink.rows.single().id
        val messages: List<WebSocketMessageEntity> = sink.webSocketMessages
        assertEquals(listOf(0, 1, 2), messages.map { it.frameIndex })
        assertTrue(messages.all { it.trafficId == parentId && it.connectionId == "ws.example:443" })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.CLIENT), messages.map { it.direction })
        assertEquals("hello", messages[0].payloadText)
        assertTrue(messages[0].isText)
        assertEquals(WebSocketParser.OP_TEXT, messages[0].opcode)
        assertEquals("hi there", messages[1].payloadText)
        assertFalse(messages[2].isText)
        assertNull(messages[2].payloadText)
        assertTrue(messages[2].payloadBytes!!.contentEquals(byteArrayOf(0, 1, 2, 3, 0x7f)))
        assertEquals(5L, messages[2].sizeBytes)
        assertFalse(messages[2].payloadTruncated)
    }

    @Test
    fun `websocket text over MAX_TEXT_CHARS is truncated and flagged, size keeps the true length`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        val state = MitmProxyEngine.ConnectionProtocolState()
        state.wsParent = engine.emitTransaction(request(), response(), 1L, "ws.example:443", logRequests = true, logResponses = true)
        val big = "x".repeat(WebSocketMessageEntity.MAX_TEXT_CHARS + 1_000)

        engine.emitWsFrame(textFrame(big), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)

        val row = sink.webSocketMessages.single()
        assertEquals(WebSocketMessageEntity.MAX_TEXT_CHARS, row.payloadText!!.length)
        assertTrue(row.payloadTruncated)
        assertEquals(big.length.toLong(), row.sizeBytes)
    }

    @Test
    fun `websocket payload logging follows the direction toggle`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        val state = MitmProxyEngine.ConnectionProtocolState()
        state.wsParent = engine.emitTransaction(request(), response(), 1L, "ws.example:443", logRequests = true, logResponses = false)

        engine.emitWsFrame(textFrame("kept"), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = false)
        engine.emitWsFrame(textFrame("dropped"), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = false)

        assertEquals("kept", sink.webSocketMessages[0].payloadText)
        assertNull(sink.webSocketMessages[1].payloadText)
        assertEquals("dropped".length.toLong(), sink.webSocketMessages[1].sizeBytes)
    }

    @Test
    fun `protocolChildren off keeps the legacy flat WS rows even with a sink and parent`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        engine.protocolChildren = false
        val state = MitmProxyEngine.ConnectionProtocolState().apply { wsUrl = "wss://ws.example/chat" }
        state.wsParent = engine.emitTransaction(request(), response(), 1L, "ws.example:443", logRequests = true, logResponses = true)

        engine.emitWsFrame(textFrame("hello"), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        engine.emitWsFrame(textFrame("hi"), isClient = false, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)

        assertTrue(sink.webSocketMessages.isEmpty())
        val flat = sink.rows.drop(1).map { it.entity }
        assertEquals(listOf("WS→", "WS←"), flat.map { it.method })
        assertEquals("wss://ws.example/chat (TEXT)", flat[0].url)
        assertEquals("hello", flat[0].requestBody)
        assertEquals("hi", flat[1].responseBody)
    }

    @Test
    fun `websocket frames before the upgrade row exists fall back to flat rows`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        val state = MitmProxyEngine.ConnectionProtocolState() // wsParent == null
        engine.emitWsFrame(textFrame("orphan"), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        assertTrue(sink.webSocketMessages.isEmpty())
        assertEquals("WS→", sink.rows.single().entity.method)
    }

    @Test
    fun `websocket children of a dropped parent are refused, never written as flat rows`() {
        val sink = RecordingCaptureSink()
        val engine = MitmProxyEngine(sink = sink)
        val state = MitmProxyEngine.ConnectionProtocolState()
        state.wsParent = RecordingCaptureSink.deadParent()
        engine.emitWsFrame(textFrame("lost"), isClient = true, wsState = state, host = "ws.example", port = 443, logRequests = true, logResponses = true)
        assertTrue(sink.rows.isEmpty())
        assertTrue(sink.webSocketMessages.isEmpty())
        assertEquals(1L, sink.droppedChildren.get())
    }

    // -- gRPC over a real Http2Session ---------------------------------------------------------------

    private fun literalHeader(name: String, value: String): ByteArray {
        require(name.length < 127 && value.length < 127)
        return byteArrayOf(0x40) +
            byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(value.length.toByte()) + value.toByteArray(Charsets.US_ASCII)
    }

    private fun grpcFrame(payload: ByteArray): ByteArray =
        byteArrayOf(0) + byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        ) + payload

    private fun headersFrame(streamId: Int, block: ByteArray, flags: Int) =
        Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, flags, streamId, block)

    private fun dataFrame(streamId: Int, payload: ByteArray, endStream: Boolean) =
        Http2Parser.H2Frame(payload.size, Http2Parser.FRAME_DATA, if (endStream) Http2Parser.FLAG_END_STREAM else 0, streamId, payload)

    private fun rstStreamFrame(streamId: Int, code: Int) =
        Http2Parser.H2Frame(4, Http2Parser.FRAME_RST_STREAM, 0, streamId, byteArrayOf(0, 0, 0, code.toByte()))

    private val END_HEADERS = Http2Parser.FLAG_END_HEADERS
    private val END_STREAM = Http2Parser.FLAG_END_STREAM

    /** Wires an Http2Session into the engine exactly like the h2 relay does. */
    private class GrpcHarness(val sink: RecordingCaptureSink = RecordingCaptureSink()) {
        val engine = MitmProxyEngine(sink = sink)
        val table = MitmProxyEngine.GrpcCallTable()
        val starts = CopyOnWriteArrayList<GrpcCallStart>()
        val events = CopyOnWriteArrayList<GrpcEvent>()
        val ends = CopyOnWriteArrayList<GrpcCallEnd>()
        val session = Http2Session(
            onTransaction = { throw AssertionError("gRPC must never surface as an h2 transaction: $it") },
            onGrpcMessage = { events += it; engine.emitGrpcMessage(it, "grpc.example.com", 443, logRequests = true, logResponses = true, table = table) },
            onGrpcCallStarted = { starts += it; engine.emitGrpcCallStart(it, "grpc.example.com", 443, logRequests = true, table = table) },
            onGrpcCallEnded = { ends += it; engine.emitGrpcCallEnd(it, "grpc.example.com", 443, logRequests = true, logResponses = true, table = table) },
        )
    }

    private val grpcRequestBlock
        get() = literalHeader(":method", "POST") + literalHeader(":scheme", "https") +
            literalHeader(":path", "/my.Service/GetUser") + literalHeader(":authority", "grpc.example.com") +
            literalHeader("content-type", "application/grpc") + literalHeader("te", "trailers")

    @Test
    fun `a unary grpc call is one parent row with call, ordered messages, trailer status and frame timeline`() {
        val h = GrpcHarness()
        val protoRequest = byteArrayOf(0x08, 0x2a, 0x12, 0x05) + "alice".toByteArray()

        h.session.onClientFrames(listOf(headersFrame(3, grpcRequestBlock, END_HEADERS)))
        // Parent row exists as soon as the call is identified, before any message.
        assertEquals(1, h.sink.rows.size)
        val parent = h.sink.rows.single()
        assertEquals("gRPC", parent.entity.protocol)
        assertEquals("POST", parent.entity.method)
        assertEquals("https://grpc.example.com/my.Service/GetUser", parent.entity.url)
        assertNull("statusCode stays NULL until trailers", parent.entity.statusCode)
        assertFalse(ProtocolRows.hasSyntheticGrpcHeader(parent.entity))
        assertEquals(listOf(":method", ":scheme", ":path", ":authority"), parent.headers.filter { it.kind == HeaderKind.REQUEST_PSEUDO }.map { it.name })
        assertEquals(listOf("content-type", "te"), parent.headers.filter { it.kind == HeaderKind.REQUEST }.map { it.name })
        assertEquals(1, h.sink.grpcCalls.size)
        assertNull(h.sink.grpcCalls.single().grpcStatus)
        assertEquals("my.Service", h.sink.grpcCalls.single().service)
        assertEquals("GetUser", h.sink.grpcCalls.single().method)

        h.session.onClientFrames(listOf(dataFrame(3, grpcFrame(protoRequest), endStream = true)))
        h.session.onServerFrames(listOf(headersFrame(3, literalHeader(":status", "200") + literalHeader("content-type", "application/grpc"), END_HEADERS)))
        h.session.onServerFrames(listOf(dataFrame(3, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = false)))
        h.session.onServerFrames(listOf(dataFrame(3, grpcFrame(byteArrayOf(0x08, 0x02)), endStream = false)))
        h.session.onServerFrames(listOf(headersFrame(3, literalHeader("grpc-status", "0") + literalHeader("grpc-message", "OK%20done"), END_HEADERS or END_STREAM)))

        assertEquals("no flat per-message rows", 1, h.sink.rows.size)
        assertEquals(3, h.sink.grpcMessages.size)
        assertEquals(listOf(0, 1, 2), h.sink.grpcMessages.map { it.messageIndex })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.SERVER), h.sink.grpcMessages.map { it.direction })
        assertTrue(h.sink.grpcMessages.all { it.trafficId == parent.id && it.streamId == 3 })
        assertTrue(h.sink.grpcMessages[0].payload!!.contentEquals(protoRequest))
        assertTrue(h.sink.grpcMessages[0].decodedText!!.contains("alice"))

        assertEquals(1, h.ends.size)
        val call = h.sink.grpcCalls.single()
        assertEquals(parent.id, call.trafficId)
        assertEquals(0, call.grpcStatus)
        assertEquals("OK done", call.grpcMessage)
        val patch = h.sink.patches.single()
        assertEquals(parent.id, patch.targetId)
        assertEquals(200, patch.patch.statusCode)
        assertEquals(protoRequest.size + 5L, patch.patch.requestSizeBytes)
        assertEquals(2 * (2 + 5L), patch.patch.responseSizeBytes)
        assertEquals(listOf(":status"), patch.headers!!.filter { it.kind == HeaderKind.RESPONSE_PSEUDO }.map { it.name })
        assertEquals(listOf("grpc-status" to "0", "grpc-message" to "OK%20done"), patch.headers!!.filter { it.kind == HeaderKind.RESPONSE_TRAILER }.map { it.name to it.value })

        val frames = h.sink.http2StreamEvents
        assertTrue(frames.all { it.trafficId == parent.id && it.streamId == 3 })
        assertEquals(listOf(1, 0, 1, 0, 0, 1), frames.map { it.frameType })
        assertEquals("END_STREAM END_HEADERS", frames.last().note)
        assertTrue(h.table.byStream.isEmpty())
    }

    @Test
    fun `RST_STREAM closes the call with a null status and the reset code in the frame timeline`() {
        val h = GrpcHarness()
        h.session.onClientFrames(listOf(headersFrame(5, grpcRequestBlock, END_HEADERS)))
        h.session.onClientFrames(listOf(dataFrame(5, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = false)))
        h.session.onServerFrames(listOf(rstStreamFrame(5, 8))) // CANCEL

        assertEquals(1, h.ends.size)
        assertNull(h.sink.grpcCalls.single().grpcStatus)
        assertEquals(1, h.sink.patches.size)
        assertNull(h.sink.patches.single().patch.statusCode)
        assertEquals("RST_STREAM CANCEL", h.sink.http2StreamEvents.last().note)
        assertTrue(h.table.byStream.isEmpty())
    }

    @Test
    fun `trailers-only response ends the call with grpc-status and status pseudo header in one block`() {
        val h = GrpcHarness()
        h.session.onClientFrames(listOf(headersFrame(7, grpcRequestBlock, END_HEADERS)))
        h.session.onClientFrames(listOf(dataFrame(7, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = true)))
        h.session.onServerFrames(listOf(headersFrame(7, literalHeader(":status", "200") + literalHeader("content-type", "application/grpc") + literalHeader("grpc-status", "16"), END_HEADERS or END_STREAM)))

        assertEquals(1, h.ends.size)
        assertEquals(16, h.sink.grpcCalls.single().grpcStatus)
        assertEquals(200, h.sink.patches.single().patch.statusCode)
    }

    @Test
    fun `interleaved grpc streams keep their messages under their own parents`() {
        val h = GrpcHarness()
        h.session.onClientFrames(listOf(headersFrame(1, grpcRequestBlock, END_HEADERS)))
        h.session.onClientFrames(listOf(headersFrame(3, literalHeader(":method", "POST") + literalHeader(":scheme", "https") + literalHeader(":path", "/my.Service/List") + literalHeader(":authority", "grpc.example.com") + literalHeader("content-type", "application/grpc"), END_HEADERS)))
        h.session.onClientFrames(listOf(dataFrame(3, grpcFrame(byteArrayOf(0x08, 0x03)), endStream = true)))
        h.session.onClientFrames(listOf(dataFrame(1, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = true)))

        assertEquals(2, h.sink.rows.size)
        val byUrl = h.sink.rows.associateBy { it.entity.url }
        val one = byUrl.getValue("https://grpc.example.com/my.Service/GetUser").id
        val three = byUrl.getValue("https://grpc.example.com/my.Service/List").id
        assertEquals(one, h.sink.grpcMessages.single { it.streamId == 1 }.trafficId)
        assertEquals(three, h.sink.grpcMessages.single { it.streamId == 3 }.trafficId)
        assertEquals(0, h.sink.grpcMessages.single { it.streamId == 3 }.messageIndex)
        assertEquals(0, h.sink.grpcMessages.single { it.streamId == 1 }.messageIndex)
    }

    @Test
    fun `protocolChildren off keeps the legacy flat x-grpc rows`() {
        val h = GrpcHarness()
        h.engine.protocolChildren = false
        h.session.onClientFrames(listOf(headersFrame(1, grpcRequestBlock, END_HEADERS)))
        h.session.onClientFrames(listOf(dataFrame(1, grpcFrame(byteArrayOf(0x08, 0x01)), endStream = true)))

        assertTrue(h.sink.grpcMessages.isEmpty())
        assertTrue(h.sink.grpcCalls.isEmpty())
        val flat = h.sink.rows.single().entity
        assertTrue(ProtocolRows.hasSyntheticGrpcHeader(flat))
        assertEquals("gRPC", flat.protocol)
    }

    @Test
    fun `sse events rows are built from the event stream body`() {
        val body = "event: tick\nid: 7\nretry: 5000\ndata: one\ndata: two\n\ndata: plain\n\n: comment only\n\n"
        val rows = ProtocolRows.sseEvents(body, now = 99L, firstIndex = 4)
        assertEquals(2, rows.size)
        assertEquals("tick", rows[0].eventName)
        assertEquals("one\ntwo", rows[0].data)
        assertEquals("7", rows[0].eventId)
        assertEquals(5_000L, rows[0].retryMs)
        assertEquals(4, rows[0].eventIndex)
        assertNull("default 'message' event name is stored as null", rows[1].eventName)
        assertEquals("plain", rows[1].data)
        assertEquals(5, rows[1].eventIndex)
        assertTrue(rows.all { it.timestampMs == 99L })
    }
}
