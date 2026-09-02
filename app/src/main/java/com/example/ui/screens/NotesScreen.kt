package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.example.data.export.FileExporter
import com.example.data.export.MediaStoreExporter
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.repository.NotesRepository
import com.example.LocalBottomOverlayHeight
import com.example.ui.components.BadgePill
import com.example.ui.components.DriveParallaxFrom
import com.example.ui.components.EmptyState
import com.example.ui.components.FloatingCapsuleHeader
import com.example.ui.components.GlassActionPill
import com.example.ui.components.GlassCard
import com.example.ui.components.RequestrFilterChip
import com.example.ui.components.RequestrSegmentedTabs
import com.example.ui.components.SearchInputField
import com.example.ui.text.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Notes & Docs — Phase B redesign (PHASE-B-REBRAND-NO-MOCKUP-COVERAGE §Notes).
 *
 * Two modes share one screen: the list (search + folder chips + note cards) and the full editor
 * (format tabs, title, body, live preview). Both use the shipped [FloatingCapsuleHeader] as the
 * single header primitive; the header call sites are the only two `FloatingCapsuleHeader(...)`
 * invocations in this file, so a future header primitive can swap in at exactly those spots.
 */
@Composable
fun NotesScreen(
    onMiniSettings: () -> Unit
) {
    val folders by NotesRepository.folders.collectAsState()
    val notes by NotesRepository.notes.collectAsState()

    // The open note lives in NotesRepository.activeNote (not local screen state) so that every
    // path that opens a note -- this list, Home's "Recently opened", search, a deeplink -- goes
    // through NotesRepository.openNote(), which is also what records the open in
    // RecentActivityRepository. Lifecycle consequences, handled explicitly:
    //  * NotesRepository.deleteNote() clears activeNote when the deleted id is the open one, so
    //    the editor can never stay bound to a ghost; and resolveEditorNote() re-reads the live
    //    list so a stale snapshot (e.g. from a recents row) shows the note's current content.
    //  * Backing out / saving calls NotesRepository.closeNote(), so the editor does not spring
    //    back open on the next visit. It intentionally *does* survive navigating away with the
    //    editor open (bottom-nav round trip), same as ApiCollectionsRepository.currentActiveRequest.
    val activeNote by NotesRepository.activeNote.collectAsState()
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    // "New note" is local: nothing exists to record as opened until the first save.
    var isCreatingNew by remember { mutableStateOf(false) }

    val filteredNotes = remember(notes, selectedFolderId, searchQuery) {
        filterAndSortNotes(notes, selectedFolderId, searchQuery)
    }
    val editorNote = remember(activeNote, notes) { resolveEditorNote(activeNote, notes) }

    if (editorNote != null || isCreatingNew) {
        NoteEditor(
            existing = editorNote,
            onClose = {
                NotesRepository.closeNote()
                isCreatingNew = false
            },
        )
    } else {
        NotesList(
            notes = notes,
            filteredNotes = filteredNotes,
            folders = folders,
            selectedFolderId = selectedFolderId,
            onSelectFolder = { selectedFolderId = it },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onOpenNote = { NotesRepository.openNote(it) },
            onCreateNote = { isCreatingNew = true },
            onMiniSettings = onMiniSettings,
        )
    }
}

// ───────────────────────────── List mode ─────────────────────────────

