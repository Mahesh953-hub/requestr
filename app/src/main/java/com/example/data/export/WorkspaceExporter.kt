package com.example.data.export

import android.content.Context
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace system — exports/imports the full app state as a single JSON file.
 *
 * A workspace includes: intercept rules, saved API requests, flow graphs,
 * environments, notes, and settings. This enables backup/restore and
 * sharing configurations between devices.
 *
 * TODO #50 (Workspace system).
 */
object WorkspaceExporter {

    /**
     * Exports the full workspace as a JSON string.
     * Includes: rules, saved requests, flows, notes.
     */
    suspend fun exportWorkspace(context: Context): String = withContext(Dispatchers.IO) {
        val db = RequestrDatabase.get(context)
        val root = JSONObject()
        root.put("version", "1.0")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", "3.8")

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
        val savedRequests = db.savedApiRequestDao().getAll()
        val requestsArray = JSONArray()
        savedRequests.forEach { req ->
            requestsArray.put(JSONObject().apply {
                put("id", req.id)
                put("name", req.name)
                put("method", req.method)
                put("url", req.url)
                put("isFavorite", req.isFavorite)
                put("updatedAt", req.updatedAt)
            })
        }
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
