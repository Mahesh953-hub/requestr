package com.example.ui.screens

import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem

/**
 * Pure, JVM-testable helpers behind [NotesScreen]. Kept out of the composable so the list
 * ordering, the empty-state copy and the export naming can be unit-tested without Robolectric
 * (same pattern as `SavedApisGrouping.kt` / `CapturedTrafficHelpers.kt`).
 */

/**
 * Resolves what the editor should be bound to, given the repository's [active] note (a snapshot
 * taken when `NotesRepository.openNote` was called -- possibly from Home's recents, possibly
 * minutes ago) and the live [notes] list.
 *
 * - `null` active -> `null` (editor closed).
 * - active id present in [notes] -> the *live* item, so the editor shows the note's current
 *   title/content/format rather than the snapshot it was opened with.
 * - active id absent from [notes] -> the snapshot itself. This is the transient window between
 *   `openNote` and the Room flow catching up (or a note that only ever existed in memory); a
 *   genuinely deleted note never reaches here because `NotesRepository.deleteNote` clears
 *   `activeNote` for the deleted id.
 */
fun resolveEditorNote(active: NoteItem?, notes: List<NoteItem>): NoteItem? {
    if (active == null) return null
    return notes.firstOrNull { it.id == active.id } ?: active
}

/**
 * Applies the folder filter and free-text search, then orders pinned notes first and the most
 * recently updated first within each group. Search matches title or body, case-insensitive.
 */
fun filterAndSortNotes(
    notes: List<NoteItem>,
    selectedFolderId: String?,
    query: String,
): List<NoteItem> {
    val trimmed = query.trim()
    return notes
        .filter { note ->
            (selectedFolderId == null || note.folderId == selectedFolderId) &&
                (trimmed.isEmpty() ||
                    note.title.contains(trimmed, ignoreCase = true) ||
                    note.content.contains(trimmed, ignoreCase = true))
        }
        .sortedWith(compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAt })
}

/** Which empty state the notes list should render, if any. */
enum class NotesEmptyKind { NONE, NO_NOTES_YET, NO_MATCHES }

fun notesEmptyKind(totalNotes: Int, visibleNotes: Int): NotesEmptyKind = when {
    visibleNotes > 0 -> NotesEmptyKind.NONE
    totalNotes == 0 -> NotesEmptyKind.NO_NOTES_YET
    else -> NotesEmptyKind.NO_MATCHES
}

/** Copy for the [NotesEmptyKind.NO_MATCHES] state, naming the active search and/or folder. */
fun notesNoMatchesMessage(query: String, folderName: String?): String {
    val q = query.trim()
    return when {
        q.isNotEmpty() && folderName != null -> "No notes in $folderName match \"$q\""
        q.isNotEmpty() -> "No notes match \"$q\""
        folderName != null -> "No notes in $folderName yet"
        else -> "No notes to show"
    }
}

/** File extension used when exporting a note of the given format. */
fun noteExportExtension(format: NoteFormatType): String = when (format) {
    NoteFormatType.HTML -> ".html"
    NoteFormatType.MARKDOWN -> ".md"
    NoteFormatType.CHECKLIST, NoteFormatType.PLAIN -> ".txt"
}

/** MIME type used when exporting a note of the given format. */
fun noteExportMimeType(format: NoteFormatType): String = when (format) {
    NoteFormatType.HTML -> "text/html"
    NoteFormatType.MARKDOWN -> "text/markdown"
    NoteFormatType.CHECKLIST, NoteFormatType.PLAIN -> "text/plain"
}

/**
 * Safe export file name: title with anything outside `[a-zA-Z0-9._-]` replaced by `_`,
 * falling back to `note` for a blank title.
 */
fun noteExportFileName(title: String, format: NoteFormatType): String {
    val safe = title.trim().ifBlank { "note" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return safe + noteExportExtension(format)
}

/** One-line preview of a note body for the list card. */
fun notePreviewLine(content: String, maxChars: Int = 120): String =
    content.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(maxChars)
