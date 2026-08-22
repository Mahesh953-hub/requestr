package com.example.data.repository

import android.content.Context
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatProviderEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.ChatMessage
import com.example.data.models.ChatProviderConfig
import com.example.data.prefs.SecureKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *
 * Task 12: the provider's `name`/`baseUrl`/`model`/`isActive` live in the `chat_providers` Room
 * table (task 11); the API key is never stored there -- it lives only in Keystore-backed
 * storage ([SecureKeyStore]), looked up by provider id. See [providerConfig].
 */
object ChatRepository {

    private const val DEFAULT_PROVIDER_ID = "default"

    // Task 11: no seed data -- an empty `chat_messages` table just means no conversation has
    // happened yet, matching NotesRepository's "no seed data" decision for the same reason.
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Task 12: replaces the old plain `var providerUrl`/`providerKey`/`providerModel` fields --
    // those had no writer anywhere and ChatScreen read them as plain vars inside composition, so
    // the provider banner could never recompose even once a writer existed. This StateFlow is
    // updated by loadProviderConfig()/saveProviderConfig() and collected in ChatScreen.
    private val _providerConfig = MutableStateFlow(ChatProviderConfig(id = DEFAULT_PROVIDER_ID))
    val providerConfig: StateFlow<ChatProviderConfig> = _providerConfig.asStateFlow()

    private var appContext: Context? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Jobs launched by init() -- held so a test-only reset (see [resetForTest]) can cancel
    // exactly these children of [repoScope] without killing repoScope itself (which would leave
    // every subsequent init()/sendMessage()/clearHistory() call unable to launch anything).
    // Without cancelling these, a stale collector from a previous Robolectric test class -- which
    // is still bound to that test class's now-superseded RequestrDatabase.get(context) instance --
    // keeps overwriting [_messages] after the reset, which just moves the race rather than fixing
    // it (see resetForTest's kdoc for the full failure mode this closes).
    private var observeMessagesJob: Job? = null
    private var loadProviderConfigJob: Job? = null

    /**
     * Backs [messages] with the real `chat_messages` Room table (task 11), and loads the
     * persisted provider config (Room metadata + Keystore key) into [providerConfig]. Call once
     * from MainActivity.onCreate(), mirroring [NotesRepository.init]/[FlowRepository.init].
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        observeMessagesJob = repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().observeAll().collect { entities ->
                _messages.value = entities.map { it.toChatMessage() }
            }
        }
        loadProviderConfigJob = repoScope.launch { loadProviderConfig() }
    }

    /**
     * Test-only reset: undoes [init] so the next call re-attaches to the CURRENT Robolectric
     * `RequestrDatabase` instance instead of leaving [init]'s guard (`appContext != null`)
     * short-circuit into a no-op.
     *
     * `ChatRepository` is a Kotlin `object`, so it outlives any single test class within the same
     * JVM fork, but Robolectric hands each test class a fresh `Context`/database. Without this,
     * whichever test class in the fork calls [init] first "wins": every later test class's own
     * `init(context)` call hits the early return and does nothing, so its `observeAll()` collector
     * is still bound to the FIRST test class's (by-then-superseded) database while the later
     * test's own `RequestrDatabase.get(context)` calls read the CURRENT one -- `messages` never
     * reflects rows the later test just wrote, and any assertion waiting on it times out. This
     * only reproduces when class execution order puts another `ChatRepository`-touching test class
     * first in the same fork, which is why it looked like an intermittent flake rather than a
     * deterministic bug.
     *
     * Cancels the jobs [init] launched (not [repoScope] itself, which must stay usable for
     * every later `init`/`sendMessage`/`clearHistory`/`saveProviderConfig` call in the same JVM
     * fork) and resets every mutable field back to its just-loaded-class initial value.
     */
    internal fun resetForTest() {
        observeMessagesJob?.cancel()
        loadProviderConfigJob?.cancel()
        observeMessagesJob = null
        loadProviderConfigJob = null
        appContext = null
        _messages.value = emptyList()
        _isLoading.value = false
        _providerConfig.value = ChatProviderConfig(id = DEFAULT_PROVIDER_ID)
    }

