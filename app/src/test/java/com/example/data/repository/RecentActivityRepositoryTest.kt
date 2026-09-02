package com.example.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home rebrand §6: proves [RecentActivityRepository] is a real, deduplicated, ordered, bounded
 * usage history -- not the `savedApis.take(2) + notes.take(2)` list with literal "Recent"/"Saved"
 * strings Home used to fabricate.
 *
 * Runs against a fresh in-memory [RequestrDatabase] bound via [RecentActivityRepository.attachForTest]
 * (never the shared file-backed singleton). Writes are awaited with
 * [RecentActivityRepository.awaitPendingWrites] and the clock is injected, so every assertion is
 * deterministic rather than sleep-and-hope.
 */
@RunWith(RobolectricTestRunner::class)
class RecentActivityRepositoryTest {

    private lateinit var db: RequestrDatabase
    private var now = 1_000_000L

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
        RecentActivityRepository.attachForTest(db)
        RecentActivityRepository.clock = { now }
    }

    @After
    fun tearDown() {
        RecentActivityRepository.resetForTest()
        db.close()
    }

    private suspend fun awaitRecents(timeoutMs: Long = 3000, condition: (List<RecentActivityItem>) -> Boolean): List<RecentActivityItem> {
        val start = System.currentTimeMillis()
        while (!condition(RecentActivityRepository.recents.value)) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("recents did not reach expected state within ${timeoutMs}ms: ${RecentActivityRepository.recents.value}")
            }
            delay(10)
        }
        return RecentActivityRepository.recents.value
    }

    private fun open(type: RecentItemType, id: String, title: String = "Title $id", preview: String = "preview $id") {
        now += 1_000
        RecentActivityRepository.recordOpened(type, id, subtype = "SUB", title = title, preview = preview)
    }

    @Test
    fun `a fresh table has no rows -- nothing is seeded`() = runBlocking {
        assertEquals(0, db.recentActivityDao().count())
        assertTrue(RecentActivityRepository.recents.value.isEmpty())
    }

    @Test
    fun `recording an open persists a row with the real timestamp`() = runBlocking {
        open(RecentItemType.NOTE, "n1", title = "  Auth notes ", preview = "line one\nline   two")
        RecentActivityRepository.awaitPendingWrites()

        val rows = db.recentActivityDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("NOTE", rows[0].itemType)
        assertEquals("n1", rows[0].itemId)
        assertEquals("Auth notes", rows[0].title)
        assertEquals("line one line two", rows[0].preview)
        assertEquals(now, rows[0].openedAt)

        val recents = awaitRecents { it.size == 1 }
        assertEquals(RecentItemType.NOTE, recents[0].type)
        assertEquals(now, recents[0].openedAt)
    }

    @Test
    fun `re-opening the same item updates its row instead of appending a duplicate`() = runBlocking {
        open(RecentItemType.API, "a1")
        RecentActivityRepository.awaitPendingWrites()
        val firstOpenedAt = db.recentActivityDao().getAll().single().openedAt

        open(RecentItemType.API, "a1", title = "Renamed on reopen")
        RecentActivityRepository.awaitPendingWrites()

        val rows = db.recentActivityDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("Renamed on reopen", rows[0].title)
        assertTrue(rows[0].openedAt > firstOpenedAt)
        assertEquals(1, awaitRecents { it.size == 1 && it[0].title == "Renamed on reopen" }.size)
    }

    @Test
    fun `same id under a different type is a different item`() = runBlocking {
        open(RecentItemType.API, "shared-id")
        open(RecentItemType.NOTE, "shared-id")
        RecentActivityRepository.awaitPendingWrites()
        assertEquals(2, db.recentActivityDao().count())
    }

    @Test
    fun `recents flow is ordered newest first and re-opening moves an item to the top`() = runBlocking {
        open(RecentItemType.NOTE, "n1")
        open(RecentItemType.API, "a1")
        open(RecentItemType.TOOL, "t1")
        RecentActivityRepository.awaitPendingWrites()
        var recents = awaitRecents { it.size == 3 && it[0].itemId == "t1" }
        assertEquals(listOf("t1", "a1", "n1"), recents.map { it.itemId })

        open(RecentItemType.NOTE, "n1")
        RecentActivityRepository.awaitPendingWrites()
        recents = awaitRecents { it.size == 3 && it[0].itemId == "n1" }
        assertEquals(listOf("n1", "t1", "a1"), recents.map { it.itemId })
    }

    @Test
    fun `history is capped at MAX_ENTRIES and the oldest rows are pruned`() = runBlocking {
        val total = RecentActivityRepository.MAX_ENTRIES + 7
        for (i in 1..total) open(RecentItemType.NOTE, "n$i")
        RecentActivityRepository.awaitPendingWrites()

        assertEquals(RecentActivityRepository.MAX_ENTRIES, db.recentActivityDao().count())
        val survivingIds = db.recentActivityDao().getAll().map { it.itemId }
        // The 7 oldest (n1..n7) are gone; the newest (n57) is first.
        for (i in 1..7) assertTrue("n$i should have been pruned", "n$i" !in survivingIds)
        assertEquals("n$total", survivingIds.first())
        assertEquals("n8", survivingIds.last())

        val recents = awaitRecents { it.size == RecentActivityRepository.MAX_ENTRIES }
        assertEquals("n$total", recents.first().itemId)
    }

    @Test
    fun `forget removes the row for a deleted item so it cannot be re-opened from Home`() = runBlocking {
        open(RecentItemType.NOTE, "n1")
        open(RecentItemType.NOTE, "n2")
        RecentActivityRepository.awaitPendingWrites()
        awaitRecents { it.size == 2 }

        RecentActivityRepository.forget(RecentItemType.NOTE, "n1")
        RecentActivityRepository.awaitPendingWrites()

        assertEquals(listOf("n2"), db.recentActivityDao().getAll().map { it.itemId })
        assertEquals(listOf("n2"), awaitRecents { it.size == 1 }.map { it.itemId })
    }

    @Test
    fun `forgetAll removes only the given type`() = runBlocking {
        open(RecentItemType.CHAT, "c1")
        open(RecentItemType.CHAT, "c2")
        open(RecentItemType.API, "a1")
        RecentActivityRepository.awaitPendingWrites()

        RecentActivityRepository.forgetAll(RecentItemType.CHAT)
        RecentActivityRepository.awaitPendingWrites()

        assertEquals(listOf("a1"), db.recentActivityDao().getAll().map { it.itemId })
    }

    @Test
    fun `updateSnapshot refreshes title and preview without bumping openedAt, and ignores unknown items`() = runBlocking {
        open(RecentItemType.API, "a1", title = "Old name", preview = "old.example.com")
        RecentActivityRepository.awaitPendingWrites()
        val openedAt = db.recentActivityDao().getAll().single().openedAt

        now += 60_000
        RecentActivityRepository.updateSnapshot(RecentItemType.API, "a1", "POST", "New name", "new.example.com")
        RecentActivityRepository.updateSnapshot(RecentItemType.API, "never-opened", "GET", "Ghost", "ghost")
        RecentActivityRepository.awaitPendingWrites()

        val rows = db.recentActivityDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("New name", rows[0].title)
        assertEquals("new.example.com", rows[0].preview)
        assertEquals("POST", rows[0].subtype)
        assertEquals(openedAt, rows[0].openedAt)
    }

    @Test
    fun `clearAll empties the history`() = runBlocking {
        open(RecentItemType.API, "a1")
        open(RecentItemType.FLOW, "f1")
        RecentActivityRepository.awaitPendingWrites()

        RecentActivityRepository.clearAll()
        RecentActivityRepository.awaitPendingWrites()

        assertEquals(0, db.recentActivityDao().count())
        assertTrue(awaitRecents { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `a blank item id is never recorded`() = runBlocking {
        open(RecentItemType.TOOL, "   ")
        RecentActivityRepository.awaitPendingWrites()
        assertEquals(0, db.recentActivityDao().count())
    }

    @Test
    fun `recording before init is a silent no-op, never a crash`() = runBlocking {
        RecentActivityRepository.resetForTest()
        RecentActivityRepository.recordOpened(RecentItemType.NOTE, "n1", "MARKDOWN", "t", "p")
        RecentActivityRepository.updateSnapshot(RecentItemType.NOTE, "n1", "MARKDOWN", "t", "p")
        RecentActivityRepository.forget(RecentItemType.NOTE, "n1")
        RecentActivityRepository.clearAll()
        assertTrue(RecentActivityRepository.recents.value.isEmpty())
        assertEquals(0, db.recentActivityDao().count())
    }
}
