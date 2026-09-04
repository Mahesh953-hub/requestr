package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.GrpcCallEntity
import com.example.data.local.GrpcMessageEntity
import com.example.data.local.Http2StreamEventEntity
import com.example.data.local.MessageDirection
import com.example.data.local.RequestrDatabase
import com.example.data.local.SseEventEntity
import com.example.data.local.WebSocketMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 20: [PersistenceQueue] as a [CaptureSink] -- the v20 child tables are written through
 * `PersistenceOp.AppendChildren` against a real in-memory Room database, resolving the parent
 * handle inside the actor so a child queued before its parent's insert committed still lands on
 * the right `trafficId`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PersistenceQueueChildrenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1), capacity = 64)
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
    }

    private fun parentRow(protocol: String = "HTTP/1.1", status: Int? = 101) = CapturedTrafficEntity(
        sessionId = 1L,
        method = "GET",
        url = "wss://ws.example/chat",
        host = "ws.example",
        path = "/chat",
        statusCode = status,
        protocol = protocol,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = null,
        requestSizeBytes = 0,
        responseSizeBytes = 0,
        latencyMs = 0,
        timestamp = 1_000L,
        wasDecrypted = true,
        connectionId = "ws.example:443",
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    private fun wsMessage(index: Int, direction: MessageDirection, text: String) = WebSocketMessageEntity(
        trafficId = 0, connectionId = "ws.example:443", direction = direction, opcode = 1, isText = true,
        payloadText = text, payloadBytes = null, payloadTruncated = false, sizeBytes = text.length.toLong(),
        timestampMs = 2_000L + index, frameIndex = index,
    )

    @Test
    fun `websocket children queued right after the parent insert resolve to its id in order`() = runBlocking {
        val sink: CaptureSink = queue
        val parent = sink.transaction(parentRow())
        // No await here: the children are submitted while the parent insert is still queued.
        sink.webSocketMessage(parent, wsMessage(0, MessageDirection.CLIENT, "hello"))
        sink.webSocketMessage(parent, wsMessage(1, MessageDirection.SERVER, "world"))
        sink.webSocketMessage(parent, wsMessage(2, MessageDirection.CLIENT, "bye"))
        queue.awaitIdle()

        val id = withTimeout(5_000) { parent.await() }
        val rows = db.webSocketMessageDao().forTraffic(id)
        assertEquals(listOf(0, 1, 2), rows.map { it.frameIndex })
        assertEquals(listOf("hello", "world", "bye"), rows.map { it.payloadText })
        assertEquals(listOf(MessageDirection.CLIENT, MessageDirection.SERVER, MessageDirection.CLIENT), rows.map { it.direction })
        assertEquals(3, db.webSocketMessageDao().countForTraffic(id))
        assertEquals(0L, PersistenceHealth.snapshot().orphanedPatches)
    }

    @Test
    fun `sse grpc and h2 frame children land under the parent and grpc call upserts on the trafficId`() = runBlocking {
        val sink: CaptureSink = queue
        val parent = sink.transaction(parentRow(protocol = "gRPC", status = null))
        sink.sseEvents(parent, listOf(
            SseEventEntity(trafficId = 0, eventName = null, data = "one", eventId = "1", retryMs = null, timestampMs = 1, eventIndex = 0),
            SseEventEntity(trafficId = 0, eventName = "tick", data = "two", eventId = null, retryMs = 3_000, timestampMs = 2, eventIndex = 1),
        ))
        sink.sseEvents(parent, emptyList()) // no-op, must not enqueue anything
        sink.grpcCall(parent, GrpcCallEntity(trafficId = 0, service = "my.Service", method = "GetUser", encoding = "proto", streamId = 3, grpcStatus = null, grpcMessage = null))
        sink.grpcMessage(parent, GrpcMessageEntity(
            trafficId = 0, streamId = 3, direction = MessageDirection.CLIENT, compressed = false, messageIndex = 0,
            payload = byteArrayOf(8, 42), payloadTruncated = false, decodedText = "1: 42", sizeBytes = 2, timestampMs = 5,
        ))
        sink.grpcMessage(parent, GrpcMessageEntity(
            trafficId = 0, streamId = 3, direction = MessageDirection.SERVER, compressed = false, messageIndex = 1,
            payload = byteArrayOf(8, 1), payloadTruncated = false, decodedText = "1: 1", sizeBytes = 2, timestampMs = 6,
        ))
        // Trailers arrive: the same primary key is REPLACEd with the final status.
        sink.grpcCall(parent, GrpcCallEntity(trafficId = 0, service = "my.Service", method = "GetUser", encoding = "proto", streamId = 3, grpcStatus = 7, grpcMessage = "denied"))
        sink.http2StreamEvents(parent, listOf(
            Http2StreamEventEntity(trafficId = 0, connectionId = "c", streamId = 3, direction = MessageDirection.CLIENT, frameType = 1, flags = 4, payloadLength = 40, timestampMs = 1, note = "END_HEADERS"),
            Http2StreamEventEntity(trafficId = 0, connectionId = "c", streamId = 3, direction = MessageDirection.SERVER, frameType = 1, flags = 5, payloadLength = 8, timestampMs = 9, note = "END_STREAM END_HEADERS"),
        ))
        queue.awaitIdle()

        val id = withTimeout(5_000) { parent.await() }
        val sse = db.sseEventDao().forTraffic(id)
        assertEquals(listOf("one", "two"), sse.map { it.data })
        assertEquals(listOf(null, "tick"), sse.map { it.eventName })
        assertEquals(3_000L, sse[1].retryMs)

        val call = db.grpcCallDao().forTraffic(id) ?: throw AssertionError("grpc_calls row missing")
        assertEquals(7, call.grpcStatus)
        assertEquals("denied", call.grpcMessage)
        assertEquals(1, db.grpcCallDao().count()) // upsert, not a second row

        val messages = db.grpcMessageDao().forTraffic(id)
        assertEquals(listOf(0, 1), messages.map { it.messageIndex })
        assertTrue(messages[0].payload!!.contentEquals(byteArrayOf(8, 42)))
        assertEquals(MessageDirection.SERVER, messages[1].direction)

        val frames = db.http2StreamEventDao().forTraffic(id)
        assertEquals(listOf("END_HEADERS", "END_STREAM END_HEADERS"), frames.map { it.note })
        assertTrue(frames.all { it.trafficId == id })
    }

    @Test
    fun `deleting the parent cascades to every child table`() = runBlocking {
        val sink: CaptureSink = queue
        val parent = sink.transaction(parentRow())
        sink.webSocketMessage(parent, wsMessage(0, MessageDirection.CLIENT, "x"))
        sink.sseEvents(parent, listOf(SseEventEntity(trafficId = 0, eventName = null, data = "d", eventId = null, retryMs = null, timestampMs = 1, eventIndex = 0)))
        sink.grpcCall(parent, GrpcCallEntity(trafficId = 0, service = "s", method = "m", encoding = "proto", streamId = 1, grpcStatus = 0, grpcMessage = null))
        sink.grpcMessage(parent, GrpcMessageEntity(trafficId = 0, streamId = 1, direction = MessageDirection.CLIENT, compressed = false, messageIndex = 0, payload = null, payloadTruncated = false, decodedText = null, sizeBytes = 0, timestampMs = 1))
        sink.http2StreamEvents(parent, listOf(Http2StreamEventEntity(trafficId = 0, connectionId = "c", streamId = 1, direction = MessageDirection.CLIENT, frameType = 0, flags = 0, payloadLength = 0, timestampMs = 1, note = null)))
        queue.awaitIdle()
        val id = withTimeout(5_000) { parent.await() }
        assertEquals(1, db.webSocketMessageDao().count())

        db.capturedTrafficDao().deleteById(id)

        assertEquals(0, db.webSocketMessageDao().countForTraffic(id))
        assertEquals(0, db.sseEventDao().countForTraffic(id))
        assertNull(db.grpcCallDao().forTraffic(id))
        assertEquals(0, db.grpcMessageDao().countForTraffic(id))
        assertEquals(0, db.http2StreamEventDao().count())
    }

    @Test
    fun `children of a dead parent are refused without touching the queue and counted as orphaned`() = runBlocking {
        val sink: CaptureSink = queue
        val dead = RecordingCaptureSink.deadParent()
        assertTrue(dead.isDead)

        sink.webSocketMessage(dead, wsMessage(0, MessageDirection.CLIENT, "lost"))
        sink.grpcMessage(dead, GrpcMessageEntity(trafficId = 0, streamId = 1, direction = MessageDirection.CLIENT, compressed = false, messageIndex = 0, payload = null, payloadTruncated = false, decodedText = null, sizeBytes = 0, timestampMs = 1))
        sink.sseEvents(dead, emptyList()) // empty list short-circuits before the dead check

        assertEquals(0, queue.pending)
        queue.awaitIdle()
        assertEquals(0, db.webSocketMessageDao().count())
        assertEquals(0, db.grpcMessageDao().count())
        assertEquals(2L, PersistenceHealth.snapshot().orphanedPatches)
    }
}
