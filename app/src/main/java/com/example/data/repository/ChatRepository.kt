package com.example.data.repository

import android.content.Context
import com.example.data.local.ChatConversationEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatProviderEntity
import com.example.data.local.RequestrDatabase
import com.example.data.models.ChatMessage
import com.example.data.models.ChatProviderConfig
import com.example.data.models.ChatProviderKind
import com.example.data.models.deriveChatTitle
import com.example.data.models.validateProviderFields
import com.example.data.prefs.ChatSettingsStore
import com.example.data.prefs.SecureKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Chat repository — manages Reqy Assistant chat conversations.
 *
 * Supports multiple provider *kinds* (see [ChatProviderKind]: OpenAI, Anthropic, Gemini,
 * OpenRouter, Groq (Free), HuggingFace (Free), Ollama (Local), + Custom), each keyed by
 * [ChatProviderConfig.id] == [ChatProviderKind.id], with exactly one marked `isActive` in Room
 * at a time (`chat_providers.isActive`) -- see [saveProviderConfig]/[loadProviderConfig]. Falls
 * back to a local echo/mock response when no provider is active/configured, or when Local Mode
 * ([ChatSettingsStore.isLocalModeEnabled]) is explicitly turned on.
 *
 * Task 12: the provider's `name`/`baseUrl`/`model`/`isActive` live in the `chat_providers` Room
 * table (task 11); the API key is never stored there -- it lives only in Keystore-backed
 * storage ([SecureKeyStore]), looked up by provider id. See [providerConfig].
 */

/**
 * Outcome of [ChatRepository.saveProviderConfig]. The UI must not show a success confirmation
 * (e.g. "Saved and activated.") until it has actually received [Success] -- see that function's
 * kdoc for why an optimistic in-memory update alone is not enough to claim success.
 */
