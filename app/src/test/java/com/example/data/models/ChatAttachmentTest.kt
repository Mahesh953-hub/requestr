package com.example.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [org.json.JSONObject] is backed by the Android SDK stub jar in plain JVM unit tests (this
 * module's `testOptions.unitTests.isReturnDefaultValues = true` makes unmocked calls return
 * defaults/null instead of throwing) -- so, like [ChatProviderConfigTest], anything that touches
 * [encodeAttachment]/[decodeAttachment] needs Robolectric's real `org.json` implementation.
 */
@RunWith(RobolectricTestRunner::class)
class ChatAttachmentTest {

    @Test
    fun `encodeAttachment then decodeAttachment round-trips kind, name, size, uri and caption`() {
        val attachment = ChatAttachment(
            kind = ChatAttachmentKind.IMAGE,
            name = "photo.png",
            sizeBytes = 12345L,
            uri = "content://media/external/images/1",
        )
        val encoded = encodeAttachment(attachment, "here's a screenshot of the bug")
        val (decoded, body) = decodeAttachment(encoded)

        assertEquals(attachment, decoded)
        assertEquals("here's a screenshot of the bug", body)
    }

    @Test
    fun `decodeAttachment on plain content with no marker returns null and the content unchanged`() {
        val plain = "just a normal chat message, no attachment here"
        val (decoded, body) = decodeAttachment(plain)
        assertNull(decoded)
        assertEquals(plain, body)
    }

    @Test
    fun `decodeAttachment round-trips an empty caption`() {
        val attachment = ChatAttachment(kind = ChatAttachmentKind.FILE, name = "report.pdf", sizeBytes = 999L)
        val encoded = encodeAttachment(attachment, "")
        val (decoded, body) = decodeAttachment(encoded)
        assertEquals(attachment, decoded)
        assertEquals("", body)
    }

    @Test
    fun `formatAttachmentSize picks bytes, KB or MB units`() {
        assertEquals("512 B", formatAttachmentSize(512))
        assertEquals("12.3 KB", formatAttachmentSize(12595))
        assertEquals("1.4 MB", formatAttachmentSize((1.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun `MAX_ATTACHMENT_SIZE_BYTES is exactly 8 MB`() {
        assertEquals(8L * 1024 * 1024, MAX_ATTACHMENT_SIZE_BYTES)
    }

    @Test
    fun `ChatCodeFence wrap produces a fenced block containsFence recognizes`() {
        val wrapped = ChatCodeFence.wrap("val x = 1", language = "kotlin")
        assertTrue(wrapped.startsWith("```kotlin"))
        assertTrue(wrapped.endsWith("```"))
        assertTrue(ChatCodeFence.containsFence(wrapped))
    }

    @Test
    fun `ChatCodeFence containsFence is false for plain text with no triple backticks`() {
        assertTrue(!ChatCodeFence.containsFence("no code here, just prose."))
    }
}
