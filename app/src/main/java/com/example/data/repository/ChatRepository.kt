package com.example.data.repository

import android.content.Context
import com.example.data.local.ChatMessageEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Chat repository — manages AI chat conversations.
 *
 * Supports configurable API providers (OpenAI-compatible format) for
 * chat completion. Falls back to a local echo/mock response when no
 * provider is configured.
 */
object ChatRepository {

    // Task 11: no seed data -- an empty `chat_messages` table just means no conversation has
    // happened yet, matching NotesRepository's "no seed data" decision for the same reason.
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var providerUrl: String = ""
    var providerKey: String = ""
    var providerModel: String = ""

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Backs [messages] with the real `chat_messages` Room table (task 11). Call once from
     * MainActivity.onCreate(), mirroring [NotesRepository.init]/[FlowRepository.init].
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().observeAll().collect { entities ->
                _messages.value = entities.map { it.toChatMessage() }
            }
        }
    }

    /** Sends a user message and generates a response. */
    suspend fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message
        val userMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        _messages.value = _messages.value + userMsg
        persist(userMsg)

        _isLoading.value = true

        // Generate response
        val response = if (providerUrl.isNotBlank() && providerKey.isNotBlank()) {
            callProvider(text)
        } else {
            // Local fallback: echo with context awareness
            generateLocalResponse(text)
        }

        val assistantMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "assistant",
            content = response,
            timestamp = System.currentTimeMillis(),
        )
        _messages.value = _messages.value + assistantMsg
        persist(assistantMsg)
        _isLoading.value = false
    }

    /** Clears the chat history, both in-memory and in Room. */
    fun clearHistory() {
        _messages.value = emptyList()
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().getAll().forEach {
                RequestrDatabase.get(ctx).chatMessageDao().deleteById(it.id)
            }
        }
    }

    private fun persist(message: ChatMessage) {
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().upsert(message.toEntity())
        }
    }

    private fun ChatMessage.toEntity() = ChatMessageEntity(
        id = id, role = role, content = content, timestamp = timestamp
    )

    private fun ChatMessageEntity.toChatMessage() = ChatMessage(
        id = id, role = role, content = content, timestamp = timestamp
    )

    /**
     * Calls an OpenAI-compatible chat completion API.
     * Works with OpenAI, Anthropic (via proxy), or any compatible endpoint.
     */
    private suspend fun callProvider(userMessage: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = org.json.JSONObject().apply {
                put("model", providerModel.ifBlank { "gpt-3.5-turbo" })
                put("messages", org.json.JSONArray().apply {
                    // Include conversation history for context
                    _messages.value.takeLast(10).forEach { msg ->
                        put(org.json.JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        })
                    }
                })
                put("max_tokens", 1000)
            }

            val request = okhttp3.Request.Builder()
                .url(providerUrl)
                .addHeader("Authorization", "Bearer $providerKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "No response")
                    ?: "Empty response from provider"
            } else {
                "Error ${response.code}: ${response.message}"
            }
        } catch (e: Exception) {
            "Connection failed: ${e.message}. Check provider URL and API key in Settings."
        }
    }

    /**
     * Local fallback response generator when no provider is configured.
     * Provides contextually relevant answers about the app's own features.
     */
    private fun generateLocalResponse(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") ->
                "Hey! I'm requestr's built-in assistant. I can help you with API testing, interception, flows, and tools. What do you need?"
            lower.contains("intercept") || lower.contains("capture") ->
                "To start capturing traffic: go to the Intercept tab, tap the Record button, and requestr will route traffic through the VPN tunnel. Make sure you have the CA certificate installed for HTTPS."
            lower.contains("flow") || lower.contains("automation") ->
                "Flows let you chain API calls, tools, and conditions. Go to the Flow tab, tap + to add nodes, then drag output ports to connect them. Tap Run to execute."
            lower.contains("api") || lower.contains("test") ->
                "The API Testr lets you send HTTP requests with headers, auth, and body. You can save requests, chain them (extract tokens from responses), and run test assertions."
            lower.contains("tool") ->
                "The Tool Library has 35+ built-in tools: base64, hex, JWT decoder, hash functions, JSON formatter, URL encoder, and more. You can also create custom tools with regex replace."
            lower.contains("cert") || lower.contains("ca") ->
                "The CA certificate is in Settings > CA Certificate. You can export it as .pem, .0, .cert, or .p12 format. For HTTPS interception, install it in the system trust store (requires root)."
            lower.contains("rule") || lower.contains("block") || lower.contains("redirect") ->
                "Rules let you block, redirect, or modify intercepted traffic. Go to Intercept > filter icon > Rules & Modifiers. There are 5 default rules pre-loaded for ad blocking and auth logging."
            lower.contains("help") ->
                "I can help with: interception, API testing, flows/automation, tools, rules, certificates, and app navigation. What specific feature do you need help with?"
            else ->
                "I don't have an AI provider configured, so I'm running in local mode. I can answer questions about requestr's features. Try asking about interception, flows, API testing, tools, rules, or certificates."
        }
    }
}
