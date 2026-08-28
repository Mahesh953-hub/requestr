package com.example.data.export

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.local.SavedApiRequestEntity
import com.example.data.local.toApiRequestItem
import com.example.data.local.toEntity
import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionTarget
import com.example.data.models.AuthType
import com.example.data.models.ChainExtractionRule
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
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

    /**
     * Master plan §1.6(b) — a saved request's headers/body/auth/query/formData/chainExtractions/
     * assertions used to be silently dropped by the old id/name/method/url-only export. This
     * confirms a fully-populated [ApiRequestItem] survives a real export -> import round trip
     * through the actual DB (not just the JSON helpers in isolation).
     */
    @Test
    fun `a fully populated saved request round-trips losslessly through export and import`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val original = ApiRequestItem(
            id = "req-full-1",
            name = "Create Order",
            folderId = "folder-9",
            method = HttpMethod.POST,
            url = "https://api.example.com/orders",
            queryParams = listOf(
                KeyValueItem(key = "dryRun", value = "true", isEnabled = true),
                KeyValueItem(key = "trace", value = "1", isEnabled = false),
            ),
            headers = listOf(
                KeyValueItem(key = "X-Trace-Id", value = "abc123", isEnabled = true),
            ),
            authType = AuthType.API_KEY,
            apiKeyHeader = "X-Api-Key",
            apiKeyValue = "secret-key",
            apiKeyLocation = ApiKeyLocation.QUERY_PARAM,
            bodyType = RequestBodyType.JSON,
            bodyContent = "{\"item\":\"widget\"}",
            formData = listOf(KeyValueItem(key = "file", value = "receipt.png", isEnabled = true)),
            isFavorite = true,
            chainExtractions = listOf(
                ChainExtractionRule(id = "chain-1", jsonPath = "orderId", variableName = "orderId", enabled = true),
            ),
            assertions = listOf(
                Assertion(
                    id = "assert-1",
                    target = AssertionTarget.BODY_JSON_FIELD,
                    targetKey = "status",
                    operator = AssertionOperator.EQUALS,
                    expectedValue = "created",
                    enabled = true,
                ),
            ),
            mockEnabled = true,
            mockStatusCode = 503,
            mockStatusMessage = "Service Unavailable",
            mockHeaders = listOf(
                KeyValueItem(key = "Retry-After", value = "30", isEnabled = true),
                KeyValueItem(key = "X-Debug", value = "off", isEnabled = false),
            ),
            mockBody = "{\"mocked\":\"order-response\"}",
            mockDelayMs = 750L,
        )
        db.savedApiRequestDao().upsert(original.toEntity())

        val json = WorkspaceExporter.exportWorkspace(context)

        db.savedApiRequestDao().clearAll()
        val result = WorkspaceExporter.importWorkspace(context, json)
        assertEquals(1, result.requestsImported)
        assertTrue(result.errors.isEmpty())

        val roundTripped = db.savedApiRequestDao().getAll().first().toApiRequestItem()
        assertEquals(original.name, roundTripped.name)
        assertEquals(original.folderId, roundTripped.folderId)
        assertEquals(original.method, roundTripped.method)
        assertEquals(original.url, roundTripped.url)
        assertEquals(original.queryParams.map { it.key to it.value to it.isEnabled }, roundTripped.queryParams.map { it.key to it.value to it.isEnabled })
        assertEquals(original.headers.map { it.key to it.value }, roundTripped.headers.map { it.key to it.value })
        assertEquals(original.authType, roundTripped.authType)
        assertEquals(original.apiKeyHeader, roundTripped.apiKeyHeader)
        assertEquals(original.apiKeyValue, roundTripped.apiKeyValue)
        assertEquals(original.apiKeyLocation, roundTripped.apiKeyLocation)
        assertEquals(original.bodyType, roundTripped.bodyType)
        assertEquals(original.bodyContent, roundTripped.bodyContent)
        assertEquals(original.formData.map { it.key to it.value }, roundTripped.formData.map { it.key to it.value })
        assertEquals(original.isFavorite, roundTripped.isFavorite)
        assertEquals(original.chainExtractions.map { it.jsonPath to it.variableName }, roundTripped.chainExtractions.map { it.jsonPath to it.variableName })
        assertEquals(original.assertions.map { it.target to it.targetKey to it.operator to it.expectedValue }, roundTripped.assertions.map { it.target to it.targetKey to it.operator to it.expectedValue })
        // Master plan §2.8 -- these six fields were the ones actually missing before this fix.
        assertEquals(original.mockEnabled, roundTripped.mockEnabled)
        assertTrue(roundTripped.mockEnabled)
        assertEquals(original.mockStatusCode, roundTripped.mockStatusCode)
        assertEquals(original.mockStatusMessage, roundTripped.mockStatusMessage)
        assertEquals(original.mockHeaders.map { it.key to it.value to it.isEnabled }, roundTripped.mockHeaders.map { it.key to it.value to it.isEnabled })
        assertEquals(original.mockBody, roundTripped.mockBody)
        assertEquals(original.mockDelayMs, roundTripped.mockDelayMs)
    }

    /**
     * Master plan §1.6(b) — a workspace exported by a build *before* this fix only ever wrote
     * id/name/method/url/isFavorite/updatedAt for a saved request. [jsonToApiRequestItem]'s
     * `opt*` defaults must still import that shape without throwing or dropping the request.
     */
    @Test
    fun `an old-format saved request JSON (pre-fidelity export) still imports cleanly`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val oldFormatJson = """
            {
              "version": "1.0",
              "savedRequests": [
                {
                  "id": "old-req-1",
                  "name": "Legacy Ping",
                  "method": "GET",
                  "url": "https://legacy.example.com/ping",
                  "isFavorite": true,
                  "updatedAt": 1700000000000
                }
              ]
            }
        """.trimIndent()

        val result = WorkspaceExporter.importWorkspace(context, oldFormatJson)
        assertEquals(1, result.requestsImported)
        assertTrue(result.errors.isEmpty())

        val imported = db.savedApiRequestDao().getAll().first().toApiRequestItem()
        assertEquals("Legacy Ping", imported.name)
        assertEquals(HttpMethod.GET, imported.method)
        assertEquals("https://legacy.example.com/ping", imported.url)
        assertTrue(imported.isFavorite)
        assertEquals(AuthType.NONE, imported.authType)
        assertEquals(RequestBodyType.NONE, imported.bodyType)
        assertTrue(imported.headers.isEmpty())
        assertTrue(imported.chainExtractions.isEmpty())
        assertTrue(imported.assertions.isEmpty())
    }
}
