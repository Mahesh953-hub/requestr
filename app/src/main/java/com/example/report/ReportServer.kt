package com.example.report

import android.content.Context
import android.util.Log
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.HeaderKind
import com.example.data.local.RequestrDatabase
import com.example.data.local.TransactionTimingEntity
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * S5 task 41 + 62: lifecycle-tied HTTP server that renders a browsable static report for one
 * capture session and supports one-click repeat of a single transaction.
 *
 * Reuses the [LocalApiServer] pattern:
 *  - [ServerSocket] on 127.0.0.1 by default
 *  - explicit opt-in LAN binding via [bindToLan]
 *  - 256-bit pairing token from [SettingsDataStore] gates every route
 *  - UTF-8 byte-exact [Content-Length] handling (CAT-1 fix)
 *
 * The server is deliberately independent of [LocalApiServer] and [LanDashboardServer] so it can
 * be toggled and routed separately. No route is reachable without the bearer token; the HTML
 * pages embed the token in query strings and inline JS so a browser can still navigate them.
 *
 * Cross-origin: no CORS wildcard is ever emitted. If a request carries an [Origin] header that
 * does not match the server's own host, the server returns 403.
 *
 * Repeat (task 62): POST /repeat executes exactly one upstream request per click through the
 * injected [repeatExecutor]. No hidden retries, no automatic redirect re-send beyond whatever the
 * executor itself does, and the double-click guard disables the button on first click. Attempts
 * are rate-limited per token.
 *
 * HAR download is delegated to the injected [harProvider] seam so this file does not need the
 * sibling's `data/export/HarWriter.kt` to be present first. The default provider emits a valid,
 * minimal HAR 1.2 document; production should wire it to the real session-level `HarWriter`.
 */

object ReportServer {
    private const val TAG = "ReportServer"
    const val DEFAULT_PORT = 9878

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    val isRunning: Boolean get() = _isRunning.value

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var bindToLan: Boolean = false
        private set

    /** Compose/readable address the server is currently bound to. */
    val bindHost: String get() = if (bindToLan) "0.0.0.0" else "127.0.0.1"

    @Volatile
    private var pairingToken: String? = null

    /**
     * Minimum milliseconds between repeat attempts for the same token. Configurable so tests do
     * not wait the full production window.
     */
    @Volatile
    var repeatRateLimitMs: Long = 5000L

    private val rateLimitLastMs = ConcurrentHashMap<String, Long>()

    /** Result of a single one-click repeat attempt. */
    data class RepeatAttempt(
        val success: Boolean,
        val statusCode: Int?,
        val latencyMs: Long,
        val error: String? = null,
    )

    /**
     * HAR provider seam. Production should set this to the sibling's session-level `HarWriter`
     * (e.g. `HarWriter.forSession(context, sessionId).toJson()`). The default fallback produces a
     * valid minimal HAR 1.2 document so the Report Server compiles before the sibling lands.
     */
    var harProvider: suspend (sessionId: Long) -> String = { sessionId ->
        ReportRenderer.defaultHarForSession(sessionId)
    }

    /**
     * Repeat executor seam. Production should set this to a function that routes the transaction
     * through the sibling's `ReplayJobRunner` / `ReplayQueue` and returns exactly one attempt result
     * (which the runner persists into `replay_results`). The default returns a clear failure so the
     * endpoint is safe before wiring.
     */
    var repeatExecutor: suspend (transactionId: Long) -> RepeatAttempt = { id ->
        RepeatAttempt(false, null, 0, "repeat executor not wired for transaction $id")
    }

