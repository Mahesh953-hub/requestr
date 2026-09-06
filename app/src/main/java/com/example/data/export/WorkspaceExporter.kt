package com.example.data.export

import android.content.Context
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.toApiRequestItem
import com.example.data.local.toEntity
import com.example.data.models.ApiRequestItem
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionTarget
import com.example.data.models.AuthType
import com.example.data.models.ApiKeyLocation
import com.example.data.models.ChainExtractionRule
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import com.example.interception.rules.BodyCryptoConfig
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.LegacyInterceptRule
import com.example.interception.rules.LegacyRuleConverter
import com.example.interception.rules.RuleApiJson
import com.example.interception.rules.RuleValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace system — exports/imports the app's typed intercept rules (+ folders), saved API requests, and every
 * flow (pipeline metadata + nodes + connections, across all flows, not just the "default" one)
 * as a single JSON file.
 *
 * Environments, notes, and settings are not part of this export. Notes become exportable
 * after flow/notes-repository work lands; extending the export to cover them is a clean
 * follow-up at that point.
 */
object WorkspaceExporter {

    /**
     * Exports the workspace as a JSON string.
     * Includes: intercept rules, saved requests, every flow pipeline, and every flow's nodes
     * and connections.
     */
    suspend fun exportWorkspace(context: Context): String = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val root = JSONObject()
        root.put("version", "1.0")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", com.example.BuildConfig.VERSION_NAME)

        // Intercept rules -- S4 task 34: the typed rule library (every rule, enabled or not, in
        // evaluation order) plus its folders, in the same `schemaVersion: 2` shape LocalApiServer
        // emits (RuleApiJson). `schemaVersion` at the root tells import which dialect it is
        // reading; a file without it is a v1 export whose flat `interceptRules` array is
        // converted through LegacyRuleConverter on the way in.
        root.put("schemaVersion", RuleApiJson.SCHEMA_VERSION)
        val rulesArray = JSONArray()
        db.interceptorRuleDao().getAllOrdered().forEach { rulesArray.put(RuleApiJson.ruleToJson(it)) }
        root.put("interceptorRules", rulesArray)
        val foldersArray = JSONArray()
        db.ruleFolderDao().getAll().forEach { foldersArray.put(RuleApiJson.folderToJson(it)) }
        root.put("ruleFolders", foldersArray)

        // Saved API requests
        //
        // Master plan §1.6(b): this used to hand-roll a *second*, lossy mapping (id/name/
        // method/url/isFavorite/updatedAt only), silently dropping headers, body, auth, query
        // params, formData, chainExtractions and assertions on every export. It now goes
        // through [SavedApiRequestMapper]'s existing entity<->model mapping
        // (com.example.data.local.toApiRequestItem(), `internal` and therefore reachable from
        // this module) into [requestToJson], which owns the full field list. An OLD export
        // produced by the previous id/name/method/url-only shape still imports cleanly --
        // see [jsonToApiRequestItem]'s opt* defaults below.
        val savedRequests = db.savedApiRequestDao().getAll()
        val requestsArray = JSONArray()
        savedRequests.forEach { req -> requestsArray.put(requestToJson(req.toApiRequestItem())) }
        root.put("savedRequests", requestsArray)

