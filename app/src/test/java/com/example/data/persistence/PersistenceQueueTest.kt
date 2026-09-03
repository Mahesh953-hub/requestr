package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppliedRuleEntity
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.DnsQueryEntity
import com.example.data.local.HeaderKind
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S2 task 15: the single-writer queue's contract -- ordering, two-phase write, atomic
 * parent+children, drop-oldest-with-metric under saturation, DAO-failure isolation, orphaned
 * patch accounting, barrier semantics, and the back-pressured bulk path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PersistenceQueueTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue

    /** Gate the actor so tests can fill the channel deterministically. */
    private val gate = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1), capacity = 4)
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
    }

    private fun tx(host: String, sessionId: Long = 1L, status: Int? = null) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "GET",
        url = "https://$host/",
        host = host,
        path = "/",
        statusCode = status,
        protocol = "HTTP/1.1",
        requestHeaders = mapOf("Host" to host),
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = null,
        requestSizeBytes = 0,
        responseSizeBytes = 0,
        latencyMs = 0,
        timestamp = 1_000L,
        wasDecrypted = true,
        captureMethod = CaptureRowMethod.MITM_PROXY,
    )

    private fun header(kind: HeaderKind, ordinal: Int, name: String, value: String) =
        TransactionHeaderEntity(trafficId = 0, kind = kind, ordinal = ordinal, name = name, value = value)

    @Test
    fun `insert with children commits atomically and resolves the handle`() = runBlocking {
        val handle = queue.insertTransaction(
            tx("a.example"),
            headers = listOf(
                header(HeaderKind.RESPONSE, 0, "Set-Cookie", "a=1"),
                header(HeaderKind.RESPONSE, 1, "Set-Cookie", "b=2"),
                header(HeaderKind.REQUEST, 0, "Host", "a.example"),
            ),
            timings = TransactionTimingEntity(trafficId = 0, connectMs = 12, tlsMs = 34),
            appliedRules = listOf(
                AppliedRuleEntity(trafficId = 0, ruleId = "r1", ruleName = "Block", stage = "GATEWAY", phase = "REQUEST", outcome = "APPLIED", appliedAt = 5),
            ),
        )
        val id = withTimeout(5_000) { handle.await() }

        assertEquals(id, handle.idOrNull)
        val headers = db.transactionHeaderDao().forTraffic(id)
        assertEquals(3, headers.size)
        assertTrue(headers.all { it.trafficId == id })
        assertEquals(listOf("a=1", "b=2"), db.transactionHeaderDao().named(id, HeaderKind.RESPONSE, "set-cookie").map { it.value })
        assertEquals(12L, db.transactionTimingDao().forTraffic(id)?.connectMs)
        assertNull(db.transactionTimingDao().forTraffic(id)?.dnsMs)
        assertEquals(1, db.appliedRuleDao().forTraffic(id).size)
        assertEquals(1L, PersistenceHealth.snapshot().committedWrites)
    }

    @Test
    fun `two-phase write - response patch lands on the row the request insert created`() = runBlocking {
        val handle = queue.insertTransaction(tx("b.example"))
        // Patch queued BEFORE the insert has necessarily run -- same channel, so it is ordered after.
        queue.patchDecodedExchange(
            target = handle,
            patch = tx("b.example", status = 204).copy(
                responseHeaders = mapOf("Server" to "x"),
                latencyMs = 77,
                responseSizeBytes = 10,
            ),
            timings = TransactionTimingEntity(trafficId = 0, waitMs = 40, totalMs = 77),
        )
        queue.awaitIdle()

        val id = handle.idOrNull!!
        val row = db.capturedTrafficDao().getById(id)!!
        assertEquals(204, row.statusCode)
        assertEquals(mapOf("Server" to "x"), row.responseHeaders)
        assertEquals(77L, row.latencyMs)
        assertEquals(40L, db.transactionTimingDao().forTraffic(id)?.waitMs)
        assertEquals(2L, PersistenceHealth.snapshot().committedWrites)
        assertEquals(0L, PersistenceHealth.snapshot().orphanedPatches)
    }

    @Test
    fun `attach headers replaces only the kinds supplied`() = runBlocking {
        val handle = queue.insertTransaction(
            tx("c.example"),
            headers = listOf(header(HeaderKind.REQUEST, 0, "Host", "c"), header(HeaderKind.RESPONSE, 0, "Old", "1")),
        )
        queue.attachHeaders(handle, listOf(header(HeaderKind.RESPONSE, 0, "New", "2"), header(HeaderKind.RESPONSE, 1, "New", "3")))
        queue.awaitIdle()
        val id = handle.idOrNull!!
        assertEquals(listOf("Host"), db.transactionHeaderDao().forTraffic(id, HeaderKind.REQUEST).map { it.name })
        assertEquals(listOf("2", "3"), db.transactionHeaderDao().forTraffic(id, HeaderKind.RESPONSE).map { it.value })
    }

    @Test
    fun `saturation drops the oldest op, counts it and kills its handle`() = runBlocking {
        // Block the actor with a Custom op that waits on the gate, then overfill the channel.
        queue.execute("block") { gate.await() }
        // Give the actor a moment to pick up the blocker so it is not sitting in the channel.
        waitUntil { queue.pending == 0 }

        val handles = (1..6).map { queue.insertTransaction(tx("h$it.example")) }
        // capacity 4: h1 and h2 must have been evicted to admit h5 and h6.
        assertTrue(handles[0].isDead)
        assertTrue(handles[1].isDead)
        assertFalse(handles[2].isDead)
        val snap = PersistenceHealth.snapshot()
        assertEquals(2L, snap.droppedWrites)
        assertTrue(snap.saturated)
        assertNotNull(snap.lastDropAt)
        assertNotNull(snap.headline)

        // A patch targeting a dead handle is counted as orphaned, never applied.
        gate.complete(Unit)
        queue.awaitIdle()
        queue.patchDecodedExchange(handles[0], tx("h1.example", status = 500))
        queue.awaitIdle()

        val rows = db.capturedTrafficDao().getBySession(1L)
        assertEquals(listOf("h3.example", "h4.example", "h5.example", "h6.example"), rows.map { it.host }.sorted())
        assertTrue(rows.none { it.statusCode == 500 })
        assertEquals(1L, PersistenceHealth.snapshot().orphanedPatches)
        var thrown: Throwable? = null
        try {
            handles[0].await()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is DroppedWriteException)
    }

    @Test
    fun `a DAO failure discards one op, records it and keeps the actor alive`() = runBlocking {
        queue.execute("boom") { throw IllegalStateException("disk on fire") }
        val handle = queue.insertTransaction(tx("after-failure.example"))
        queue.awaitIdle()

        val snap = PersistenceHealth.snapshot()
        assertEquals(1L, snap.failedWrites)
        assertEquals("boom: disk on fire", snap.lastFailure)
        assertNotNull(handle.idOrNull)
        assertEquals(1, db.capturedTrafficDao().getBySession(1L).size)
        assertTrue(snap.headline!!.contains("failed"))
    }

    @Test
    fun `a failed insert rolls back its children and kills its handle`() = runBlocking {
        // Duplicate primary key on the child forces the FK-cascaded insert to throw mid-transaction.
        val dup = header(HeaderKind.REQUEST, 0, "A", "1").copy(id = 42)
        val handle = queue.insertTransaction(tx("rollback.example"), headers = listOf(dup, dup))
        queue.awaitIdle()

        assertTrue(handle.isDead)
        assertEquals(0, db.capturedTrafficDao().getBySession(1L).size)
        assertEquals(0, db.transactionHeaderDao().count())
        assertEquals(1L, PersistenceHealth.snapshot().failedWrites)
    }

    @Test
    fun `dns and custom ops are serialised through the same actor`() = runBlocking {
        val order = mutableListOf<String>()
        queue.execute("first") { order += "first" }
        queue.insertDns(
            DnsQueryEntity(sessionId = 1, transactionId = 7, hostname = "d.example", queryType = "A", isResponse = false, resolvedIps = emptyList(), resolver = "UDP:53"),
        )
        queue.execute("last") { order += "last" }
        queue.awaitIdle()
        assertEquals(listOf("first", "last"), order)
        assertEquals(1, db.dnsQueryDao().getBySession(1L).size)
        assertEquals(3L, PersistenceHealth.snapshot().committedWrites)
    }

    @Test
    fun `batch listener sees committed counts and touched sessions`() = runBlocking {
        val seen = mutableListOf<PersistenceQueue.Batch>()
        queue.addBatchListener { seen += it }
        queue.insertTransaction(tx("s1.example", sessionId = 11))
        queue.insertTransaction(tx("s2.example", sessionId = 12))
        queue.awaitIdle()
        val total = seen.sumOf { it.insertedTransactions }
        assertEquals(2, total)
        assertEquals(setOf(11L, 12L), seen.flatMap { it.touchedSessionIds }.toSet())
    }

    @Test
    fun `back-pressured bulk insert returns the row id`() = runBlocking {
        val id = withTimeout(5_000) { queue.insertTransactionAndAwait(tx("bulk.example")) }
        assertEquals("bulk.example", db.capturedTrafficDao().getById(id)?.host)
    }

    @Test
    fun `resolved handles patch legacy rows by bare id`() = runBlocking {
        val id = db.capturedTrafficDao().insert(tx("legacy.example"))
        queue.attachTimings(RowHandle.resolved(id), TransactionTimingEntity(trafficId = 0, totalMs = 5))
        queue.awaitIdle()
        assertEquals(5L, db.transactionTimingDao().forTraffic(id)?.totalMs)
    }

    @Test
    fun `closed queue refuses ops and kills their handles`() = runBlocking {
        queue.close()
        val handle = queue.insertTransaction(tx("late.example"))
        assertTrue(handle.isDead)
        assertFalse(queue.submit(PersistenceOp.InsertDns(
            DnsQueryEntity(sessionId = 1, transactionId = 1, hostname = "x", queryType = "A", isResponse = false, resolvedIps = emptyList(), resolver = "UDP:53"),
        )))
        queue.awaitIdle() // must return immediately on a closed queue
    }

    @Test
    fun `forDatabase returns one queue per database instance`() {
        val a = PersistenceQueue.forDatabase(db)
        val b = PersistenceQueue.forDatabase(db)
        assertTrue(a === b)
        val other = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertFalse(a === PersistenceQueue.forDatabase(other))
        } finally {
            other.close()
            PersistenceQueue.resetForTest()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
        assertTrue("condition not met in time", cond())
    }
}
