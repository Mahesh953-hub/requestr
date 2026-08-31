package com.example.data.models

import java.util.Calendar

/**
 * Pure helpers for chat-title derivation, quote-reply, and formatting a captured
 * request/response as attached context. Kept dependency-free (no Android APIs, no
 * [com.example.data.repository.ChatRepository] coupling) so they are directly unit-testable.
 */

/**
 * Task/Screen-4 note: the old "name this chat" modal is gone (see `reqy-assistant-light.html`'s
 * legend: "The old 'New Chat' naming-modal concept is fully removed -- new chat is instant").
 * The title shown in the drawer/header is therefore always DERIVED unless the user explicitly
 * renamed the chat from the drawer, in which case [override] wins.
 *
 * - [override] non-blank -> always wins (explicit rename).
 * - Otherwise, once there is at least one user message, the title becomes that message's text
 *   (attachment marker stripped), truncated to 40 chars.
 * - Otherwise (brand-new, empty chat), a timestamped placeholder derived from [sessionStartedAt]
 *   so two different "New chat"s opened seconds apart are still distinguishable in the drawer.
 */
fun deriveChatTitle(
    messages: List<ChatMessage>,
    override: String?,
    sessionStartedAt: Long,
): String {
    if (!override.isNullOrBlank()) return override.trim()

    val firstUser = messages.firstOrNull { it.role == "user" }
    if (firstUser != null) {
        val (_, body) = decodeAttachment(firstUser.content)
        val preview = body.ifBlank { firstUser.content }.trim().replace(Regex("\\s+"), " ")
        if (preview.isNotBlank()) {
            return if (preview.length > 40) preview.take(40).trimEnd() + "\u2026" else preview
        }
    }
    return "New chat \u2014 ${formatClockTime(sessionStartedAt)}"
}

/** "6:02 PM"-style clock formatting, independent of [ChatMessage.timestamp] formatting in the UI. */
fun formatClockTime(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val hour24 = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
    val amPm = if (hour24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(hour12, minute, amPm)
}

/**
 * Builds the composer prefix for the lock panel's "Reply" (quote-reply) action -- prepends a
 * `>`-quoted copy of [original]'s text (attachment marker stripped) so the user's next message
 * carries visible context, matching the mockup's `usermsg-reply` action.
 */
fun buildQuoteReplyPrefix(original: ChatMessage): String {
    val (_, body) = decodeAttachment(original.content)
    val source = body.ifBlank { original.content }
    val quoted = source.lines().joinToString("\n") { "> $it" }
    return "$quoted\n\n"
}

/**
 * Formats a captured request/response transaction (see
 * [com.example.data.interception.CaptureLogRepository] / [CapturedTrafficItem] -- requestr's
 * actual traffic-capture log, which unlike [com.example.data.repository.ApiCollectionsRepository]
 * saved requests already carries a REAL executed response, not just a request template) as a
 * fenced context block for the "Request/Response" attachment action.
 */
fun formatCapturedTrafficAsContext(item: CapturedTrafficItem): String {
    val sb = StringBuilder()
    sb.append("${item.method.name} ${item.url}\n")
    if (item.requestHeaders.isNotEmpty()) {
        sb.append("\nRequest headers:\n")
        item.requestHeaders.forEach { (k, v) -> sb.append("$k: $v\n") }
    }
    if (item.requestBody.isNotBlank()) {
        sb.append("\nRequest body:\n${item.requestBody}\n")
    }
    sb.append("\n-> ${item.statusCode} ${item.statusMessage} (${item.latencyMs}ms)\n")
    if (item.responseHeaders.isNotEmpty()) {
        sb.append("\nResponse headers:\n")
        item.responseHeaders.forEach { (k, v) -> sb.append("$k: $v\n") }
    }
    if (item.responseBody.isNotBlank()) {
        sb.append("\nResponse body:\n${item.responseBody}\n")
    }
    return ChatCodeFence.wrap(sb.toString().trim(), language = "http")
}
