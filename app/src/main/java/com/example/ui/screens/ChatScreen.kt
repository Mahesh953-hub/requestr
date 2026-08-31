@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.interception.CaptureLogRepository
import com.example.data.models.ChatAttachment
import com.example.data.models.ChatAttachmentKind
import com.example.data.models.ChatCodeFence
import com.example.data.models.ChatMessage
import com.example.data.models.ChatProviderConfig
import com.example.data.models.ChatProviderKind
import com.example.data.models.MAX_ATTACHMENT_SIZE_BYTES
import com.example.data.models.buildQuoteReplyPrefix
import com.example.data.models.decodeAttachment
import com.example.data.models.deriveChatTitle
import com.example.data.models.encodeAttachment
import com.example.data.models.formatCapturedTrafficAsContext
import com.example.data.models.formatClockTime
import com.example.data.models.isBlockedCleartextHost
import com.example.data.models.validateProviderFields
import com.example.data.prefs.ChatSettingsStore
import com.example.data.repository.ChatRepository
import com.example.data.repository.SaveProviderResult
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatByteCount
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * Reqy Assistant screen — full completeness pass against
 * `docs/designs/ai-chat/reqy-assistant-light.html` (4 screens: New Chat, Settings, Side Drawer,
 * Attachment Menu). See the phase-A shared context + verification dossier for the full audit
 * trail; the summary of what changed in this pass:
 *
 * - Provider chips: 8 real kinds ([ChatProviderKind]) instead of a single hardcoded endpoint.
 * - Attachment sheet: Image / File / Code / Request-Response / Screenshot, all real.
 * - Long-press lock panel on USER bubbles: Copy / Edit / Reply / Delete / Select (NOT a pin
 *   feature -- see [UserMessageLockPanel]'s kdoc for why).
 * - New Chat is instant, no naming modal (unchanged from before this pass -- already correct).
 * - A side drawer (AI History / New Chat / Collections / Flows / Environments / model status /
 *   Settings shortcut) and a full-page internal Settings screen (Provider + Instructions +
 *   Export + General) are new -- rendered as internal overlay state, not new NavController
 *   routes, so this stays inside files this task owns.
 */
@Composable
fun ChatScreen(
    onMiniSettings: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToFlows: () -> Unit,
) {
    val messages by ChatRepository.messages.collectAsState()
    val isLoading by ChatRepository.isLoading.collectAsState()
    val providerConfig by ChatRepository.providerConfig.collectAsState()
    val titleOverride by ChatRepository.titleOverride.collectAsState()
    val conversations by ChatRepository.conversations.collectAsState()
    val activeConversationId by ChatRepository.activeConversationId.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHapticFeedback()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    var inputText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<Pair<ChatAttachment, String>?>(null) }
    var showDrawer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val chatTitle = deriveChatTitle(messages, titleOverride, ChatRepository.currentSessionStartedAt())

    fun sendCurrentInput() {
        val attachment = pendingAttachment
        val text = if (attachment != null) {
            encodeAttachment(attachment.first, inputText.trim())
        } else {
            inputText.trim()
        }
        if (text.isNotBlank() && !isLoading) {
            haptics(HapticFeedbackType.LongPress)
            inputText = ""
            pendingAttachment = null
            scope.launch { ChatRepository.sendMessage(text) }
        }
    }

    // Auto-scroll to bottom on new messages / loading-state changes.
    LaunchedEffect(messages.size, isLoading) {
        val target = if (isLoading) messages.size else messages.size - 1
        if (target >= 0) {
            listState.animateScrollToItem(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            SectionHeader(
                title = chatTitle,
                // The gear reopens Reqy's OWN full-page Settings screen (mockup Screen 2) rather
                // than MainActivity's generic small quick-settings sheet -- the external
                // `onMiniSettings` callback param is kept for caller-signature compatibility
                // (MainActivity.kt is off-limits this wave) but intentionally never invoked here.
                onMiniSettings = { showSettings = true },
                onBack = null,
                actions = {
                    IconButton(
                        onClick = { showDrawer = true },
                        modifier = Modifier.semantics { contentDescription = "Open Reqy drawer" }
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Reqy drawer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { ChatRepository.startNewChat() },
                        modifier = Modifier.semantics { contentDescription = "New chat" }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )

            // Provider status banner (tap deep-links into Settings' Provider block).
            if (!providerConfig.isConfigured) {
                ProviderNotConfiguredBanner(onOpenSettings = { showSettings = true })
            }

            if (selectMode) {
                SelectModeBar(
                    count = selectedIds.size,
                    onCancel = { selectMode = false; selectedIds = emptySet() },
                    onDelete = {
                        ChatRepository.deleteMessages(selectedIds)
                        selectMode = false
                        selectedIds = emptySet()
                    },
                )
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item { ReqyEmptyState() }
                }

                items(messages, key = { it.id }) { msg ->
                    val isError = msg.role == "assistant" && isReqyProviderError(msg.content)
                    if (isError) {
                        ReqyErrorBubble(
                            message = msg,
                            onRetry = {
                                val lastUser = messages.lastOrNull { it.role == "user" }?.content
                                if (!lastUser.isNullOrBlank() && !isLoading) {
                                    haptics(HapticFeedbackType.LongPress)
                                    scope.launch { ChatRepository.sendMessage(lastUser) }
                                }
                            }
                        )
                    } else {
                        ChatBubble(
                            msg = msg,
                            selectMode = selectMode,
                            selected = msg.id in selectedIds,
                            onToggleSelected = {
                                selectedIds = if (msg.id in selectedIds) selectedIds - msg.id else selectedIds + msg.id
                            },
                            onCopy = { copyToClipboard(context, msg.content) },
                            onEdit = { newText -> scope.launch { ChatRepository.editMessageAndResend(msg.id, newText) } },
                            onReply = { inputText = buildQuoteReplyPrefix(msg) + inputText },
                            onDelete = { ChatRepository.deleteMessage(msg.id) },
                            onEnterSelect = { selectMode = true; selectedIds = setOf(msg.id) },
                            onRegenerate = {
                                val lastUser = messages.lastOrNull { it.role == "user" }?.content
                                if (!lastUser.isNullOrBlank() && !isLoading) {
                                    scope.launch { ChatRepository.sendMessage(lastUser) }
                                }
                            },
                        )
                    }
                }

                if (isLoading) {
                    item { ThinkingIndicator() }
                }
            }

            // Prompt composer
            ReqyComposerBar(
                text = inputText,
                onTextChange = { inputText = it },
                enabled = (inputText.isNotBlank() || pendingAttachment != null) && !isLoading,
                pendingAttachment = pendingAttachment,
                onRemoveAttachment = { pendingAttachment = null },
                providerSupportsVision = ChatProviderKind.fromId(providerConfig.id).supportsVision,
                onAttachClick = {
                    // "Keyboard must close before/as this sheet opens" -- the mockup names this
                    // explicitly as a bug to avoid, not optional polish.
                    focusManager.clearFocus()
                    showAttachSheet = true
                },
                onSend = { sendCurrentInput() }
            )
        }

        if (showAttachSheet) {
            AttachmentSheet(
                onDismiss = { showAttachSheet = false },
                onImagePicked = { attachment ->
                    pendingAttachment = attachment to ""
                    showAttachSheet = false
                },
                onFilePicked = { attachment ->
                    pendingAttachment = attachment to ""
                    showAttachSheet = false
                },
                onCodeAttached = { code, language ->
                    inputText = if (inputText.isBlank()) ChatCodeFence.wrap(code, language) else inputText + "\n\n" + ChatCodeFence.wrap(code, language)
                    showAttachSheet = false
                },
                onRequestResponseAttached = { formatted ->
                    inputText = if (inputText.isBlank()) formatted else inputText + "\n\n" + formatted
                    showAttachSheet = false
                },
                onScreenshotRequested = {
                    // Minor fix: dismiss the sheet FIRST and let a couple of frames render
                    // before capturing -- previously the capture ran while this Dialog was
                    // still on screen, so PixelCopy grabbed the whole window including the
                    // attachment sheet itself (documented as a known limitation; now fixed).
                    // Uses ChatScreen's own `scope` (bound to this composable's lifecycle, which
                    // outlives the dismissed sheet) rather than a scope local to AttachmentSheet,
                    // which would get cancelled the instant the sheet leaves composition.
                    showAttachSheet = false
                    scope.launch {
                        androidx.compose.runtime.withFrameNanos {}
                        androidx.compose.runtime.withFrameNanos {}
                        kotlinx.coroutines.delay(120)
                        val attachment = captureScreenshotAttachment(context, view)
                        if (attachment != null) {
                            pendingAttachment = attachment to ""
                        } else {
                            android.widget.Toast.makeText(
                                context, "Couldn't capture the screen on this device.", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }

        if (showDrawer) {
            ReqyDrawer(
                onClose = { showDrawer = false },
                onOpenSettings = { showDrawer = false; showSettings = true },
                onNewChat = { ChatRepository.startNewChat(); showDrawer = false },
                onRename = { conversationId, title -> ChatRepository.renameChat(title, conversationId) },
                onSwitchConversation = { id -> ChatRepository.switchToConversation(id) },
                onDeleteConversation = { id -> ChatRepository.deleteConversation(id) },
                onNavigateHome = onNavigateHome,
                onNavigateToCollections = onNavigateToCollections,
                onNavigateToFlows = onNavigateToFlows,
                conversations = conversations,
                activeConversationId = activeConversationId,
                chatTitle = chatTitle,
                providerConfig = providerConfig,
            )
        }

        if (showSettings) {
            ReqySettingsScreen(onClose = { showSettings = false })
        }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val (_, body) = decodeAttachment(text)
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Reqy message", body.ifBlank { text }))
}

// ─── SELECT-MODE BAR (multi-select delete, lock panel's "Select" action) ──────

@Composable
private fun SelectModeBar(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onCancel)
            )
            Text(
                "Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(enabled = count > 0, onClick = onDelete)
            )
        }
    }
}

// ─── PROVIDER NOT-CONFIGURED BANNER ───────────────────────────────────────────

@Composable
private fun ProviderNotConfiguredBanner(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenSettings)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 1.dp)
        )
        Text(
            buildString {
                append("No AI provider configured. ")
                append("Running in local mode. Tap to configure in Settings.")
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// ─── EMPTY STATE ──────────────────────────────────────────────────────────────

@Composable
private fun ReqyEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "\uD83D\uDCAC",
            fontSize = 40.sp,
            modifier = Modifier.alpha(0.5f)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "Ask me anything about requestr",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "API testing, interception, flows, tools, rules, certificates",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── CHAT BUBBLE ──────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(
    msg: ChatMessage,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (String) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onEnterSelect: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val isUser = msg.role == "user"
    var showLockPanel by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val (attachment, body) = remember(msg.content) { decodeAttachment(msg.content) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (selectMode && isUser) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
        }
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .then(
                        if (isUser) {
                            Modifier.combinedClickable(
                                onClick = { if (selectMode) onToggleSelected() },
                                // Lock panel -- the mockup's `.lockpanel` on user bubbles has
                                // exactly 5 actions (Copy/Edit/Reply/Delete/Select), NOT a
                                // pin/anchor feature -- see reqy-assistant-light.html lines
                                // 262-275. This directly supersedes the earlier audit's guess.
                                onLongClick = { if (!selectMode) showLockPanel = true },
                            )
                        } else Modifier
                    )
            ) {
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp,
                            )
                        )
                        .background(
                            if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (!isUser) {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = 4.dp,
                                        bottomEnd = 16.dp,
                                    )
                                )
                            } else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Column {
                        if (attachment != null) {
                            AttachmentPreview(attachment)
                            if (body.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (body.isNotBlank()) {
                            MessageBody(body, isUser)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatReqyTimestamp(msg.timestamp),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp)
                    )
                    if (!isUser) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Copy",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onCopy)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Regenerate",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onRegenerate)
                        )
                    }
                }
            }

            if (showLockPanel) {
                UserMessageLockPanel(
                    onDismiss = { showLockPanel = false },
                    onCopy = { showLockPanel = false; onCopy() },
                    onEdit = { showLockPanel = false; showEditDialog = true },
                    onReply = { showLockPanel = false; onReply() },
                    onDelete = { showLockPanel = false; onDelete() },
                    onSelect = { showLockPanel = false; onEnterSelect() },
                )
            }
        }
    }

    if (showEditDialog) {
        EditMessageDialog(
            initialText = body,
            onDismiss = { showEditDialog = false },
            onConfirm = { newText -> showEditDialog = false; onEdit(newText) },
        )
    }
}

