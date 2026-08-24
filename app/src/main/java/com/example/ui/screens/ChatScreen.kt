package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.ChatMessage
import com.example.data.repository.ChatRepository
import com.example.ui.components.SectionHeader
import com.example.ui.components.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * Reqy Assistant screen — AI assistant chat with configurable provider.
 *
 * v6.0 (plan task 13, Lane C): renamed/redesigned from "Chats" to "Reqy Assistant" as a
 * **presentation-only** change. No new assistant capabilities, tools, agents, or streaming
 * were added. The backup [ChatRepository] contract is preserved exactly: configurable
 * OpenAI-compatible provider, Keystore-backed key, Room-persisted conversation history,
 * local fallback when no provider is configured, and the same provider-failure strings.
 *
 * Provider failures are surfaced as a dedicated red error bubble with a retry affordance.
 * The error is detected purely from the existing assistant message content (see
 * [isReqyProviderError]) — the failure behavior itself is unchanged; only its rendering
 * differs. Retry re-sends the last user message through the same [ChatRepository.sendMessage]
 * path, so no new network/code path is introduced.
 */
@Composable
fun ChatScreen(
    onMiniSettings: () -> Unit,
) {
    val messages by ChatRepository.messages.collectAsState()
    val isLoading by ChatRepository.isLoading.collectAsState()
    val providerConfig by ChatRepository.providerConfig.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHapticFeedback()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom on new messages / loading-state changes.
    LaunchedEffect(messages.size, isLoading) {
        val target = if (isLoading) messages.size else messages.size - 1
        if (target >= 0) {
            listState.animateScrollToItem(target)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SectionHeader(
            title = "Reqy Assistant",
            onMiniSettings = onMiniSettings,
            onBack = null,
            actions = {
                IconButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.semantics { contentDescription = "Clear chat history" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear chat history",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        )

        // Provider status banner (presentation redesign — same not-configured semantics).
        if (!providerConfig.isConfigured) {
            ProviderNotConfiguredBanner()
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
                    ChatBubble(msg)
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
            enabled = inputText.isNotBlank() && !isLoading,
            onSend = {
                val text = inputText.trim()
                if (text.isNotBlank() && !isLoading) {
                    haptics(HapticFeedbackType.LongPress)
                    inputText = ""
                    scope.launch { ChatRepository.sendMessage(text) }
                }
            }
        )
    }

    if (showClearDialog) {
        ClearHistoryConfirmDialog(
            onConfirm = {
                showClearDialog = false
                ChatRepository.clearHistory()
            },
            onDismiss = { showClearDialog = false }
        )
    }
}

// ─── PROVIDER NOT-CONFIGURED BANNER ───────────────────────────────────────────

@Composable
private fun ProviderNotConfiguredBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
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
                append("Running in local mode. Configure in Settings.")
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
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp)
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
                Text(
                    text = msg.content,
                    fontSize = 12.5.sp,
                    lineHeight = (12.5.sp.value * 1.5f).sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = formatReqyTimestamp(msg.timestamp),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .padding(start = 4.dp, top = 3.dp)
                    .then(if (isUser) Modifier else Modifier)
            )
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
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .shadow(4.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, top = 7.dp, bottom = 7.dp, end = 8.dp)
            .semantics { contentDescription = "Message composer" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── CLEAR HISTORY CONFIRMATION DIALOG ────────────────────────────────────────

@Composable
private fun ClearHistoryConfirmDialog(
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
                text = "Clear Chat History?",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All messages in this conversation will be permanently deleted. This action cannot be undone.",
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
 */
internal fun isReqyProviderError(content: String): Boolean {
    val trimmed = content.trim()
    if (trimmed.startsWith("Connection failed:")) return true
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
