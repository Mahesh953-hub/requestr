package com.example.data.export

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace system — exports/imports the app's intercept rules, saved API requests, and
 * flow graphs (nodes + connections) as a single JSON file.
 *
 * Environments, notes, and settings are not part of this export. Notes become exportable
 * after flow/notes-repository work lands; extending the export to cover them is a clean
 * follow-up at that point.
 */
object WorkspaceExporter {

    /**
     * Exports the workspace as a JSON string.
     * Includes: intercept rules, saved requests, flow nodes and connections.
     */
    suspend fun exportWorkspace(context: Context): String = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val root = JSONObject()
        root.put("version", "1.0")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", com.example.BuildConfig.VERSION_NAME)

        // Intercept rules
        val rules = db.interceptRuleDao().getEnabledRules()
        val rulesArray = JSONArray()
        rules.forEach { rule ->
            rulesArray.put(JSONObject().apply {
                put("name", rule.name)
                put("pattern", rule.pattern)
                put("domain", rule.domain)
                put("action", rule.action.name)
                put("enabled", rule.enabled)
                put("replacementValue", rule.replacementValue)
            })
        }
        root.put("interceptRules", rulesArray)

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

        // Flow nodes and connections
        val flowNodes = db.flowNodeDao().getAll()
        val flowConns = db.flowConnectionDao().getAll()
        val nodesArray = JSONArray()
        flowNodes.forEach { node ->
            nodesArray.put(JSONObject().apply {
                put("id", node.id)
                put("type", node.type)
                put("label", node.label)
                put("x", node.positionX)
                put("y", node.positionY)
                put("configData", node.configData)
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
            })
        }
        root.put("flowNodes", nodesArray)
        root.put("flowConnections", connsArray)

        root.toString(2)
    }

    /**
     * Imports a workspace from a JSON string. Merges with existing data
     * (does not delete existing entries).
     */
    suspend fun importWorkspace(context: Context, json: String): ImportResult = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val root = JSONObject(json)
        var rulesImported = 0
        var requestsImported = 0
        var nodesImported = 0
        var connsImported = 0
        val errors = mutableListOf<String>()

        // Import rules
        root.optJSONArray("interceptRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val r = arr.getJSONObject(i)
                    db.interceptRuleDao().insert(
                        com.example.data.local.InterceptRuleEntity(
                            name = r.optString("name", ""),
                            pattern = r.optString("pattern", ""),
                            domain = r.optString("domain", ""),
                            // A workspace exported by an older build won't know FORCE_MITM (or any
                            // future action) yet -- fall back to LOG_ONLY rather than failing the
                            // whole rule import on an unrecognized action name.
                            action = runCatching {
                                com.example.data.local.RuleAction.valueOf(r.optString("action", "LOG_ONLY"))
                            }.getOrDefault(com.example.data.local.RuleAction.LOG_ONLY),
                            enabled = r.optBoolean("enabled", true),
                            replacementValue = r.optString("replacementValue").takeIf { it.isNotBlank() },
                        )
                    )
                    rulesImported++
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

        // Import flow nodes
        root.optJSONArray("flowNodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                try {
                    val n = arr.getJSONObject(i)
                    db.flowNodeDao().upsert(
                        com.example.data.local.FlowNodeEntity(
                            id = n.getString("id"),
                            type = n.optString("type", "API"),
                            label = n.optString("label", ""),
                            positionX = n.optDouble("x", 0.0).toFloat(),
                            positionY = n.optDouble("y", 0.0).toFloat(),
                            configData = n.optString("configData", ""),
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
                            fromNodeId = c.getString("from"),
                            toNodeId = c.getString("to"),
                            fromPort = c.optString("fromPort", "out"),
                            toPort = c.optString("toPort", "in"),
                        )
                    )
                    connsImported++
                } catch (e: Exception) { errors.add("Conn $i: ${e.message}") }
            }
        }

        ImportResult(rulesImported, requestsImported, nodesImported, connsImported, errors)
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
        val nodesImported: Int,
        val connsImported: Int,
        val errors: List<String>,
    ) {
        val summary: String
            get() = "Imported $rulesImported rules, $requestsImported requests, $nodesImported nodes, $connsImported connections" +
                if (errors.isNotEmpty()) " (${errors.size} errors)" else ""
    }
}
