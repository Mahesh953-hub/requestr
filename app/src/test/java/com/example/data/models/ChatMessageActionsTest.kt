package com.example.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric is required here too -- see [ChatAttachmentTest]'s kdoc; some cases decode a marker. */
@RunWith(RobolectricTestRunner::class)
class ChatMessageActionsTest {

    @Test
    fun `deriveChatTitle prefers a non-blank override over anything else`() {
        val messages = listOf(ChatMessage(id = "1", role = "user", content = "hello there", timestamp = 1L))
        val title = deriveChatTitle(messages, override = "My Renamed Chat", sessionStartedAt = 1L)
        assertEquals("My Renamed Chat", title)
    }

    @Test
    fun `deriveChatTitle falls back to a timestamped placeholder when there are no user messages`() {
        val title = deriveChatTitle(emptyList(), override = null, sessionStartedAt = 0L)
        assertTrue(title.startsWith("New chat \u2014 "))
    }

    @Test
    fun `deriveChatTitle uses the first user message text, stripped of any attachment marker`() {
        val attachment = ChatAttachment(kind = ChatAttachmentKind.IMAGE, name = "a.png", sizeBytes = 10L)
        val encoded = encodeAttachment(attachment, "what's wrong with this screenshot")
        val messages = listOf(ChatMessage(id = "1", role = "user", content = encoded, timestamp = 1L))
        val title = deriveChatTitle(messages, override = null, sessionStartedAt = 0L)
        assertEquals("what's wrong with this screenshot", title)
    }

    @Test
    fun `deriveChatTitle truncates long first messages to 40 chars plus an ellipsis`() {
        val longText = "a".repeat(80)
        val messages = listOf(ChatMessage(id = "1", role = "user", content = longText, timestamp = 1L))
        val title = deriveChatTitle(messages, override = null, sessionStartedAt = 0L)
        assertEquals(41, title.length) // 40 chars + the ellipsis character
        assertTrue(title.endsWith("\u2026"))
    }

    @Test
    fun `deriveChatTitle collapses internal whitespace runs`() {
        val messages = listOf(ChatMessage(id = "1", role = "user", content = "hi   there\n\nfriend", timestamp = 1L))
        val title = deriveChatTitle(messages, override = null, sessionStartedAt = 0L)
        assertEquals("hi there friend", title)
    }

    @Test
    fun `formatClockTime renders 12-hour am-pm style`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2024, 0, 1, 18, 2, 0)
        assertEquals("6:02 PM", formatClockTime(cal.timeInMillis))
        cal.set(2024, 0, 1, 0, 5, 0)
        assertEquals("12:05 AM", formatClockTime(cal.timeInMillis))
    }

    @Test
    fun `buildQuoteReplyPrefix quotes each line with a leading angle bracket`() {
        val original = ChatMessage(id = "1", role = "user", content = "line one\nline two", timestamp = 1L)
        val prefix = buildQuoteReplyPrefix(original)
        assertEquals("> line one\n> line two\n\n", prefix)
    }

    @Test
    fun `buildQuoteReplyPrefix strips any attachment marker before quoting`() {
        val attachment = ChatAttachment(kind = ChatAttachmentKind.FILE, name = "x.txt", sizeBytes = 1L)
        val original = ChatMessage(id = "1", role = "user", content = encodeAttachment(attachment, "see attached"), timestamp = 1L)
        val prefix = buildQuoteReplyPrefix(original)
        assertEquals("> see attached\n\n", prefix)
    }

    @Test
    fun `formatCapturedTrafficAsContext produces an http-fenced block with method, url and status`() {
        val item = CapturedTrafficItem(
            method = HttpMethod.POST,
            url = "https://api.example.com/login",
            requestHeaders = mapOf("Content-Type" to "application/json"),
            requestBody = "{\"user\":\"a\"}",
            statusCode = 401,
            statusMessage = "Unauthorized",
            latencyMs = 55,
            responseHeaders = mapOf("content-type" to "application/json"),
            responseBody = "{\"error\":\"bad creds\"}",
        )
        val formatted = formatCapturedTrafficAsContext(item)
        assertTrue(formatted.startsWith("```http"))
        assertTrue(formatted.contains("POST https://api.example.com/login"))
        assertTrue(formatted.contains("401 Unauthorized"))
        assertTrue(formatted.contains("bad creds"))
        assertTrue(ChatCodeFence.containsFence(formatted))
    }
}