sealed interface SaveProviderResult {
    object Success : SaveProviderResult
    data class Failure(val reason: String) : SaveProviderResult
}

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

    // Reqy Assistant completeness pass, item 1: title override is now per-conversation (see
    // ChatConversationEntity.titleIsOverride/title in Room) rather than a single global
    // ChatSettingsStore key -- a single global override made no sense once multiple
    // conversations exist. `null` means "derive it" for the ACTIVE conversation.
    private val _titleOverride = MutableStateFlow<String?>(null)
    val titleOverride: StateFlow<String?> = _titleOverride.asStateFlow()

    // Every known conversation, most-recently-updated first -- backs the drawer's "AI History"
    // list.
    private val _conversations = MutableStateFlow<List<ChatConversationEntity>>(emptyList())
    val conversations: StateFlow<List<ChatConversationEntity>> = _conversations.asStateFlow()

    // Which conversation [messages]/[titleOverride] currently reflect. Empty string means "no
    // conversation switched to yet" -- only true for the brief window before init()'s
    // ensureActiveConversation() completes.
    private val _activeConversationId = MutableStateFlow("")
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    // When the CURRENT chat session started (reset by startNewChat()/switchToConversation()) --
    // feeds the placeholder title shown before the first message exists, e.g.
    // "New chat -- 6:02 PM".
    private var sessionStartedAt: Long = System.currentTimeMillis()
    fun currentSessionStartedAt(): Long = sessionStartedAt

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
    private var observeConversationsJob: Job? = null
    private var loadProviderConfigJob: Job? = null

    /**
     * Backs [messages] with the real `chat_messages` Room table (task 11), loads the active
     * provider's config (Room metadata + Keystore key) into [providerConfig], loads every saved
     * conversation into [conversations], and switches to the most-recently-updated one (creating
     * one if none exist yet -- see [ensureActiveConversation]). Call once from
     * MainActivity.onCreate(), mirroring [NotesRepository.init]/[FlowRepository.init].
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        observeConversationsJob = repoScope.launch {
            RequestrDatabase.get(ctx).chatConversationDao().observeAll().collect { rows ->
                _conversations.value = rows
            }
        }
        loadProviderConfigJob = repoScope.launch {
            loadProviderConfig()
            ensureActiveConversation(ctx)
        }
    }

    /**
     * Guarantees at least one conversation exists and is active -- picks the most-recently-
     * updated saved conversation (e.g. the one MIGRATION_16_17 folded old messages into), or
     * creates a fresh empty one if the table is genuinely empty (fresh install, or a v16 install
     * that never sent a message so MIGRATION_16_17's `WHERE EXISTS` guard skipped the insert).
     */
    private suspend fun ensureActiveConversation(ctx: Context) {
        val dao = RequestrDatabase.get(ctx).chatConversationDao()
        val target = dao.getAll().firstOrNull() ?: run {
            val now = System.currentTimeMillis()
            val fresh = ChatConversationEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = "New chat",
                titleIsOverride = false,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsert(fresh)
            fresh
        }
        switchToConversation(target.id)
    }

    /**
     * Switches [messages]/[titleOverride]/[currentSessionStartedAt] to reflect [conversationId] --
     * used both by [ensureActiveConversation] on startup and by the drawer's conversation list
     * (tap an entry to switch to it). Cancels and relaunches [observeMessagesJob] against the new
     * conversation's own `chat_messages` rows, following the same cancel-and-relaunch idiom
     * `init()` already used for a single conversation before this pass.
     */
    fun switchToConversation(conversationId: String) {
        val ctx = appContext ?: return
        _activeConversationId.value = conversationId
        observeMessagesJob?.cancel()
        observeMessagesJob = repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().observeByConversation(conversationId).collect { entities ->
                _messages.value = entities.map { it.toChatMessage() }
            }
        }
        repoScope.launch {
            val conv = RequestrDatabase.get(ctx).chatConversationDao().getById(conversationId)
            sessionStartedAt = conv?.createdAt ?: System.currentTimeMillis()
            _titleOverride.value = if (conv?.titleIsOverride == true) conv.title else null
        }
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
        observeConversationsJob?.cancel()
        loadProviderConfigJob?.cancel()
        observeMessagesJob = null
        observeConversationsJob = null
        loadProviderConfigJob = null
        appContext = null
        _messages.value = emptyList()
        _isLoading.value = false
        _providerConfig.value = ChatProviderConfig(id = DEFAULT_PROVIDER_ID)
        _titleOverride.value = null
        _conversations.value = emptyList()
        _activeConversationId.value = ""
        sessionStartedAt = System.currentTimeMillis()
    }

    /**
     * Reloads [providerConfig] from Room (metadata) + [SecureKeyStore] (key) for whichever
     * `chat_providers` row currently has `isActive = true`. Safe to call multiple times, e.g.
     * after [saveProviderConfig]. Multiple rows (one per [ChatProviderKind]) may exist at once
     * -- only the active one feeds [providerConfig]/`callProvider`.
     */
    private suspend fun loadProviderConfig() {
        val ctx = appContext ?: return
        val entity = RequestrDatabase.get(ctx).chatProviderDao().getAll()
            .firstOrNull { it.isActive }
            ?: return // nothing saved yet -- keep the all-blank default

        _providerConfig.value = entity.toConfig(readKey(ctx, entity.id))
    }

    private suspend fun readKey(ctx: Context, id: String): String {
        return when (val result = SecureKeyStore.getSecret(ctx, id)) {
            is SecureKeyStore.SecretReadResult.Found -> result.value
            SecureKeyStore.SecretReadResult.Absent -> ""
            // Task 12 non-negotiable: an unreadable key must never look like "no key entered".
            // ChatScreen/Settings key this off apiKey.isBlank() vs a dedicated flag; since
            // isConfigured only gates whether callProvider is reachable (never silently swallowed
            // as success), surfacing "" here plus the provider still failing loudly at call time
            // is acceptable, but we log so the underlying cause is diagnosable.
            SecureKeyStore.SecretReadResult.Unreadable -> {
                android.util.Log.w(
                    "ChatRepository",
                    "Stored API key for provider $id is unreadable (auth tag failed) " +
                        "-- treating as not configured; user must re-enter it in Settings.",
                )
                ""
            }
        }
    }

    private fun ChatProviderEntity.toConfig(apiKey: String) = ChatProviderConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        isActive = isActive,
    )

    /**
     * Loads a specific provider kind's saved config (Room metadata + Keystore key) without
     * touching [providerConfig] -- used by the Settings provider-chip row to pre-fill fields
     * when the user switches chips, without discarding whatever chip is currently active.
     * Falls back to [ChatProviderKind]'s catalog defaults (endpoint/model) when nothing has
     * been saved for that id yet.
     */
    suspend fun getProviderConfigById(id: String): ChatProviderConfig {
        val kind = ChatProviderKind.fromId(id)
        val ctx = appContext
        val key = if (ctx != null) readKey(ctx, id) else ""
        val entity = ctx?.let { RequestrDatabase.get(it).chatProviderDao().getAll().firstOrNull { row -> row.id == id } }
        return if (entity != null) {
            entity.toConfig(key)
        } else {
            ChatProviderConfig(
                id = id,
                name = kind.displayName,
                baseUrl = kind.defaultBaseUrl,
                model = kind.defaultModel,
                apiKey = key,
                isActive = false,
            )
        }
    }

    /**
     * All saved `chat_providers` rows as [ChatProviderConfig] (no API key populated -- this is
     * only used to render each chip's "configured"/"active" indicator, never to call a provider).
     */
    fun observeProviderEntities(): Flow<List<ChatProviderConfig>> {
        val ctx = appContext ?: return flowOf(emptyList())
        return RequestrDatabase.get(ctx).chatProviderDao().observeAll().map { rows ->
            rows.map { it.toConfig(apiKey = "") }
        }
    }

    /**
     * Persists [config]'s metadata to Room and its key to [SecureKeyStore], marks it the sole
     * active provider (deactivating every other saved row), then updates [providerConfig] so the
     * UI reflects the change immediately (not just on the next `observeAll()` emission, which
     * the `chat_providers` table alone can't drive since the key isn't in it).
     *
     * Suspends until BOTH the Room write and the Keystore write have actually finished, and
     * returns whether they succeeded -- callers MUST NOT show a "saved" confirmation before this
     * returns [SaveProviderResult.Success]. [providerConfig] is still updated optimistically the
     * instant this is called (so a responsive UI can re-render immediately), but that optimistic
     * value is rolled back to whatever it was before this call if either write throws, so a
     * failed save never leaves a phantom "active" provider sitting in memory that Room/Keystore
     * don't actually agree with.
     */
    suspend fun saveProviderConfig(config: ChatProviderConfig): SaveProviderResult {
        val activeConfig = config.copy(isActive = true)
        val previous = _providerConfig.value
        _providerConfig.value = activeConfig
        val ctx = appContext
        if (ctx == null) {
            _providerConfig.value = previous
            return SaveProviderResult.Failure("The app isn't ready yet. Try again in a moment.")
        }
        return try {
            withContext(Dispatchers.IO) {
                val dao = RequestrDatabase.get(ctx).chatProviderDao()
                dao.getAll().forEach { existing ->
                    if (existing.id != activeConfig.id && existing.isActive) {
                        dao.upsert(existing.copy(isActive = false))
                    }
                }
                dao.upsert(
                    ChatProviderEntity(
                        id = activeConfig.id,
                        name = activeConfig.name,
                        baseUrl = activeConfig.baseUrl,
                        model = activeConfig.model,
                        isActive = true,
                    ),
                )
                if (activeConfig.apiKey.isNotBlank()) {
                    SecureKeyStore.putSecret(ctx, activeConfig.id, activeConfig.apiKey)
                } else {
                    SecureKeyStore.removeSecret(ctx, activeConfig.id)
                }
            }
            SaveProviderResult.Success
        } catch (e: Exception) {
            _providerConfig.value = previous
            SaveProviderResult.Failure(
                "Couldn't save provider settings: ${redactSecretFromMessage(e.message, activeConfig.apiKey)}. Nothing was changed -- try again.",
            )
        }
    }

    /**
     * Drawer/header "Rename" -- overrides the derived title for [conversationId] (the ACTIVE
     * conversation by default) until it's cleared again with `title = null`, in which case the
     * title goes back to being derived from that conversation's own messages (see
     * [recomputeTitleIfNotOverridden]).
     */
    fun renameChat(title: String?, conversationId: String = _activeConversationId.value) {
        val trimmed = title?.trim()?.takeIf { it.isNotBlank() }
        if (conversationId == _activeConversationId.value) {
            _titleOverride.value = trimmed
        }
        val ctx = appContext ?: return
        repoScope.launch {
            val dao = RequestrDatabase.get(ctx).chatConversationDao()
            val existing = dao.getById(conversationId) ?: return@launch
            if (trimmed != null) {
                dao.upsert(existing.copy(title = trimmed, titleIsOverride = true, updatedAt = System.currentTimeMillis()))
            } else {
                dao.upsert(existing.copy(titleIsOverride = false, updatedAt = System.currentTimeMillis()))
                recomputeTitleIfNotOverridden(ctx, conversationId)
            }
        }
    }

    /**
     * Header/drawer "New Chat" -- instant, no naming modal (see the mockup's legend: "the old
     * 'New Chat' naming-modal concept is fully removed"). Creates and switches to a genuinely
     * new [ChatConversationEntity] row rather than clearing the current conversation's history --
     * the previous conversation stays intact in the drawer's "AI History" list.
     *
     * [messages]/[titleOverride] are cleared synchronously first (optimistic UI update) so the
     * "instant" feel is preserved even though creating the Room row and re-pointing
     * [observeMessagesJob] at it happens asynchronously on [repoScope].
     */
    fun startNewChat() {
        val newId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        _messages.value = emptyList()
        _titleOverride.value = null
        sessionStartedAt = now
        _activeConversationId.value = newId
        val ctx = appContext ?: return
        observeMessagesJob?.cancel()
        repoScope.launch {
            RequestrDatabase.get(ctx).chatConversationDao().upsert(
                ChatConversationEntity(id = newId, title = "New chat", titleIsOverride = false, createdAt = now, updatedAt = now),
            )
            observeMessagesJob = repoScope.launch {
                RequestrDatabase.get(ctx).chatMessageDao().observeByConversation(newId).collect { entities ->
                    _messages.value = entities.map { it.toChatMessage() }
                }
            }
        }
    }

    /**
     * Drawer's per-conversation delete. Deleting the currently-ACTIVE conversation switches to
     * another existing one (or creates a fresh empty one if it was the last conversation --
     * never leaves the app with zero conversations, see [ensureActiveConversation]).
     */
    fun deleteConversation(conversationId: String) {
        val ctx = appContext ?: return
        repoScope.launch {
            val convDao = RequestrDatabase.get(ctx).chatConversationDao()
            RequestrDatabase.get(ctx).chatMessageDao().deleteByConversation(conversationId)
            convDao.deleteById(conversationId)
            if (conversationId == _activeConversationId.value) {
                ensureActiveConversation(ctx)
            }
        }
    }

    /** Sends a user message and generates a response. */
    suspend fun sendMessage(text: String) {
        if (text.isBlank()) return
        // init() launches ensureActiveConversation() fire-and-forget on repoScope, so a caller
        // that awaits init() and immediately calls sendMessage() (every real caller, and
        // ChatRepositoryRoomTest) can otherwise race it and see the placeholder "" id. Join the
        // in-flight job (a no-op once it's already completed) instead of re-running
        // ensureActiveConversation ourselves, which would risk creating a second conversation
        // row if both ran concurrently.
        loadProviderConfigJob?.join()
        val conversationId = _activeConversationId.value

        // Add user message
        val userMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        _messages.value = _messages.value + userMsg
        persist(userMsg, conversationId)

        _isLoading.value = true

        // Generate response
        val config = _providerConfig.value
        val localModeForced = appContext?.let { ChatSettingsStore.isLocalModeEnabled(it) } ?: false
        val response = if (config.isConfigured && !localModeForced) {
            callProvider(config)
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
        persist(assistantMsg, conversationId)
        _isLoading.value = false

        appContext?.let { notifyAssistantReplyIfEnabled(it, response) }
    }

    /**
     * Lock panel's "Edit this message and resend" (see the mockup's `usermsg-edit` action):
     * drops [originalId] and every message after it (its now-stale assistant reply included),
     * then resends [newText] as a fresh user message.
     */
    suspend fun editMessageAndResend(originalId: String, newText: String) {
        if (newText.isBlank()) return
        val current = _messages.value
        val idx = current.indexOfFirst { it.id == originalId }
        if (idx == -1) return
        val toRemove = current.subList(idx, current.size)
        _messages.value = current.subList(0, idx)
        val ctx = appContext
        if (ctx != null) {
            val dao = RequestrDatabase.get(ctx).chatMessageDao()
            toRemove.forEach { dao.deleteById(it.id) }
        }
        sendMessage(newText)
    }

    /** Lock panel's "Delete this message" (`usermsg-delete`) / multi-select delete. */
    fun deleteMessage(id: String) {
        deleteMessages(setOf(id))
    }

    /** Bulk delete for the lock panel's "Select" multi-select mode (`usermsg-select`). */
    fun deleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        _messages.value = _messages.value.filterNot { it.id in ids }
        val ctx = appContext ?: return
        repoScope.launch {
            val dao = RequestrDatabase.get(ctx).chatMessageDao()
            ids.forEach { dao.deleteById(it) }
        }
    }

    /**
     * Clears the ACTIVE conversation's history only, both in-memory and in Room -- kept name-
     * and signature-compatible with every pre-multi-conversation caller/test (there was only
     * ever one conversation before this pass). See [clearAllConversations] for "wipe every
     * conversation", which the Settings/drawer UI must offer as a clearly DIFFERENT action now
     * that "clear history" is ambiguous between "this chat" and "everything".
     */
    fun clearHistory() {
        _messages.value = emptyList()
        val ctx = appContext ?: return
        val conversationId = _activeConversationId.value
        repoScope.launch {
            deleteAllPersistedMessages(ctx, conversationId)
            recomputeTitleIfNotOverridden(ctx, conversationId)
        }
    }

    /**
     * Settings/drawer "Clear ALL conversations" -- wipes every conversation's messages AND the
     * conversation rows themselves, then creates one fresh empty conversation so the app never
     * ends up with zero conversations (mirrors [ensureActiveConversation]'s own guarantee).
     */
    fun clearAllConversations() {
        _messages.value = emptyList()
        val ctx = appContext ?: return
        repoScope.launch {
            val convDao = RequestrDatabase.get(ctx).chatConversationDao()
            val msgDao = RequestrDatabase.get(ctx).chatMessageDao()
            convDao.getAll().forEach { msgDao.deleteByConversation(it.id) }
            convDao.getAll().forEach { convDao.deleteById(it.id) }
            ensureActiveConversation(ctx)
        }
    }

    /**
     * The actual row-deletion [clearHistory] fires-and-forgets on [repoScope] for production
     * callers (a UI "clear chat" action has nothing meaningful to await). Extracted as its own
     * `suspend` function so tests can call and await it directly instead of racing a background
     * `launch` -- `ChatRepositoryRoomTest`'s `@Before` previously called `clearHistory()` and
     * moved on immediately, so a still-in-flight delete from a PREVIOUS test could land after the
     * next test's `sendMessage()`, deleting the very row that test's assertion was waiting for.
     */
    internal suspend fun deleteAllPersistedMessages(context: Context, conversationId: String) {
        RequestrDatabase.get(context).chatMessageDao().deleteByConversation(conversationId)
    }

    /**
     * Keeps [conversationId]'s Room `title` in sync with its own messages -- called after every
     * new message is persisted into it, and after an explicit rename is cleared back to "derive
     * it". No-ops once the user has explicitly renamed the conversation ([titleIsOverride]).
     */
    private suspend fun recomputeTitleIfNotOverridden(ctx: Context, conversationId: String) {
        val convDao = RequestrDatabase.get(ctx).chatConversationDao()
        val conv = convDao.getById(conversationId) ?: return
        if (conv.titleIsOverride) return
        val msgs = RequestrDatabase.get(ctx).chatMessageDao().getByConversation(conversationId).map { it.toChatMessage() }
        val derived = deriveChatTitle(msgs, null, conv.createdAt)
        if (derived != conv.title) {
            convDao.upsert(conv.copy(title = derived, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun persist(message: ChatMessage, conversationId: String) {
        val ctx = appContext ?: return
        repoScope.launch {
            RequestrDatabase.get(ctx).chatMessageDao().upsert(message.toEntity(conversationId))
            recomputeTitleIfNotOverridden(ctx, conversationId)
        }
    }

    private fun ChatMessage.toEntity(conversationId: String) = ChatMessageEntity(
        id = id, role = role, content = content, timestamp = timestamp, conversationId = conversationId,
    )

    private fun ChatMessageEntity.toChatMessage() = ChatMessage(
        id = id, role = role, content = content, timestamp = timestamp
    )

    private fun notifyAssistantReplyIfEnabled(ctx: Context, replyText: String) {
        if (!ChatSettingsStore.areNotificationsEnabled(ctx)) return
        try {
            com.example.data.notifications.ReqyNotifier.notifyAssistantReply(ctx, replyText)
        } catch (e: Exception) {
            android.util.Log.w("ChatRepository", "Reqy reply notification failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // OpenAI-compatible request/response (OpenAI, OpenRouter, Groq, + Custom -- see
    // ChatProviderKind.isOpenAiCompatible).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the OpenAI-compatible chat-completion request body: `model`, a `messages` array
     * windowed to the last 10 messages of [history] (oldest-to-newest, matching [ChatMessage]
     * ordering), and `max_tokens`. Extracted as a pure function (task 12) so the shape can be
     * asserted on directly in [com.example.data.repository.ChatProviderConfigTest] without a
     * real network call. Locked shape -- do not add/rename/remove keys here; provider-specific
     * additions (system instructions, temperature) are layered on top in
     * [buildOpenAiCompatibleRequest] instead, which callers other than that test always go
     * through.
     */
    internal fun buildRequestBodyJson(config: ChatProviderConfig, history: List<ChatMessage>): JSONObject {
        return JSONObject().apply {
            put("model", config.model.ifBlank { "gpt-3.5-turbo" })
            put("messages", JSONArray().apply {
                history.takeLast(10).forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            put("max_tokens", 1000)
        }
    }

    private fun buildOpenAiCompatibleRequest(
        config: ChatProviderConfig,
        history: List<ChatMessage>,
        temperature: Double,
        instructions: String,
    ): Request {
        val body = buildRequestBodyJson(config, history)
        if (instructions.isNotBlank()) {
            val withSystem = JSONArray()
            withSystem.put(JSONObject().apply { put("role", "system"); put("content", instructions) })
            val existing = body.getJSONArray("messages")
            for (i in 0 until existing.length()) withSystem.put(existing.get(i))
            body.put("messages", withSystem)
        }
        body.put("temperature", temperature)
        val builder = Request.Builder()
            .url(config.baseUrl)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
        if (config.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        return builder.build()
    }

    internal fun parseOpenAiStyleResponse(body: String): String {
        val json = JSONObject(body)
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "No response")
            ?: "Empty response from provider"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Anthropic (`x-api-key` + `anthropic-version` headers, `/v1/messages` shape).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun buildAnthropicRequestBodyJson(
        config: ChatProviderConfig,
        history: List<ChatMessage>,
        instructions: String,
    ): JSONObject {
        return JSONObject().apply {
            put("model", config.model.ifBlank { ChatProviderKind.ANTHROPIC.defaultModel })
            put("max_tokens", 1024)
            if (instructions.isNotBlank()) put("system", instructions)
            put("messages", JSONArray().apply {
                history.takeLast(10).forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
            })
        }
    }

    private fun buildAnthropicRequest(
        config: ChatProviderConfig,
        history: List<ChatMessage>,
        instructions: String,
    ): Request {
        val body = buildAnthropicRequestBodyJson(config, history, instructions)
        return Request.Builder()
            .url(config.baseUrl.ifBlank { ChatProviderKind.ANTHROPIC.defaultBaseUrl })
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    internal fun parseAnthropicResponse(body: String): String {
        val json = JSONObject(body)
        return json.optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("text", "No response")
            ?: "Empty response from provider"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Gemini (`:generateContent?key=` URL, `contents[].parts[].text` shape, `model` role name).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun buildGeminiRequestBodyJson(history: List<ChatMessage>, instructions: String): JSONObject {
        return JSONObject().apply {
            if (instructions.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().put("text", instructions)) })
                })
            }
            put("contents", JSONArray().apply {
                history.takeLast(10).forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", JSONArray().apply { put(JSONObject().put("text", msg.content)) })
                    })
                }
            })
        }
    }

    internal fun buildGeminiUrl(baseUrl: String, model: String, apiKey: String): String {
        val base = baseUrl.ifBlank { ChatProviderKind.GEMINI.defaultBaseUrl }.trimEnd('/')
        val effectiveModel = model.ifBlank { ChatProviderKind.GEMINI.defaultModel }
        return "$base/$effectiveModel:generateContent?key=$apiKey"
    }

    private fun buildGeminiRequest(
        config: ChatProviderConfig,
        history: List<ChatMessage>,
        instructions: String,
    ): Request {
        val body = buildGeminiRequestBodyJson(history, instructions)
        return Request.Builder()
            .url(buildGeminiUrl(config.baseUrl, config.model, config.apiKey))
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    internal fun parseGeminiResponse(body: String): String {
        val json = JSONObject(body)
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "No response")
            ?: "Empty response from provider"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // HuggingFace Inference API (Free) -- single-turn `{"inputs": text}`, no auth required.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun buildHuggingFaceRequestBodyJson(userMessage: String): JSONObject =
        JSONObject().put("inputs", userMessage)

    private fun buildHuggingFaceRequest(config: ChatProviderConfig, userMessage: String): Request {
        val base = config.baseUrl.ifBlank { ChatProviderKind.HUGGINGFACE.defaultBaseUrl }.trimEnd('/')
        val model = config.model.ifBlank { ChatProviderKind.HUGGINGFACE.defaultModel }
        val builder = Request.Builder()
            .url("$base/$model")
            .addHeader("Content-Type", "application/json")
            .post(buildHuggingFaceRequestBodyJson(userMessage).toString().toRequestBody("application/json".toMediaTypeOrNull()))
        if (config.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        return builder.build()
    }

    internal fun parseHuggingFaceResponse(body: String): String {
        val trimmed = body.trim()
        return try {
            val text = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)?.optString("generated_text", "") ?: ""
            } else {
                val obj = JSONObject(trimmed)
                obj.optString("generated_text", obj.optString("error", ""))
            }
            text.ifBlank { "Empty response from provider" }
        } catch (e: Exception) {
            "Empty response from provider"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Ollama (Local) -- `/api/chat`, `{model, messages, stream:false}`, no auth.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun buildOllamaRequestBodyJson(
        config: ChatProviderConfig,
        history: List<ChatMessage>,
        temperature: Double,
    ): JSONObject {
        return JSONObject().apply {
            put("model", config.model.ifBlank { ChatProviderKind.OLLAMA.defaultModel })
            put("stream", false)
            put("options", JSONObject().put("temperature", temperature))
            put("messages", JSONArray().apply {
                history.takeLast(10).forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
            })
        }
    }

    private fun buildOllamaRequest(config: ChatProviderConfig, history: List<ChatMessage>, temperature: Double): Request {
        val base = config.baseUrl.ifBlank { ChatProviderKind.OLLAMA.defaultBaseUrl }.trimEnd('/')
        val body = buildOllamaRequestBodyJson(config, history, temperature)
        return Request.Builder()
            .url("$base/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    internal fun parseOllamaResponse(body: String): String {
        val json = JSONObject(body)
        return json.optJSONObject("message")?.optString("content", "No response")
            ?: "Empty response from provider"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Dispatch
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Renders [url] for logging with its ENTIRE query string (and fragment) stripped -- only
     * scheme, host, and path survive. Gemini puts its API key in `?key=...` (see
     * [buildGeminiUrl]), but the point of stripping the whole query rather than special-casing
     * the literal parameter name `key` is that we wire eight provider kinds (OpenAI, Anthropic,
     * Gemini, OpenRouter, Groq, HuggingFace, Ollama, Custom) and any of them -- especially
     * CUSTOM, where a user supplies an arbitrary base URL -- could carry a token/signature under
     * any query parameter name (`access_token`, `token`, `sig`, `api_key`, a presigned-URL
     * signature, ...). Redaction here is unconditional, not an opt-in per provider, so a ninth
     * provider added later doesn't need to remember to ask for it.
     */
    internal fun redactUrlForLogging(url: HttpUrl): String =
        url.newBuilder().query(null).fragment(null).build().toString()

    /**
     * Removes every occurrence of [secret] from [message] before it's shown to the user or
     * returned from [callProvider]. Defense in depth for a concrete leak path: OkHttp's
     * `Request.Builder().url(String)` throws `IllegalArgumentException` with the exact,
     * unparsed URL string in its message when a provider's base URL is malformed, and for
     * Gemini that string is `buildGeminiUrl(...)`'s own `.../generateContent?key=<apiKey>` --
     * so a malformed-URL exception could otherwise echo the API key straight into the chat
     * transcript (which IS persisted to Room and rendered on screen, unlike raw-logging, which
     * the user must explicitly opt into). Only redacts non-blank secrets; blank keys can't leak
     * anything and `"".replace(...)` would otherwise no-op harmlessly anyway.
     */
    internal fun redactSecretFromMessage(message: String?, secret: String): String {
        val safeMessage = message ?: "Unknown error"
        return if (secret.isNotBlank()) safeMessage.replace(secret, "[redacted]") else safeMessage
    }

    private suspend fun callProvider(config: ChatProviderConfig): String {
        val kind = ChatProviderKind.fromId(config.id)

        // Defensive re-check -- the UI already validates before enabling Send (see
        // ChatProviderCatalog.validateProviderFields), but callProvider must never trust that a
        // caller validated first (e.g. a stale/edited-out-of-band providerConfig).
        validateProviderFields(kind, config.baseUrl, config.apiKey)?.let { reason ->
            return "Configuration error: $reason"
        }

        val ctx = appContext
        val timeoutSeconds = ctx?.let { ChatSettingsStore.getTimeoutSeconds(it) }
            ?: ChatSettingsStore.DEFAULT_TIMEOUT_SECONDS
        val temperature = ctx?.let { ChatSettingsStore.getTemperature(it) }
            ?: ChatSettingsStore.DEFAULT_TEMPERATURE
        val rawLogging = ctx?.let { ChatSettingsStore.isRawLoggingEnabled(it) } ?: false
        val instructions = ctx?.let { ChatSettingsStore.getInstructions(it) }
            ?: ChatSettingsStore.DEFAULT_INSTRUCTIONS
        val history = _messages.value

        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout((timeoutSeconds * 2).toLong(), TimeUnit.SECONDS)
                .build()

            val request = when (kind) {
                ChatProviderKind.ANTHROPIC -> buildAnthropicRequest(config, history, instructions)
                ChatProviderKind.GEMINI -> buildGeminiRequest(config, history, instructions)
                ChatProviderKind.HUGGINGFACE -> buildHuggingFaceRequest(
                    config,
                    history.lastOrNull { it.role == "user" }?.content ?: "",
                )
                ChatProviderKind.OLLAMA -> buildOllamaRequest(config, history, temperature)
                else -> buildOpenAiCompatibleRequest(config, history, temperature, instructions)
            }

            if (rawLogging) {
                // Redact unconditionally -- see redactUrlForLogging's kdoc. The URL is NOT
                // safe to log as-is: Gemini's own convention puts the API key in `?key=...`.
                android.util.Log.d("ChatRepository", "Reqy request (raw logging ON) -> ${redactUrlForLogging(request.url)}")
            }

            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val bodyStr = response.body?.string() ?: ""
            if (rawLogging) {
                android.util.Log.d("ChatRepository", "Reqy response ${response.code}: $bodyStr")
            }

            if (!response.isSuccessful) return "Error ${response.code}: ${response.message}"

            when (kind) {
                ChatProviderKind.ANTHROPIC -> parseAnthropicResponse(bodyStr)
                ChatProviderKind.GEMINI -> parseGeminiResponse(bodyStr)
                ChatProviderKind.HUGGINGFACE -> parseHuggingFaceResponse(bodyStr)
                ChatProviderKind.OLLAMA -> parseOllamaResponse(bodyStr)
                else -> parseOpenAiStyleResponse(bodyStr)
            }
        } catch (e: Exception) {
            // Redact unconditionally -- see redactSecretFromMessage's kdoc. A malformed-URL
            // IllegalArgumentException from Request.Builder().url() can otherwise echo
            // config.apiKey verbatim (Gemini embeds it in the URL itself).
            "Connection failed: ${redactSecretFromMessage(e.message, config.apiKey)}. Check provider URL and API key in Settings."
        }
    }

    /**
     * Local fallback response generator when no provider is configured (or Local Mode is on).
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
