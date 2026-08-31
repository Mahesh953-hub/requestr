package com.example.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for [ChatProviderKind]'s catalog shape plus the cleartext-host / field-validation
 * helpers, run before any network call from both the Settings chip UI and
 * [com.example.data.repository.ChatRepository.callProvider]'s defensive re-check.
 */
class ChatProviderCatalogTest {

    @Test
    fun `chip order matches the mockup's exact 8-provider sequence`() {
        val ids = ChatProviderKind.chipOrder.map { it.id }
        assertEquals(
            listOf("openai", "anthropic", "gemini", "openrouter", "groq", "huggingface", "ollama", "custom"),
            ids,
        )
    }

    @Test
    fun `fromId resolves known ids and falls back to CUSTOM for unknown ids`() {
        assertEquals(ChatProviderKind.OPENAI, ChatProviderKind.fromId("openai"))
        assertEquals(ChatProviderKind.OLLAMA, ChatProviderKind.fromId("ollama"))
        assertEquals(ChatProviderKind.CUSTOM, ChatProviderKind.fromId("some-unknown-id"))
    }

    @Test
    fun `no-key providers never require an api key`() {
        assertFalse(ChatProviderKind.HUGGINGFACE.requiresApiKey)
        assertFalse(ChatProviderKind.OLLAMA.requiresApiKey)
        assertFalse(ChatProviderKind.CUSTOM.requiresApiKey)
    }

    @Test
    fun `extractHost strips scheme, port, userinfo and path`() {
        assertEquals("api.example.com", extractHost("https://api.example.com/v1/chat"))
        assertEquals("10.0.2.2", extractHost("http://10.0.2.2:11434/api/chat"))
        assertEquals("example.com", extractHost("https://user:pass@example.com:8443/x"))
        assertEquals("localhost", extractHost("http://localhost"))
    }

    @Test
    fun `isBlockedCleartextHost only blocks plain http to hosts outside the allow-list`() {
        assertFalse(isBlockedCleartextHost("https://example.com"))
        assertFalse(isBlockedCleartextHost("http://10.0.2.2:11434"))
        assertFalse(isBlockedCleartextHost("http://localhost:8080"))
        assertFalse(isBlockedCleartextHost("http://127.0.0.1"))
        assertTrue(isBlockedCleartextHost("http://example.com"))
        assertTrue(isBlockedCleartextHost("http://192.168.1.5:8000"))
    }

    @Test
    fun `validateProviderFields flags a blank base url only when the kind has no usable default`() {
        // CUSTOM has a blank defaultBaseUrl, so a blank input leaves nothing to fall back to.
        assertNotNull(validateProviderFields(ChatProviderKind.CUSTOM, "", ""))
        // Ollama is editable too, but its defaultBaseUrl (the emulator host alias) is non-blank,
        // so a blank UI field still resolves to a usable endpoint -- no error.
        assertNull(validateProviderFields(ChatProviderKind.OLLAMA, "", ""))
        // OpenAI has a fixed (non-editable) base URL, so a blank baseUrl input is fine --
        // callers fall back to ChatProviderKind.defaultBaseUrl.
        assertNull(validateProviderFields(ChatProviderKind.OPENAI, "", "sk-abc"))
    }

    @Test
    fun `validateProviderFields flags a blank api key only for key-requiring kinds`() {
        assertNotNull(validateProviderFields(ChatProviderKind.OPENAI, "https://api.openai.com/v1/chat/completions", ""))
        assertNull(validateProviderFields(ChatProviderKind.HUGGINGFACE, "", ""))
        assertNull(validateProviderFields(ChatProviderKind.OLLAMA, "http://10.0.2.2:11434", ""))
    }

    @Test
    fun `validateProviderFields blocks a plain-http custom endpoint on a disallowed host with a precise reason`() {
        val reason = validateProviderFields(ChatProviderKind.CUSTOM, "http://my-server.local/api", "")
        assertNotNull(reason)
        assertTrue(reason!!.contains("my-server.local"))
        assertTrue(reason.contains("10.0.2.2"))
    }

    @Test
    fun `validateProviderFields allows a plain-http ollama endpoint on the emulator host alias`() {
        assertNull(validateProviderFields(ChatProviderKind.OLLAMA, "http://10.0.2.2:11434", ""))
    }

    @Test
    fun `isOpenAiCompatible is true only for the shared-shape providers`() {
        assertTrue(ChatProviderKind.OPENAI.isOpenAiCompatible)
        assertTrue(ChatProviderKind.OPENROUTER.isOpenAiCompatible)
        assertTrue(ChatProviderKind.GROQ.isOpenAiCompatible)
        assertTrue(ChatProviderKind.CUSTOM.isOpenAiCompatible)
        assertFalse(ChatProviderKind.ANTHROPIC.isOpenAiCompatible)
        assertFalse(ChatProviderKind.GEMINI.isOpenAiCompatible)
        assertFalse(ChatProviderKind.HUGGINGFACE.isOpenAiCompatible)
        assertFalse(ChatProviderKind.OLLAMA.isOpenAiCompatible)
    }
}
