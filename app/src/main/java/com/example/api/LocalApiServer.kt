package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import com.example.data.repository.FlowRepository
import com.example.data.repository.ToolsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.security.SecureRandom

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

    // Compose-observable: a plain @Volatile var never triggers recomposition because
    // Compose's snapshot system only tracks State/StateFlow reads, not raw field reads.
    // SettingsScreen's Switch previously read the old `isRunning` field directly, so the
    // label + Switch state never updated after tapping it even though start()/stop() were
    // toggling the server correctly underneath. Exposing a StateFlow lets the UI
    // `collectAsState()` it and recompose immediately on start/stop.
    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Backward-compatible synchronous snapshot (non-Compose-observable). */
    val isRunning: Boolean get() = _isRunning.value

    @Volatile var port: Int = 9876
        private set

    /** Initialize with application context for DB access */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The pairing token gating `/api/observe` (and every other route via [Authorization: Bearer]
     * once it's generated). Cached in-memory once loaded so [route] doesn't hit DataStore on
     * every request; [ensurePairingToken] populates it on first [start] and [regeneratePairingToken]
     * refreshes it.
     */
    @Volatile private var pairingToken: String? = null

    private fun settings(): SettingsDataStore? = appContext?.let { SettingsDataStore.get(it) }

    /** Generates a 256-bit [SecureRandom] token on first call, persists it, and caches it. */
    private suspend fun ensurePairingToken() {
        val ds = settings() ?: return
        val existing = ds.pairingToken.first()
        if (existing != null) {
            pairingToken = existing
            return
        }
        val fresh = generateToken()
        ds.setPairingToken(fresh)
        pairingToken = fresh
        Log.i(TAG, "Generated new SDK pairing token (reveal it via Settings > SDK Pairing)")
    }

    /** Regenerates the pairing token immediately (invalidates the old one). Exposed for Settings' "Regenerate" button. */
    suspend fun regeneratePairingToken(): String {
        val ds = settings() ?: throw IllegalStateException("LocalApiServer not initialized")
        val fresh = generateToken()
        ds.setPairingToken(fresh)
        pairingToken = fresh
        return fresh
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32) // 256 bits
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun start(port: Int = 9876) {
        if (_isRunning.value || appContext == null) return
        this.port = port
        _isRunning.value = true

        serverJob = scope.launch {
            try {
                ensurePairingToken()
                serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "API server started on 127.0.0.1:$port")
                while (isActive && _isRunning.value) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (_isRunning.value) Log.w(TAG, "Accept interrupted")
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
        _isRunning.value = false
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
                var authHeader: String? = null
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.startsWith("Content-Length:", ignoreCase = true))
                        contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
                    if (hdr.startsWith("Authorization:", ignoreCase = true))
                        authHeader = hdr.substringAfter(":").trim()
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val (status, ct, resp) = route(method, path, body, authHeader)
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

    private suspend fun route(method: String, path: String, body: String, authHeader: String? = null): Triple<Int, String, String> {
        return try {
            when {
                path == "/api/ping" -> ok("""{"status":"ok","version":"4.0"}""")
                path == "/api/status" -> apiStatus()

                path == "/api/pairing-info" && method == "GET" -> pairingInfo()

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

                path == "/api/observe" && method == "POST" -> {
                    // Task 6: the only route that requires pairing auth today -- the local
                    // 127.0.0.1 bind (start()) remains a secondary defense, this is the primary
                    // one since ANY local process can otherwise reach a loopback-bound socket.
                    if (!isAuthorized(authHeader)) {
                        err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    } else {
                        receiveObserverEvents(body)
                    }
                }

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

    /**
     * Validates the `Authorization: Bearer <token>` header (case-insensitive scheme, exact-match
     * token) against the pairing token generated in [ensurePairingToken]. If no token has been
     * generated yet (e.g. `receiveObserverEvents` reached before any [start] ever ran once --
     * shouldn't happen in production since [start] always calls [ensurePairingToken] first, but
     * defends a caller who bypasses `start()`), every request is rejected rather than silently
     * allowed.
     */
    private fun isAuthorized(authHeader: String?): Boolean {
        val expected = pairingToken ?: return false
        val header = authHeader ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring("Bearer ".length).trim()
        return presented == expected
    }

    /**
     * `GET /api/pairing-info` — reveals the pairing token + port so a third-party app's user (or
     * the MV3 browser extension) can copy it in. Gated by the "Show Pairing Token" toggle in
     * Settings ([SettingsDataStore.showPairingToken]); returns 403 otherwise so the token can't be
     * fetched by an unrelated local process without the user explicitly opting in first.
     */
    private suspend fun pairingInfo(): Triple<Int, String, String> {
        val ds = settings() ?: return err(500, "not initialized")
        val revealing = ds.showPairingToken.first()
        val revealUntil = ds.pairingTokenRevealUntil.first()
        if (!revealing || System.currentTimeMillis() > revealUntil) {
            return err(403, "pairing token reveal is disabled -- enable 'Show Pairing Token' in Settings")
        }
        val token = pairingToken ?: ds.pairingToken.first() ?: return err(500, "no pairing token generated yet")
        return ok("""{"token":"$token","port":$port}""")
    }

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

    /**
     * Ingest a batch of events POSTed by a third-party app's embedded `requestr-sdk`
     * (`RequestrObserver.flush()`), matching the shape:
     * `{"events":[{"url":..,"method":..,"headers":{..},"statusCode":..,"bodySize":..,
     *   "durationMs":..,"source":"sdk","timestamp":..}, ...]}`
     *
     * Persists each event as a [com.example.data.local.CapturedTrafficEntity] row under a
     * dedicated, always-present [com.example.data.local.CaptureTier.SDK] session so SDK-sourced
     * traffic shows up in the same Intercept traffic log / [com.example.data.local.CaptureSessionEntity]-
     * backed UI as VPN/root/Shizuku-captured traffic, rather than being accepted-and-discarded.
     */
    private suspend fun receiveObserverEvents(body: String): Triple<Int, String, String> {
        return try {
            val json = JSONObject(body)
            val events = json.optJSONArray("events") ?: return err(400, "missing 'events' array")
            val dao = db()?.capturedTrafficDao() ?: return err(500, "db unavailable")
            val sessionId = sdkSessionId()
            var inserted = 0
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val url = e.optString("url", "")
                if (url.isEmpty()) continue
                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                val path = runCatching { java.net.URI(url).path }.getOrNull() ?: ""
                val headersJson = e.optJSONObject("headers")
                val headers = mutableMapOf<String, String>()
                headersJson?.keys()?.forEachRemaining { key -> headers[key] = headersJson.optString(key, "") }
                val statusCodeRaw = e.optInt("statusCode", -1)
                val entity = com.example.data.local.CapturedTrafficEntity(
                    sessionId = sessionId,
                    method = e.optString("method", "GET"),
                    url = url,
                    host = host,
                    path = path,
                    statusCode = if (statusCodeRaw >= 0) statusCodeRaw else null,
                    protocol = "sdk",
                    requestHeaders = headers,
                    requestSizeBytes = 0,
                    responseSizeBytes = e.optLong("bodySize", 0),
                    latencyMs = e.optLong("durationMs", 0),
                    timestamp = e.optLong("timestamp", System.currentTimeMillis()),
                    wasDecrypted = true,
                )
                dao.insert(entity)
                inserted++
            }
            ok("""{"received":$inserted}""")
        } catch (e: Exception) {
            err(400, e.message ?: "bad request")
        }
    }

    /**
     * Every SDK-sourced event needs a `sessionId` to satisfy [com.example.data.local.CapturedTrafficEntity]'s
     * schema and to let the UI group/filter SDK traffic distinctly from an actual VPN/root
     * capture run. Rather than requiring the app's own capture engine to be running, this
     * lazily creates (once) and then reuses a single always-open
     * [com.example.data.local.CaptureTier.SDK] [com.example.data.local.CaptureSessionEntity] row,
     * so third-party apps can observe traffic even when the user has never started a capture.
     */
    private suspend fun sdkSessionId(): Long {
        val sessionDao = db()?.captureSessionDao() ?: return 0L
        val existing = sessionDao.observeAll().first().firstOrNull { it.tier == com.example.data.local.CaptureTier.SDK && it.endedAt == null }
        if (existing != null) return existing.id
        return sessionDao.insert(
            com.example.data.local.CaptureSessionEntity(
                tier = com.example.data.local.CaptureTier.SDK,
                port = port,
            )
        )
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
