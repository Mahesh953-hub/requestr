package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
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
 * Task 11 — ChatRepository's message history used to live only in a `MutableStateFlow`, wiped
 * by `clearHistory()` or process death. This proves chat history round-trips through the real
 * `chat_messages` Room table with role and ordering preserved, and that `clearHistory()` deletes
 * the persisted rows rather than only resetting the in-memory list.
 *
 * `ChatRepository` is a Kotlin `object`, so it outlives this test class within the same JVM fork
 * -- if another test class (e.g. `ChatProviderConfigTest`) calls into `ChatRepository` first,
 * `init(context)`'s `appContext != null` guard makes THIS class's `init()` call a no-op, leaving
 * the message collector bound to that earlier test's now-superseded `RequestrDatabase` instance.
 * [ChatRepository.resetForTest] undoes `init()` (cancelling that collector) so every test here
 * starts from a genuinely clean singleton bound to the CURRENT Robolectric database, and
 * [ChatRepository.deleteAllPersistedMessages] is awaited directly instead of racing
 * `clearHistory()`'s fire-and-forget background delete.
 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryRoomTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() = runBlocking {
        ChatRepository.resetForTest()
        RequestrDatabase.get(context).chatMessageDao().getAll().forEach {
            RequestrDatabase.get(context).chatMessageDao().deleteById(it.id)
        }
        // Multi-conversation pass: also clear any leftover chat_conversations rows from a
        // previous test class in this same Robolectric JVM fork (RequestrDatabase.INSTANCE can
        // outlive a single test class the same way ChatRepository itself does -- see this
        // class's own kdoc above), so init()'s ensureActiveConversation() always starts this
        // test from a genuinely empty conversation table.
        RequestrDatabase.get(context).chatConversationDao().getAll().forEach {
            RequestrDatabase.get(context).chatConversationDao().deleteById(it.id)
        }
        // Provider-save tests below (added for the "claims success before the write completes"
        // fix) share this JVM fork with ChatProviderConfigTest, which manages its own
        // chat_providers cleanup in ITS @Before -- but that only runs before ITS tests, not
        // before these, so clear it here too for this class's own determinism regardless of
        // cross-class run order.
        RequestrDatabase.get(context).chatProviderDao().getAll().forEach {
            RequestrDatabase.get(context).chatProviderDao().deleteById(it.id)
        }
        ChatRepository.init(context)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3000, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `chat history round-trips through Room with role and ordering preserved`() = runBlocking {
        // No provider configured (ChatProviderConfig() defaults to blank baseUrl/apiKey), so
        // sendMessage exercises the local-mock path -- exactly what task 12 keeps as the honest
        // fallback -- while still writing both messages to Room.
        ChatRepository.sendMessage("hello")

        awaitUntil { RequestrDatabase.get(context).chatMessageDao().getAll().size >= 2 }

        val rows = RequestrDatabase.get(context).chatMessageDao().getAll()
        assertEquals(2, rows.size)
        assertEquals("user", rows[0].role)
        assertEquals("hello", rows[0].content)
        assertEquals("assistant", rows[1].role)
        assertTrue(rows[0].timestamp <= rows[1].timestamp)

        awaitUntil { ChatRepository.messages.value.size >= 2 }
        val inMemory = ChatRepository.messages.value
        assertEquals("user", inMemory[0].role)
        assertEquals("assistant", inMemory[1].role)
    }

    @Test
    fun `clearHistory deletes the persisted rows, not just the in-memory list`() = runBlocking {
        ChatRepository.sendMessage("test message")
        awaitUntil { RequestrDatabase.get(context).chatMessageDao().getAll().isNotEmpty() }

        // clearHistory() sets _messages empty synchronously AND launches a fire-and-forget
        // delete. The test awaits both the database deletion and the Room observer to catch
        // up — observeAll() emits per-transaction intermediate states, so the observer may
        // briefly see a non-empty snapshot if deleteById rows are interleaved.
        ChatRepository.clearHistory()
        awaitUntil { RequestrDatabase.get(context).chatMessageDao().getAll().isEmpty() }
        awaitUntil { ChatRepository.messages.value.isEmpty() }

        assertTrue(RequestrDatabase.get(context).chatMessageDao().getAll().isEmpty())
        assertTrue(ChatRepository.messages.value.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // saveProviderConfig -- "claims success before the write completes" fix. The UI must not
    // show "Saved and activated." until this suspend call actually returns Success, and a
    // failure must roll the optimistic in-memory providerConfig back rather than leaving a
    // phantom active provider.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `saveProviderConfig returns Success and persists to Room once both writes actually complete`() = runBlocking {
        // A blank apiKey routes to SecureKeyStore.removeSecret, which never touches
        // AndroidKeyStore (only the AES key generation path does) -- so this exercises a real,
        // fully-successful save under Robolectric, not a mocked one.
        val config = ChatProviderConfig(
            id = "openai", name = "OpenAI",
            baseUrl = "https://api.openai.com/v1/chat/completions", model = "gpt-4o-mini",
            apiKey = "", isActive = true,
        )

        val result = ChatRepository.saveProviderConfig(config)

        assertTrue("Expected Success once Room + Keystore writes both complete", result is SaveProviderResult.Success)
        assertEquals("openai", ChatRepository.providerConfig.value.id)
        val rows = RequestrDatabase.get(context).chatProviderDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("openai", rows[0].id)
        assertTrue("the saved row must actually be marked active in Room, not just in memory", rows[0].isActive)
    }

    @Test
    fun `saveProviderConfig rolls back the in-memory config and reports failure when the Keystore write fails`() = runBlocking {
        // Establish a known-good baseline first (blank apiKey -- guaranteed to succeed, see the
        // test above) so there's a real "previous" value to roll back to.
        val baseline = ChatProviderConfig(
            id = "openai", name = "OpenAI",
            baseUrl = "https://api.openai.com/v1/chat/completions", model = "gpt-4o-mini",
            apiKey = "", isActive = true,
        )
        val baselineResult = ChatRepository.saveProviderConfig(baseline)
        assertTrue(baselineResult is SaveProviderResult.Success)

        // A non-blank apiKey routes to SecureKeyStore.putSecret, which calls
        // getOrCreateSecretKey -- and AndroidKeyStore is NOT available under Robolectric (see
        // SecureKeyStoreTest's documented boundary), so this is a REAL failure, not simulated.
        val secretApiKey = "sk-should-never-leak-anywhere-in-this-test"
        val attempted = baseline.copy(model = "gpt-4o", apiKey = secretApiKey)
        val result = ChatRepository.saveProviderConfig(attempted)

        assertTrue("Expected Failure when the Keystore write throws", result is SaveProviderResult.Failure)
        val reason = (result as SaveProviderResult.Failure).reason
        assertTrue("failure reason must be actionable, not silent", reason.contains("Couldn't save provider settings"))
        assertFalse("failure reason must never echo the raw API key", reason.contains(secretApiKey))

        // Rolled back: the in-memory providerConfig must be exactly the pre-attempt baseline
        // (including its model), not the attempted-and-failed config -- no phantom active
        // provider left showing an unsaved model/key change.
        assertEquals(baseline.model, ChatRepository.providerConfig.value.model)
        assertEquals(baseline.apiKey, ChatRepository.providerConfig.value.apiKey)
    }
}
