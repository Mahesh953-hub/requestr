package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
