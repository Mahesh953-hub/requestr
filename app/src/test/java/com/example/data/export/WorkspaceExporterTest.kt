package com.example.data.export

import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FlowConnectionEntity
import com.example.data.local.FlowNodeEntity
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
import kotlinx.coroutines.flow.first
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
        // MUST-FIX regression coverage below imports crypto rules *disabled* -- getEnabledRules()
        // alone would leave those rows behind for the next test in this class (Robolectric
        // doesn't reset RequestrDatabase's singleton between test methods -- see this class's
        // own KDoc), so this clears every rule, not just the enabled ones.
        db.interceptRuleDao().observeAll().first().forEach { db.interceptRuleDao().delete(it) }
        db.savedApiRequestDao().clearAll()
        db.flowNodeDao().getAllAcrossFlows().forEach { db.flowNodeDao().deleteById(it.id) }
        db.flowConnectionDao().getAllAcrossFlows().forEach { db.flowConnectionDao().deleteById(it.id) }
        db.flowPipelineDao().getAll().forEach { db.flowPipelineDao().deleteById(it.id) }
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

    // ── MUST-FIX regression coverage: crypto-rule fields + the import-time safety gate ────────

    /**
     * Generic field-drift regression: rather than hardcoding assertions for just the four
     * crypto fields this fix addresses, this builds an [InterceptRuleEntity] with every
     * non-`id` field set to a distinctive, non-default value and asserts the round-tripped row
     * is `equals()` to the original (minus `id`, Room's autogenerated primary key, which is
     * never round-tripped -- import always inserts a fresh row). Because [InterceptRuleEntity]
     * is a `data class`, `equals()` covers every declared property automatically, so a FUTURE
     * field added to the entity and forgotten in [WorkspaceExporter]'s export/import mapping
     * fails this test too, not just the fields known to be missing today.
     *
     * Uses [RuleAction.MODIFY_REQUEST] (not a crypto action) and `enabled = true` so this stays
     * independent of the crypto-rule import gate exercised separately below, and so the rule is
     * actually included in the export ([RequestrDatabase]'s `interceptRuleDao().getEnabledRules()`
     * only exports enabled rules).
     */
    @Test
    fun `every InterceptRuleEntity field survives an export-import round trip except id (generic field-drift regression)`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val fixture = InterceptRuleEntity(
            name = "distinctive-name",
            pattern = "distinctive-pattern",
            domain = "distinctive.example",
            action = RuleAction.MODIFY_REQUEST,
            enabled = true,
            replacementValue = """{"addHeaders":{"X-Test":"1"}}""",
            createdAt = 1_600_000_000_000L,
            cryptoKeyAlias = "distinctive-alias",
            cryptoAlgorithm = "distinctive-algorithm",
            cryptoDirection = "DECRYPT",
            cryptoScope = "REQUEST",
        )
        db.interceptRuleDao().insert(fixture)

        val json = WorkspaceExporter.exportWorkspace(context)
        db.interceptRuleDao().observeAll().first().forEach { db.interceptRuleDao().delete(it) }
        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(1, result.rulesImported)
        assertTrue(result.errors.isEmpty())
        assertTrue("expected no crypto-rule warnings for a non-crypto action", result.warnings.isEmpty())

        val imported = db.interceptRuleDao().observeAll().first().single()
        assertEquals(fixture.copy(id = imported.id), imported)
    }

    /**
     * MUST-FIX: previously the four crypto fields (`cryptoKeyAlias`/`cryptoAlgorithm`/
     * `cryptoDirection`/`cryptoScope`) were omitted from the export entirely, so re-importing a
     * crypto rule always reconstructed it *enabled* with a *blank* alias -- which, combined with
     * `RuleEnforcer`'s crypto path failing open on a bad key, silently turned an "encrypt this
     * body" rule into one that transmits plaintext after a round trip. This is the straight
     * happy-path check that a crypto rule whose alias *does* exist on the destination device
     * (the [hasKeyAlias] test seam standing in for a real `AndroidKeyStore` lookup, since
     * `AndroidKeyStore` itself isn't available under Robolectric) round-trips fully enabled,
     * with the real alias intact.
     */
    @Test
    fun `a crypto rule whose alias exists on this device round-trips enabled with all four crypto fields intact`() = runBlocking {
        val db = RequestrDatabase.get(context)
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "encrypt-auth-body",
                pattern = ".*",
                domain = "api.example.com",
                action = RuleAction.BODY_ENCRYPT_AES,
                enabled = true,
                cryptoKeyAlias = "my-real-alias",
                cryptoAlgorithm = "AES/GCM/NoPadding",
                cryptoDirection = "ENCRYPT",
                cryptoScope = "REQUEST",
            )
        )

        val json = WorkspaceExporter.exportWorkspace(context)
        db.interceptRuleDao().observeAll().first().forEach { db.interceptRuleDao().delete(it) }
        val result = WorkspaceExporter.importWorkspace(context, json, hasKeyAlias = { alias -> alias == "my-real-alias" })

        assertEquals(1, result.rulesImported)
        assertTrue(result.warnings.isEmpty())

        val imported = db.interceptRuleDao().observeAll().first().single()
        assertTrue("a crypto rule with a real, present alias must stay enabled", imported.enabled)
        assertEquals("my-real-alias", imported.cryptoKeyAlias)
        assertEquals("AES/GCM/NoPadding", imported.cryptoAlgorithm)
        assertEquals("ENCRYPT", imported.cryptoDirection)
        assertEquals("REQUEST", imported.cryptoScope)
    }

    /**
     * MUST-FIX: a blank `cryptoKeyAlias` (e.g. the rule was never finished being configured
     * before export, or was exported by a pre-fix build that never wrote the field at all --
     * see the old-format-JSON test below) must never import as an enabled, non-functional
     * crypto rule. Importing it disabled -- never silently dropped -- with a warning is the
     * chosen behaviour (see [WorkspaceExporter.importWorkspace]'s crypto-rule gate KDoc).
     */
    @Test
    fun `a crypto rule with a blank alias imports disabled with a discoverable warning, not enabled`() = runBlocking {
        val json = """
            {"version":"1.0","interceptRules":[
              {"name":"broken-encrypt","pattern":".*","domain":"*","action":"BODY_ENCRYPT_AES",
               "enabled":true,"cryptoKeyAlias":"","cryptoAlgorithm":"AES/GCM/NoPadding",
               "cryptoDirection":"ENCRYPT","cryptoScope":"BOTH"}
            ]}
        """.trimIndent()

        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(1, result.rulesImported)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("broken-encrypt"))
        assertTrue(result.summary.contains("warning"))

        val db = RequestrDatabase.get(context)
        val imported = db.interceptRuleDao().observeAll().first().single()
        assertFalse("a blank-alias crypto rule must never import enabled", imported.enabled)
    }

    /**
     * MUST-FIX: the alias is an `AndroidKeyStore` entry name, which never travels with an
     * export -- a *non-blank* alias that simply doesn't exist yet on the importing device is
     * the common case, not an edge case, and must be treated the same as a blank one: disabled,
     * with a warning, never silently enabled and non-functional.
     */
    @Test
    fun `a crypto rule whose alias does not exist on this device imports disabled with a discoverable warning`() = runBlocking {
        val json = """
            {"version":"1.0","interceptRules":[
              {"name":"decrypt-response","pattern":".*","domain":"*","action":"BODY_DECRYPT_AES",
               "enabled":true,"cryptoKeyAlias":"alias-from-another-device",
               "cryptoAlgorithm":"AES/GCM/NoPadding","cryptoDirection":"DECRYPT","cryptoScope":"RESPONSE"}
            ]}
        """.trimIndent()

        val result = WorkspaceExporter.importWorkspace(context, json, hasKeyAlias = { false })

        assertEquals(1, result.rulesImported)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("decrypt-response"))
        assertTrue(result.warnings.single().contains("alias-from-another-device"))

        val db = RequestrDatabase.get(context)
        val imported = db.interceptRuleDao().observeAll().first().single()
        assertFalse(imported.enabled)
        // The alias itself is preserved (not blanked out) so the user can see what to fix.
        assertEquals("alias-from-another-device", imported.cryptoKeyAlias)
    }

    /**
     * A crypto rule that was ALREADY disabled at export time must not spuriously gain a
     * warning -- the gate only needs to fire when it would otherwise silently import as an
     * enabled, non-functional rule.
     */
    @Test
    fun `a crypto rule that was already disabled at export time gets no spurious warning`() = runBlocking {
        val json = """
            {"version":"1.0","interceptRules":[
              {"name":"already-off","pattern":".*","domain":"*","action":"BODY_ENCRYPT_AES",
               "enabled":false,"cryptoKeyAlias":"","cryptoAlgorithm":"AES/GCM/NoPadding",
               "cryptoDirection":"ENCRYPT","cryptoScope":"BOTH"}
            ]}
        """.trimIndent()

        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(1, result.rulesImported)
        assertTrue(result.warnings.isEmpty())
        val db = RequestrDatabase.get(context)
        assertFalse(db.interceptRuleDao().observeAll().first().single().enabled)
    }

    /**
     * A non-crypto rule with a blank replacementValue/no crypto fields at all (e.g. a
     * pre-existing export from before the crypto columns existed) must still import cleanly --
     * the new `optString(...)` reads in the crypto-rule gate must not throw or misbehave when
     * the keys are simply absent from the JSON.
     */
    @Test
    fun `an old-format rule JSON with no crypto keys at all still imports cleanly`() = runBlocking {
        val json = """
            {"version":"1.0","interceptRules":[
              {"name":"legacy-block","pattern":"ads","domain":"*","action":"BLOCK","enabled":true}
            ]}
        """.trimIndent()

        val result = WorkspaceExporter.importWorkspace(context, json)

        assertEquals(1, result.rulesImported)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())

        val db = RequestrDatabase.get(context)
        val imported = db.interceptRuleDao().observeAll().first().single()
        assertTrue(imported.enabled)
        assertEquals("", imported.cryptoKeyAlias)
        assertEquals("AES/GCM/NoPadding", imported.cryptoAlgorithm)
        assertEquals("ENCRYPT", imported.cryptoDirection)
        assertEquals("BOTH", imported.cryptoScope)
    }

    // ── Follow-up: same generic field-drift coverage for every other exported entity ──────────
    //
    // The InterceptRuleEntity test above (`every InterceptRuleEntity field survives...`) is a
    // reflection-free "did any field get forgotten" check: because these entities are Kotlin
    // `data class`es, `equals()` compares every declared property automatically, so a future
    // field added to the entity and forgotten in WorkspaceExporter's export/import mapping fails
    // the test too -- not just the fields already known to be missing. The three tests below
    // apply that same pattern to the three other entity types WorkspaceExporter round-trips.
    // Writing them surfaced two more MUST-FIX-class drift bugs that inspection alone had missed
    // (same as the crypto fields originally): FlowNodeEntity/FlowConnectionEntity were missing
    // `flowId`/`createdAt`, and SavedApiRequestEntity/ApiRequestItem was missing all five async
    // 202-polling fields (`pollingEnabled`/`pollingIntervalMs`/`pollingMaxAttempts`/
    // `pollingLocationHeader`/`pollingAllowCrossOriginRedirect`). Both are fixed above in
    // [WorkspaceExporter]; these tests are the regression guard for that fix.

    /**
     * `flowId` (which flow graph a node belongs to -- multi-flow/pipeline support) and
     * `createdAt` were previously omitted from both the export and import JSON for flow nodes,
     * so any round trip silently reassigned every node to `flowId = "default"` and a fresh
     * `createdAt`, corrupting multi-flow workspaces on import. Also asserts the JSON payload
     * genuinely contains a `"flowId"` key (not just that the round-tripped value happens to
     * match the entity default) -- `flowId = "default"` is used deliberately here because
     * [WorkspaceExporter.exportWorkspace] itself only ever calls `flowNodeDao().getAll()` with
     * no argument, i.e. scoped to the "default" flow; that single-flow export scope is a
     * separate, pre-existing limitation (flagged in this task's report) distinct from the
     * field-drift bug this test targets.
     */
    @Test
    fun `every FlowNodeEntity field survives an export-import round trip except id (generic field-drift regression)`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val fixture = FlowNodeEntity(
            id = "node-drift-1",
            flowId = "default",
            type = "distinctive-type",
            label = "distinctive-label",
            positionX = 12.5f,
            positionY = 99.25f,
            configData = """{"distinctive":true}""",
            createdAt = 1_601_000_000_000L,
        )
        db.flowNodeDao().upsert(fixture)

        val json = WorkspaceExporter.exportWorkspace(context)
        val exportedNode = JSONObject(json).getJSONArray("flowNodes").getJSONObject(0)
        assertTrue("flowId must actually be serialized, not just coincide with the import default", exportedNode.has("flowId"))
        assertTrue(exportedNode.has("createdAt"))

        db.flowNodeDao().clearFlow()
        val result = WorkspaceExporter.importWorkspace(context, json)
        assertEquals(1, result.nodesImported)
        assertTrue(result.errors.isEmpty())

        val imported = db.flowNodeDao().getAll().single()
        assertEquals(fixture, imported)
    }

    /**
     * Same generic field-drift regression as the [FlowNodeEntity] test above, for
     * [FlowConnectionEntity]'s `flowId`/`createdAt`.
     */
    @Test
    fun `every FlowConnectionEntity field survives an export-import round trip except id (generic field-drift regression)`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val fixture = FlowConnectionEntity(
            id = "conn-drift-1",
            flowId = "default",
            fromNodeId = "node-a",
            fromPort = "distinctive-from-port",
            toNodeId = "node-b",
            toPort = "distinctive-to-port",
            createdAt = 1_602_000_000_000L,
        )
        db.flowConnectionDao().upsert(fixture)

        val json = WorkspaceExporter.exportWorkspace(context)
        val exportedConn = JSONObject(json).getJSONArray("flowConnections").getJSONObject(0)
        assertTrue("flowId must actually be serialized, not just coincide with the import default", exportedConn.has("flowId"))
        assertTrue(exportedConn.has("createdAt"))

        db.flowConnectionDao().clearFlow()
        val result = WorkspaceExporter.importWorkspace(context, json)
        assertEquals(1, result.connsImported)
        assertTrue(result.errors.isEmpty())

        val imported = db.flowConnectionDao().getAll().single()
        assertEquals(fixture, imported)
    }

    /**
     * Same generic field-drift regression, at the entity level, for [SavedApiRequestEntity] --
     * complements the existing manual field-by-field test above (`a fully populated saved
     * request round-trips losslessly...`), which asserts field-by-field and therefore would not
     * fail on a brand-new field forgotten by both the entity fixture *and* the export/import
     * mapping. Unlike [InterceptRuleEntity]'s `id` (Room-autogenerated, never round-tripped),
     * [SavedApiRequestEntity.id] is a client-generated, non-autogenerate `@PrimaryKey`, so it
     * round-trips exactly and needs no `.copy(id = ...)` before the equality check.
     *
     * Writing this test is what surfaced the async 202-polling fields
     * (`pollingEnabled`/`pollingIntervalMs`/`pollingMaxAttempts`/`pollingLocationHeader`/
     * `pollingAllowCrossOriginRedirect`) being completely absent from both [requestToJson] and
     * [jsonToApiRequestItem] -- now fixed above.
     */
    @Test
    fun `every SavedApiRequestEntity field survives an export-import round trip (generic field-drift regression)`() = runBlocking {
        val db = RequestrDatabase.get(context)
        val fixture = SavedApiRequestEntity(
            id = "req-drift-1",
            name = "distinctive-name",
            folderId = "distinctive-folder",
            method = "PUT",
            url = "https://distinctive.example.com/resource",
            queryParams = listOf(
                com.example.data.local.SavedKeyValue(id = "q1", key = "q", value = "v", isEnabled = false, description = "qd"),
            ),
            headers = listOf(
                com.example.data.local.SavedKeyValue(id = "h1", key = "H", value = "V", isEnabled = true, description = "hd"),
            ),
            authType = "BASIC",
            bearerToken = "distinctive-bearer",
            basicUsername = "distinctive-user",
            basicPassword = "distinctive-pass",
            apiKeyHeader = "X-Distinctive-Key",
            apiKeyValue = "distinctive-api-key",
            apiKeyLocation = "QUERY_PARAM",
            bodyType = "JSON",
            bodyContent = "{\"distinctive\":true}",
            formData = listOf(
                com.example.data.local.SavedKeyValue(id = "f1", key = "file", value = "x.png", isEnabled = true, description = "fd"),
            ),
            preScript = "console.log('pre')",
            postScript = "console.log('post')",
            isFavorite = true,
            updatedAt = 1_650_000_000_000L,
            chainExtractions = listOf(
                com.example.data.local.SavedChainExtractionRule(id = "c1", jsonPath = "$.id", variableName = "orderId", enabled = false),
            ),
            assertions = listOf(
                com.example.data.local.SavedAssertion(id = "a1", target = "BODY_JSON_FIELD", targetKey = "status", operator = "CONTAINS", expectedValue = "ok", enabled = false),
            ),
            mockEnabled = true,
            mockStatusCode = 599,
            mockStatusMessage = "distinctive-status",
            mockHeaders = listOf(
                com.example.data.local.SavedKeyValue(id = "m1", key = "X-Mock", value = "1", isEnabled = true, description = "md"),
            ),
            mockBody = "{\"distinctive\":\"mock\"}",
            mockDelayMs = 12345L,
            pollingEnabled = true,
            pollingIntervalMs = 4242L,
            pollingMaxAttempts = 7,
            pollingLocationHeader = "X-Distinctive-Location",
            pollingAllowCrossOriginRedirect = true,
        )
        db.savedApiRequestDao().upsert(fixture)

        val json = WorkspaceExporter.exportWorkspace(context)
        val exportedRequest = JSONObject(json).getJSONArray("savedRequests").getJSONObject(0)
        assertTrue("pollingEnabled must actually be serialized, not just coincide with the import default", exportedRequest.has("pollingEnabled"))

        db.savedApiRequestDao().clearAll()
        val result = WorkspaceExporter.importWorkspace(context, json)
        assertEquals(1, result.requestsImported)
        assertTrue(result.errors.isEmpty())

        val imported = db.savedApiRequestDao().getAll().single()
        assertEquals(fixture, imported)
    }

    // ── Multi-flow export completeness (transplant slice 11 gap) ──────────────────────────────

    /**
     * MUST-FIX-class data-loss bug: [WorkspaceExporter.exportWorkspace] used to call
     * `flowNodeDao().getAll()`/`flowConnectionDao().getAll()` with no argument, which defaults
     * to `flowId = "default"` -- so a user with multiple flows (real, shipped feature: see
     * [com.example.ui.screens.FlowScreen]'s flow picker, driven by `FlowRepository.allFlows`,
     * itself backed by [com.example.data.local.FlowPipelineDao.observeAll]) got back only their
     * default flow on export, silently losing every other flow with no warning.
     *
     * This builds two distinct flows -- each with its own [com.example.data.local.FlowPipelineEntity]
     * (name/status/etc., the metadata the flow picker actually renders), its own node, and its
     * own connection -- exports, clears every flow table, imports, and asserts:
     *  1. Both pipelines round-trip (by name, so a real regression -- not just an accidental
     *     empty-list-equals-empty-list pass -- would be caught).
     *  2. Every node/connection lands back in the SAME flow it started in (no silent collapse
     *     onto "default", the bug this whole test class exists to catch).
     *  3. Nothing merged or cross-linked: flow A's connection still only references flow A's
     *     nodes, not flow B's.
     */
    @Test
    fun `exportWorkspace covers every flow, not just default -- multi-flow round trip preserves each flow's identity`() = runBlocking {
        val db = RequestrDatabase.get(context)

        db.flowPipelineDao().upsert(
            com.example.data.local.FlowPipelineEntity(
                id = "flow-alpha",
                name = "Alpha Pipeline",
                description = "2 nodes · trigger: Intercept",
                triggerType = "Intercept",
                status = "RUNNING",
                lastRunText = "5m ago",
                runsCount = 3,
                createdAt = 1_610_000_000_000L,
            )
        )
        db.flowPipelineDao().upsert(
            com.example.data.local.FlowPipelineEntity(
                id = "flow-beta",
                name = "Beta Pipeline",
                description = "2 nodes · trigger: Manual",
                triggerType = "Manual",
                status = "DRAFT",
                lastRunText = "Never",
                runsCount = 0,
                createdAt = 1_611_000_000_000L,
            )
        )

        val alphaNode1 = FlowNodeEntity(id = "a-node-1", flowId = "flow-alpha", type = "INTERCEPT_TRIGGER", label = "Alpha Trigger", positionX = 10f, positionY = 20f, configData = "alpha-1")
        val alphaNode2 = FlowNodeEntity(id = "a-node-2", flowId = "flow-alpha", type = "TRANSFORM", label = "Alpha Transform", positionX = 30f, positionY = 40f, configData = "alpha-2")
        val betaNode1 = FlowNodeEntity(id = "b-node-1", flowId = "flow-beta", type = "CONDITION", label = "Beta Condition", positionX = 50f, positionY = 60f, configData = "beta-1")
        val betaNode2 = FlowNodeEntity(id = "b-node-2", flowId = "flow-beta", type = "ACTION", label = "Beta Action", positionX = 70f, positionY = 80f, configData = "beta-2")
        listOf(alphaNode1, alphaNode2, betaNode1, betaNode2).forEach { db.flowNodeDao().upsert(it) }

        val alphaConn = FlowConnectionEntity(id = "a-conn-1", flowId = "flow-alpha", fromNodeId = "a-node-1", toNodeId = "a-node-2", fromPort = "out", toPort = "in")
        val betaConn = FlowConnectionEntity(id = "b-conn-1", flowId = "flow-beta", fromNodeId = "b-node-1", toNodeId = "b-node-2", fromPort = "true", toPort = "in")
        listOf(alphaConn, betaConn).forEach { db.flowConnectionDao().upsert(it) }

        val json = WorkspaceExporter.exportWorkspace(context)
        val root = JSONObject(json)
        assertEquals("both flows must be exported, not just the default one", 2, root.getJSONArray("flowPipelines").length())
        assertEquals("nodes from both flows must be exported", 4, root.getJSONArray("flowNodes").length())
        assertEquals("connections from both flows must be exported", 2, root.getJSONArray("flowConnections").length())

        // Clear every flow table so the import is exercising real inserts.
        db.flowNodeDao().getAllAcrossFlows().forEach { db.flowNodeDao().deleteById(it.id) }
        db.flowConnectionDao().getAllAcrossFlows().forEach { db.flowConnectionDao().deleteById(it.id) }
        db.flowPipelineDao().getAll().forEach { db.flowPipelineDao().deleteById(it.id) }

        val result = WorkspaceExporter.importWorkspace(context, json)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.pipelinesImported)
        assertEquals(4, result.nodesImported)
        assertEquals(2, result.connsImported)

        val importedPipelines = db.flowPipelineDao().getAll().associateBy { it.id }
        assertEquals("Alpha Pipeline", importedPipelines["flow-alpha"]?.name)
        assertEquals("Beta Pipeline", importedPipelines["flow-beta"]?.name)

        val importedAlphaNodes = db.flowNodeDao().getAll("flow-alpha")
        val importedBetaNodes = db.flowNodeDao().getAll("flow-beta")
        assertEquals(setOf(alphaNode1, alphaNode2), importedAlphaNodes.toSet())
        assertEquals(setOf(betaNode1, betaNode2), importedBetaNodes.toSet())
        // Nothing merged into the wrong flow or collapsed onto "default".
        assertTrue(importedAlphaNodes.none { it.id.startsWith("b-") })
        assertTrue(importedBetaNodes.none { it.id.startsWith("a-") })

        val importedAlphaConns = db.flowConnectionDao().getAll("flow-alpha")
        val importedBetaConns = db.flowConnectionDao().getAll("flow-beta")
        assertEquals(listOf(alphaConn), importedAlphaConns)
        assertEquals(listOf(betaConn), importedBetaConns)
        // Flow alpha's connection must still only reference flow alpha's nodes -- no cross-linking.
        assertTrue(importedAlphaConns.single().fromNodeId.startsWith("a-"))
        assertTrue(importedAlphaConns.single().toNodeId.startsWith("a-"))
        assertTrue(importedBetaConns.single().fromNodeId.startsWith("b-"))
        assertTrue(importedBetaConns.single().toNodeId.startsWith("b-"))
    }
}
