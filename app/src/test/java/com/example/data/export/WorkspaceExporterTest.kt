package com.example.data.export

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.local.SavedApiRequestEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 13 — WorkspaceExporter had zero call sites anywhere in the app and three honesty bugs:
 * an overclaiming KDoc (claimed environments/notes/settings that were never written), a
 * hardcoded "appVersion", and a requestsImported counter that was declared but never
 * incremented. Uses Robolectric because WorkspaceExporter reads/writes through
 * RequestrDatabase's real DAOs, which the plain android.jar stub cannot satisfy.
 *
 * Tables are cleared before each test so RequestrDatabase's process-wide singleton (cached in
 * a companion object, which Robolectric does not reset between test methods in the same class)
 * doesn't leak rows between tests.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceExporterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearTables() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.interceptRuleDao().getEnabledRules().forEach { db.interceptRuleDao().delete(it) }
        db.savedApiRequestDao().clearAll()
        db.flowNodeDao().clearFlow()
        db.flowConnectionDao().clearFlow()
    }

    @Test
    fun `export JSON contains the documented top-level keys and real app version`() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.interceptRuleDao().insert(
            InterceptRuleEntity(name = "block-ads", pattern = "ads", domain = "*", action = RuleAction.BLOCK, enabled = true)
        )
        db.savedApiRequestDao().upsert(
            SavedApiRequestEntity(id = "req-1", name = "Get Users", method = "GET", url = "https://example.com/users")
        )

        val json = WorkspaceExporter.exportWorkspace(context)
        val root = JSONObject(json)

        assertTrue(root.has("version"))
        assertTrue(root.has("exportedAt"))
        assertTrue(root.has("appVersion"))
        assertTrue(root.has("interceptRules"))
        assertTrue(root.has("savedRequests"))
        assertTrue(root.has("flowNodes"))
        assertTrue(root.has("flowConnections"))
        assertEquals(com.example.BuildConfig.VERSION_NAME, root.getString("appVersion"))
        assertEquals(1, root.getJSONArray("interceptRules").length())
        assertEquals(1, root.getJSONArray("savedRequests").length())
    }

    @Test
    fun `importWorkspace reports a non-zero requestsImported count`() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.savedApiRequestDao().upsert(
            SavedApiRequestEntity(id = "req-1", name = "Get Users", method = "GET", url = "https://example.com/users")
        )
        db.interceptRuleDao().insert(
            InterceptRuleEntity(name = "block-ads", pattern = "ads", domain = "*", action = RuleAction.BLOCK, enabled = true)
        )
        val json = WorkspaceExporter.exportWorkspace(context)

        // Clear again so the import is exercising a real insert, not just re-upserting the
        // same rows that already exist.
        db.savedApiRequestDao().clearAll()
        db.interceptRuleDao().getEnabledRules().forEach { db.interceptRuleDao().delete(it) }

        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(1, result.rulesImported)
        assertEquals(1, result.requestsImported)
        assertTrue(result.errors.isEmpty())

        val imported = db.savedApiRequestDao().getAll()
        assertEquals(1, imported.size)
        assertEquals("Get Users", imported.first().name)
    }

    @Test
    fun `malformed top-level JSON throws rather than silently returning an empty result`() {
        var threw = false
        try {
            runBlocking { WorkspaceExporter.importWorkspace(context, "{ not valid json") }
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `malformed array entries are recorded as errors rather than thrown`() = runBlocking {
        // Valid top-level JSON, but a flow node missing the required "id" field.
        val json = """{"version":"1.0","flowNodes":[{"type":"API"}]}"""
        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(0, result.nodesImported)
        assertTrue(result.errors.isNotEmpty())
    }
}
