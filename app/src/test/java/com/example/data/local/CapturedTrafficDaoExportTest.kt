package com.example.data.local

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for [CapturedTrafficDao.getSessionRangePageBeforeCursor]/
 * [CapturedTrafficDao.countInSessionRange], added to replace `LocalApiServer.exportCaptures`'s
 * previous session-scoped path: `getSessionPageBeforeCursor(...).filter { it.timestamp in
 * fromTs..toTs }`. That in-memory filter computed `nextCursor` from the *filtered* list rather
 * than the raw keyset page, so a session with a run of more than `limit` consecutive
 * out-of-range rows made the export loop forever re-fetching the same discarded rows without
 * ever reaching the in-range rows further back. These tests build exactly that shape (a long
 * out-of-range run sitting between two in-range clusters) directly against the DAO to prove the
 * combined SQL filter doesn't have that failure mode.
 *
 * Each test builds a fresh in-memory database so rows never leak between methods or classes:
 * [RequestrDatabase.get] is a shared file-backed singleton (`requestr.db`) that other Robolectric
 * tests also write to, so asserting exact row counts against it would be order-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CapturedTrafficDaoExportTest {

    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(sessionId: Long, timestamp: Long) = CapturedTrafficEntity(
        sessionId = sessionId,
        method = "GET",
        url = "https://example.com/$timestamp",
        host = "example.com",
        timestamp = timestamp,
    )

    @Test
    fun `only returns rows for the requested session and time range`() = runBlocking {
        val dao = db.capturedTrafficDao()
        dao.insert(row(sessionId = 1, timestamp = 150)) // in range, session 1
        dao.insert(row(sessionId = 1, timestamp = 50))  // out of range (too old), session 1
        dao.insert(row(sessionId = 1, timestamp = 250)) // out of range (too new), session 1
        dao.insert(row(sessionId = 2, timestamp = 150)) // in range, but wrong session

        val rows = dao.getSessionRangePageBeforeCursor(
            sessionId = 1, fromTs = 100, toTs = 200, beforeTimestamp = null, beforeId = null, limit = 10,
        )

        assertEquals(1, rows.size)
        assertEquals(150L, rows[0].timestamp)
        assertEquals(1L, rows[0].sessionId)
    }

    @Test
    fun `a long out-of-range run does not block pagination from reaching in-range rows further back`() = runBlocking {
        val dao = db.capturedTrafficDao()
        // Newest-first layout: a run of 5 out-of-range rows (all newer than the [100, 200]
        // range, longer than the page limit below), followed by every in-range row further
        // back. The old in-memory `.filter { it.timestamp in 100..200 }` over a limit=2 raw
        // page would consume the 5-row out-of-range run two rows at a time, discard every one
        // of them, and compute its next cursor from the (empty) filtered list -- never reaching
        // the in-range rows further back. The SQL-time `BETWEEN` filter skips that run entirely.
        val timestamps = listOf(250, 240, 230, 220, 210, 190, 180, 130, 120)
        val inRangeTimestamps = setOf(190, 180, 130, 120)
        timestamps.forEach { dao.insert(row(sessionId = 1, timestamp = it.toLong())) }

        val limit = 2
        var cursorTs: Long? = null
        var cursorId: Long? = null
        val collected = mutableListOf<CapturedTrafficEntity>()
        var pages = 0
        while (pages < 20) { // hard bound so a real regression fails the test instead of hanging
            val page = dao.getSessionRangePageBeforeCursor(
                sessionId = 1, fromTs = 100, toTs = 200,
                beforeTimestamp = cursorTs, beforeId = cursorId, limit = limit,
            )
            pages++
            if (page.isEmpty()) break
            collected += page
            val last = page.last()
            cursorTs = last.timestamp
            cursorId = last.id
            if (page.size < limit) break
        }

        assertEquals(
            "must reach every in-range row despite the out-of-range run being longer than the page size",
            inRangeTimestamps.size,
            collected.size,
        )
        assertEquals(inRangeTimestamps, collected.map { it.timestamp.toInt() }.toSet())
        assertTrue("must not need more pages than the raw row count", pages <= timestamps.size)
    }

    @Test
    fun `cursor pagination never returns the same row twice and preserves newest-first order`() = runBlocking {
        val dao = db.capturedTrafficDao()
        val timestamps = (1..6).map { it * 10L } // 10, 20, ..., 60, all in range
        timestamps.forEach { dao.insert(row(sessionId = 1, timestamp = it)) }

        val page1 = dao.getSessionRangePageBeforeCursor(1, 0, 1000, null, null, 3)
        assertEquals(listOf(60L, 50L, 40L), page1.map { it.timestamp })

        val last = page1.last()
        val page2 = dao.getSessionRangePageBeforeCursor(1, 0, 1000, last.timestamp, last.id, 3)
        assertEquals(listOf(30L, 20L, 10L), page2.map { it.timestamp })

        val page3 = dao.getSessionRangePageBeforeCursor(1, 0, 1000, page2.last().timestamp, page2.last().id, 3)
        assertTrue(page3.isEmpty())
    }

    @Test
    fun `countInSessionRange counts only matching rows, ignoring other sessions and out-of-range rows`() = runBlocking {
        val dao = db.capturedTrafficDao()
        dao.insert(row(sessionId = 1, timestamp = 150))
        dao.insert(row(sessionId = 1, timestamp = 160))
        dao.insert(row(sessionId = 1, timestamp = 50))
        dao.insert(row(sessionId = 2, timestamp = 150))

        assertEquals(2, dao.countInSessionRange(sessionId = 1, fromTs = 100, toTs = 200))
    }

    @Test
    fun `an empty session-range result set returns an empty list, not null`() = runBlocking {
        val dao = db.capturedTrafficDao()
        val rows = dao.getSessionRangePageBeforeCursor(999, 0, 1000, null, null, 10)
        assertTrue(rows.isEmpty())
        assertNull(rows.firstOrNull())
    }
}
