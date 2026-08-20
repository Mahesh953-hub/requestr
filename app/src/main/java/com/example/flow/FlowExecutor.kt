package com.example.flow

import android.util.Log
import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import kotlinx.coroutines.delay

data class FlowResult(
    val success: Boolean,
    val nodesExecuted: Int,
    val totalDurationMs: Long,
    val outputs: Map<String, String>,
    val errors: List<String>,
)

class FlowExecutor(
    private val httpExecutor: suspend (url: String, method: String, headers: Map<String, String>, body: String) -> String,
) {
    companion object {
        private const val TAG = "FlowExecutor"
    }

    suspend fun execute(nodes: List<FlowNodeItem>, connections: List<FlowConnectionItem>): FlowResult {
        val t0 = System.currentTimeMillis()
        val adjacency = buildAdjacency(connections)
        val nodeMap = nodes.associateBy { it.id }
        val outputs = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        var executed = 0

        // Find entry points: nodes with no incoming connections
        val hasIncoming = connections.map { it.toNodeId }.toSet()
        val entryNodes = nodes.filter { it.id !in hasIncoming }

        if (entryNodes.isEmpty()) {
            return FlowResult(false, 0, 0, emptyMap(), listOf("No entry/trigger nodes found"))
        }

        // BFS walk — execute each node, pass outputs forward
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        entryNodes.forEach { queue.addLast(it.id) }

        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            if (nodeId in visited) continue
            val node = nodeMap[nodeId] ?: continue
            visited.add(nodeId)

            // Gather inputs from upstream connections
            val upstreamInputs = connections
                .filter { it.toNodeId == nodeId }
                .associate { it.toPort to (outputs[it.fromNodeId] ?: "") }

            val result = executeNode(node, upstreamInputs, httpExecutor)
            if (result != null) {
                outputs[nodeId] = result
                executed++
            } else {
                errors.add("Node '${node.title}' (${node.type.label}) returned no output")
            }

            // Enqueue downstream nodes
            adjacency[nodeId]?.forEach { nextId ->
                if (nextId !in visited) queue.addLast(nextId)
            }
        }

        val duration = System.currentTimeMillis() - t0
        return FlowResult(
            success = errors.isEmpty(),
            nodesExecuted = executed,
            totalDurationMs = duration,
            outputs = outputs,
            errors = errors,
        )
    }

    private suspend fun executeNode(
        node: FlowNodeItem,
        inputs: Map<String, String>,
        http: suspend (String, String, Map<String, String>, String) -> String,
    ): String? {
        return when (node.type) {
            FlowNodeType.API -> executeApiNode(node, http)
            FlowNodeType.INTERCEPT_TRIGGER -> {
                // Trigger nodes don't "execute" in a run — they represent the entry point.
                // In a manual run, just pass through. In event-driven mode, the trigger
                // is the capture event that started the flow.
                "trigger:${node.title}:activated"
            }
            FlowNodeType.TRANSFORM -> executeTransformNode(node, inputs)
            FlowNodeType.CONDITION -> executeConditionNode(node, inputs)
            FlowNodeType.TEXT -> node.configData.ifBlank { node.title }
            FlowNodeType.TOOL -> executeToolNode(node, inputs)
            FlowNodeType.OUTPUT -> inputs.values.firstOrNull() ?: node.title
            FlowNodeType.DELAY -> executeDelayNode(node)
        }
    }

    private suspend fun executeApiNode(
        node: FlowNodeItem,
        http: suspend (String, String, Map<String, String>, String) -> String,
    ): String {
        return try {
            val config = node.configData
            // configData is JSON-like: {"url":"...","method":"GET","headers":{...},"body":"..."}
            val url = extractJsonField(config, "url") ?: "https://httpbin.org/get"
            val method = extractJsonField(config, "method") ?: "GET"
            val body = extractJsonField(config, "body") ?: ""

            val headers = mutableMapOf<String, String>(
                "User-Agent" to "requestr-flow/3.0",
                "Accept" to "application/json",
            )

            http(url, method, headers, body)
        } catch (e: Exception) {
            Log.w(TAG, "API node '${node.title}' failed: ${e.message}")
            "{\"error\": \"${e.message}\"}"
        }
    }

    private fun executeTransformNode(node: FlowNodeItem, inputs: Map<String, String>): String {
        val input = inputs.values.firstOrNull() ?: return "{}"
        val config = node.configData

        // Apply JSONPath-like extraction: configData is e.g. "token" or "$.data.token"
        val path = config.trim()
        if (path.isEmpty()) return input

        return try {
            JsonFieldExtractor.extract(input, path) ?: input
        } catch (_: Exception) {
            input
        }
    }

    private fun executeConditionNode(node: FlowNodeItem, inputs: Map<String, String>): String {
        val input = inputs.values.firstOrNull() ?: "false"
        // configData: "status == 200" or "body contains token" or "isEmpty" or custom expression
        val config = node.configData.lowercase().trim()
        return when {
            config.contains("status") && config.contains("200") -> {
                if (input.contains("200") || input.contains("\"status\":200")) "true" else "false"
            }
            config.contains("contains") -> {
                val keyword = config.substringAfter("contains").trim()
                if (input.contains(keyword, ignoreCase = true)) "true" else "false"
            }
            config.contains("empty") || config.contains("isempty") -> {
                if (input.isBlank() || input == "{}") "true" else "false"
            }
            config.contains("not empty") || config.contains("notempty") -> {
                if (input.isNotBlank() && input != "{}") "true" else "false"
            }
            config.contains("equals") -> {
                val expected = config.substringAfter("equals").trim()
                if (input.trim().equals(expected, ignoreCase = true)) "true" else "false"
            }
            config.contains("matches") -> {
                val pattern = config.substringAfter("matches").trim()
                if (runCatching { Regex(pattern).containsMatchIn(input) }.getOrDefault(false)) "true" else "false"
            }
            else -> "true"
        }
    }

    /**
     * TOOL node: dispatches to real ToolsRepository functions based on configData.
     * configData format: {"toolId":"base64-encode","input":"data to process"}
     */
    private fun executeToolNode(node: FlowNodeItem, inputs: Map<String, String>): String {
        val config = node.configData
        val toolId = extractJsonField(config, "toolId") ?: node.title.lowercase().replace(" ", "-")
        val input = extractJsonField(config, "input")
            ?: inputs.values.firstOrNull()
            ?: ""

        return try {
            com.example.data.repository.ToolsRepository.executeToolFunction(toolId, input)
        } catch (e: Exception) {
            Log.w(TAG, "Tool node '${node.title}' (toolId=$toolId) failed: ${e.message}")
            "{\"error\": \"Tool execution failed: ${e.message}\"}"
        }
    }

    /**
     * DELAY node: pauses execution for the configured duration.
     * configData: milliseconds as string (e.g., "2000" for 2 seconds)
     */
    private suspend fun executeDelayNode(node: FlowNodeItem): String {
        val delayMs = node.configData.trim().toLongOrNull() ?: 1000L
        val clamped = delayMs.coerceIn(100L, 60_000L) // min 100ms, max 60s
        delay(clamped)
        return "delayed:${clamped}ms"
    }

    private fun buildAdjacency(connections: List<FlowConnectionItem>): Map<String, List<String>> {
        val adj = mutableMapOf<String, MutableList<String>>()
        connections.forEach { conn ->
            adj.getOrPut(conn.fromNodeId) { mutableListOf() }.add(conn.toNodeId)
        }
        return adj
    }

    /**
     * v3.2 Part C — C2: delegates to the shared [JsonFieldExtractor] (moved out during C2 so the
     * request-chaining feature reuses this exact regex pair instead of re-implementing it).
     */
    private fun extractJsonField(json: String, key: String): String? =
        JsonFieldExtractor.extractByKey(json, key.trim().removeSurrounding("\""))
}
