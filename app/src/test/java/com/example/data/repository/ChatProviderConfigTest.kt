package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatProviderEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.ChatMessage
import com.example.data.models.ChatProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 12 — [ChatProviderConfig] round-trip and request-body-shape coverage. Room-backed parts
 * use the real [RequestrDatabase] singleton via Robolectric, matching the precedent set by
 * `WorkspaceExporterTest` (no `Room.inMemoryDatabaseBuilder` usage exists anywhere in this repo).
 */
@RunWith(RobolectricTestRunner::class)
class ChatProviderConfigTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearProviders() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.chatProviderDao().getAll().forEach { db.chatProviderDao().deleteById(it.id) }
    }

    @Test
    fun `a provider row round-trips through Room with no key column present`() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.chatProviderDao().upsert(
            ChatProviderEntity(
                id = "provider-1",
                name = "My Endpoint",
                baseUrl = "https://api.example.com/v1/chat/completions",
                model = "gpt-4o-mini",
                isActive = true,
            )
        )

        val rows = db.chatProviderDao().getAll()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("provider-1", row.id)
        assertEquals("My Endpoint", row.name)
        assertEquals("https://api.example.com/v1/chat/completions", row.baseUrl)
        assertEquals("gpt-4o-mini", row.model)
        assertTrue(row.isActive)

        // ChatProviderEntity has no apiKey field at all -- this is a compile-time guarantee, but
        // assert on the declared property names too so a future accidental re-add is caught by
        // this test's intent, not just by the KDoc comment on the entity.
        val propertyNames = ChatProviderEntity::class.java.declaredFields.map { it.name }
        assertFalse(
            "ChatProviderEntity must never gain an apiKey/key column -- the key stays in SecureKeyStore",
            propertyNames.any { it.contains("key", ignoreCase = true) },
        )
    }

    @Test
    fun `isConfigured is false for a blank baseUrl or a blank apiKey, true only when both are set`() {
        assertFalse(ChatProviderConfig(baseUrl = "", apiKey = "sk-abc").isConfigured)
        assertFalse(ChatProviderConfig(baseUrl = "https://example.com", apiKey = "").isConfigured)
        assertFalse(ChatProviderConfig(baseUrl = "", apiKey = "").isConfigured)
        assertTrue(ChatProviderConfig(baseUrl = "https://example.com", apiKey = "sk-abc").isConfigured)
    }

    @Test
    fun `the request-body builder produces the expected OpenAI-compatible JSON`() {
        val config = ChatProviderConfig(model = "gpt-4o-mini", baseUrl = "https://x", apiKey = "sk-1")
        val history = listOf(
            ChatMessage(id = "1", role = "user", content = "hi", timestamp = 1),
            ChatMessage(id = "2", role = "assistant", content = "hello", timestamp = 2),
        )
        val body = ChatRepository.buildRequestBodyJson(config, history)

        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(1000, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hi", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("hello", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `the request-body builder windows to the last 10 messages and falls back to gpt-3_5-turbo when model is blank`() {
        val config = ChatProviderConfig(model = "", baseUrl = "https://x", apiKey = "sk-1")
        val history = (1..15).map {
            ChatMessage(id = it.toString(), role = if (it % 2 == 0) "assistant" else "user", content = "msg-$it", timestamp = it.toLong())
        }
        val body = ChatRepository.buildRequestBodyJson(config, history)

        assertEquals("gpt-3.5-turbo", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(10, messages.length())
        // The window must be the LAST 10 (msg-6..msg-15), oldest-first within the window.
        assertEquals("msg-6", messages.getJSONObject(0).getString("content"))
        assertEquals("msg-15", messages.getJSONObject(9).getString("content"))
    }
}
