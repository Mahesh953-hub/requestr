package com.example.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.DnsQueryEntity
import com.example.data.local.HeaderKind
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S2 task 19: session pruning and quotas. Per-session cap trims oldest-first and cascades to the
 * child tables; whole-session eviction (age cap, spill cap, DB ceiling) takes protocol children,
 * the session row and the spill directory in one go; quota exhaustion pauses capture, the queue
 * refuses transactions while paused without counting orphaned patches, and the pause lifts once
 * the store is back under its caps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionPrunerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RequestrDatabase
    private lateinit var queue: PersistenceQueue
    private lateinit var store: BodyStore
    private var now = 1_000_000_000L
    private var fakeDbBytes: Long? = null

    @Before
    fun setUp() {
        PersistenceHealth.reset()
        db = Room.inMemoryDatabaseBuilder(context, RequestrDatabase::class.java).allowMainThreadQueries().build()
        queue = PersistenceQueue.forTest(db, Dispatchers.IO.limitedParallelism(1), clock = { now })
        store = BodyStore.forTest(tmp.newFolder("captures"), inlineLimit = 64)
        queue.attachBodyStore(store)
    }

    @After
    fun tearDown() {
        runBlocking { queue.close() }
        db.close()
        PersistenceHealth.reset()
    }

    private fun pruner(config: SessionPruner.Config) = SessionPruner(
        db = db,
        bodyStore = { queue.bodyStore },
        config = config,
        clock = { now },
        dbBytes = { fakeDbBytes ?: 0L },
    )

    private fun tx(sessionId: Long, timestamp: Long, host: String = "h$timestamp.example", body: ByteArray? = null) =
        CapturedTrafficEntity(
            sessionId = sessionId,
            method = "GET",
            url = "https://$host/",
            host = host,
            path = "/",
            statusCode = 200,
            protocol = "HTTP/1.1",
            requestHeaders = mapOf("Host" to host),
            responseHeaders = emptyMap(),
            requestBody = null,
            responseBody = null,
            responseBodyBytes = body,
            isResponseBodyBinary = body != null,
            requestSizeBytes = 0,
            responseSizeBytes = body?.size?.toLong() ?: 0L,
            latencyMs = 0,
            timestamp = timestamp,
            wasDecrypted = true,
            captureMethod = CaptureRowMethod.MITM_PROXY,
        )

    private suspend fun session(startedAt: Long, endedAt: Long?): Long =
        db.captureSessionDao().insert(CaptureSessionEntity(startedAt = startedAt, endedAt = endedAt))

    private suspend fun insertAll(sessionId: Long, count: Int, from: Long = 1L, withChildren: Boolean = true) {
        for (i in 0 until count) {
            val ts = from + i
            queue.insertTransaction(
                tx(sessionId, ts),
                headers = if (withChildren) listOf(TransactionHeaderEntity(trafficId = 0, kind = HeaderKind.RESPONSE, ordinal = 0, name = "X-N", value = "$ts")) else emptyList(),
                timings = if (withChildren) TransactionTimingEntity(trafficId = 0, waitMs = ts) else null,
            )
        }
        queue.awaitIdle()
    }

    private fun sessionDir(sessionId: Long) = File(store.root, sessionId.toString())

    // -- per-session cap -------------------------------------------------------------------------

    @Test
    fun `per-session cap keeps the newest rows and cascades to headers and timings`() = runBlocking {
        val sid = session(startedAt = now, endedAt = null)
        insertAll(sid, count = 8)
        val p = pruner(SessionPruner.Config(maxTransactionsPerSession = 5))

        val result = p.enforce(setOf(sid))

        assertEquals(3, result.prunedTransactions)
        assertEquals(0, result.prunedSessions)
        assertNull(result.paused)
        val remaining = db.capturedTrafficDao().getBySession(sid)
        assertEquals(5, remaining.size)
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), remaining.map { it.timestamp })
        assertEquals(5, db.transactionHeaderDao().count())
        assertEquals(5, db.transactionTimingDao().count())
        assertEquals(setOf(8L, 7L, 6L, 5L, 4L), db.transactionTimingDao().getAll().map { it.waitMs }.toSet())
        assertEquals(3L, PersistenceHealth.snapshot().prunedTransactions)
        assertNull(PersistenceHealth.snapshot().capturePaused)
    }

    @Test
    fun `per-session cap deletes the spilled files of the rows it removes`() = runBlocking {
        val sid = session(startedAt = now, endedAt = null)
        val big = ByteArray(200) { 0x7F }
        for (ts in 1L..3L) queue.insertTransaction(tx(sid, ts, body = big))
        queue.awaitIdle()
        assertEquals(3, sessionDir(sid).listFiles()?.size)

        val result = pruner(SessionPruner.Config(maxTransactionsPerSession = 1)).enforce(setOf(sid))

        assertEquals(2, result.prunedTransactions)
        assertEquals(400L, result.freedSpillBytes)
        val leftover = sessionDir(sid).listFiles()!!.map { it.name }
        assertEquals(1, leftover.size)
        val survivor = db.capturedTrafficDao().getBySession(sid).single()
        assertEquals(3L, survivor.timestamp)
        assertEquals("$sid/${leftover.single()}", survivor.responseBodyFilePath)
    }

    @Test
    fun `a session under the cap is left alone`() = runBlocking {
        val sid = session(startedAt = now, endedAt = null)
        insertAll(sid, count = 4)

        val result = pruner(SessionPruner.Config(maxTransactionsPerSession = 5)).enforce(setOf(sid))

        assertTrue(result.isEmpty)
        assertEquals(4, db.capturedTrafficDao().countForSession(sid))
    }

    // -- whole-session eviction ------------------------------------------------------------------

    @Test
    fun `age cap deletes an ended session with every child table and its spill directory`() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val old = session(startedAt = now - 10 * day, endedAt = now - 9 * day)
        val live = session(startedAt = now - 1000, endedAt = null)
        queue.insertTransaction(tx(old, 1L, body = ByteArray(200)))
        queue.insertTransaction(tx(live, 2L))
        queue.insertDns(DnsQueryEntity(sessionId = old, transactionId = 1, hostname = "old.example", queryType = "A", isResponse = false))
        queue.awaitIdle()
        db.tcpConnectionDao().upsert(TcpConnectionEntity("c-old", old, "10.0.0.2", "1.1.1.1", 1234, 443))
        db.tcpConnectionDao().upsert(TcpConnectionEntity("c-live", live, "10.0.0.2", "1.1.1.1", 1235, 443))
        assertTrue(sessionDir(old).isDirectory)

        val result = pruner(SessionPruner.Config(maxSessionAgeMs = 7 * day)).enforce()

        assertEquals(1, result.prunedSessions)
        assertEquals(1, result.prunedTransactions)
        assertEquals(200L, result.freedSpillBytes)
        assertNull(db.captureSessionDao().getById(old))
        assertNotNull(db.captureSessionDao().getById(live))
        assertEquals(0, db.capturedTrafficDao().countForSession(old))
        assertEquals(1, db.capturedTrafficDao().countForSession(live))
        assertTrue(db.dnsQueryDao().getBySession(old).isEmpty())
        assertTrue(db.tcpConnectionDao().getBySession(old).isEmpty())
        assertEquals(1, db.tcpConnectionDao().getBySession(live).size)
        assertFalse(sessionDir(old).exists())
    }

    @Test
    fun `a session that ended recently is kept by the age cap`() = runBlocking {
        val sid = session(startedAt = now - 5000, endedAt = now - 1000)
        insertAll(sid, count = 2)

        val result = pruner(SessionPruner.Config(maxSessionAgeMs = 60_000)).enforce()

        assertTrue(result.isEmpty)
        assertNotNull(db.captureSessionDao().getById(sid))
    }

    @Test
    fun `db ceiling evicts ended sessions oldest-first and stops once under`() = runBlocking {
        // All recent, so the age cap does not interfere with what is being asserted here.
        val s1 = session(startedAt = now - 4000, endedAt = now - 3000)
        val s2 = session(startedAt = now - 2000, endedAt = now - 1000)
        val live = session(startedAt = now - 500, endedAt = null)
        insertAll(s1, 2, withChildren = false)
        insertAll(s2, 2, from = 10, withChildren = false)
        insertAll(live, 2, from = 20, withChildren = false)
        // Reported size falls by 100 per evicted ended session: 350 -> 250 (under the 300 cap).
        val p = SessionPruner(
            db = db, bodyStore = { null }, config = SessionPruner.Config(maxDbBytes = 300), clock = { now },
            dbBytes = { 150L + 100L * db.captureSessionDao().getEndedOldestFirst().size },
        )

        val result = p.enforce()

        assertEquals(1, result.prunedSessions)
        assertEquals(2, result.prunedTransactions)
        assertNull(result.paused)
        assertNull(db.captureSessionDao().getById(s1))
        assertNotNull(db.captureSessionDao().getById(s2))
        assertNotNull(db.captureSessionDao().getById(live))
        assertNull(PersistenceHealth.snapshot().capturePaused)
    }

    // -- quota exhaustion ------------------------------------------------------------------------

    @Test
    fun `db ceiling with nothing left to evict pauses capture and the queue refuses transactions`() = runBlocking {
        val live = session(startedAt = now, endedAt = null)
        insertAll(live, 1, withChildren = false)
        fakeDbBytes = 5L * 1024 * 1024
        val p = pruner(SessionPruner.Config(maxDbBytes = 1024 * 1024))

        val result = p.enforce(setOf(live))

        assertEquals(PersistenceHealth.PauseReason.QUOTA_DB_BYTES, result.paused)
        val snap = PersistenceHealth.snapshot()
        assertEquals(PersistenceHealth.PauseReason.QUOTA_DB_BYTES, snap.capturePaused)
        assertEquals("database 5 MiB over the 1 MiB limit", snap.capturePausedDetail)
        assertEquals("Capture paused: database 5 MiB over the 1 MiB limit", snap.headline)
        assertNotNull(db.captureSessionDao().getById(live))

        // Producer refuses new transactions while paused; patches for the refused handle are not
        // counted as orphans (nothing was lost that the user was not already told about).
        val handle = queue.insertTransaction(tx(live, 99L))
        assertTrue(handle.isDead)
        queue.patchDecodedExchange(handle, tx(live, 99L))
        queue.awaitIdle()
        assertEquals(1, db.capturedTrafficDao().countForSession(live))
        assertEquals(1L, PersistenceHealth.snapshot().quotaRejectedWrites)
        assertEquals(0L, PersistenceHealth.snapshot().orphanedPatches)
        // DNS rows are not transactions and still flow.
        queue.insertDns(DnsQueryEntity(sessionId = live, transactionId = 1, hostname = "x.example", queryType = "A", isResponse = false))
        queue.awaitIdle()
        assertEquals(1, db.dnsQueryDao().getBySession(live).size)

        // Space comes back (user cleared traffic, files removed) -> next evaluation resumes.
        fakeDbBytes = 10L
        val after = p.enforce()
        assertNull(after.paused)
        assertNull(PersistenceHealth.snapshot().capturePaused)
        assertFalse(queue.insertTransaction(tx(live, 100L)).isDead)
        queue.awaitIdle()
        assertEquals(2, db.capturedTrafficDao().countForSession(live))
    }

    @Test
    fun `spill cap evicts ended sessions first and pauses only when the live session alone is over`() = runBlocking {
        val ended = session(startedAt = now - 2000, endedAt = now - 1000) // recent: age cap stays out of it
        val live = session(startedAt = now, endedAt = null)
        queue.insertTransaction(tx(ended, 1L, body = ByteArray(300)))
        queue.insertTransaction(tx(live, 2L, body = ByteArray(300)))
        queue.awaitIdle()
        assertEquals(600L, store.totalBytes())

        // Cap 400: evicting the ended session (300) brings it to 300 -> under, no pause.
        val first = pruner(SessionPruner.Config(maxSpillBytes = 400)).enforce()
        assertEquals(1, first.prunedSessions)
        assertEquals(300L, first.freedSpillBytes)
        assertNull(first.paused)
        assertEquals(300L, store.totalBytes())
        assertFalse(sessionDir(ended).exists())

        // Cap 100: only the live session remains and it is over -> pause on spill bytes.
        val second = pruner(SessionPruner.Config(maxSpillBytes = 100)).enforce()
        assertEquals(PersistenceHealth.PauseReason.QUOTA_SPILL_BYTES, second.paused)
        assertEquals(PersistenceHealth.PauseReason.QUOTA_SPILL_BYTES, PersistenceHealth.snapshot().capturePaused)
        assertTrue(PersistenceHealth.snapshot().capturePausedDetail!!.startsWith("saved bodies "))
        assertNotNull(db.captureSessionDao().getById(live))
        assertEquals(1, db.capturedTrafficDao().countForSession(live))
    }

    // -- queue integration -----------------------------------------------------------------------

    @Test
    fun `attached to the queue, the pruner runs after each committed batch`() = runBlocking {
        val sid = session(startedAt = now, endedAt = null)
        queue.attachPruner(pruner(SessionPruner.Config(maxTransactionsPerSession = 3)))

        insertAll(sid, count = 10, withChildren = false)

        assertEquals(3, db.capturedTrafficDao().countForSession(sid))
        assertEquals(listOf(10L, 9L, 8L), db.capturedTrafficDao().getBySession(sid).map { it.timestamp })
        assertEquals(7L, PersistenceHealth.snapshot().prunedTransactions)

        queue.attachPruner(null)
        insertAll(sid, count = 2, from = 50, withChildren = false)
        assertEquals(5, db.capturedTrafficDao().countForSession(sid))
    }

    @Test
    fun `config rejects non-positive caps`() {
        for (bad in listOf<() -> Unit>(
            { SessionPruner.Config(maxTransactionsPerSession = 0) },
            { SessionPruner.Config(maxSessionAgeMs = 0) },
            { SessionPruner.Config(maxSpillBytes = -1) },
            { SessionPruner.Config(maxDbBytes = 0) },
        )) {
            try {
                bad(); throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(5_000, SessionPruner.Config().maxTransactionsPerSession)
    }
}
