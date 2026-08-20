package com.example.plugin

/**
 * Core plugin interface for requestr extensions.
 *
 * Plugins hook into interception, flow execution, and tool pipelines.
 * Built-in plugins are registered at startup; external plugins can be
 * loaded from /sdcard/requestr/plugins/ as .jar or .dex files.
 */
interface Plugin {
    /** Globally unique plugin identifier, e.g. "com.example.json-prettifier" */
    val id: String

    /** Human-readable name shown in the UI */
    val name: String

    /** Short description of what the plugin does */
    val description: String

    /** Plugin version string */
    val version: String

    /** Author or organization */
    val author: String

    /** Permissions this plugin requires (empty list = none needed) */
    val requiredPermissions: List<PluginPermission>
        get() = emptyList()

    /** Called when the plugin is loaded and registered */
    fun onLoad() {}

    /** Called when the plugin is unloaded (app shutdown, user disable) */
    fun onUnload() {}

    /**
     * Intercept outgoing HTTP requests. Return null to pass through,
     * or return a modified request to transform it.
     */
    fun onBeforeRequest(request: PluginHttpRequest): PluginHttpRequest? = null

    /**
     * Intercept incoming HTTP responses. Return null to pass through,
     * or return a modified response body. The response object carries
     * the original status code, headers, and body.
     */
    fun onAfterResponse(response: PluginHttpResponse): PluginHttpResponse? = null

    /**
     * Decode custom/non-standard protocol data. Return the decoded
     * payload as a string, or null if this plugin can't decode it.
     */
    fun customDecode(contentType: String, rawBytes: ByteArray): String? = null

    /**
     * Tool execution hook. Called when a flow executes a tool node.
     * Plugins can provide custom tool implementations.
     */
    fun executeCustomTool(toolName: String, input: Map<String, String>): String? = null
}

/**
 * HTTP request model for plugin hooks.
 */
data class PluginHttpRequest(
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

/**
 * HTTP response model for plugin hooks.
 */
data class PluginHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val requestUrl: String
)

/**
 * Plugin permissions model.
 */
enum class PluginPermission(val displayName: String, val description: String) {
    READ_CAPTURES("Read Captures", "Access intercepted HTTP traffic logs"),
    MODIFY_REQUESTS("Modify Requests", "Transform outgoing HTTP requests"),
    MODIFY_RESPONSES("Modify Responses", "Transform incoming HTTP responses"),
    EXECUTE_TOOLS("Execute Tools", "Provide custom tool implementations"),
    READ_FLOWS("Read Flows", "Access flow automation definitions"),
    EXECUTE_FLOWS("Execute Flows", "Trigger flow executions"),
    READ_SETTINGS("Read Settings", "Read app configuration"),
    NETWORK_ACCESS("Network Access", "Make outbound network requests")
}
