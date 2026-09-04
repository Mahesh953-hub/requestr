package com.example.data.search

import android.content.Context
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.MessageDirection
import com.example.data.local.RequestrDatabase
import com.example.data.local.SearchPresetEntity
import com.example.data.local.SseEventEntity
import com.example.data.local.WebSocketMessageEntity
import com.example.data.persistence.CaptureSink
import com.example.data.persistence.PersistenceHealth
import com.example.data.persistence.PersistenceQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3 task 22, end to end on the bundled SQLite driver (the only runtime with FTS5 -- Robolectric's
 * framework SQLite reports `no such module: fts5`, which [Migration21To22Test] covers): rows written
 * through [PersistenceQueue] are found by `MATCH` on url / host / header / body, protocol children
 * make their parent findable, deletes drop the FTS row, binary bodies are not searchable by content,
 * the `LIKE` fallback agrees with the index, and [FtsSchema.ensure] populates an existing database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrafficFtsIndexTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue
    private val fts = TrafficSearchCompiler(useFts = true)
    private val like = TrafficSearchCompiler(useFts = false)

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        FtsSchema.available.set(false)
        context.deleteDatabase(DB_NAME)
        // File-backed so a second raw connection can drop/re-create the index under Room's pool.
        db = Room.databaseBuilder(context, RequestrDatabase::class.java, DB_NAME)
            .setDriver(BundledSQLiteDriver())
            .addCallback(FtsSchema.roomCallback)
            .build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1), capacity = 64)
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        context.deleteDatabase(DB_NAME)
        FtsSchema.available.set(false)
    }

    /** Runs [block] against a raw bundled-driver connection to the same file. */
    private fun <R> raw(block: (FtsSchema.Sql) -> R): R {
        val conn = BundledSQLiteDriver().open(context.getDatabasePath(DB_NAME).path)
        try { return block(FtsSchema.sql(conn)) } finally { conn.close() }
    }

    private fun row(
        url: String, host: String, path: String, status: Int = 200, method: String = "GET",
        requestHeaders: Map<String, String> = emptyMap(), responseHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null, responseBody: String? = null, binaryResponse: Boolean = false,
        latency: Long = 10, timestamp: Long = 1_000L, protocol: String = "HTTP/1.1",
    ) = CapturedTrafficEntity(
        sessionId = 1L, method = method, url = url, host = host, path = path, statusCode = status, protocol = protocol,
        requestHeaders = requestHeaders, responseHeaders = responseHeaders, requestBody = requestBody, responseBody = responseBody,
        requestSizeBytes = 0, responseSizeBytes = 0, latencyMs = latency, timestamp = timestamp, wasDecrypted = true,
        isResponseBodyBinary = binaryResponse, responseBodyBytes = if (binaryResponse) byteArrayOf(0x50, 0x4b, 0x03, 0x04) else null,
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    private companion object { const val DB_NAME = "traffic-fts-index-test.db" }

    private suspend fun ids(compiler: TrafficSearchCompiler, raw: String): List<Long> {
        val c = compiler.compile(raw)
        return db.trafficFtsDao().search(SimpleSQLiteQuery(c.sql, c.args.toTypedArray())).map { it.id }
    }

    private suspend fun ftsCount(): Long = db.useWriterConnection { conn ->
        conn.usePrepared("SELECT COUNT(*) FROM ${FtsSchema.TABLE}") { it.step(); it.getLong(0) }
    }

    private suspend fun insert(entity: CapturedTrafficEntity): Long {
        val h = queue.insertTransaction(entity)
        queue.awaitIdle()
        return withTimeout(5_000) { h.await() }
    }

    @Test
    fun `index exists on open and rows inserted through the queue are found by url host header and body`() = runBlocking {
        val a = insert(row("https://api.example/v1/users", "api.example", "/v1/users", requestHeaders = mapOf("x-request-id" to "req-7f3a"), responseBody = "{\"error\":\"rate limit exceeded\"}", status = 429, timestamp = 1_000))
        val b = insert(row("https://cdn.example/app.js", "cdn.example", "/app.js", responseHeaders = mapOf("content-type" to "application/javascript"), responseBody = "function boot() {}", timestamp = 2_000))
        assertTrue("callback created the FTS5 index on the bundled driver (Room opens lazily)", FtsSchema.available.get())
        assertEquals(2, ftsCount())

        assertEquals(listOf(a), ids(fts, "rate"))
        assertEquals(listOf(a), ids(fts, "\"rate limit\""))
        assertEquals(listOf(a), ids(fts, "req-7f3a"))
        assertEquals(listOf(a), ids(fts, "header:x-request-id"))
        assertEquals(listOf(b), ids(fts, "boot"))
        assertEquals(listOf(b), ids(fts, "app.js"))
        assertEquals(listOf(b, a), ids(fts, "example"))
        assertEquals(listOf(a), ids(fts, "example status:429"))
        assertEquals(listOf(b), ids(fts, "example NOT status:429"))
        assertEquals(listOf(b), ids(fts, "ct:javascript"))
        assertEquals(emptyList<Long>(), ids(fts, "body:x-request-id"))
        assertEquals(emptyList<Long>(), ids(fts, "nonexistent"))
    }

    @Test
    fun `LIKE fallback returns the same hits as the index`() = runBlocking {
        val a = insert(row("https://api.example/v1/users", "api.example", "/v1/users", responseBody = "rate limit exceeded", status = 429, timestamp = 1_000))
        val b = insert(row("https://cdn.example/app.js", "cdn.example", "/app.js", responseBody = "function boot() {}", timestamp = 2_000))
        for (q in listOf("rate", "boot", "example", "example status:429", "cdn OR users", "-cdn", "body:limit", "header:zzz")) {
            assertEquals(q, ids(fts, q), ids(like, q))
        }
        assertEquals(listOf(b, a), ids(like, "example"))
    }

    @Test
    fun `protocol children make the parent findable and update through the queue`() = runBlocking {
        val sink: CaptureSink = queue
        val parent = sink.transaction(row("wss://ws.example/chat", "ws.example", "/chat", status = 101, protocol = "WEBSOCKET"))
        sink.webSocketMessage(parent, WebSocketMessageEntity(trafficId = 0, connectionId = "c", direction = MessageDirection.CLIENT, opcode = 1, isText = true, payloadText = "subscribe ticker:BTCUSD", payloadBytes = null, payloadTruncated = false, sizeBytes = 10, timestampMs = 1, frameIndex = 0))
        sink.webSocketMessage(parent, WebSocketMessageEntity(trafficId = 0, connectionId = "c", direction = MessageDirection.SERVER, opcode = 2, isText = false, payloadText = null, payloadBytes = byteArrayOf(1, 2), payloadTruncated = false, sizeBytes = 2, timestampMs = 2, frameIndex = 1))
        val sse = sink.transaction(row("https://events.example/stream", "events.example", "/stream", responseHeaders = mapOf("content-type" to "text/event-stream"), timestamp = 3_000))
        sink.sseEvents(sse, listOf(SseEventEntity(trafficId = 0, eventName = "deploy", data = "build 4821 finished", eventId = null, retryMs = null, timestampMs = 3, eventIndex = 0)))
        queue.awaitIdle()
        val wsId = withTimeout(5_000) { parent.await() }
        val sseId = withTimeout(5_000) { sse.await() }

        assertEquals(listOf(wsId), ids(fts, "BTCUSD"))
        assertEquals(listOf(wsId), ids(fts, "body:subscribe"))
        assertEquals(listOf(sseId), ids(fts, "4821"))
        assertEquals(listOf(sseId), ids(fts, "deploy"))
        assertEquals(ids(fts, "BTCUSD"), ids(like, "BTCUSD"))
        assertEquals(ids(fts, "4821"), ids(like, "4821"))
    }

    @Test
    fun `binary bodies are findable by url and headers but never by content, and deletes drop the FTS row`() = runBlocking {
        val id = insert(row("https://files.example/archive.zip", "files.example", "/archive.zip", responseHeaders = mapOf("content-type" to "application/zip"), binaryResponse = true))
        assertEquals(listOf(id), ids(fts, "archive"))
        assertEquals(listOf(id), ids(fts, "ct:zip"))
        assertEquals(emptyList<Long>(), ids(fts, "PK"))
        db.capturedTrafficDao().deleteById(id)
        assertEquals(0, ftsCount())
        assertEquals(emptyList<Long>(), ids(fts, "archive"))
    }

    @Test
    fun `patching the decoded exchange re-indexes the row`() = runBlocking {
        val id = insert(row("https://api.example/x", "api.example", "/x", responseBody = "pending"))
        assertEquals(listOf(id), ids(fts, "pending"))
        db.useWriterConnection { conn ->
            conn.usePrepared("UPDATE captured_traffic SET responseBody = ? WHERE id = ?") { it.bindText(1, "completed payload"); it.bindLong(2, id); it.step() }
        }
        assertEquals(emptyList<Long>(), ids(fts, "pending"))
        assertEquals(listOf(id), ids(fts, "completed"))
        assertEquals(1, ftsCount())
    }

    @Test
    fun `ensure populates an existing database including children and is idempotent`() = runBlocking {
        val sink: CaptureSink = queue
        val parent = sink.transaction(row("wss://ws.example/chat", "ws.example", "/chat", status = 101, protocol = "WEBSOCKET", responseBody = null))
        sink.webSocketMessage(parent, WebSocketMessageEntity(trafficId = 0, connectionId = "c", direction = MessageDirection.CLIENT, opcode = 1, isText = true, payloadText = "hello from the socket", payloadBytes = null, payloadTruncated = false, sizeBytes = 10, timestampMs = 1, frameIndex = 0))
        val plain = insert(row("https://api.example/x", "api.example", "/x", responseBody = "plain body"))
        val wsId = withTimeout(5_000) { parent.await() }

        // Simulate a database that predates the index (framework-driver migration without FTS5).
        raw { FtsSchema.drop(it) }
        assertFalse(FtsSchema.available.get())
        raw { assertTrue(FtsSchema.ensure(it)) }
        assertTrue(FtsSchema.available.get())
        assertEquals(2, ftsCount())
        assertEquals(listOf(wsId), ids(fts, "socket"))
        assertEquals(listOf(plain), ids(fts, "plain"))

        // Second ensure: nothing duplicated, nothing lost.
        raw { assertTrue(FtsSchema.ensure(it)) }
        assertEquals(2, ftsCount())
        assertEquals(listOf(wsId), ids(fts, "socket"))
    }

    @Test
    fun `search presets round trip`() = runBlocking {
        val dao = db.searchPresetDao()
        dao.upsert(SearchPresetEntity(name = "Server errors", query = "status:5xx", createdAt = 1L))
        dao.upsert(SearchPresetEntity(name = "Slow API", query = "", conditions = listOf("host:api", "duration:>1000"), combineWithOr = false, createdAt = 2L))
        val all = dao.getAll()
        assertEquals(listOf("Server errors", "Slow API"), all.map { it.name })
        assertEquals(listOf("host:api", "duration:>1000"), all[1].conditions)
        dao.deleteById(all[0].id)
        assertEquals(listOf("Slow API"), dao.getAll().map { it.name })
    }
}
