package com.example.data.repository

import com.example.data.models.ChatMessage
import com.example.data.models.ChatProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure coverage for [ChatRepository]'s per-provider request-body builders and response parsers
 * (Anthropic/Gemini/HuggingFace/Ollama) added alongside the 8-provider catalog. These are plain
 * JSON-in/JSON-out functions with no network/Room dependency, but they DO use [org.json], which
 * needs Robolectric's real implementation under this module's `isReturnDefaultValues = true`
 * unit-test config (same reasoning as [ChatProviderConfigTest], which this class mirrors).
 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryProviderShapesTest {

    private val history = listOf(
        ChatMessage(id = "1", role = "user", content = "hi", timestamp = 1),
        ChatMessage(id = "2", role = "assistant", content = "hello", timestamp = 2),
    )

    // ─── Anthropic ─────────────────────────────────────────────────────────

    @Test
    fun `anthropic request body includes model, max_tokens, optional system and windowed messages`() {
        val config = ChatProviderConfig(id = "anthropic", model = "claude-3-5-sonnet-20241022", baseUrl = "https://api.anthropic.com/v1/messages", apiKey = "sk-ant")
        val body = ChatRepository.buildAnthropicRequestBodyJson(config, history, "Be concise")

        assertEquals("claude-3-5-sonnet-20241022", body.getString("model"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertEquals("Be concise", body.getString("system"))
        assertEquals(2, body.getJSONArray("messages").length())
    }

    @Test
    fun `anthropic request body falls back to the catalog default model when blank`() {
        val config = ChatProviderConfig(id = "anthropic", model = "", baseUrl = "https://api.anthropic.com/v1/messages", apiKey = "sk-ant")
        val body = ChatRepository.buildAnthropicRequestBodyJson(config, history, "")
        assertEquals("claude-3-5-sonnet-20241022", body.getString("model"))
        assertTrue(!body.has("system"))
    }

    @Test
    fun `parseAnthropicResponse extracts content 0 text`() {
        val body = """{"content":[{"type":"text","text":"hi there"}]}"""
        assertEquals("hi there", ChatRepository.parseAnthropicResponse(body))
    }

    @Test
    fun `parseAnthropicResponse falls back when content array is missing`() {
        assertEquals("Empty response from provider", ChatRepository.parseAnthropicResponse("{}"))
    }

    // ─── Gemini ────────────────────────────────────────────────────────────

    @Test
    fun `gemini request body maps assistant role to model and user role stays user`() {
        val body = ChatRepository.buildGeminiRequestBodyJson(history, "")
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("hi", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `gemini request body includes a systemInstruction only when instructions are non-blank`() {
        val withInstructions = ChatRepository.buildGeminiRequestBodyJson(history, "Be terse")
        assertTrue(withInstructions.has("systemInstruction"))
        val withoutInstructions = ChatRepository.buildGeminiRequestBodyJson(history, "")
        assertTrue(!withoutInstructions.has("systemInstruction"))
    }

    @Test
    fun `gemini url appends model and generateContent with the api key as a query param`() {
        val url = ChatRepository.buildGeminiUrl("https://generativelanguage.googleapis.com/v1beta/models", "gemini-1.5-flash", "key-123")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=key-123", url)
    }

    @Test
    fun `gemini url trims a trailing slash on the base and falls back to defaults when blank`() {
        val url = ChatRepository.buildGeminiUrl("https://generativelanguage.googleapis.com/v1beta/models/", "", "key-123")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=key-123", url)
    }

    @Test
    fun `parseGeminiResponse extracts candidates 0 content parts 0 text`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"gemini says hi"}]}}]}"""
        assertEquals("gemini says hi", ChatRepository.parseGeminiResponse(body))
    }

    @Test
    fun `parseGeminiResponse falls back when candidates are missing`() {
        assertEquals("Empty response from provider", ChatRepository.parseGeminiResponse("{}"))
    }

    // ─── HuggingFace ───────────────────────────────────────────────────────

    @Test
    fun `huggingface request body is a single inputs field, single-turn only`() {
        val body = ChatRepository.buildHuggingFaceRequestBodyJson("what is 2+2")
        assertEquals("what is 2+2", body.getString("inputs"))
        assertEquals(1, body.length())
    }

    @Test
    fun `parseHuggingFaceResponse handles the array-of-generated_text shape`() {
        val body = """[{"generated_text":"the answer is 4"}]"""
        assertEquals("the answer is 4", ChatRepository.parseHuggingFaceResponse(body))
    }

    @Test
    fun `parseHuggingFaceResponse handles the object-with-generated_text shape`() {
        val body = """{"generated_text":"single object reply"}"""
        assertEquals("single object reply", ChatRepository.parseHuggingFaceResponse(body))
    }

    @Test
    fun `parseHuggingFaceResponse surfaces the object error field when generated_text is absent`() {
        val body = """{"error":"model is loading"}"""
        assertEquals("model is loading", ChatRepository.parseHuggingFaceResponse(body))
    }

    @Test
    fun `parseHuggingFaceResponse falls back on unparsable content`() {
        assertEquals("Empty response from provider", ChatRepository.parseHuggingFaceResponse("not json at all"))
    }

    // ─── Ollama ────────────────────────────────────────────────────────────

    @Test
    fun `ollama request body sets stream false and nests temperature under options`() {
        val config = ChatProviderConfig(id = "ollama", model = "llama3.2", baseUrl = "http://10.0.2.2:11434")
        val body = ChatRepository.buildOllamaRequestBodyJson(config, history, 0.5)
        assertEquals("llama3.2", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(0.5, body.getJSONObject("options").getDouble("temperature"), 0.0001)
        assertEquals(2, body.getJSONArray("messages").length())
    }

    @Test
    fun `ollama request body falls back to the catalog default model when blank`() {
        val config = ChatProviderConfig(id = "ollama", model = "", baseUrl = "http://10.0.2.2:11434")
        val body = ChatRepository.buildOllamaRequestBodyJson(config, history, 0.7)
        assertEquals("llama3.2", body.getString("model"))
    }

    @Test
    fun `parseOllamaResponse extracts message content`() {
        val body = """{"message":{"role":"assistant","content":"ollama reply"}}"""
        assertEquals("ollama reply", ChatRepository.parseOllamaResponse(body))
    }

    @Test
    fun `parseOllamaResponse falls back when message is missing`() {
        assertEquals("Empty response from provider", ChatRepository.parseOllamaResponse("{}"))
    }

    // ─── OpenAI-style (shared by OpenAI, OpenRouter, Groq, Custom) ─────────

    @Test
    fun `parseOpenAiStyleResponse extracts choices 0 message content`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"openai-style reply"}}]}"""
        assertEquals("openai-style reply", ChatRepository.parseOpenAiStyleResponse(body))
    }

    @Test
    fun `parseOpenAiStyleResponse falls back when choices are missing`() {
        assertEquals("Empty response from provider", ChatRepository.parseOpenAiStyleResponse("{}"))
    }
}
