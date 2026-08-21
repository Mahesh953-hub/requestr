package com.example.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in plugin that prettifies JSON response bodies,
 * adds an "x-requestr-json-prettified" header, and provides
 * a "json-prettify" custom tool for Flow automation.
 */
class JsonPrettifierPlugin : Plugin {
    override val id = "com.requestr.json-prettifier"
    override val name = "JSON Prettifier"
    override val description = "Auto-formats JSON responses with indentation and adds a prettified header marker"
    override val version = "1.0.0"
    override val author = "requestr"

    override val requiredPermissions = listOf(
        PluginPermission.MODIFY_RESPONSES,
        PluginPermission.EXECUTE_TOOLS
    )

    override fun onLoad() {
        // No setup needed
    }

    override fun onUnload() {
        // No cleanup needed
    }

    override fun onAfterResponse(response: PluginHttpResponse): PluginHttpResponse? {
        val contentType = response.headers["Content-Type"] ?: response.headers["content-type"] ?: return null
        if (!contentType.contains("json", ignoreCase = true)) return null

        val bodyStr = String(response.body)
        return try {
            val pretty = when {
                bodyStr.trimStart().startsWith("{") ->
                    JSONObject(bodyStr).toString(2)
                bodyStr.trimStart().startsWith("[") ->
                    JSONArray(bodyStr).toString(2)
                else -> return null
            }
            val newHeaders = response.headers.toMutableMap()
            newHeaders["x-requestr-json-prettified"] = "true"
            response.copy(body = pretty.toByteArray(), headers = newHeaders)
        } catch (_: Exception) {
            null
        }
    }

    override fun executeCustomTool(toolName: String, input: Map<String, String>): String? {
        if (toolName != "json-prettify") return null
        val raw = input["json"] ?: return """{"error":"missing 'json' parameter"}"""
        return try {
            when {
                raw.trimStart().startsWith("{") -> JSONObject(raw).toString(2)
                raw.trimStart().startsWith("[") -> JSONArray(raw).toString(2)
                else -> """{"error":"invalid JSON"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }
}