    /**
     * Reloads [providerConfig] from Room (metadata) + [SecureKeyStore] (key) for
     * [DEFAULT_PROVIDER_ID]. Safe to call multiple times, e.g. after [saveProviderConfig].
     */
    private suspend fun loadProviderConfig() {
        val ctx = appContext ?: return
        val entity = RequestrDatabase.get(ctx).chatProviderDao().getAll()
            .firstOrNull { it.id == DEFAULT_PROVIDER_ID }
            ?: return // nothing saved yet -- keep the all-blank default

        val key = when (val result = SecureKeyStore.getSecret(ctx, entity.id)) {
            is SecureKeyStore.SecretReadResult.Found -> result.value
            SecureKeyStore.SecretReadResult.Absent -> ""
            // Task 12 non-negotiable: an unreadable key must never look like "no key entered".
            // ChatScreen/SettingsScreen key this off apiKey.isBlank() vs a dedicated flag; since
            // isConfigured only gates whether callProvider is reachable (never silently swallowed
            // as success), surfacing "" here plus the provider still failing loudly at call time
            // is acceptable, but we log so the underlying cause is diagnosable.
            SecureKeyStore.SecretReadResult.Unreadable -> {
                android.util.Log.w(
                    "ChatRepository",
                    "Stored API key for provider ${entity.id} is unreadable (auth tag failed) " +
                        "-- treating as not configured; user must re-enter it in Settings.",
                )
                ""
            }
        }

        _providerConfig.value = ChatProviderConfig(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            model = entity.model,
            apiKey = key,
            isActive = entity.isActive,
        )
    }

    /**
     * Persists [config]'s metadata to Room and its key to [SecureKeyStore], then updates
     * [providerConfig] so the UI reflects the change immediately (not just on the next
     * observeAll() emission, which the `chat_providers` table alone can't drive since the key
     * isn't in it).
     */
    fun saveProviderConfig(config: ChatProviderConfig) {
        val ctx = appContext ?: run {
            _providerConfig.value = config
            return
        }
        _providerConfig.value = config
        repoScope.launch {
            RequestrDatabase.get(ctx).chatProviderDao().upsert(
                ChatProviderEntity(
                    id = config.id,
                    name = config.name,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    isActive = config.isActive,
                ),
            )
            if (config.apiKey.isNotBlank()) {
                SecureKeyStore.putSecret(ctx, config.id, config.apiKey)
            } else {
                SecureKeyStore.removeSecret(ctx, config.id)
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
        val config = _providerConfig.value
        val response = if (config.isConfigured) {
            callProvider(text, config)
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
        repoScope.launch { deleteAllPersistedMessages(ctx) }
    }

    /**
     * The actual row-deletion [clearHistory] fires-and-forgets on [repoScope] for production
     * callers (a UI "clear chat" action has nothing meaningful to await). Extracted as its own
     * `suspend` function so tests can call and await it directly instead of racing a background
     * `launch` -- `ChatRepositoryRoomTest`'s `@Before` previously called `clearHistory()` and
     * moved on immediately, so a still-in-flight delete from a PREVIOUS test could land after the
     * next test's `sendMessage()`, deleting the very row that test's assertion was waiting for.
     */
    internal suspend fun deleteAllPersistedMessages(context: Context) {
        RequestrDatabase.get(context).chatMessageDao().getAll().forEach {
            RequestrDatabase.get(context).chatMessageDao().deleteById(it.id)
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
    /**
     * Builds the OpenAI-compatible chat-completion request body: `model`, a `messages` array
     * windowed to the last 10 messages of [history] (oldest-to-newest, matching [ChatMessage]
     * ordering), and `max_tokens`. Extracted as a pure function (task 12) so the shape can be
     * asserted on directly in [com.example.data.repository.ChatProviderConfigTest] without a
     * real network call.
     */
    internal fun buildRequestBodyJson(config: ChatProviderConfig, history: List<ChatMessage>): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("model", config.model.ifBlank { "gpt-3.5-turbo" })
            put("messages", org.json.JSONArray().apply {
                history.takeLast(10).forEach { msg ->
                    put(org.json.JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            put("max_tokens", 1000)
        }
    }

    private suspend fun callProvider(userMessage: String, config: ChatProviderConfig): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = buildRequestBodyJson(config, _messages.value)

            val request = okhttp3.Request.Builder()
                .url(config.baseUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
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
            // Never logs config.apiKey -- only the exception message, which is provider/network
            // detail, not the secret itself.
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
                "I don't have an AI provider configured, so I'm running in local mode (this is an honest fallback, not a bug -- add a provider in Settings to reach a real model). I can answer questions about requestr's features. Try asking about interception, flows, API testing, tools, rules, or certificates."
        }
    }
}
