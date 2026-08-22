package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CustomToolEntity
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 11 — `ToolsRepository.customTools` used to be a plain `ConcurrentHashMap`, wiped on
 * process death. This proves a custom tool created in a "previous session" (inserted directly
 * into the `custom_tools` Room row, simulating what a real restart would leave on disk) is
 * loaded back by [ToolsRepository.init] and is immediately executable through
 * [ToolsRepository.executeToolFunction] — not just present in [ToolsRepository.catalog].
 *
 * `ToolsRepository` is a process-wide singleton, so [ToolsRepository.init] is only meaningfully
 * called once per test JVM (guarded by its own `appContext != null` check, same as every other
 * repository's init()). That is fine here: the first call attaches the Room collector, and every
 * later call (this class's other test method, or ToolsRepositoryCustomToolTest elsewhere) shares
 * that already-live collector.
 */
@RunWith(RobolectricTestRunner::class)
class ToolsRepositoryRoomTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() = runBlocking {
        RequestrDatabase.get(context).customToolDao().getAll().forEach {
            RequestrDatabase.get(context).customToolDao().deleteById(it.id)
        }
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
    fun `a custom tool persisted in Room from a prior session survives init and is executable`() = runBlocking {
        RequestrDatabase.get(context).customToolDao().upsert(
            CustomToolEntity(
                id = "custom-restart-check",
                name = "Restart Check",
                operation = "regex-replace",
                pattern = "[0-9]+",
                replacement = "#",
            )
        )

        ToolsRepository.init(context)

        awaitUntil { ToolsRepository.catalog.value.any { it.id == "custom-restart-check" } }

        val result = ToolsRepository.executeToolFunction("custom-restart-check", "abc123def456")
        assertEquals("abc#def#", result)
    }

    @Test
    fun `addCustomTool called after init still persists the row to Room`() = runBlocking {
        ToolsRepository.init(context)
        ToolsRepository.addCustomTool(
            name = "Post Init Tool",
            operation = "template",
            pattern = "Wrapped: {input}",
            replacement = "",
        )

        awaitUntil {
            RequestrDatabase.get(context).customToolDao().getAll().any { it.id == "custom-post-init-tool" }
        }

        val row = RequestrDatabase.get(context).customToolDao().getAll().first { it.id == "custom-post-init-tool" }
        assertEquals("template", row.operation)
        assertTrue(ToolsRepository.catalog.value.any { it.id == "custom-post-init-tool" })
    }
}
