package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.local.RequestrDatabase
import com.example.data.repository.FlowRepository
import com.example.data.repository.ToolsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket

/**
 * Embedded REST API server for programmatic access to requestr internals.
 *
 * Starts on configurable port (default 9876), listens on localhost only.
 * Exposes captures, flows, tools, rules, and plugins via plain HTTP/1.1.
 */
object LocalApiServer {
    private const val TAG = "LocalApi"

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    @Volatile var isRunning: Boolean = false
        private set

    @Volatile var port: Int = 9876
        private set

    /** Initialize with application context for DB access */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun start(port: Int = 9876) {
        if (isRunning || appContext == null) return
        this.port = port
        isRunning = true

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "API server started on 127.0.0.1:$port")
                while (isActive && isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (isRunning) Log.w(TAG, "Accept interrupted")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "API server stopped")
    }

    // ── HTTP handler ──────────────────────────────────────────────

    private suspend fun handleClient(socket: java.net.Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = OutputStreamWriter(s.outputStream)
                val line = reader.readLine() ?: return
                val parts = line.split(" ", limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                var contentLength = 0
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.startsWith("Content-Length:", ignoreCase = true))
                        contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val (status, ct, resp) = route(method, path, body)
                writer.write("HTTP/1.1 $status ${statusText(status)}\r\n")
                writer.write("Content-Type: $ct\r\n")
                writer.write("Content-Length: ${resp.length}\r\n")
                writer.write("Access-Control-Allow-Origin: *\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(resp)
                writer.flush()
            }
        } catch (_: Exception) { }
    }

    // ── Router ────────────────────────────────────────────────────

    private suspend fun route(method: String, path: String, body: String): Triple<Int, String, String> {
        return try {
            when {
                path == "/api/ping" -> ok("""{"status":"ok","version":"4.0"}""")
                path == "/api/status" -> apiStatus()

                path == "/api/captures" && method == "GET" -> listCaptures()
                path.startsWith("/api/captures/") && method == "GET" -> getCapture(path.removePrefix("/api/captures/"))

                path == "/api/flows" && method == "GET" -> listFlows()
                path == "/api/flows" && method == "POST" -> createFlow(body)
                path.startsWith("/api/flows/") && path.endsWith("/execute") && method == "POST" ->
                    executeFlow(path.removePrefix("/api/flows/").removeSuffix("/execute"))
                path.startsWith("/api/flows/") && method == "DELETE" ->
                    deleteFlow(path.removePrefix("/api/flows/"))

                path == "/api/tools" && method == "GET" -> listTools()
                path == "/api/tools/execute" && method == "POST" -> executeTool(body)

                path == "/api/rules" && method == "GET" -> listRules()

                path == "/api/plugin/execute" && method == "POST" -> dispatchPlugin(body)

                else -> err(404, "not found: $path")
            }
        } catch (e: Exception) {
            err(500, e.message ?: "internal error")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun ok(json: String) = Triple(200, "application/json", json)
    private fun err(code: Int, msg: String) = Triple(code, "application/json", """{"error":"${msg.replace("\"","'")}"}""")

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 404 -> "Not Found"; 500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun db() = appContext?.let { RequestrDatabase.get(it) }

    // ── Endpoints ──────────────────────────────────────────────────

    private suspend fun apiStatus(): Triple<Int, String, String> {
        val caps = try {
            com.example.data.interception.InterceptionStateRepository.capabilities.value
        } catch (_: Exception) { null }
        val root = caps?.isRootUsable ?: false
        val shizuku = caps?.isShizukuUsable ?: false
        val ca = caps?.isCaInstalled ?: false
        return ok("""{"root":$root,"shizuku":$shizuku,"ca":$ca,"api_running":true,"port":$port}""")
    }

    private suspend fun listCaptures(): Triple<Int, String, String> {
        return try {
            val dao = db()?.capturedTrafficDao() ?: return ok("[]")
            val logs = dao.observeAll().first()
            val items = logs.take(50).joinToString(",") { log ->
                """{"id":${log.id},"method":"${log.method}","url":"${log.url.replace("\"","\\\"")}","status":${log.statusCode},"timestamp":${log.timestamp}}"""
            }
            ok("[$items]")
        } catch (_: Exception) { ok("[]") }
    }

    private suspend fun getCapture(id: String): Triple<Int, String, String> {
        val longId = id.toLongOrNull() ?: return err(400, "invalid id")
        return try {
            val dao = db()?.capturedTrafficDao() ?: return err(500, "db unavailable")
            val log = dao.getById(longId) ?: return err(404, "capture not found")
            val reqH = JSONObject(log.requestHeaders).toString()
            val resH = JSONObject(log.responseHeaders).toString()
            ok("""{"id":${log.id},"method":"${log.method}","url":"${log.url.replace("\"","\\\"")}","host":"${log.host.replace("\"","\\\"")}","requestHeaders":$reqH,"requestBody":${JSONObject.quote(log.requestBody ?: "")},"status":${log.statusCode},"responseHeaders":$resH,"responseBody":${JSONObject.quote(log.responseBody ?: "")}}""")
        } catch (_: Exception) { err(500, "query failed") }
    }

    private suspend fun listFlows(): Triple<Int, String, String> {
        return try {
            val dao = db()?.flowNodeDao() ?: return ok("[]")
            val nodes = dao.getAll()
            ok("[" + nodes.joinToString(",") { n ->
                """{"id":"${n.id}","type":"${n.type}","label":"${n.label.replace("\"","\\\"")}"}"""
            } + "]")
        } catch (_: Exception) { ok("[]") }
    }

    private suspend fun createFlow(body: String): Triple<Int, String, String> {
        return try {
            val json = JSONObject(body)
            val label = json.optString("label", "API Flow")
            val nodeType = json.optString("type", "api")
            val config = json.optString("config", body)
            val node = com.example.data.models.FlowNodeItem(
                title = label,
                type = try { com.example.data.models.FlowNodeType.valueOf(nodeType.uppercase()) }
                        catch (_: Exception) { com.example.data.models.FlowNodeType.API },
                configData = config
            )
            FlowRepository.addNode(node)
            ok("""{"status":"created","id":"${node.id}","label":"$label"}""")
        } catch (e: Exception) { err(400, e.message ?: "bad request") }
    }

    private suspend fun executeFlow(id: String): Triple<Int, String, String> {
        return try {
            val result = FlowRepository.executeFlow()
            ok("""{"flowId":"$id","result":${JSONObject.quote(result)}}""")
        } catch (e: Exception) { err(500, e.message ?: "execution failed") }
    }

    private suspend fun deleteFlow(id: String): Triple<Int, String, String> {
        return try {
            FlowRepository.removeConnection(id)
            ok("""{"status":"deleted","id":"$id"}""")
        } catch (e: Exception) { err(500, e.message ?: "delete failed") }
    }

    private fun listTools(): Triple<Int, String, String> {
        return try {
            val tools = ToolsRepository.getToolsCatalog()
            ok("[" + tools.joinToString(",") { tool ->
                """{"id":"${tool.id}","title":"${tool.title.replace("\"","\\\"")}","description":"${tool.description.replace("\"","\\\"")}","category":"${tool.category.name}"}"""
            } + "]")
        } catch (_: Exception) { ok("[]") }
    }

    private fun executeTool(body: String): Triple<Int, String, String> {
        return try {
            val json = JSONObject(body)
            val toolId = json.optString("toolId", "")
            val input = json.optString("input", "")
            if (toolId.isEmpty()) return err(400, "missing toolId")
            val result = ToolsRepository.executeToolFunction(toolId, input)
            ok("""{"toolId":"$toolId","result":${JSONObject.quote(result)}}""")
        } catch (e: Exception) { err(400, e.message ?: "bad request") }
    }

    private suspend fun listRules(): Triple<Int, String, String> {
        return try {
            val dao = db()?.interceptRuleDao() ?: return ok("[]")
            val rules = dao.observeAll().first()
            ok("[" + rules.joinToString(",") { rule ->
                """{"id":${rule.id},"name":"${rule.name.replace("\"","\\\"")}","action":"${rule.action.name}","pattern":"${rule.pattern.replace("\"","\\\"")}","enabled":${rule.enabled}}"""
            } + "]")
        } catch (_: Exception) { ok("[]") }
    }

    private fun dispatchPlugin(body: String): Triple<Int, String, String> {
        return try {
            val json = JSONObject(body)
            val toolName = json.optString("tool", "")
            val input = mutableMapOf<String, String>()
            json.keys().forEachRemaining { key ->
                if (key != "tool") input[key] = json.optString(key, "")
            }
            val result = com.example.plugin.PluginManager.dispatchCustomTool(toolName, input)
            if (result != null) ok(result)
            else err(404, "no plugin handled '$toolName'")
        } catch (e: Exception) { err(400, e.message ?: "bad request") }
    }
}