        // Flow pipelines (multi-flow support, transplant slice 11).
        //
        // Flows are already in scope for this export (nodes/connections below have always been
        // exported) -- unlike environments/notes/settings, which this class's KDoc deliberately
        // excludes. But exporting nodes/connections without their owning FlowPipelineEntity is
        // an incomplete implementation of that scope, not a smaller deliberate boundary: [FlowScreen]'s
        // flow picker (`PipelinePillItem`) renders `pipeline.name`, and `FlowRepository`'s
        // `allFlows` is populated from `flowPipelineDao().observeAll()` -- NOT from whatever
        // flowIds happen to appear in flow_nodes/flow_connections. So a flow whose nodes/
        // connections import successfully but whose FlowPipelineEntity row was never exported
        // is worse than "the user can't tell it apart from another flow": it has no name and
        // never appears in the picker at all -- its nodes are silently orphaned in the DB.
        val pipelines = db.flowPipelineDao().getAll()
        val pipelinesArray = JSONArray()
        pipelines.forEach { p ->
            pipelinesArray.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("triggerType", p.triggerType)
                put("status", p.status)
                put("lastRunText", p.lastRunText)
                put("runsCount", p.runsCount)
                put("createdAt", p.createdAt)
            })
        }
        root.put("flowPipelines", pipelinesArray)

        // Flow nodes and connections -- across EVERY flow, not just "default". getAll() on both
        // DAOs defaults its `flowId` parameter to "default", so a user with multiple flows (see
        // FlowRepository.allFlows / FlowScreen's flow picker) previously got only their default
        // flow back on export, silently losing every other flow with no warning. Each node/
        // connection's own `flowId` (exported below) is what lets import reconstruct the
        // original graphs instead of merging everything into one flow.
        val flowNodes = db.flowNodeDao().getAllAcrossFlows()
        val flowConns = db.flowConnectionDao().getAllAcrossFlows()
        val nodesArray = JSONArray()
        flowNodes.forEach { node ->
            nodesArray.put(JSONObject().apply {
                put("id", node.id)
                put("type", node.type)
                put("label", node.label)
                put("x", node.positionX)
                put("y", node.positionY)
                put("configData", node.configData)
                // Generic field-drift fix: flowId/createdAt were previously omitted here, so
                // every imported node silently landed back on flowId="default" and a fresh
                // createdAt regardless of which flow graph it actually belonged to or when it
                // was really created. See jsonToApiRequestItem's KDoc / the crypto-fields MUST-FIX
                // above for the same class of bug on a different entity.
                put("flowId", node.flowId)
                put("createdAt", node.createdAt)
            })
        }
        val connsArray = JSONArray()
        flowConns.forEach { conn ->
            connsArray.put(JSONObject().apply {
                put("id", conn.id)
                put("from", conn.fromNodeId)
                put("to", conn.toNodeId)
                put("fromPort", conn.fromPort)
                put("toPort", conn.toPort)
                // Same fix as flow nodes above.
                put("flowId", conn.flowId)
                put("createdAt", conn.createdAt)
            })
        }
        root.put("flowNodes", nodesArray)
        root.put("flowConnections", connsArray)

        root.toString(2)
    }

    /**
     * Imports a workspace from a JSON string. Merges with existing data
     * (does not delete existing entries).
     *
     * [hasKeyAlias] -- defaults to the real [com.example.data.prefs.SecureKeyStore.hasKey] --
     * is a test seam for the crypto-rule import gate below (see that block's KDoc); production
     * callers never need to pass it.
     */
    suspend fun importWorkspace(
        context: Context,
        json: String,
        hasKeyAlias: (String) -> Boolean = com.example.data.prefs.SecureKeyStore::hasKey,
    ): ImportResult = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val root = JSONObject(json)
        var rulesImported = 0
        var foldersImported = 0
        var requestsImported = 0
        var pipelinesImported = 0
        var nodesImported = 0
        var connsImported = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Import rules (S4 task 34). Two dialects:
        //  - v2 (`schemaVersion: 2`, `interceptorRules` + `ruleFolders`): typed rules imported as
        //    they are, folders first so `folderId`s can be remapped onto the ids this device hands
        //    out;
        //  - v1 (no `schemaVersion`, flat `interceptRules`): each entry is parsed into a
        //    LegacyInterceptRule and converted through LegacyRuleConverter -- the one converter
        //    shared with the v23 migration and the local API -- so a v1 file exported by the
        //    previous release imports with identical behaviour.
        // Both paths append after the current tail (sortOrder = max + 1, ...) so imported rules
        // evaluate after the rules already on the device, and both run RuleValidation.
        val ruleDao = db.interceptorRuleDao()
        val folderDao = db.ruleFolderDao()
        val nowMs = System.currentTimeMillis()
        var nextSortOrder = ruleDao.maxSortOrder() + 1
        val folderIdMap = HashMap<Long, Long>()
        root.optJSONArray("ruleFolders")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val f = arr.getJSONObject(i)
                    val entity = RuleApiJson.folderFromJson(f, nowMs)
                    val newId = folderDao.insert(entity)
                    if (f.has("id")) folderIdMap[f.optLong("id")] = newId
                    foldersImported++
                } catch (e: Exception) { errors.add("Folder $i: ${e.message}") }
            }
        }

        // Crypto-rule import gate: a key alias names an AndroidKeyStore entry, which by design
        // never travels with an export (it's per-device key material, not exportable data) -- so
        // a blank alias, or an alias that simply doesn't exist yet on *this* device, is the
        // normal case for an imported crypto rule, not an edge case. Importing such a rule
        // *enabled* would have it fail closed on every matched body (BodyCryptoStage refuses to
        // forward plaintext the user believes is encrypted), which is safe but surprising.
        // Instead: import it (never silently drop the user's configuration) but force it
        // disabled, and record a warning so this is discoverable at import time.
        fun cryptoGateReason(keyAlias: String): String? = when {
            keyAlias.isBlank() -> "no key alias was set when this rule was exported"
            !hasKeyAlias(keyAlias) ->
                "key alias '$keyAlias' does not exist on this device (Keystore aliases never travel with an export)"
            else -> null
        }
        suspend fun insertTyped(rule: InterceptorRule, label: String, index: Int) {
            var toInsert = rule
            val config = rule.config
            if (config is BodyCryptoConfig && rule.enabled) {
                val reason = cryptoGateReason(config.keyAlias)
                if (reason != null) {
                    toInsert = toInsert.copy(enabled = false)
                    warnings.add(
                        "Rule '${rule.name.ifBlank { "(unnamed)" }}': imported disabled -- $reason. " +
                            "Edit the rule and set a valid key alias, then re-enable it.",
                    )
                }
            }
            val problems = RuleValidation.validate(toInsert, enabledRules = 0, acknowledged = true)
            if (problems.isNotEmpty()) {
                errors.add("$label $index: " + problems.joinToString("; ") { "${it.field}: ${it.message}" })
                return
            }
            ruleDao.insert(InterceptorRuleEntity.fromDomain(toInsert))
            nextSortOrder = toInsert.sortOrder + 1
            rulesImported++
        }

        root.optJSONArray("interceptorRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val r = arr.getJSONObject(i)
                    val body = JSONObject(r.toString()).apply { remove("id"); remove("sortOrder"); remove("folderId") }
                    when (val parsed = RuleApiJson.parseNewRule(body, sortOrder = nextSortOrder, nowMs = nowMs)) {
                        is RuleApiJson.Parsed.Invalid -> errors.add("Rule $i: ${parsed.message}")
                        is RuleApiJson.Parsed.Ok -> {
                            val folderId = if (r.has("folderId") && !r.isNull("folderId")) folderIdMap[r.optLong("folderId")] else null
                            insertTyped(parsed.rule.copy(folderId = folderId, legacyRuleId = null), "Rule", i)
                        }
                    }
                } catch (e: Exception) { errors.add("Rule $i: ${e.message}") }
            }
        }

        root.optJSONArray("interceptRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val r = arr.getJSONObject(i)
                    val legacy = LegacyInterceptRule.fromJson(r, nowMs)
                    when (val converted = LegacyRuleConverter.convert(legacy, nextSortOrder)) {
                        is LegacyRuleConverter.ConversionResult.Unconvertible -> errors.add("Rule $i: ${converted.reason}")
                        is LegacyRuleConverter.ConversionResult.Converted -> {
                            // The converter's own blank-alias note is superseded by the import gate
                            // below (which also disables the rule); keep every other parity note.
                            for (w in converted.warnings) {
                                if (w.contains("blank key alias")) continue
                                warnings.add("Rule '${legacy.name.ifBlank { "(unnamed)" }}': $w")
                            }
                            insertTyped(converted.rule.copy(legacyRuleId = null), "Rule", i)
                        }
                    }
                } catch (e: Exception) { errors.add("Rule $i: ${e.message}") }
            }
        }

        // Import saved API requests
        //
        // Master plan §1.6(b): jsonToApiRequestItem() reads every field requestToJson() writes,
        // each with an opt* default -- so a NEW export (full ApiRequestItem) round-trips
        // losslessly, and an OLD export (only id/name/method/url/isFavorite/updatedAt) still
        // imports fine, just with every field this build added since then at its normal
        // default (e.g. authType NONE, no headers).
        root.optJSONArray("savedRequests")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val r = arr.getJSONObject(i)
                    db.savedApiRequestDao().upsert(jsonToApiRequestItem(r).toEntity())
                    requestsImported++
                } catch (e: Exception) { errors.add("Request $i: ${e.message}") }
            }
        }

        // Import flow pipelines -- must run before nodes/connections below so a flow's
        // FlowPipelineEntity row exists (name/status/etc.) as soon as its nodes do, matching
        // export's ordering. See exportWorkspace()'s "Flow pipelines" block for why this entity
        // is required, not optional, for a multi-flow workspace to reconstruct correctly.
        root.optJSONArray("flowPipelines")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val p = arr.getJSONObject(i)
                    db.flowPipelineDao().upsert(
                        com.example.data.local.FlowPipelineEntity(
                            id = p.getString("id"),
                            name = p.optString("name", ""),
                            description = p.optString("description", ""),
                            triggerType = p.optString("triggerType", "Manual"),
                            status = p.optString("status", "DRAFT"),
                            lastRunText = p.optString("lastRunText", "Never"),
                            runsCount = p.optInt("runsCount", 0),
                            createdAt = if (p.has("createdAt")) p.optLong("createdAt", System.currentTimeMillis()) else System.currentTimeMillis(),
                        )
                    )
                    pipelinesImported++
                } catch (e: Exception) { errors.add("Pipeline $i: ${e.message}") }
            }
        }

        // Import flow nodes
        root.optJSONArray("flowNodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val n = arr.getJSONObject(i)
                    db.flowNodeDao().upsert(
                        com.example.data.local.FlowNodeEntity(
                            id = n.getString("id"),
                            flowId = n.optString("flowId", "default"),
                            type = n.optString("type", "API"),
                            label = n.optString("label", ""),
                            positionX = n.optDouble("x", 0.0).toFloat(),
                            positionY = n.optDouble("y", 0.0).toFloat(),
                            configData = n.optString("configData", ""),
                            createdAt = if (n.has("createdAt")) n.optLong("createdAt", System.currentTimeMillis()) else System.currentTimeMillis(),
                        )
                    )
                    nodesImported++
                } catch (e: Exception) { errors.add("Node $i: ${e.message}") }
            }
        }

        // Import flow connections
        root.optJSONArray("flowConnections")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val c = arr.getJSONObject(i)
                    db.flowConnectionDao().upsert(
                        com.example.data.local.FlowConnectionEntity(
                            id = c.getString("id"),
                            flowId = c.optString("flowId", "default"),
                            fromNodeId = c.getString("from"),
                            toNodeId = c.getString("to"),
                            fromPort = c.optString("fromPort", "out"),
                            toPort = c.optString("toPort", "in"),
                            createdAt = if (c.has("createdAt")) c.optLong("createdAt", System.currentTimeMillis()) else System.currentTimeMillis(),
                        )
                    )
                    connsImported++
                } catch (e: Exception) { errors.add("Conn $i: ${e.message}") }
            }
        }

        ImportResult(rulesImported, requestsImported, pipelinesImported, nodesImported, connsImported, errors, warnings, foldersImported)
    }

    // ── Full-fidelity saved-request JSON (§1.6(b)) ───────────────────────────────────────────

    /**
     * Serializes every field of [item] -- not just id/name/method/url/isFavorite/updatedAt --
     * so [importWorkspace] can reconstruct the exact same [ApiRequestItem] a Postman export of
     * the same request would carry (headers, query params, auth, body, formData,
     * chainExtractions, assertions, apiKeyLocation, and the §2.8 mock-mode fields
     * (mockEnabled/mockStatusCode/mockStatusMessage/mockHeaders/mockBody/mockDelayMs) all
     * included).
     */
    private fun requestToJson(item: ApiRequestItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("name", item.name)
        item.folderId?.let { put("folderId", it) }
        put("method", item.method.name)
        put("url", item.url)
        put("queryParams", keyValueListToJson(item.queryParams))
        put("headers", keyValueListToJson(item.headers))
        put("authType", item.authType.name)
        put("bearerToken", item.bearerToken)
        put("basicUsername", item.basicUsername)
        put("basicPassword", item.basicPassword)
        put("apiKeyHeader", item.apiKeyHeader)
        put("apiKeyValue", item.apiKeyValue)
        put("apiKeyLocation", item.apiKeyLocation.name)
        put("bodyType", item.bodyType.name)
        put("bodyContent", item.bodyContent)
        put("formData", keyValueListToJson(item.formData))
        put("preScript", item.preScript)
        put("postScript", item.postScript)
        put("isFavorite", item.isFavorite)
        put("updatedAt", item.updatedAt)
        // Master plan §2.8 -- these six fields were added in the same change that introduced
        // this full-fidelity export/import and were missed the first time around: exporting and
        // re-importing a mock-enabled request silently turned mock mode back off and dropped its
        // configured response.
        put("mockEnabled", item.mockEnabled)
        put("mockStatusCode", item.mockStatusCode)
        put("mockStatusMessage", item.mockStatusMessage)
        put("mockHeaders", keyValueListToJson(item.mockHeaders))
        put("mockBody", item.mockBody)
        put("mockDelayMs", item.mockDelayMs)
        // Generic field-drift fix: async 202-polling config (Master plan §2.x LOW-ADD) was
        // added to ApiRequestItem/SavedApiRequestEntity after this exporter was last updated
        // and was never wired in here -- a saved request with polling configured silently lost
        // that configuration on export/import, same class of bug as the crypto fields MUST-FIX.
        put("pollingEnabled", item.pollingEnabled)
        put("pollingIntervalMs", item.pollingIntervalMs)
        put("pollingMaxAttempts", item.pollingMaxAttempts)
        put("pollingLocationHeader", item.pollingLocationHeader)
        put("pollingAllowCrossOriginRedirect", item.pollingAllowCrossOriginRedirect)
        put("chainExtractions", JSONArray().apply {
            item.chainExtractions.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("jsonPath", rule.jsonPath)
                    put("variableName", rule.variableName)
                    put("enabled", rule.enabled)
                })
            }
        })
        put("assertions", JSONArray().apply {
            item.assertions.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("target", a.target.name)
                    put("targetKey", a.targetKey)
                    put("operator", a.operator.name)
                    put("expectedValue", a.expectedValue)
                    put("enabled", a.enabled)
                })
            }
        })
    }

    /**
     * Reverse of [requestToJson], with an `opt*` default for every field so an OLD workspace
     * export (only id/name/method/url/isFavorite/updatedAt -- everything else absent) still
     * imports without error, just at each field's normal [ApiRequestItem] default.
     */
    private fun jsonToApiRequestItem(r: JSONObject): ApiRequestItem = ApiRequestItem(
        id = r.getString("id"),
        name = r.optString("name", "Untitled Request"),
        folderId = r.optString("folderId", "").ifBlank { null },
        method = runCatching { HttpMethod.valueOf(r.optString("method", "GET")) }.getOrDefault(HttpMethod.GET),
        url = r.optString("url", ""),
        queryParams = jsonToKeyValueList(r.optJSONArray("queryParams")),
        headers = jsonToKeyValueList(r.optJSONArray("headers")),
        authType = runCatching { AuthType.valueOf(r.optString("authType", "NONE")) }.getOrDefault(AuthType.NONE),
        bearerToken = r.optString("bearerToken", ""),
        basicUsername = r.optString("basicUsername", ""),
        basicPassword = r.optString("basicPassword", ""),
        apiKeyHeader = r.optString("apiKeyHeader", "X-API-Key"),
        apiKeyValue = r.optString("apiKeyValue", ""),
        apiKeyLocation = runCatching { ApiKeyLocation.valueOf(r.optString("apiKeyLocation", "HEADER")) }.getOrDefault(ApiKeyLocation.HEADER),
        bodyType = runCatching { RequestBodyType.valueOf(r.optString("bodyType", "NONE")) }.getOrDefault(RequestBodyType.NONE),
        bodyContent = r.optString("bodyContent", ""),
        formData = jsonToKeyValueList(r.optJSONArray("formData")),
        preScript = r.optString("preScript", ""),
        postScript = r.optString("postScript", ""),
        isFavorite = r.optBoolean("isFavorite", false),
        updatedAt = r.optLong("updatedAt", System.currentTimeMillis()),
        mockEnabled = r.optBoolean("mockEnabled", false),
        mockStatusCode = r.optInt("mockStatusCode", 200),
        mockStatusMessage = r.optString("mockStatusMessage", "OK"),
        mockHeaders = jsonToKeyValueList(r.optJSONArray("mockHeaders")),
        mockBody = r.optString("mockBody", "{\"mocked\": true}"),
        mockDelayMs = r.optLong("mockDelayMs", 0L),
        pollingEnabled = r.optBoolean("pollingEnabled", false),
        pollingIntervalMs = r.optLong("pollingIntervalMs", 2000L),
        pollingMaxAttempts = r.optInt("pollingMaxAttempts", 10),
        pollingLocationHeader = r.optString("pollingLocationHeader", "Location"),
        pollingAllowCrossOriginRedirect = r.optBoolean("pollingAllowCrossOriginRedirect", false),
        chainExtractions = r.optJSONArray("chainExtractions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChainExtractionRule(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    jsonPath = o.optString("jsonPath", ""),
                    variableName = o.optString("variableName", ""),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } ?: emptyList(),
        assertions = r.optJSONArray("assertions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Assertion(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    target = runCatching { AssertionTarget.valueOf(o.optString("target", "STATUS_CODE")) }.getOrDefault(AssertionTarget.STATUS_CODE),
                    targetKey = o.optString("targetKey", ""),
                    operator = runCatching { AssertionOperator.valueOf(o.optString("operator", "EQUALS")) }.getOrDefault(AssertionOperator.EQUALS),
                    expectedValue = o.optString("expectedValue", ""),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } ?: emptyList(),
    )

    private fun keyValueListToJson(items: List<KeyValueItem>): JSONArray = JSONArray().apply {
        items.forEach { kv ->
            put(JSONObject().apply {
                put("id", kv.id)
                put("key", kv.key)
                put("value", kv.value)
                put("isEnabled", kv.isEnabled)
                put("description", kv.description)
            })
        }
    }

    private fun jsonToKeyValueList(arr: JSONArray?): List<KeyValueItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            KeyValueItem(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                key = o.optString("key", ""),
                value = o.optString("value", ""),
                isEnabled = o.optBoolean("isEnabled", true),
                description = o.optString("description", ""),
            )
        }
    }

    data class ImportResult(
        val rulesImported: Int,
        val requestsImported: Int,
        /** Multi-flow support: how many [com.example.data.local.FlowPipelineEntity] rows imported. */
        val pipelinesImported: Int,
        val nodesImported: Int,
        val connsImported: Int,
        val errors: List<String>,
        /**
         * Non-fatal notices about an item that *was* imported but in a deliberately degraded
         * state -- e.g. a crypto rule (see [importWorkspace]'s crypto-rule gate) imported
         * disabled because its AndroidKeyStore alias is blank or doesn't exist on this device.
         * Distinct from [errors] (which means the item was NOT imported at all): the item is
         * still present in the DB, just not doing what its export-time config implied. Folded
         * into [summary] so the existing "show the summary in a Toast" call site
         * ([com.example.ui.screens.SettingsScreen]'s workspace-import launcher) surfaces these
         * without needing its own change.
         */
        val warnings: List<String> = emptyList(),
        /** S4 task 34: how many typed rule folders (`ruleFolders`, v2 exports only) were imported. */
        val foldersImported: Int = 0,
    ) {
        val summary: String
            get() {
                val folderPart = if (foldersImported > 0) " in $foldersImported folders" else ""
                val base = "Imported $rulesImported rules$folderPart, $requestsImported requests, $pipelinesImported flows, $nodesImported nodes, $connsImported connections"
                val notes = buildList {
                    if (warnings.isNotEmpty()) add("${warnings.size} warning(s): ${warnings.joinToString("; ")}")
                    if (errors.isNotEmpty()) add("${errors.size} error(s)")
                }
                return if (notes.isEmpty()) base else "$base (${notes.joinToString("; ")})"
            }
    }
}
