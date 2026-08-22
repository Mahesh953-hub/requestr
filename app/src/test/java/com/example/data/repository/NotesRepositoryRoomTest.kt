package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 11 — NotesRepository used to hold its notes/folders in plain `MutableStateFlow`s that
 * vanished on process death. This proves the fix: notes now persist through the real `notes`
 * Room table, and — per the plan's explicit seed-data rule ("prefer no seed data at all for
 * notes ... so a user who deletes every note does not get them resurrected") — the table carries
 * zero seed rows, so deleting everything really does leave it empty.
 *
 * Uses Robolectric because [NotesRepository.init] reads through [RequestrDatabase]'s real DAOs.
 * `RequestrDatabase.get()`'s companion-object singleton is not reset by Robolectric between test
 * methods in the same class (see WorkspaceExporterTest), so tables are cleared in [Before] and
 * test note ids are unique per test to avoid cross-method interference from the live collector.
 */
@RunWith(RobolectricTestRunner::class)
class NotesRepositoryRoomTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() = runBlocking {
        RequestrDatabase.get(context).noteDao().getAll().forEach {
            RequestrDatabase.get(context).noteDao().deleteById(it.id)
        }
        NotesRepository.init(context)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3000, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `saveNote persists to Room and the note survives being read back from the DAO`() = runBlocking {
        val note = NoteItem(
            id = "note-rt-1",
            title = "Persistence check",
            content = "Room-backed content",
            format = NoteFormatType.MARKDOWN,
        )
        NotesRepository.saveNote(note)

        awaitUntil { RequestrDatabase.get(context).noteDao().getAll().any { it.id == "note-rt-1" } }

        val row = RequestrDatabase.get(context).noteDao().getAll().first { it.id == "note-rt-1" }
        assertEquals("Persistence check", row.title)
        assertEquals("Room-backed content", row.content)
        assertEquals("MARKDOWN", row.format)

        awaitUntil { NotesRepository.notes.value.any { it.id == "note-rt-1" } }
        assertEquals("Persistence check", NotesRepository.notes.value.first { it.id == "note-rt-1" }.title)
    }

    @Test
    fun `deleting all notes leaves the table empty and does not resurrect any seed rows`() = runBlocking {
        NotesRepository.saveNote(NoteItem(id = "note-rt-2", title = "A", content = "a"))
        NotesRepository.saveNote(NoteItem(id = "note-rt-3", title = "B", content = "b"))
        awaitUntil {
            RequestrDatabase.get(context).noteDao().getAll().map { it.id }.let {
                it.contains("note-rt-2") && it.contains("note-rt-3")
            }
        }

        NotesRepository.deleteNote("note-rt-2")
        NotesRepository.deleteNote("note-rt-3")

        awaitUntil { RequestrDatabase.get(context).noteDao().getAll().isEmpty() }
        assertTrue(RequestrDatabase.get(context).noteDao().getAll().isEmpty())

        // No reseed logic exists for notes at all (unlike NoteFolder's small default taxonomy),
        // so a further wait confirms the table stays genuinely empty rather than being
        // repopulated by any startup/observe side effect.
        kotlinx.coroutines.delay(200)
        assertTrue(RequestrDatabase.get(context).noteDao().getAll().isEmpty())
        assertFalse(NotesRepository.notes.value.any { it.id == "note-rt-2" || it.id == "note-rt-3" })
    }
}