@Composable
private fun MessageBody(body: String, isUser: Boolean) {
    // Fenced ```code``` blocks (Code Snippet / Request-Response attachments, or any AI reply
    // that happens to contain one) render as a monospace block; everything else is plain text.
    if (ChatCodeFence.containsFence(body)) {
        val parts = remember(body) { splitFencedSegments(body) }
        Column {
            parts.forEach { (isCode, segment) ->
                if (isCode) {
                    Text(
                        text = segment,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
                            .padding(8.dp)
                    )
                } else if (segment.isNotBlank()) {
                    Text(
                        text = segment.trim(),
                        fontSize = 12.5.sp,
                        lineHeight = (12.5.sp.value * 1.5f).sp,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    } else {
        Text(
            text = body,
            fontSize = 12.5.sp,
            lineHeight = (12.5.sp.value * 1.5f).sp,
            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Splits [content] into (isCode, text) segments around ```fenced``` blocks. Pure + testable. */
internal fun splitFencedSegments(content: String): List<Pair<Boolean, String>> {
    val regex = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```")
    val result = mutableListOf<Pair<Boolean, String>>()
    var lastEnd = 0
    for (match in regex.findAll(content)) {
        if (match.range.first > lastEnd) result.add(false to content.substring(lastEnd, match.range.first))
        result.add(true to (match.groups[1]?.value ?: ""))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < content.length) result.add(false to content.substring(lastEnd))
    return result
}

@Composable
private fun AttachmentPreview(attachment: ChatAttachment) {
    when (attachment.kind) {
        ChatAttachmentKind.IMAGE -> {
            if (attachment.uri.isNotBlank()) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = "\uD83D\uDDBC ${attachment.name} (${formatByteCount(attachment.sizeBytes)})",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChatAttachmentKind.FILE -> AttachmentChip(icon = Icons.Default.Description, attachment = attachment)
        ChatAttachmentKind.SCREENSHOT -> {
            if (attachment.uri.isNotBlank()) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = "\uD83D\uDCF7 ${attachment.name} (${formatByteCount(attachment.sizeBytes)})",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentChip(icon: androidx.compose.ui.graphics.vector.ImageVector, attachment: ChatAttachment) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(text = "${attachment.name} \u00b7 ${formatByteCount(attachment.sizeBytes)}", fontSize = 10.sp)
    }
}

// ─── LONG-PRESS LOCK PANEL (user bubbles only) ────────────────────────────────

/**
 * Exactly the mockup's `.lockpanel` (`reqy-assistant-light.html` lines 262-275): Copy, Edit
 * (edit + resend), Reply (quote-reply), a divider, Delete (danger), Select (enter multi-select).
 * The name "lock panel" is the mockup author's design terminology for an anchored context menu
 * -- it is NOT a message-pinning feature; the earlier audit's guess to that effect was checked
 * against the actual HTML and is wrong (see this file's top kdoc + the final report).
 */
@Composable
private fun UserMessageLockPanel(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
        DropdownMenuItem(
            text = { Text("Edit") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = onEdit,
        )
        DropdownMenuItem(
            text = { Text("Reply") },
            leadingIcon = { Icon(Icons.Default.Reply, contentDescription = null) },
            onClick = onReply,
        )
        androidx.compose.material3.HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
        )
        DropdownMenuItem(
            text = { Text("Select") },
            leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
            onClick = onSelect,
        )
    }
}

@Composable
private fun EditMessageDialog(initialText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(18.dp)
                .fillMaxWidth(0.92f)
        ) {
            Text("Edit message", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Resend", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = text.isNotBlank()) { onConfirm(text) }.padding(8.dp)
                )
            }
        }
    }
}

// ─── ERROR BUBBLE (provider failure) ──────────────────────────────────────────

@Composable
private fun ReqyErrorBubble(
    message: ChatMessage,
    onRetry: () -> Unit
) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(end = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp,
                    )
                )
                .background(errorColor.copy(alpha = 0.1f))
                .border(1.dp, errorColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(errorColor.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CONN_ERR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = errorColor
                        )
                    }
                    Text(
                        text = "Connection failed",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = errorColor
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message.content,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // Retry affordance (DESIGN-SYSTEM §15 error state: retry GlassActionPill pattern).
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { contentDescription = "Retry message" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Retry",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ─── THINKING INDICATOR (animated dots — the loading/streaming state) ─────────

@Composable
private fun ThinkingIndicator() {
    val animationsEnabled = com.example.ui.theme.LocalAnimationsEnabled.current
    val infiniteTransition = rememberInfiniteTransition(label = "reqyThinking")

    // Three dots pulse out of phase; gated behind LocalAnimationsEnabled.
    val d1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "td1"
    )
    val d2 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "td2"
    )
    val d3 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "td3"
    )

    val a1 = if (animationsEnabled) d1 else 0.8f
    val a2 = if (animationsEnabled) d2 else 0.5f
    val a3 = if (animationsEnabled) d3 else 0.25f

    Row(
        modifier = Modifier
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp,
                )
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { contentDescription = "Reqy is thinking" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Dot(alpha = a1)
            Dot(alpha = a2)
            Dot(alpha = a3)
        }
        Text(
            text = "Thinking...",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

// ─── PROMPT COMPOSER BAR ──────────────────────────────────────────────────────

@Composable
private fun ReqyComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    pendingAttachment: Pair<ChatAttachment, String>?,
    onRemoveAttachment: () -> Unit,
    providerSupportsVision: Boolean,
    onAttachClick: () -> Unit,
    onSend: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (pendingAttachment != null) {
            val attachment = pendingAttachment.first
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    when (attachment.kind) {
                        ChatAttachmentKind.IMAGE -> Icons.Default.Image
                        ChatAttachmentKind.FILE -> Icons.Default.Description
                        ChatAttachmentKind.SCREENSHOT -> Icons.Default.CameraAlt
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "${attachment.name} \u00b7 ${formatByteCount(attachment.sizeBytes)}",
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    modifier = Modifier.size(16.dp).clickable(onClick = onRemoveAttachment),
                )
            }
            if (attachment.kind == ChatAttachmentKind.IMAGE && !providerSupportsVision) {
                Text(
                    "This provider doesn't support images -- it will only see the file name/size, not the picture itself.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .shadow(4.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(start = 6.dp, top = 7.dp, bottom = 7.dp, end = 8.dp)
                .semantics { contentDescription = "Message composer" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onAttachClick, modifier = Modifier.semantics { contentDescription = "Add attachment" }) {
                Icon(Icons.Default.AttachFile, contentDescription = "Add attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Ask about requestr...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            // Send button — 38dp primary capsule (DESIGN-SYSTEM §15 send affordance).
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, onClick = onSend)
                    .semantics { contentDescription = "Send message" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─── ATTACHMENT SHEET (5 real actions) ────────────────────────────────────────

@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onImagePicked: (ChatAttachment) -> Unit,
    onFilePicked: (ChatAttachment) -> Unit,
    onCodeAttached: (code: String, language: String) -> Unit,
    onRequestResponseAttached: (formatted: String) -> Unit,
    onScreenshotRequested: () -> Unit,
) {
    val context = LocalContext.current
    var sizeError by remember { mutableStateOf<String?>(null) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var showRequestResponseDialog by remember { mutableStateOf(false) }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val info = pickedFileInfo(context, uri)
        if (info.second > MAX_ATTACHMENT_SIZE_BYTES) {
            sizeError = "${info.first} is ${formatByteCount(info.second)} -- over the 8 MB attachment limit."
            return@rememberLauncherForActivityResult
        }
        onImagePicked(ChatAttachment(ChatAttachmentKind.IMAGE, info.first, info.second, uri.toString()))
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val info = pickedFileInfo(context, uri)
        if (info.second > MAX_ATTACHMENT_SIZE_BYTES) {
            sizeError = "${info.first} is ${formatByteCount(info.second)} -- over the 8 MB attachment limit."
            return@rememberLauncherForActivityResult
        }
        onFilePicked(ChatAttachment(ChatAttachmentKind.FILE, info.first, info.second, uri.toString()))
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Text("Add to message", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            if (sizeError != null) {
                Text(sizeError ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachmentAction(Icons.Default.Image, "Image", "Upload from gallery") {
                    imageLauncher.launch("image/*")
                }
                AttachmentAction(Icons.Default.Description, "File", "Upload any file") {
                    fileLauncher.launch(arrayOf("*/*"))
                }
                AttachmentAction(Icons.Default.Code, "Code Snippet", "Share code") {
                    showCodeDialog = true
                }
                AttachmentAction(Icons.Default.SwapHoriz, "Request/Response", "Share from history") {
                    showRequestResponseDialog = true
                }
                AttachmentAction(Icons.Default.CameraAlt, "Screenshot", "Capture & share") {
                    // The actual capture happens in ChatScreen AFTER this sheet is fully
                    // dismissed (see onScreenshotRequested's kdoc at its call site) -- capturing
                    // while still inside this Dialog is exactly what caused the screenshot to
                    // include the sheet itself.
                    onScreenshotRequested()
                }
            }
        }
    }

    if (showCodeDialog) {
        CodeSnippetDialog(
            onDismiss = { showCodeDialog = false },
            onConfirm = { code, language -> showCodeDialog = false; onCodeAttached(code, language) },
        )
    }
    if (showRequestResponseDialog) {
        RequestResponsePickerDialog(
            onDismiss = { showRequestResponseDialog = false },
            onPicked = { formatted -> showRequestResponseDialog = false; onRequestResponseAttached(formatted) },
        )
    }
}

@Composable
private fun AttachmentAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun pickedFileInfo(context: android.content.Context, uri: android.net.Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    }
    if (size <= 0L) {
        size = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
        }.getOrNull() ?: 0L
    }
    return name to size
}

private suspend fun captureScreenshotAttachment(context: android.content.Context, view: android.view.View): ChatAttachment? {
    val activity = view.context as? android.app.Activity ?: context as? android.app.Activity ?: return null
    if (view.width <= 0 || view.height <= 0) return null
    return try {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val success = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            android.view.PixelCopy.request(
                activity.window,
                bitmap,
                { result -> if (cont.isActive) cont.resume(result == android.view.PixelCopy.SUCCESS) {} },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
        if (!success) return null
        val file = java.io.File(context.cacheDir, "reqy_screenshot_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        ChatAttachment(ChatAttachmentKind.SCREENSHOT, file.name, file.length(), uri.toString())
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun CodeSnippetDialog(onDismiss: () -> Unit, onConfirm: (code: String, language: String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text("Share code", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = language, onValueChange = { language = it },
                label = { Text("Language (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Code") }, modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Attach", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = code.isNotBlank()) { onConfirm(code, language.trim()) }.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun RequestResponsePickerDialog(onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<com.example.data.models.CapturedTrafficItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        items = CaptureLogRepository.getAllTraffic(context).take(30)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text("Share from history", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            // Substitution note (see final report): the mockup says "API Testr history/collections",
            // but requestr's captured-traffic log is used instead because it (unlike saved API
            // requests) carries a REAL executed response body, matching "method, URL, headers,
            // body, response" -- a strictly better fit for this action's stated purpose.
            Text(
                "From requestr's captured traffic (has real responses, unlike saved request templates).",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (items.isEmpty()) {
                Text("No captured traffic yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(formatCapturedTrafficAsContext(item)) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "${item.method.name} ${item.statusCode} \u2014 ${item.host}${item.path}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }
    }
}

// ─── SIDE DRAWER ("Reqy's own drawer, not the app's main navigation drawer") ──

/**
 * Mirrors `reqy-assistant-light.html` Screen 3, now as a real multi-conversation surface
 * (Reqy Assistant completeness pass, item 1):
 * - "AI History" lists every saved [ChatConversationEntity] (most-recently-updated first),
 *   tap one to switch to it, with per-row rename/delete affordances.
 * - `drw-back` ("Home") and `drawer-collections`/`drawer-flows` are real cross-section
 *   navigation now, wired through `MainActivity.kt`'s existing named-callback convention (see
 *   `onNavigateHome`/`onNavigateToCollections`/`onNavigateToFlows` below) instead of a Toast.
 * - `drawer-environments` genuinely has no dedicated screen anywhere in this app yet (confirmed:
 *   `EnvironmentRepository`/`Environment.kt` back request-variable substitution only -- there is
 *   no Environments management UI, not even embedded in another screen, for this item to
 *   navigate to). It is rendered as a disabled, honestly-labelled row instead of a live link or
 *   a dead-end Toast.
 */
@Composable
private fun ReqyDrawer(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onRename: (conversationId: String, title: String?) -> Unit,
    onSwitchConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToFlows: () -> Unit,
    conversations: List<com.example.data.local.ChatConversationEntity>,
    activeConversationId: String,
    chatTitle: String,
    providerConfig: ChatProviderConfig,
) {
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // id to current title
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // id to title
    val kind = ChatProviderKind.fromId(providerConfig.id)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClose)
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .align(Alignment.CenterStart)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Close, contentDescription = "Close drawer",
                    modifier = Modifier.size(20.dp).clickable(onClick = onClose),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("R", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                Column {
                    Text("Reqy", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("requestr's assistant", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            DrawerItem("New Chat", "Start fresh (no naming step)", onClick = onNewChat)
            DrawerItem("Rename chat", chatTitle) { renameTarget = activeConversationId to chatTitle }
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text(
                "AI HISTORY \u00b7 ${conversations.size}",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            conversations.forEach { conv ->
                val isActive = conv.id == activeConversationId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSwitchConversation(conv.id); onClose() }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            conv.title, fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "Updated ${formatClockTime(conv.updatedAt)}",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renameTarget = conv.id to conv.title }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename \"${conv.title}\"", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = { deleteTarget = conv.id to conv.title }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete \"${conv.title}\"", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text("TOOLS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            DrawerItem("Home", "Back to the main app") { onClose(); onNavigateHome() }
            DrawerItem("Collections", "Saved API requests") { onClose(); onNavigateToCollections() }
            DrawerItem("Flows", "Automation pipelines") { onClose(); onNavigateToFlows() }
            DrawerItem("Environments", "No dedicated screen in requestr yet -- managed only as request variables", onClick = null)
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp)
            ) {
                Text(
                    text = (providerConfig.model.ifBlank { kind.defaultModel }).ifBlank { "No model selected" },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (providerConfig.isConfigured) "via ${kind.displayName} \u00b7 direct" else "Local mode (no provider configured)",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            DrawerItem("Settings", "Provider, instructions, data", onClick = onOpenSettings)
        }
    }

    renameTarget?.let { (id, current) ->
        RenameChatDialog(
            current = current,
            onDismiss = { renameTarget = null },
            onConfirm = { title -> renameTarget = null; onRename(id, title) },
        )
    }

    deleteTarget?.let { (id, title) ->
        ClearHistoryConfirmDialog(
            title = "Delete \"$title\"?",
            message = "This conversation and every message in it will be permanently deleted. This action cannot be undone.",
            onConfirm = { deleteTarget = null; onDeleteConversation(id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun DrawerItem(title: String, subtitle: String? = null, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick!!) else it }
            .padding(vertical = 8.dp)
    ) {
        Text(
            title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f))
        }
    }
}

@Composable
private fun RenameChatDialog(current: String, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text("Rename chat", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Cancel", modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Save", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onConfirm(text.trim().ifBlank { null }) }.padding(8.dp)
                )
            }
        }
    }
}

// ─── FULL-PAGE REQY SETTINGS (mockup Screen 2) ────────────────────────────────

/**
 * Reqy's own full-page settings -- a single real destination reached from both the header gear
 * and the drawer's "Settings" shortcut (mockup legend note 2: "one single real destination").
 * Rendered as an internal overlay state (not a NavController route) so it stays inside files
 * this task owns; see this file's top kdoc for why.
 *
 * Scope deviations from the mockup (documented in the final report):
 * - Model field is free text pre-filled with a sensible default, not a live fetched model list.
 * - Appearance is scoped to a Reqy-only "compact density" toggle (not a shared Theme.kt hook --
 *   `Theme.kt`/`Color.kt`/`SettingsScreen.kt` are off-limits this wave).
 * - Advanced is flattened into this page instead of a separate sub-page.
 */
@Composable
private fun ReqySettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeConfig by ChatRepository.providerConfig.collectAsState()
    val messages by ChatRepository.messages.collectAsState()
    val conversations by ChatRepository.conversations.collectAsState()

    var selectedKind by remember { mutableStateOf(ChatProviderKind.fromId(activeConfig.id)) }
    var baseUrl by remember { mutableStateOf(activeConfig.baseUrl.ifBlank { selectedKind.defaultBaseUrl }) }
    var model by remember { mutableStateOf(activeConfig.model.ifBlank { selectedKind.defaultModel }) }
    var apiKey by remember { mutableStateOf(activeConfig.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var savedNotice by remember { mutableStateOf(false) }

    var instructions by remember { mutableStateOf(ChatSettingsStore.getInstructions(context)) }
    var localMode by remember { mutableStateOf(ChatSettingsStore.isLocalModeEnabled(context)) }
    var notificationsEnabled by remember { mutableStateOf(ChatSettingsStore.areNotificationsEnabled(context)) }
    var compactDensity by remember { mutableStateOf(ChatSettingsStore.isCompactDensity(context)) }
    var timeoutText by remember { mutableStateOf(ChatSettingsStore.getTimeoutSeconds(context).toString()) }
    var temperatureText by remember { mutableStateOf(ChatSettingsStore.getTemperature(context).toString()) }
    var rawLogging by remember { mutableStateOf(ChatSettingsStore.isRawLoggingEnabled(context)) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    fun selectChip(kind: ChatProviderKind) {
        selectedKind = kind
        savedNotice = false
        validationError = null
        scope.launch {
            val cfg = ChatRepository.getProviderConfigById(kind.id)
            baseUrl = cfg.baseUrl.ifBlank { kind.defaultBaseUrl }
            model = cfg.model.ifBlank { kind.defaultModel }
            apiKey = cfg.apiKey
        }
    }

    fun saveProvider() {
        val error = validateProviderFields(selectedKind, baseUrl, apiKey)
        if (error != null) {
            validationError = error
            savedNotice = false
            return
        }
        validationError = null
        savedNotice = false
        // The confirmation below must follow the REAL outcome of both the Room write and the
        // Keystore write -- not just this optimistic-in-memory-update call returning. Awaiting
        // the suspend result is what stops the UI from claiming "Saved and activated." while a
        // write is still in flight or has actually failed (see saveProviderConfig's kdoc).
        scope.launch {
            when (val result = ChatRepository.saveProviderConfig(
                ChatProviderConfig(
                    id = selectedKind.id,
                    name = selectedKind.displayName,
                    baseUrl = baseUrl.ifBlank { selectedKind.defaultBaseUrl },
                    model = model.ifBlank { selectedKind.defaultModel },
                    apiKey = apiKey,
                    isActive = true,
                )
            )) {
                is SaveProviderResult.Success -> {
                    savedNotice = true
                    validationError = null
                }
                is SaveProviderResult.Failure -> {
                    savedNotice = false
                    validationError = result.reason
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(title = "Settings", onBack = onClose, onMiniSettings = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SettingsSectionCard("AI Provider") {
                Text("Provider", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatProviderKind.chipOrder.forEach { kind ->
                        ProviderChip(kind = kind, selected = kind == selectedKind, isActiveProvider = kind.id == activeConfig.id && activeConfig.isActive) {
                            selectChip(kind)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (selectedKind.editableBaseUrl) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it; savedNotice = false },
                        label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                } else {
                    Text("Endpoint: ${selectedKind.defaultBaseUrl}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedKind.requiresApiKey || selectedKind == ChatProviderKind.CUSTOM) {
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; savedNotice = false },
                        label = { Text(if (selectedKind.requiresApiKey) "API Key" else "API Key (optional)") },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle key visibility")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = model, onValueChange = { model = it; savedNotice = false },
                    label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                if (validationError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(validationError ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                if (savedNotice) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Saved and activated.", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = ::saveProvider)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Save & Activate", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp)
                }
            }

            SettingsSectionCard("Instructions") {
                Text(
                    "Applied as a system prompt to every new message (not just new chats).",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it; ChatSettingsStore.setInstructions(context, it) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }

            SettingsSectionCard("Export") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsActionButton("Export Chat") {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            exportChatAsMarkdown(context, messages)
                        }
                    }
                    SettingsActionButton("Export Config") {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            exportConfigAsJson(context, activeConfig, instructions)
                        }
                    }
                }
            }

            SettingsSectionCard("General") {
                SettingsToggleRow(
                    title = "Appearance", subtitle = "Compact message density (Reqy-only; shares the app's live theme colors already)",
                    checked = compactDensity, onCheckedChange = { compactDensity = it; ChatSettingsStore.setCompactDensity(context, it) },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Data & Storage", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                SettingsRow(
                    title = "Clear this chat",
                    subtitle = "${messages.size} messages in the current conversation",
                ) { showClearDialog = true }
                SettingsRow(
                    title = "Clear ALL conversations",
                    subtitle = "${conversations.size} conversation${if (conversations.size == 1) "" else "s"} in \"AI History\" -- wipes every one of them",
                ) { showClearAllDialog = true }
                SettingsToggleRow(
                    title = "Notifications", subtitle = "Notify when Reqy finishes a reply",
                    checked = notificationsEnabled, onCheckedChange = { notificationsEnabled = it; ChatSettingsStore.setNotificationsEnabled(context, it) },
                )
                SettingsToggleRow(
                    title = "Local Mode", subtitle = "Never call a network provider, even if one is configured",
                    checked = localMode, onCheckedChange = { localMode = it; ChatSettingsStore.setLocalModeEnabled(context, it) },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Advanced", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it; it.toIntOrNull()?.let { v -> ChatSettingsStore.setTimeoutSeconds(context, v) } },
                    label = { Text("Timeout (seconds)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { temperatureText = it; it.toDoubleOrNull()?.let { v -> ChatSettingsStore.setTemperature(context, v) } },
                    label = { Text("Temperature") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsToggleRow(
                    title = "Raw request/response logging", subtitle = "Logcat only; never logs the API key",
                    checked = rawLogging, onCheckedChange = { rawLogging = it; ChatSettingsStore.setRawLoggingEnabled(context, it) },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        ClearHistoryConfirmDialog(
            onConfirm = { showClearDialog = false; ChatRepository.clearHistory() },
            onDismiss = { showClearDialog = false },
        )
    }
    if (showClearAllDialog) {
        ClearHistoryConfirmDialog(
            title = "Clear ALL Conversations?",
            message = "Every conversation in \"AI History\" and all of its messages will be permanently deleted. This action cannot be undone.",
            onConfirm = { showClearAllDialog = false; ChatRepository.clearAllConversations() },
            onDismiss = { showClearAllDialog = false },
        )
    }
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ProviderChip(kind: ChatProviderKind, selected: Boolean, isActiveProvider: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(kind.brandColor)))
        Text(kind.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        if (isActiveProvider) {
            Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun exportChatAsMarkdown(context: android.content.Context, messages: List<ChatMessage>) {
    val sb = StringBuilder("# Reqy Assistant Chat Export\n\n")
    messages.forEach { msg ->
        val (attachment, body) = decodeAttachment(msg.content)
        sb.append("**${msg.role}** (${formatReqyTimestamp(msg.timestamp)})")
        if (attachment != null) sb.append(" [attachment: ${attachment.name}]")
        sb.append(":\n\n").append(body.ifBlank { msg.content }).append("\n\n")
    }
    val target = com.example.data.export.MediaStoreExporter.writeText(
        context, "reqy-chat-${System.currentTimeMillis()}.md", "text/markdown", sb.toString(),
    ) ?: return
    com.example.data.export.FileExporter.shareFile(context, target, "text/markdown", "Reqy Assistant chat export")
}

/** Never includes [ChatProviderConfig.apiKey] -- see the mockup's "never the raw API key" note. */
private fun exportConfigAsJson(context: android.content.Context, config: ChatProviderConfig, instructions: String) {
    val json = org.json.JSONObject().apply {
        put("provider", config.id)
        put("baseUrl", config.baseUrl)
        put("model", config.model)
        put("instructions", instructions)
    }
    val target = com.example.data.export.MediaStoreExporter.writeText(
        context, "reqy-config-${System.currentTimeMillis()}.json", "application/json", json.toString(2),
    ) ?: return
    com.example.data.export.FileExporter.shareFile(context, target, "application/json", "Reqy Assistant config export")
}

// ─── CLEAR HISTORY CONFIRMATION DIALOG ────────────────────────────────────────

@Composable
private fun ClearHistoryConfirmDialog(
    title: String = "Clear Chat History?",
    message: String = "All messages in this conversation will be permanently deleted. This action cannot be undone.",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .padding(22.dp)
                .fillMaxWidth(0.92f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Red icon tile
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                        .semantics { contentDescription = "Cancel" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable { onConfirm() }
                        .semantics { contentDescription = "Confirm clear" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

// ─── PURE HELPERS (presentation-only; preserve backup failure strings) ────────

/**
 * Detects a provider-failure assistant message purely from its content, so the UI can
 * render the approved error bubble + retry affordance. This matches the exact strings
 * [ChatRepository.callProvider] already emits — it does **not** alter failure behavior:
 * the same message is still stored/persisted; only its presentation changes.
 *
 * - `"Connection failed: …"` — OkHttp/IO exception path.
 * - `"Error {code}: …"` — non-2xx HTTP response.
 * - `"Empty response from provider"` — successful response with no choices.
 * - `"Configuration error: …"` — new in this pass: field validation / blocked-cleartext-host
 *   failures raised defensively by [ChatRepository.callProvider] before any network call.
 */
internal fun isReqyProviderError(content: String): Boolean {
    val trimmed = content.trim()
    if (trimmed.startsWith("Connection failed:")) return true
    if (trimmed.startsWith("Configuration error:")) return true
    // "Error 500: Internal Server Error" etc.
    val errorCodeMatch = REQY_HTTP_ERROR_REGEX.matchEntire(trimmed)
    if (errorCodeMatch != null) return true
    if (trimmed == "Empty response from provider") return true
    return false
}

private val REQY_HTTP_ERROR_REGEX = Regex("^Error \\d{3}:.+")

/** Formats a message epoch-millis timestamp as HH:mm (mockup's mono 9px timestamp). */
internal fun formatReqyTimestamp(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}