@Composable
private fun NotesList(
    notes: List<NoteItem>,
    filteredNotes: List<NoteItem>,
    folders: List<com.example.data.repository.NoteFolder>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onCreateNote: () -> Unit,
    onMiniSettings: () -> Unit,
) {
    val emptyKind = notesEmptyKind(totalNotes = notes.size, visibleNotes = filteredNotes.size)
    val selectedFolderName = folders.firstOrNull { it.id == selectedFolderId }?.name

    // The note list is Notes' primary scroll container: bind it to the app-wide parallax layer.
    val listState = rememberLazyListState()
    DriveParallaxFrom(listState)

    // Transparent root so the ParallaxBackground hosted in MainActivity shows through.
    Column(modifier = Modifier.fillMaxSize()) {
        // Header primitive — single call site for list mode.
        FloatingCapsuleHeader(
            title = "Notes",
            icon = Icons.AutoMirrored.Filled.Notes,
            trailingContent = {
                GlassActionPill(
                    icon = Icons.Default.Add,
                    contentDescription = "New note",
                    onClick = onCreateNote,
                    tint = MaterialTheme.colorScheme.primary,
                )
                GlassActionPill(
                    icon = Icons.Default.Settings,
                    contentDescription = "Quick Settings",
                    onClick = onMiniSettings,
                )
            }
        )

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SearchInputField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = "Search notes & documentation...",
                onClear = { onSearchQueryChange("") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Folder chips — horizontally scrollable so every folder stays reachable on 720px.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RequestrFilterChip(
                selected = selectedFolderId == null,
                onClick = { onSelectFolder(null) },
                label = "All",
                count = notes.size,
            )
            folders.forEach { f ->
                RequestrFilterChip(
                    selected = selectedFolderId == f.id,
                    onClick = { onSelectFolder(f.id) },
                    label = "${f.iconEmoji} ${f.name}",
                    count = notes.count { it.folderId == f.id },
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (emptyKind) {
            NotesEmptyKind.NO_NOTES_YET -> EmptyState(
                icon = Icons.AutoMirrored.Filled.Notes,
                message = "No notes yet. Keep endpoints, payloads, findings and checklists here.",
                contentDescription = null,
                cta = {
                    GlassActionPill(
                        icon = Icons.Default.Add,
                        contentDescription = "Create your first note",
                        onClick = onCreateNote,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            )

            NotesEmptyKind.NO_MATCHES -> EmptyState(
                icon = Icons.Default.SearchOff,
                message = notesNoMatchesMessage(searchQuery, selectedFolderName),
                contentDescription = null,
                cta = {
                    Text(
                        text = "Clear filters",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSearchQueryChange("")
                                onSelectFolder(null)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            )

            NotesEmptyKind.NONE -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // `notes` scrolls under the nav pill (ROUTES_SCROLLING_UNDER_PILL): the list
                // reserves the overlay height itself so the last card clears the pill.
                contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current + 16.dp)
            ) {
                items(filteredNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onOpen = { onOpenNote(note) },
                        onTogglePin = { NotesRepository.togglePin(note.id) },
                        onDelete = { NotesRepository.deleteNote(note.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteItem,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        onClick = onOpen
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(note.folderColorHex))
                    )
                    Text(
                        note.folderName,
                        color = Color(note.folderColorHex),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BadgePill(text = note.format.label.uppercase(), color = 0xFF6366F1)
                    if (note.isPinned) {
                        BadgePill(text = "PINNED", color = 0xFFF59E0B)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassActionPill(
                        icon = Icons.Default.PushPin,
                        contentDescription = if (note.isPinned) "Unpin" else "Pin",
                        onClick = onTogglePin,
                        tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 30.dp,
                        iconSize = 14.dp,
                    )
                    GlassActionPill(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete",
                        onClick = onDelete,
                        tint = Color(0xFFEF4444),
                        size = 30.dp,
                        iconSize = 14.dp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                note.title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = remember(note.content) { notePreviewLine(note.content) }
            if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${note.wordCount} words · ${note.charCount} chars",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

// ───────────────────────────── Editor mode ─────────────────────────────

@Composable
private fun NoteEditor(
    existing: NoteItem?,
    onClose: () -> Unit,
) {
    // Bug fix (kept from the pre-redesign editor): this used to construct a fresh NoteItem (with
    // a brand-new random UUID) on every recomposition when creating a new note. Since
    // editTitle/editContent/editFormat are keyed on `currentNote` via remember(currentNote), and
    // NoteItem's equals()/hashCode() include `id`, every keystroke manufactured a *new*
    // currentNote identity, which reset editContent back to "" on the very next frame -- so typed
    // text never visibly landed. Wrapping the "new note" placeholder in remember(existing) keeps
    // its id (and the rest of the object) stable for the lifetime of this editing session.
    val currentNote = existing ?: remember(existing) {
        NoteItem(id = UUID.randomUUID().toString(), title = "New Security Note", content = "")
    }
    var editTitle by remember(currentNote) { mutableStateOf(currentNote.title) }
    var editContent by remember(currentNote) { mutableStateOf(currentNote.content) }
    var editFormat by remember(currentNote) { mutableStateOf(currentNote.format) }
    var isPreviewMode by remember { mutableStateOf(false) }

    val wordCount = remember(editContent) { editContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size }
    val charCount = remember(editContent) { editContent.length }

    // Export failures used to be swallowed by `?: return@launch` with no user feedback.
    // This SnackbarHost surfaces that failure (and could carry other editor feedback later).
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun exportNote() {
        scope.launch(Dispatchers.IO) {
            val text = editContent
            val fileName = noteExportFileName(editTitle, editFormat)
            val mimeType = noteExportMimeType(editFormat)
            // Complaint #5 fix: this used to write via a raw File in getExportFolder()
            // (Downloads/requestr on API 29+), which scoped storage silently blocks -- the file
            // never actually landed on disk. Route through MediaStoreExporter like every other
            // export writer.
            val target = MediaStoreExporter.writeText(context, fileName, mimeType, text)
            if (target == null) {
                showSnackbar("Export failed — could not write \"$fileName\"")
                return@launch
            }
            FileExporter.shareFile(context, target, mimeType, "Exported: $editTitle")
        }
    }

    fun saveNote() {
        val toSave = currentNote.copy(
            title = editTitle.ifBlank { "Untitled Note" },
            content = editContent,
            format = editFormat
        )
        NotesRepository.saveNote(toSave)
        onClose()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Editor mode is not a scrolling list, so it reserves the nav-pill overlay explicitly
        // (the NavHost no longer pads `notes`); the field/preview bottom edge stays put.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = LocalBottomOverlayHeight.current)
        ) {
            // Header primitive — single call site for editor mode.
            FloatingCapsuleHeader(
                title = if (existing == null) "New note" else "Edit note",
                icon = Icons.Default.Edit,
                onBack = onClose,
                showWordmarkDot = false,
                trailingContent = {
                    GlassActionPill(
                        icon = Icons.Default.Share,
                        contentDescription = "Export",
                        onClick = { exportNote() },
                    )
                    GlassActionPill(
                        icon = if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                        contentDescription = if (isPreviewMode) "Edit" else "Preview",
                        onClick = { isPreviewMode = !isPreviewMode },
                        tint = if (isPreviewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    GlassActionPill(
                        icon = Icons.Default.Check,
                        contentDescription = "Save",
                        onClick = { saveNote() },
                        tint = Color(0xFF10B981),
                    )
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Format picker — segmented tabs inside a horizontal scroller so all four labels
                // remain reachable at 720px wide with large font scales.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    RequestrSegmentedTabs(
                        items = NoteFormatType.values().toList(),
                        selectedItem = editFormat,
                        onItemSelected = { editFormat = it },
                        label = { it.label },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    textStyle = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    placeholder = { Text("Note title...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$wordCount words · $charCount characters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    BadgePill(
                        text = if (isPreviewMode) "PREVIEW" else "EDITING",
                        color = if (isPreviewMode) 0xFF6366F1 else 0xFF10B981
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Body: weight(1f) bounds the preview LazyColumn / editor field so each scrolls
                // inside the remaining height instead of growing past the screen.
                if (isPreviewMode) {
                    NotePreview(
                        format = editFormat,
                        content = editContent,
                        onContentChange = { editContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        placeholder = {
                            Text(
                                "Write Markdown, endpoints, payloads or notes here...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalBottomOverlayHeight.current + 16.dp)
        )
    }
}

@Composable
private fun NotePreview(
    format: NoteFormatType,
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surface
    ) {
        if (content.isBlank()) {
            EmptyState(
                icon = Icons.Default.Visibility,
                message = "Nothing to preview yet — switch back to editing and write something.",
            )
        } else when (format) {
            NoteFormatType.HTML -> {
                // Fix 16 (AUDIT_REPORT §11.2): walk the Spanned's real spans into an
                // AnnotatedString instead of `AnnotatedString(spanned.toString())`, which
                // discarded every span and rendered "HTML preview" as tag-stripped plain text.
                val annotated = remember(content) {
                    val spanned = HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    MarkdownRenderer.fromSpanned(spanned)
                }
                LazyColumn(modifier = Modifier.padding(14.dp)) {
                    item { Text(annotated, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) }
                }
            }

            NoteFormatType.MARKDOWN -> {
                // Fix 16: a real Markdown renderer (headings/bold/italic/code/fenced blocks/
                // lists/links) instead of showing the raw source as-is in monospace.
                val rendered = remember(content) { MarkdownRenderer.render(content) }
                LazyColumn(modifier = Modifier.padding(14.dp)) {
                    item { Text(rendered, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) }
                }
            }

            NoteFormatType.CHECKLIST -> {
                val items = remember(content) { MarkdownRenderer.parseChecklist(content) }
                if (items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Check,
                        message = "No checklist items yet. Add lines like \"- [ ] task\".",
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(14.dp)) {
                        items(items, key = { it.lineIndex }) { checkItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onContentChange(MarkdownRenderer.toggleChecklistItem(content, checkItem.lineIndex))
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checkItem.checked,
                                    onCheckedChange = {
                                        onContentChange(MarkdownRenderer.toggleChecklistItem(content, checkItem.lineIndex))
                                    }
                                )
                                Text(
                                    checkItem.text,
                                    color = if (checkItem.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    textDecoration = if (checkItem.checked) TextDecoration.LineThrough else null
                                )
                            }
                        }
                    }
                }
            }

            NoteFormatType.PLAIN -> {
                LazyColumn(modifier = Modifier.padding(14.dp)) {
                    item {
                        Text(
                            content,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
