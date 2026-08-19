package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.NoteFormatType
import com.example.data.models.NoteItem
import com.example.data.repository.NoteFolder
import com.example.data.repository.NotesRepository
import com.example.ui.components.GlassCard
import com.example.ui.components.SearchInputField
import com.example.ui.components.SectionHeader
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onMiniSettings: () -> Unit
) {
    val folders by NotesRepository.folders.collectAsState()
    val notes by NotesRepository.notes.collectAsState()

    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeNoteToEdit by remember { mutableStateOf<NoteItem?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val filteredNotes = remember(notes, selectedFolderId, searchQuery) {
        notes.filter { note ->
            (selectedFolderId == null || note.folderId == selectedFolderId) &&
                    (searchQuery.isBlank() || note.title.contains(searchQuery, ignoreCase = true) || note.content.contains(searchQuery, ignoreCase = true))
        }
    }

    if (activeNoteToEdit != null || isCreatingNew) {
        // Full Editor Mode
        val currentNote = activeNoteToEdit ?: NoteItem(id = UUID.randomUUID().toString(), title = "New Security Note", content = "")
        var editTitle by remember(currentNote) { mutableStateOf(currentNote.title) }
        var editContent by remember(currentNote) { mutableStateOf(currentNote.content) }
        var editFormat by remember(currentNote) { mutableStateOf(currentNote.format) }
        var isPreviewMode by remember { mutableStateOf(false) }

        val wordCount = remember(editContent) { editContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size }
        val charCount = remember(editContent) { editContent.length }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    activeNoteToEdit = null
                    isCreatingNew = false
                }) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { isPreviewMode = !isPreviewMode },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(if (isPreviewMode) "Edit Mode" else "Preview", color = TextPrimary, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val toSave = currentNote.copy(
                                title = editTitle.ifBlank { "Untitled Note" },
                                content = editContent,
                                format = editFormat
                            )
                            NotesRepository.saveNote(toSave)
                            activeNoteToEdit = null
                            isCreatingNew = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Format Picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NoteFormatType.values().forEach { fmt ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (editFormat == fmt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { editFormat = fmt }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(fmt.name, color = if (editFormat == fmt) Color.White else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary),
                placeholder = { Text("Note Title...", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text("$wordCount words · $charCount characters", color = TextMuted, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(10.dp))

            if (isPreviewMode) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    backgroundColor = MaterialTheme.colorScheme.surface
                ) {
                    LazyColumn(modifier = Modifier.padding(14.dp)) {
                        item {
                            Text(editContent, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary),
                    placeholder = { Text("Write Markdown, endpoints, payloads or notes here...", color = TextMuted) },
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
        }
    } else {
        // Main Notes List View
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            SectionHeader(
                title = "Notes & Docs",
                onMiniSettings = onMiniSettings,
                actions = {
                    IconButton(onClick = { isCreatingNew = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "New Note", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )

            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                SearchInputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search notes & documentation...",
                    onClear = { searchQuery = "" }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Folders Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedFolderId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { selectedFolderId = null }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text("All Notes", color = if (selectedFolderId == null) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                folders.forEach { f ->
                    val isSelected = selectedFolderId == f.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(f.colorHex) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedFolderId = f.id }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text("${f.iconEmoji} ${f.name}", color = if (isSelected) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Notes List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredNotes, key = { it.id }) { note ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        onClick = { activeNoteToEdit = note }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(note.folderColorHex))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(note.folderName, color = Color(note.folderColorHex), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { NotesRepository.togglePin(note.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PushPin,
                                            contentDescription = "Pin",
                                            tint = if (note.isPinned) MaterialTheme.colorScheme.primary else TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { NotesRepository.deleteNote(note.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(note.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                note.content.take(90).replace("\n", " "),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}
