package com.example.requestr.cli

import okhttp3.MediaType.Companion.toMediaType
import kotlin.system.exitProcess

/**
 * requestr CLI — command-line interface for managing requestr.
 *
 * Communicates with the Local API server running on the device.
 * Default API base: http://localhost:9876
 *
 * Usage:
 *   requestr captures              List recent captures
 *   requestr capture <id>          Show capture details
 *   requestr flows                 List flows
 *   requestr flow create <json>    Create a flow
 *   requestr flow execute <id>     Execute a flow
 *   requestr flow delete <id>      Delete a flow
 *   requestr tools                 List available tools
 *   requestr tool exec <id> <in>   Execute a tool
 *   requestr rules                 List intercept rules
 *   requestr plugin <tool> <json>  Dispatch to a plugin
 *   requestr status                API server status
 *   requestr ping                  Health check
 *   requestr --help                This message
 *
 * Options:
 *   --api-base <url>     API server URL (default: http://localhost:9876)
 *   --token <token>      Pairing token for bearer-protected routes (/api/rules, /api/observe,
 *                        /api/keylog). Falls back to the REQUESTR_TOKEN environment variable
 *                        so the token does not have to appear in shell history.
 *   -q, --quiet          Suppress informational output
 *   -v, --version        Print version
 */
object RequestrCli {
    /** Environment variable consulted when `--token` is not given. */
    const val TOKEN_ENV_VAR = "REQUESTR_TOKEN"