    private fun settings(): SettingsDataStore? = appContext?.let { SettingsDataStore.get(it) }

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
        Log.i(TAG, "Generated pairing token for report server")
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts the report server on [port]. Binds `127.0.0.1` unless [bindToLan] is true, in which
     * case it binds `0.0.0.0`. Auth is required regardless of binding.
     */
    fun start(context: Context, port: Int = DEFAULT_PORT, bindToLan: Boolean = false) {
        if (_isRunning.value) return
        appContext = context.applicationContext
        this.port = port
        this.bindToLan = bindToLan
        _isRunning.value = true

        serverJob = scope.launch {
            try {
                ensurePairingToken()
                val host = if (bindToLan) "0.0.0.0" else "127.0.0.1"
                serverSocket = ServerSocket(port, 10, InetAddress.getByName(host))
                Log.i(TAG, "Report server started on $host:$port (lan=$bindToLan)")
                while (isActive && _isRunning.value) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (_isRunning.value) Log.w(TAG, "Accept interrupted")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Report server error: ${e.message}")
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
        Log.i(TAG, "Report server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ", limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                var contentLength = 0
                var authHeader: String? = null
                var originHeader: String? = null
                var hostHeader: String? = null
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (hdr.startsWith("Authorization:", ignoreCase = true)) {
                        authHeader = hdr.substringAfter(":").trim()
                    }
                    if (hdr.startsWith("Origin:", ignoreCase = true)) {
                        originHeader = hdr.substringAfter(":").trim()
                    }
                    if (hdr.startsWith("Host:", ignoreCase = true)) {
                        hostHeader = hdr.substringAfter(":").trim()
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val (path, query) = splitQuery(rawPath)
                val queryToken = query["token"]

                val (status, contentType, response) = when {
                    !isAuthorized(authHeader, queryToken) -> err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    !isOriginAllowed(originHeader, hostHeader) -> err(403, "cross-origin request rejected")
                    else -> route(method, path, query, body)
                }
                writeResponse(s, status, contentType, response)
            }
        } catch (_: Exception) { }
    }

    private fun writeResponse(socket: Socket, status: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    private fun isAuthorized(authHeader: String?, queryToken: String?): Boolean {
        val expected = pairingToken ?: return false
        val bearer = authHeader?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
        return bearer == expected || queryToken == expected
    }

    private fun isOriginAllowed(origin: String?, hostHeader: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val originHost = origin.removePrefix("http://").removePrefix("https://").substringBefore("/")
        val expectedHost = if (bindToLan) {
            hostHeader ?: "0.0.0.0:$port"
        } else {
            "127.0.0.1:$port"
        }
        return originHost == expectedHost ||
            originHost == expectedHost.substringBefore(":") ||
            (!bindToLan && (originHost == "127.0.0.1" || originHost == "localhost" || originHost == "[::1]"))
    }

    private fun splitQuery(rawPath: String): Pair<String, Map<String, String>> {
        val qIdx = rawPath.indexOf('?')
        if (qIdx < 0) return rawPath to emptyMap()
        val path = rawPath.substring(0, qIdx)
        val query = rawPath.substring(qIdx + 1)
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            fun decode(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
            params[decode(key)] = decode(value)
        }
        return path to params
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun ok(json: String) = Triple(200, "application/json", json)
    private fun html(html: String) = Triple(200, "text/html; charset=utf-8", html)
    private fun err(code: Int, msg: String) = Triple(
        code,
        "application/json",
        JSONObject().put("error", msg).toString(),
    )

    private suspend fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
    ): Triple<Int, String, String> = try {
        when {
            path == "/" && method == "GET" -> sessionReport(query)
            path == "/detail" && method == "GET" -> detailReport(query)
            path == "/har" && method == "GET" -> harDownload(query)
            path == "/repeat" && method == "POST" -> repeatTransaction(query)
            else -> err(404, "not found: $path")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Route error", e)
        err(500, e.message ?: "internal error")
    }

    private suspend fun resolveSessionId(query: Map<String, String>): Long? {
        query["sessionId"]?.toLongOrNull()?.let { return it }
        val db = appContext?.let { RequestrDatabase.get(it) } ?: return null
        return db.captureSessionDao().observeAll().first().firstOrNull()?.id
    }

    private suspend fun sessionReport(query: Map<String, String>): Triple<Int, String, String> {
        val sessionId = resolveSessionId(query) ?: return err(404, "no session found")
        val db = appContext?.let { RequestrDatabase.get(it) } ?: return err(500, "db unavailable")
        val rows = db.capturedTrafficDao().getBySession(sessionId)
        val token = pairingToken.orEmpty()
        return html(ReportRenderer.renderSession(sessionId, rows, token))
    }

    private suspend fun detailReport(query: Map<String, String>): Triple<Int, String, String> {
        val id = query["id"]?.toLongOrNull() ?: return err(400, "invalid id")
        val db = appContext?.let { RequestrDatabase.get(it) } ?: return err(500, "db unavailable")
        val row = db.capturedTrafficDao().getById(id) ?: return err(404, "transaction not found")
        val requestHeaders = db.transactionHeaderDao().forTraffic(id, HeaderKind.REQUEST)
        val responseHeaders = db.transactionHeaderDao().forTraffic(id, HeaderKind.RESPONSE)
        val timings = db.transactionTimingDao().forTraffic(id)
        val token = pairingToken.orEmpty()
        return html(
            ReportRenderer.renderDetail(
                row,
                requestHeaders,
                responseHeaders,
                timings,
                token,
            ),
        )
    }

    private suspend fun harDownload(query: Map<String, String>): Triple<Int, String, String> {
        val sessionId = resolveSessionId(query) ?: return err(404, "no session found")
        val har = harProvider(sessionId)
        return Triple(200, "application/json; charset=utf-8", har)
    }

    private suspend fun repeatTransaction(query: Map<String, String>): Triple<Int, String, String> {
        val id = query["id"]?.toLongOrNull() ?: return err(400, "invalid id")
        val token = pairingToken ?: return err(401, "missing token")

        val now = System.currentTimeMillis()
        val last = rateLimitLastMs[token]
        if (last != null && now - last < repeatRateLimitMs) {
            return err(429, "rate limit: wait before repeating again")
        }
        rateLimitLastMs[token] = now

        val attempt = repeatExecutor(id)
        val json = JSONObject()
            .put("success", attempt.success)
            .put("statusCode", attempt.statusCode ?: JSONObject.NULL)
            .put("latencyMs", attempt.latencyMs)
            .put("error", attempt.error ?: JSONObject.NULL)
            .toString()
        return ok(json)
    }

    /** Visible for testing: the current pairing token, or null if not generated yet. */
    internal fun currentTokenForTest(): String? = pairingToken
}
