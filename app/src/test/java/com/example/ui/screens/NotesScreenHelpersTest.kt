package com.example.ui.screens

import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the helpers behind the Phase B [NotesScreen] redesign. No
 * Android/Compose dependency, so no Robolectric runner is needed.
 */
class NotesScreenHelpersTest {

    private fun note(
        id: String,
        title: String = "t-$id",
        content: String = "",
        folderId: String? = null,
        pinned: Boolean = false,
        updatedAt: Long = 0L,
    ) = NoteItem(
        id = id, title = title, content = content, folderId = folderId,
        isPinned = pinned, updatedAt = updatedAt,
    )

    @Test
    fun `pinned notes sort first, then most recently updated`() {
        val notes = listOf(
            note("old", updatedAt = 10),
            note("new", updatedAt = 30),
            note("pinnedOld", pinned = true, updatedAt = 5),
            note("mid", updatedAt = 20),
            note("pinnedNew", pinned = true, updatedAt = 50),
        )
        val ordered = filterAndSortNotes(notes, selectedFolderId = null, query = "").map { it.id }
        assertEquals(listOf("pinnedNew", "pinnedOld", "new", "mid", "old"), ordered)
    }

    @Test
    fun `folder filter and search compose, search is case-insensitive and matches body`() {
        val notes = listOf(
            note("a", title = "Auth flow", content = "Bearer tokens", folderId = "sec"),
            note("b", title = "Payload dump", content = "contains BEARER header", folderId = "sec"),
            note("c", title = "Bearer notes", folderId = "misc"),
        )
        assertEquals(listOf("a", "b", "c").toSet(), filterAndSortNotes(notes, null, "bearer").map { it.id }.toSet())
        assertEquals(listOf("a", "b").toSet(), filterAndSortNotes(notes, "sec", "  bearer ").map { it.id }.toSet())
        assertEquals(emptyList<String>(), filterAndSortNotes(notes, "misc", "payload").map { it.id })
    }

    @Test
    fun `empty kind distinguishes a fresh install from an over-filtered list`() {
        assertEquals(NotesEmptyKind.NONE, notesEmptyKind(totalNotes = 3, visibleNotes = 1))
        assertEquals(NotesEmptyKind.NO_NOTES_YET, notesEmptyKind(totalNotes = 0, visibleNotes = 0))
        assertEquals(NotesEmptyKind.NO_MATCHES, notesEmptyKind(totalNotes = 3, visibleNotes = 0))
    }

    @Test
    fun `no-matches copy names the active search and folder`() {
        assertEquals("No notes in Security match \"jwt\"", notesNoMatchesMessage("jwt", "Security"))
        assertEquals("No notes match \"jwt\"", notesNoMatchesMessage(" jwt ", null))
        assertEquals("No notes in Security yet", notesNoMatchesMessage("", "Security"))
        assertEquals("No notes to show", notesNoMatchesMessage("", null))
    }

    @Test
    fun `export file name is sanitised and carries the format extension`() {
        assertEquals("Auth_flow_v2.md", noteExportFileName("Auth flow/v2", NoteFormatType.MARKDOWN))
        assertEquals("note.html", noteExportFileName("   ", NoteFormatType.HTML))
        assertEquals("todo.txt", noteExportFileName("todo", NoteFormatType.CHECKLIST))
        assertEquals("plain.txt", noteExportFileName("plain", NoteFormatType.PLAIN))
    }

    @Test
    fun `export mime type matches the format`() {
        assertEquals("text/html", noteExportMimeType(NoteFormatType.HTML))
        assertEquals("text/markdown", noteExportMimeType(NoteFormatType.MARKDOWN))
        assertEquals("text/plain", noteExportMimeType(NoteFormatType.CHECKLIST))
        assertEquals("text/plain", noteExportMimeType(NoteFormatType.PLAIN))
    }

    @Test
    fun `preview line collapses whitespace and truncates`() {
        assertEquals("a b c", notePreviewLine("a\n\n  b\tc  "))
        val long = "x".repeat(500)
        assertTrue(notePreviewLine(long).length == 120)
    }

    // ── resolveEditorNote (active note hoisted into NotesRepository.activeNote) ──

    @Test
    fun `resolveEditorNote returns null when nothing is open`() {
        assertNull(resolveEditorNote(null, listOf(note("a"))))
    }

    @Test
    fun `resolveEditorNote prefers the live list item over the opened snapshot`() {
        val snapshot = note("a", title = "old title", content = "old body", updatedAt = 1)
        val live = note("a", title = "new title", content = "new body", updatedAt = 2)
        val resolved = resolveEditorNote(snapshot, listOf(note("z"), live))
        assertEquals(live, resolved)
        assertEquals("new body", resolved!!.content)
    }

    @Test
    fun `resolveEditorNote falls back to the snapshot when the list has not caught up`() {
        val snapshot = note("a", title = "just opened")
        assertEquals(snapshot, resolveEditorNote(snapshot, emptyList()))
        assertEquals(snapshot, resolveEditorNote(snapshot, listOf(note("other"))))
    }
}