    private var apiBase = "http://localhost:9876"
    private var quiet = false
    /** Never logged or echoed -- only ever placed in the Authorization header. */
    private var token: String? = null
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printHelp()
            exitProcess(1)
        }

        val parsed = parseArgs(args.toList())
        val (options, positional) = parsed

        options["api-base"]?.let { apiBase = it }
        quiet = options.containsKey("q") || options.containsKey("quiet")
        token = resolveToken(options, System.getenv())

        if (options.containsKey("v") || options.containsKey("version")) {
            println("requestr CLI v4.0")
            exitProcess(0)
        }

        if (options.containsKey("help")) {
            printHelp()
            exitProcess(0)
        }

        if (positional.isEmpty()) {
            System.err.println("Error: no command specified")
            printHelp()
            exitProcess(1)
        }

        val exitCode = run(positional)
        exitProcess(exitCode)
    }

    /**
     * The pairing token to send, or null for anonymous calls. `--token` wins; otherwise
     * [TOKEN_ENV_VAR]. Blank values count as absent so `REQUESTR_TOKEN=` does not send
     * `Bearer ` with nothing behind it.
     */
    internal fun resolveToken(options: Map<String, String?>, env: Map<String, String>): String? =
        options["token"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: env[TOKEN_ENV_VAR]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Exact `Authorization` header value LocalApiServer.isAuthorized accepts: the literal
     * `Bearer ` scheme followed by the token (the server compares the remainder, trimmed, for
     * equality with the pairing token).
     */
    internal fun authorizationHeaderValue(token: String): String = "Bearer $token"

    internal fun parseArgs(args: List<String>): Pair<Map<String, String?>, List<String>> {
        val options = mutableMapOf<String, String?>()
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--api-base" && i + 1 < args.size -> {
                    options["api-base"] = args[++i]
                }
                args[i] == "--token" && i + 1 < args.size -> {
                    options["token"] = args[++i]
                }
                args[i] == "--token" -> {
                    // Trailing flag with no value: record it empty so resolveToken falls back
                    // to the environment.
                    options["token"] = ""
                }
                args[i].startsWith("--") -> {
                    val key = args[i].removePrefix("--")
                    if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                        options[key] = args[++i]
                    } else {
                        options[key] = null
                    }
                }
                args[i].startsWith("-") -> {
                    val key = args[i].removePrefix("-")
                    options[key] = null
                }
                else -> positional.add(args[i])
            }
            i++
        }
        return options to positional
    }

    private fun run(args: List<String>): Int {
        return try {
            when (args[0]) {
                "captures" -> cmdCaptures(args.drop(1))
                "capture" -> cmdCaptureDetail(args.drop(1))
                "flows" -> cmdFlows(args.drop(1))
                "flow" -> cmdFlowAction(args.drop(1))
                "tools" -> cmdTools(args.drop(1))
                "tool" -> cmdToolAction(args.drop(1))
                "rules" -> cmdRules(args.drop(1))
                "plugin" -> cmdPlugin(args.drop(1))
                "status" -> cmdStatus(args.drop(1))
                "ping" -> cmdPing(args.drop(1))
                else -> {
                    System.err.println("Error: unknown command '${args[0]}'")
                    printHelp()
                    1
                }
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            1
        }
    }

    // ── Commands ─────────────────────────────────────────────────

    private fun cmdCaptures(args: List<String>): Int {
        val response = get("/api/captures")
        val items = org.json.JSONArray(response)
        if (items.length() == 0) {
            println("No captures")
            return 0
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val method = item.optString("method", "???")
            val url = item.optString("url", "???")
            val status = item.optInt("status", 0)
            val id = item.optString("id", "")
            println("[$method] $status $url  # $id")
        }
        return 0
    }

    private fun cmdCaptureDetail(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: requestr capture <id>")
            return 1
        }
        val id = args[0]
        val response = get("/api/captures/$id")
        val json = org.json.JSONObject(response)
        println("ID:       ${json.optString("id")}")
        println("Method:   ${json.optString("method")}")
        println("URL:      ${json.optString("url")}")
        println("Host:     ${json.optString("host")}")
        println("Status:   ${json.optInt("status")}")
        println("Req Head: ${json.optString("requestHeaders")}")
        println("Req Body: ${json.optString("requestBody")}")
        println("Res Head: ${json.optString("responseHeaders")}")
        println("Res Body: ${json.optString("responseBody")}")
        return 0
    }

    private fun cmdFlows(args: List<String>): Int {
        val response = get("/api/flows")
        val items = org.json.JSONArray(response)
        if (items.length() == 0) {
            println("No flows")
            return 0
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optString("id")
            val type = item.optString("type")
            val label = item.optString("label")
            println("$label  [$type]  # $id")
        }
        return 0
    }

    private fun cmdFlowAction(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: requestr flow <create|execute|delete> [...]")
            return 1
        }
        return when (args[0]) {
            "create" -> {
                val json = if (args.size > 1) args[1] else """{"label":"CLI Flow","type":"api"}"""
                post("/api/flows", json)
                println("Flow created")
                0
            }
            "execute" -> {
                if (args.size < 2) { System.err.println("Usage: requestr flow execute <id>"); return 1 }
                val result = post("/api/flows/${args[1]}/execute", "")
                println(result)
                0
            }
            "delete" -> {
                if (args.size < 2) { System.err.println("Usage: requestr flow delete <id>"); return 1 }
                delete("/api/flows/${args[1]}")
                println("Flow deleted")
                0
            }
            else -> {
                System.err.println("Unknown flow action: ${args[0]}")
                1
            }
        }
    }

    private fun cmdTools(args: List<String>): Int {
        val response = get("/api/tools")
        val items = org.json.JSONArray(response)
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optString("id")
            val title = item.optString("title")
            val cat = item.optString("category")
            println("$title  [$cat]  # $id")
        }
        return 0
    }

    private fun cmdToolAction(args: List<String>): Int {
        if (args.size < 2 || args[0] != "exec") {
            System.err.println("Usage: requestr tool exec <toolId> <input>")
            return 1
        }
        val toolId = args[1]
        val input = if (args.size > 2) args.drop(2).joinToString(" ") else ""
        val body = """{"toolId":"$toolId","input":"${input.replace("\"","\\\"")}"}"""
        val response = post("/api/tools/execute", body)
        println(response)
        return 0
    }

    private fun cmdRules(args: List<String>): Int {
        val response = get("/api/rules")
        val items = org.json.JSONArray(response)
        if (items.length() == 0) {
            println("No rules")
            return 0
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name")
            val action = item.optString("action")
            val enabled = item.optBoolean("enabled")
            val id = item.optString("id")
            println("${if (enabled) "✓" else "✗"} $name  [$action]  # $id")
        }
        return 0
    }

    private fun cmdPlugin(args: List<String>): Int {
        if (args.size < 2) {
            System.err.println("Usage: requestr plugin <toolName> <jsonInput>")
            return 1
        }
        val toolName = args[0]
        val jsonInput = if (args.size > 1) args.drop(1).joinToString(" ") else "{}"
        val body = try {
            val json = org.json.JSONObject(jsonInput)
            json.put("tool", toolName)
            json.toString()
        } catch (_: Exception) {
            """{"tool":"$toolName"}"""
        }
        val response = post("/api/plugin/execute", body)
        println(response)
        return 0
    }

    private fun cmdStatus(args: List<String>): Int {
        val response = get("/api/status")
        val json = org.json.JSONObject(response)
        println("API:       ${if (json.optBoolean("api_running")) "running" else "stopped"}")
        println("Port:      ${json.optInt("port")}")
        println("VPN:       ${json.optBoolean("isVpnActive", false)}")
        println("Root:      ${json.optBoolean("isRootDetected", false)}")
        println("Shizuku:   ${json.optBoolean("isShizukuAvailable", false)}")
        return 0
    }

    private fun cmdPing(args: List<String>): Int {
        val response = get("/api/ping")
        val json = org.json.JSONObject(response)
        println("Status:  ${json.optString("status")}")
        println("Version: ${json.optString("version")}")
        return 0
    }

    // ── HTTP helpers ─────────────────────────────────────────────

    /**
     * Attaches `Authorization: Bearer <token>` when a token is configured. Sent on every call,
     * not just the routes known to need it, so a route that gains auth server-side keeps
     * working; the server ignores the header on open routes.
     */
    private fun okhttp3.Request.Builder.withAuth(): okhttp3.Request.Builder {
        val t = token ?: return this
        return header("Authorization", authorizationHeaderValue(t))
    }

    /**
     * Guidance for an auth failure. Deliberately never includes the token that was sent (a
     * leaked token in a terminal scrollback or a CI log is as bad as one in logcat).
     */
    internal fun unauthorizedHint(tokenWasSent: Boolean): String =
        if (tokenWasSent) "HTTP 401: the pairing token was rejected. Copy the current one from Settings > Local REST API & CLI (it changes when you tap Regenerate)."
        else "HTTP 401: this route needs the pairing token. Pass --token <token> or export $TOKEN_ENV_VAR (Settings > Local REST API & CLI)."

    private fun report(response: okhttp3.Response, path: String, body: String) {
        if (response.isSuccessful || quiet) return
        if (response.code == 401) {
            System.err.println(unauthorizedHint(tokenWasSent = token != null))
        } else {
            System.err.println("HTTP ${response.code} for $path: $body")
        }
    }

    private fun get(path: String): String {
        val url = "$apiBase$path"
        val request = okhttp3.Request.Builder().url(url).get().withAuth().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        report(response, path, body)
        return body
    }

    private fun post(path: String, body: String): String {
        val url = "$apiBase$path"
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
            .withAuth()
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        report(response, path, responseBody)
        return responseBody
    }

    private fun delete(path: String): String {
        val url = "$apiBase$path"
        val request = okhttp3.Request.Builder().url(url).delete().withAuth().build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        report(response, path, responseBody)
        return responseBody
    }

    private fun printHelp() = println(helpText())

    internal fun helpText(): String = """
requestr CLI v4.0 — manage requestr from the command line

USAGE:
  requestr <command> [options]

COMMANDS:
  captures                  List recent HTTP captures
  capture <id>              Show full capture details
  flows                     List flow automations
  flow create <json>        Create a new flow from JSON
  flow execute <id>         Execute a flow by id
  flow delete <id>          Delete a flow by id
  tools                     List available tools
  tool exec <id> <input>    Execute a tool
  rules                     List intercept rules
  plugin <name> <jsonInput> Dispatch to a plugin tool
  status                    Show API and device status
  ping                      Health check

OPTIONS:
  --api-base <url>          API server URL (default: http://localhost:9876)
  --token <token>           Pairing token for bearer-protected routes (rules, observe, keylog).
                            Sent as "Authorization: Bearer <token>". If omitted, the
                            REQUESTR_TOKEN environment variable is used, which keeps the
                            token out of your shell history. Never printed back.
  -q, --quiet               Suppress informational output
  -v, --version             Print version
  --help                    This message

EXAMPLES:
  requestr --api-base http://10.0.2.2:9876 captures
  requestr tool exec base64-decode SGVsbG8=
  requestr plugin json-prettify '{"json":"{\"a\":1}"}'
  REQUESTR_TOKEN=<pairing token> requestr rules
  requestr --token <pairing token> rules
        """.trimIndent()
}
