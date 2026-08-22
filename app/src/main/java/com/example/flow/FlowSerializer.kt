package com.example.flow

import com.example.data.models.FlowConnectionItem
import com.example.data.models.FlowNodeItem
import com.example.data.models.FlowNodeType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes/deserializes a Flow graph (nodes + connections) to JSON.
 * Format is n8n-compatible: nodes have id, name, type, position, parameters.
 * Connections have source/target node IDs + port names.
 *
 * Example export:
 * {
 *   "name": "My Flow",
 *   "nodes": [{"id":"n1","name":"Get Token","type":"api","position":{"x":100,"y":100},"parameters":{...}}],
 *   "connections": [{"from":"n1","to":"n2","fromPort":"output","toPort":"input"}]
 * }
 */
object FlowSerializer {

    data class FlowExport(
        val name: String,
        val nodes: List<FlowNodeItem>,
        val connections: List<FlowConnectionItem>,
    )

    fun exportToJson(
        flowName: String,
        nodes: List<FlowNodeItem>,
        connections: List<FlowConnectionItem>,
    ): String {
        val json = JSONObject()
        json.put("name", flowName)
        json.put("version", "1.0")

        val nodesArray = JSONArray()
        nodes.forEach { node ->
            val nodeJson = JSONObject()
            nodeJson.put("id", node.id)
            nodeJson.put("name", node.title)
            nodeJson.put("type", node.type.name)
            nodeJson.put("typeVersion", 1)
            nodeJson.put("position", JSONObject().apply {
                put("x", node.x)
                put("y", node.y)
            })
            if (node.configData.isNotBlank()) {
                nodeJson.put("parameters", JSONObject(node.configData))
            }
            nodesArray.put(nodeJson)
        }
        json.put("nodes", nodesArray)

        val connsArray = JSONArray()
        connections.forEach { conn ->
            val connJson = JSONObject()
            connJson.put("source", conn.fromNodeId)
            connJson.put("target", conn.toNodeId)
            connJson.put("sourcePort", conn.fromPort)
            connJson.put("targetPort", conn.toPort)
            connsArray.put(connJson)
        }
        json.put("connections", connsArray)

        return json.toString(2)
    }

    fun importFromJson(json: String): Pair<List<FlowNodeItem>, List<FlowConnectionItem>>? {
        return try {
            val root = JSONObject(json)
            val nodesArray = root.optJSONArray("nodes") ?: return null
            val connsArray = root.optJSONArray("connections") ?: JSONArray()

            val nodes = mutableListOf<FlowNodeItem>()
            for (i in 0 until nodesArray.length()) {
                val nodeJson = nodesArray.getJSONObject(i)
                val type = nodeJson.optString("type", "API").let { typeName ->
                    runCatching { FlowNodeType.valueOf(typeName) }.getOrDefault(FlowNodeType.API)
                }
                val position = nodeJson.optJSONObject("position")
                nodes.add(
                    FlowNodeItem(
                        id = nodeJson.getString("id"),
                        title = nodeJson.optString("name", "Node"),
                        type = type,
                        x = position?.optDouble("x", 100.0)?.toFloat() ?: 100f,
                        y = position?.optDouble("y", 100.0)?.toFloat() ?: 100f,
                        configData = nodeJson.optJSONObject("parameters")?.toString() ?: "",
                    )
                )
            }

            val connections = mutableListOf<FlowConnectionItem>()
            for (i in 0 until connsArray.length()) {
                val connJson = connsArray.getJSONObject(i)
                connections.add(
                    FlowConnectionItem(
                        id = connJson.optString("id", "${connJson.getString("source")}_${connJson.getString("target")}"),
                        fromNodeId = connJson.getString("source"),
                        toNodeId = connJson.getString("target"),
                        fromPort = connJson.optString("sourcePort", "output"),
                        toPort = connJson.optString("targetPort", "input"),
                    )
                )
            }

            nodes to connections
        } catch (e: Exception) {
            null
        }
    }
}
