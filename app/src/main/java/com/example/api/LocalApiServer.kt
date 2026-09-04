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
                val line = reader.readLine() ?: return
                val parts = line.split(" ", limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                var contentLength = 0
                // W4: raw header name order+casing as they arrived on the wire, plus an
                // optional calibration tag -- see the doc on `calibrationStackHeader` below.
                val rawHeaderNames = mutableListOf<String>()
                var calibrationStack: String? = null
                var authHeader: String? = null
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                    val colon = hdr.indexOf(':')
                    if (colon > 0) rawHeaderNames.add(hdr.substring(0, colon).trim())
                    if (hdr.startsWith("Content-Length:", ignoreCase = true))
                        contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
                    if (hdr.startsWith("$CALIBRATION_STACK_HEADER:", ignoreCase = true))
                        calibrationStack = hdr.substringAfter(":").trim()
                    if (hdr.startsWith("Authorization:", ignoreCase = true))
                        authHeader = hdr.substringAfter(":").trim()
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                if (calibrationStack != null && rawHeaderNames.isNotEmpty()) {
                    // See StackFingerprinter.calibrate(): a stack issues one real request to
                    // this loopback server tagged with its own name, so the exact header order
                    // and casing it put on the wire is captured here -- the only place it can
                    // be, since this server IS the wire endpoint for that request.
                    com.example.runtime.http.StackSignalStore.registerRequestHeaderOrder(
                        "calibrate:$calibrationStack",
                        rawHeaderNames,
                    )
                }

                val (status, ct, resp) = route(method, path, body, authHeader)
                // FIX (CAT-1): `resp.length` is the UTF-16 *code-unit* count of the Kotlin
                // String, not its wire byte count. Any response containing a non-ASCII
                // character (e.g. the "Hex ↔ String Converter" tool title, U+2194 encodes to
                // 3 UTF-8 bytes but counts as 1 UTF-16 code unit) previously produced a
                // `Content-Length` smaller than the bytes actually written below, so
                // HTTP-compliant clients (including this app's own CLI, which reads exactly
                // `Content-Length` bytes) truncated the body mid-character and failed to parse
                // it as JSON. Encoding once to a UTF-8 `ByteArray` and measuring/writing that
                // makes the declared length and the wire bytes agree for every response, ASCII
                // or not. Written straight to the raw `OutputStream` (never through a char
                // `Writer`, which could re-encode with a different charset) so what's counted is
                // exactly what's sent.
                val bodyBytes = resp.toByteArray(Charsets.UTF_8)
                val header = buildString {
                    append("HTTP/1.1 $status ${statusText(status)}\r\n")
                    append("Content-Type: $ct\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = s.getOutputStream()
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
            }
        } catch (_: Exception) { }
    }

    /**
     * Header name a calibration probe (see `StackFingerprinter.calibrate`) sets to its own
     * [com.example.runtime.http.HttpStack] name, so [handleClient] knows which stack's real
     * request this is and can record its wire-level header order under a synthetic
     * `calibrate:<stack>` connection id in [com.example.runtime.http.StackSignalStore]. Never
     * inspected by any other route -- purely a signal-capture side channel for W4.
     */
    const val CALIBRATION_STACK_HEADER = "X-Requestr-Calibrate-Stack"


    // ── Router ────────────────────────────────────────────────────

    private suspend fun route(method: String, rawPath: String, body: String, authHeader: String? = null): Triple<Int, String, String> {
        // Every route below was written to exact/prefix-match a bare path with no query string
        // (the original contract -- none of the pre-existing routes ever took query params).
        // Bulk export (below) needs `?from=&to=&cursor=&limit=&format=`, so the query string is
        // split off here once, up front, rather than teaching every existing `path == "..."`
        // check to also tolerate a trailing `?...` it never expected.
        val (path, query) = splitQuery(rawPath)
        return try {
            when {
                path == "/api/ping" -> ok("""{"status":"ok","version":"4.0"}""")
                path == "/api/status" -> apiStatus()

                path == "/api/pairing-info" && method == "GET" -> pairingInfo()

                path == "/api/captures" && method == "GET" -> listCaptures()
                // Bulk, time-range/keyset-filtered export (master plan §2.4). Must be checked
                // BEFORE the generic "/api/captures/{id}" prefix match below, since
                // "/api/captures/export" would otherwise be parsed as id="export".
                path == "/api/captures/export" && method == "GET" -> exportCaptures(query)
                path.startsWith("/api/captures/") && method == "GET" -> getCapture(path.removePrefix("/api/captures/"))

                path == "/api/flows" && method == "GET" -> listFlows()
                path == "/api/flows" && method == "POST" -> createFlow(body)
                path.startsWith("/api/flows/") && path.endsWith("/execute") && method == "POST" ->
                    executeFlow(path.removePrefix("/api/flows/").removeSuffix("/execute"))
                path.startsWith("/api/flows/") && method == "DELETE" ->
                    deleteFlow(path.removePrefix("/api/flows/"))

                path == "/api/tools" && method == "GET" -> listTools()
                path == "/api/tools/execute" && method == "POST" -> executeTool(body)

                path == "/api/rules" && method == "GET" -> {
                    // FIX 2: the extension's own docs claim this endpoint is bearer-token
                    // authenticated (same as /api/observe), but until now nothing enforced
                    // that here -- extended with the exact same isAuthorized() helper
                    // /api/observe uses so the semantics (case-insensitive scheme, exact-match
                    // token) stay identical between the two routes.
                    if (!isAuthorized(authHeader)) {
                        err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    } else {
                        listRules()
                    }
                }

                // Rules CRUD (master plan §2.16 — the LAN dashboard's in-browser rules editor
                // needs create/update/delete, not just the read-only GET above). Gated by the
                // exact same pairing-Bearer check as the GET, since a rule can rewrite/mock/
                // block live traffic -- at least as sensitive as reading the rule list.
                path == "/api/rules" && method == "POST" -> {
                    if (!isAuthorized(authHeader)) err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    else createRule(body)
                }
                path.startsWith("/api/rules/") && method == "PUT" -> {
                    if (!isAuthorized(authHeader)) err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    else updateRule(path.removePrefix("/api/rules/"), body)
                }
                path.startsWith("/api/rules/") && method == "DELETE" -> {
                    if (!isAuthorized(authHeader)) err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    else deleteRule(path.removePrefix("/api/rules/"))
                }

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

                path == "/api/keylog" && method == "POST" -> {
                    // Task 13 / CAT-2d: the authenticated cross-process PRIMARY path for TLS/QUIC
                    // key material -- gated by the exact same pairing-Bearer check as
                    // /api/observe and /api/rules. A bearer of this material can decrypt TLS
                    // 1.3/QUIC traffic matching the recorded client random, so this is at least
                    // as sensitive as observed traffic and must never be reachable unauthenticated.
                    if (!isAuthorized(authHeader)) {
                        err(401, "missing or invalid Authorization: Bearer <pairing token>")
                    } else {
                        receiveKeylogLines(body)
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

    /**
     * Splits a raw HTTP request-line target (e.g. `/api/captures/export?from=1&to=2&limit=500`)
     * into its bare path and a decoded `key -> value` query-param map. Percent-decodes both key
     * and value (`URLDecoder`, `+` treated as a literal space per `application/x-www-form-urlencoded`,
     * matching every browser's own `URLSearchParams` encoding for a GET query string); a
     * malformed escape sequence falls back to the raw (undecoded) token rather than throwing, so
     * one bad param never 500s the whole request. A key with no `=` (e.g. `?debug`) maps to `""`.
     */
    private fun splitQuery(rawPath: String): Pair<String, Map<String, String>> {
        val qIdx = rawPath.indexOf('?')
        if (qIdx < 0) return rawPath to emptyMap()
        val path = rawPath.substring(0, qIdx)
        val query = rawPath.substring(qIdx + 1)
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
            fun decode(s: String) = runCatching {
                java.net.URLDecoder.decode(s, "UTF-8")
            }.getOrDefault(s)
            params[decode(rawKey)] = decode(rawValue)
        }
        return path to params
    }

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

    /**
     * `GET /api/captures` — built on [org.json.JSONArray]/[JSONObject] rather than hand-rolled
     * string interpolation with `.replace("\"","\\\"")`, which only escapes double quotes: a
     * `url`/`method` containing a backslash (or any other JSON-significant character) produced
     * invalid JSON the CLI's `org.json.JSONArray(response)` call would then throw on. `statusCode`
     * is nullable on the entity, so it serializes as JSON `null` (via [JSONObject.NULL]) rather
     * than the literal string `"null"` that plain interpolation would have produced.
     */
    private suspend fun listCaptures(): Triple<Int, String, String> {
        return try {
            val dao = db()?.capturedTrafficDao() ?: return ok("[]")
            val logs = dao.observeAll().first()
            val arr = org.json.JSONArray()
            for (log in logs.take(50)) {
                arr.put(
                    JSONObject()
                        .put("id", log.id)
                        .put("method", log.method)
                        .put("url", log.url)
                        .put("status", log.statusCode ?: JSONObject.NULL)
                        .put("timestamp", log.timestamp)
                )
            }
            ok(arr.toString())
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

    /** Hard ceiling on `?limit=` for [exportCaptures], independent of whatever the caller asks
     *  for -- mirrors [MAX_KEYLOG_LINES_PER_REQUEST]'s rationale: one export request must not be
     *  able to force an unbounded query/response regardless of client input. */
    private const val MAX_EXPORT_PAGE_SIZE = 5000
    private const val DEFAULT_EXPORT_PAGE_SIZE = 500

    /**
     * `GET /api/captures/export` — master plan §2.4 ("bulk, time-range-filtered network-log
     * export endpoint"): unlike [listCaptures] (hardcoded `.take(50)` over the *entire* loaded
     * table), this bounds the query itself via [com.example.data.local.CapturedTrafficDao]'s
     * keyset-pagination queries and accepts real filters:
     *
     *  - `from`, `to` — inclusive epoch-millisecond timestamp range (defaults: `0`..now)
     *  - `sessionId` — restrict to one capture session (omit for all sessions)
     *  - `cursor` — opaque `"<timestamp>:<id>"` pair naming the last row of the previous page
     *    (the exact value this handler put in the previous response's `"nextCursor"`); omit for
     *    the first page
     *  - `limit` — page size, default [DEFAULT_EXPORT_PAGE_SIZE], hard-capped at
     *    [MAX_EXPORT_PAGE_SIZE]
     *  - `format` — `json` (default) or `har`; `har` renders a real multi-entry HAR 1.2 document
     *    (`log.entries`, one per row) rather than the single-entry-only shape
     *    [com.example.data.interception.TrafficExporter.toHar] produces for one UI-model item.
     *
     * Response (`format=json`) includes `"nextCursor"` (null once the range is exhausted) so a
     * client can keep paging without ever loading more than `limit` rows into memory at once.
     */
    private suspend fun exportCaptures(query: Map<String, String>): Triple<Int, String, String> {
        return try {
            val dao = db()?.capturedTrafficDao() ?: return err(500, "db unavailable")
            val fromTs = query["from"]?.toLongOrNull() ?: 0L
            val toTs = query["to"]?.toLongOrNull() ?: System.currentTimeMillis()
            if (fromTs > toTs) return err(400, "'from' must be <= 'to'")
            val sessionId = query["sessionId"]?.toLongOrNull()
            val limit = (query["limit"]?.toIntOrNull() ?: DEFAULT_EXPORT_PAGE_SIZE)
                .coerceIn(1, MAX_EXPORT_PAGE_SIZE)
            val format = query["format"]?.lowercase() ?: "json"

            val cursorRaw = query["cursor"]
            var beforeTimestamp: Long? = null
            var beforeId: Long? = null
            if (!cursorRaw.isNullOrBlank()) {
                val parts = cursorRaw.split(':')
                beforeTimestamp = parts.getOrNull(0)?.toLongOrNull() ?: return err(400, "invalid cursor")
                beforeId = parts.getOrNull(1)?.toLongOrNull() ?: return err(400, "invalid cursor")
            }

            val rows = if (sessionId != null) {
                dao.getSessionRangePageBeforeCursor(sessionId, fromTs, toTs, beforeTimestamp, beforeId, limit)
            } else {
                dao.getRangePageBeforeCursor(fromTs, toTs, beforeTimestamp, beforeId, limit)
            }

            val nextCursor = if (rows.size >= limit) {
                rows.lastOrNull()?.let { "${it.timestamp}:${it.id}" }
            } else null

            if (format == "har") {
                ok(rowsToHar(rows, nextCursor))
            } else {
                val arr = org.json.JSONArray()
                for (log in rows) {
                    arr.put(
                        JSONObject()
                            .put("id", log.id)
                            .put("sessionId", log.sessionId)
                            .put("method", log.method)
                            .put("url", log.url)
                            .put("host", log.host)
                            .put("status", log.statusCode ?: JSONObject.NULL)
                            .put("requestHeaders", JSONObject(log.requestHeaders))
                            .put("responseHeaders", JSONObject(log.responseHeaders))
                            .put("requestBody", log.requestBody ?: JSONObject.NULL)
                            .put("responseBody", log.responseBody ?: JSONObject.NULL)
                            .put("requestSizeBytes", log.requestSizeBytes)
                            .put("responseSizeBytes", log.responseSizeBytes)
                            .put("latencyMs", log.latencyMs)
                            .put("timestamp", log.timestamp)
                            .put("wasDecrypted", log.wasDecrypted)
                    )
                }
                ok(
                    JSONObject()
                        .put("items", arr)
                        .put("nextCursor", nextCursor ?: JSONObject.NULL)
                        .put("count", rows.size)
                        .toString()
                )
            }
        } catch (e: Exception) {
            err(500, e.message ?: "export failed")
        }
    }

    /**
     * Renders a real multi-entry HAR 1.2 document from [rows] — one `log.entries[]` element per
     * captured transaction, following the exact same field mapping as
     * [com.example.data.interception.TrafficExporter.toHar] (unknown sizes/timings as HAR's own
     * `-1` "not available" sentinel, never a fabricated number), just over a list of
     * [com.example.data.local.CapturedTrafficEntity] rows instead of one UI-model item.
     *
     * [nextCursor] (the same opaque `"<timestamp>:<id>"` cursor [exportCaptures]'s `format=json`
     * response puts in `"nextCursor"`) is embedded as `log._nextCursor`, a `_`-prefixed extension
     * field per the HAR spec's convention for vendor/tool-specific additions (e.g. Chrome DevTools'
     * `_initiator`) -- conformant HAR consumers ignore unknown `_`-prefixed fields, so a caller
     * paging through a large HAR export can still discover where to resume without the format
     * ceasing to be valid HAR 1.2.
     */
    private fun rowsToHar(rows: List<com.example.data.local.CapturedTrafficEntity>, nextCursor: String?): String {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        fun headerArray(headers: Map<String, String>): org.json.JSONArray {
            val arr = org.json.JSONArray()
            headers.forEach { (k, v) -> arr.put(JSONObject().put("name", k).put("value", v)) }
            return arr
        }
        val entries = org.json.JSONArray()
        for (log in rows) {
            val request = JSONObject()
                .put("method", log.method)
                .put("url", log.url)
                .put("httpVersion", log.protocol.ifBlank { "HTTP/1.1" })
                .put("headers", headerArray(log.requestHeaders))
                .put("queryString", org.json.JSONArray())
                .put("headersSize", -1)
                .put("bodySize", if (log.requestSizeBytes > 0) log.requestSizeBytes else -1)
            if (!log.requestBody.isNullOrBlank()) {
                request.put(
                    "postData",
                    JSONObject()
                        .put(
                            "mimeType",
                            log.requestHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                                ?.value ?: "application/octet-stream",
                        )
                        .put("text", log.requestBody),
                )
            }
            val content = JSONObject()
                .put("size", if (log.responseSizeBytes > 0) log.responseSizeBytes else -1)
                .put(
                    "mimeType",
                    log.responseHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value ?: "",
                )
            if (!log.responseBody.isNullOrBlank()) content.put("text", log.responseBody)
            val response = JSONObject()
                .put("status", log.statusCode ?: 0)
                .put("statusText", "")
                .put("httpVersion", log.protocol.ifBlank { "HTTP/1.1" })
                .put("headers", headerArray(log.responseHeaders))
                .put("cookies", org.json.JSONArray())
                .put("content", content)
                .put("redirectURL", "")
                .put("headersSize", -1)
                .put("bodySize", if (log.responseSizeBytes > 0) log.responseSizeBytes else -1)
            entries.put(
                JSONObject()
                    .put("startedDateTime", iso.format(java.util.Date(log.timestamp)))
                    .put("time", log.latencyMs)
                    .put("request", request)
                    .put("response", response)
                    .put("cache", JSONObject())
                    .put(
                        "timings",
                        JSONObject().put("send", -1).put("wait", log.latencyMs).put("receive", -1),
                    ),
            )
        }
        val logObj = JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "requestr").put("version", "export-bulk"))
            .put("entries", entries)
            .put("_nextCursor", nextCursor ?: JSONObject.NULL)
        return JSONObject().put("log", logObj).toString(2)
    }

    /**
     * `GET /api/flows` — see [listCaptures]'s kdoc: built on [org.json.JSONArray]/[JSONObject]
     * instead of hand-rolled `.replace("\"","\\\"")` interpolation, which only escapes double
     * quotes and produces invalid JSON for a `label` containing a backslash.
     */
    private suspend fun listFlows(): Triple<Int, String, String> {
        return try {
            val dao = db()?.flowNodeDao() ?: return ok("[]")
            val nodes = dao.getAll()
            val arr = org.json.JSONArray()
            for (n in nodes) {
                arr.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("type", n.type)
                        .put("label", n.label)
                )
            }
            ok(arr.toString())
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
            ok("""{"flowId":"$id","success":${result.success},"result":${JSONObject.quote(result.message)}}""")
        } catch (e: Exception) { err(500, e.message ?: "execution failed") }
    }

    private suspend fun deleteFlow(id: String): Triple<Int, String, String> {
        return try {
            FlowRepository.removeConnection(id)
            ok("""{"status":"deleted","id":"$id"}""")
        } catch (e: Exception) { err(500, e.message ?: "delete failed") }
    }

    /**
     * `GET /api/tools` — see [listCaptures]'s kdoc: built on [org.json.JSONArray]/[JSONObject]
     * instead of hand-rolled `.replace("\"","\\\"")` interpolation. That interpolation only
     * escaped double quotes, so a custom tool's `description` containing a raw backslash (e.g.
     * `ToolsRepository.customToolToItem`'s `"Custom regex: ${ct.pattern} → ${ct.replacement}"`
     * for a `\d+`-style regex pattern) produced a bare `\d` in the response body — not valid
     * JSON per RFC 8259 (`\d` is not a legal escape), so any correct JSON parser (including the
     * CLI's `org.json.JSONArray(response)`) threw on it.
     */
    private fun listTools(): Triple<Int, String, String> {
        return try {
            val tools = ToolsRepository.getToolsCatalog()
            val arr = org.json.JSONArray()
            for (tool in tools) {
                arr.put(
                    JSONObject()
                        .put("id", tool.id)
                        .put("title", tool.title)
                        .put("description", tool.description)
                        .put("category", tool.category.name)
                )
            }
            ok(arr.toString())
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

    /**
     * `GET /api/rules` — serializes every [com.example.data.local.InterceptRuleEntity] into the
     * exact shape the MV3 extension's `enforcer.mjs` requires: `{ id, name, pattern, domain,
     * action, enabled, replacementValue }` (see enforcer.mjs:9-11's own doc comment). FIX 2:
     * previously this omitted `domain` (so `checkRules()`'s domain-only match branch could never
     * fire) and `replacementValue` (so REDIRECT/MODIFY_REQUEST/MODIFY_RESPONSE rules had no
     * configuration for `parseRequestModification()`/`parseResponseModification()` to parse).
     * Also rebuilt on [JSONObject]/[JSONArray] rather than hand-rolled string interpolation with
     * `.replace("\"","\\\"")`, which is not correct JSON escaping (breaks on backslashes,
     * newlines, and control characters — all things a regex `pattern` or a JSON-valued
     * `replacementValue` can legitimately contain). `replacementValue` is nullable on the entity,
     * so it serializes as JSON `null` (via [JSONObject.put] with a null value), never the string
     * `"null"`.
     */
    private suspend fun listRules(): Triple<Int, String, String> {
        return try {
            val dao = db()?.interceptRuleDao() ?: return ok("[]")
            val rules = dao.observeAll().first()
            val arr = org.json.JSONArray()
            for (rule in rules) {
                arr.put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("name", rule.name)
                        .put("pattern", rule.pattern)
                        .put("domain", rule.domain)
                        .put("action", rule.action.name)
                        .put("enabled", rule.enabled)
                        // org.json's JSONObject.put(key, null) REMOVES the key entirely rather
                        // than serializing a JSON null -- JSONObject.NULL is the sentinel that
                        // actually produces `"replacementValue":null` on the wire.
                        .put("replacementValue", rule.replacementValue ?: JSONObject.NULL)
                )
            }
            ok(arr.toString())
        } catch (_: Exception) { ok("[]") }
    }

    /** Serializes one [com.example.data.local.InterceptRuleEntity] with the exact same field set
     *  [listRules] emits per-entry — shared so create/update responses match the GET shape. */
    private fun ruleToJson(rule: com.example.data.local.InterceptRuleEntity): JSONObject =
        JSONObject()
            .put("id", rule.id)
            .put("name", rule.name)
            .put("pattern", rule.pattern)
            .put("domain", rule.domain)
            .put("action", rule.action.name)
            .put("enabled", rule.enabled)
            .put("replacementValue", rule.replacementValue ?: JSONObject.NULL)

    /**
     * `POST /api/rules` — creates a new [com.example.data.local.InterceptRuleEntity]. Body:
     * `{"name","pattern","domain","action","enabled","replacementValue"}`, all optional except
     * `action` must be one of [com.example.data.local.RuleAction]'s names when present (defaults
     * to `LOG_ONLY`, the same safe default the entity itself declares, when absent or
     * unrecognized) — added for the LAN dashboard's rules editor (master plan §2.16) and for any
     * other authenticated local caller.
     */
    private suspend fun createRule(body: String): Triple<Int, String, String> {
        return try {
            val dao = db()?.interceptRuleDao() ?: return err(500, "db unavailable")
            val json = JSONObject(body)
            val action = runCatching {
                com.example.data.local.RuleAction.valueOf(json.optString("action", "LOG_ONLY").uppercase())
            }.getOrDefault(com.example.data.local.RuleAction.LOG_ONLY)
            val rule = com.example.data.local.InterceptRuleEntity(
                name = json.optString("name", ""),
                pattern = json.optString("pattern", ""),
                domain = json.optString("domain", ""),
                action = action,
                enabled = json.optBoolean("enabled", true),
                replacementValue = json.optNullableString("replacementValue"),
            )
            val id = dao.insert(rule)
            ok(ruleToJson(rule.copy(id = id)).toString())
        } catch (e: Exception) {
            err(400, e.message ?: "bad request")
        }
    }

    /**
     * `PUT /api/rules/{id}` — partial update: every field in the JSON body overwrites the
     * matching column, any field the caller omits keeps its existing stored value (so a
     * dashboard "toggle enabled" click can PUT `{"enabled":false}` alone without having to
     * resend the whole rule).
     */
    private suspend fun updateRule(idRaw: String, body: String): Triple<Int, String, String> {
        val id = idRaw.toLongOrNull() ?: return err(400, "invalid id")
        return try {
            val dao = db()?.interceptRuleDao() ?: return err(500, "db unavailable")
            val existing = dao.getById(id) ?: return err(404, "rule not found")
            val json = JSONObject(body)
            val action = if (json.has("action")) {
                runCatching { com.example.data.local.RuleAction.valueOf(json.getString("action").uppercase()) }
                    .getOrDefault(existing.action)
            } else existing.action
            val updated = existing.copy(
                name = if (json.has("name")) json.optString("name", existing.name) else existing.name,
                pattern = if (json.has("pattern")) json.optString("pattern", existing.pattern) else existing.pattern,
                domain = if (json.has("domain")) json.optString("domain", existing.domain) else existing.domain,
                action = action,
                enabled = if (json.has("enabled")) json.optBoolean("enabled", existing.enabled) else existing.enabled,
                replacementValue = if (json.has("replacementValue")) json.optNullableString("replacementValue") else existing.replacementValue,
            )
            dao.update(updated)
            ok(ruleToJson(updated).toString())
        } catch (e: Exception) {
            err(400, e.message ?: "bad request")
        }
    }

    /** `DELETE /api/rules/{id}`. Idempotent: deleting an already-absent id is a 200, not a 404 —
     *  the end state ("this id no longer exists") is identical either way. */
    private suspend fun deleteRule(idRaw: String): Triple<Int, String, String> {
        val id = idRaw.toLongOrNull() ?: return err(400, "invalid id")
        return try {
            val dao = db()?.interceptRuleDao() ?: return err(500, "db unavailable")
            dao.deleteById(id)
            ok("""{"status":"deleted","id":$id}""")
        } catch (e: Exception) {
            err(500, e.message ?: "delete failed")
        }
    }

    /** Returns [key]'s string value, or null if the key is absent/JSON-null -- unlike
     *  [JSONObject.optString] (whose 1-arg overload returns `""` for both, indistinguishable
     *  from a real empty string, and whose 2-arg overload can't take a null default from
     *  Kotlin's non-null `String` parameter type). */
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    /** Flattens a `{"k":"v",...}` [JSONObject] into a `Map<String, String>`, or an empty map if
     *  [obj] is null (key absent from the payload). */
    private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEachRemaining { key -> map[key] = obj.optString(key, "") }
        return map
    }

    /** A body decoded from the wire, plus the exact byte count it actually occupies -- computed
     *  from the decoded bytes, never from the (possibly larger, Base64-inflated) wire string. */
    private class DecodedBody(val text: String, val byteCount: Long)

    /**
     * Decodes [raw] per [encoding]. The two producers disagree on whether this field exists at
     * all, which is exactly why it must be an explicit branch rather than a shared default:
     *  - MV3 extension: always sends an explicit `requestBodyEncoding`/`responseBodyEncoding`
     *    ("none"/"utf8"/"base64" — see `interceptor-main.mjs`'s `encodingForContentType`).
     *    "none" is MV3's explicit "no body available" marker (e.g. webRequest-sourced events,
     *    which cannot see bodies in MV3 at all) and must decode to nothing, not to a literal
     *    "none" string.
     *  - SDK: never sends an encoding field at all -- `requestBody`/`responseBody` are *always*
     *    Base64 when present (see `RequestrObserver.observeDetailed`'s unconditional
     *    `Base64.encodeToString`), so a null/absent [encoding] means "decode as base64", not
     *    "treat as plain text" (which would corrupt every SDK body silently).
     * Unrecognized non-null encodings (e.g. MV3's "blob-async"/"error" markers for a body it
     * could not capture) are treated as opaque text rather than dropped, so a real placeholder
     * the extension chose to send is never silently discarded.
     */
    private fun decodeBody(raw: String?, encoding: String?): DecodedBody? {
        if (raw.isNullOrEmpty()) return null
        if (encoding == "none") return null
        return if (encoding == "base64" || encoding == null) {
            val bytes = runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }.getOrNull()
                ?: return DecodedBody(raw, raw.toByteArray(Charsets.UTF_8).size.toLong())
            DecodedBody(String(bytes, Charsets.UTF_8), bytes.size.toLong())
        } else {
            DecodedBody(raw, raw.toByteArray(Charsets.UTF_8).size.toLong())
        }
    }

    /**
     * Ingest a batch of events POSTed by either of `/api/observe`'s two producers, which use two
     * genuinely different event shapes:
     *
     * 1. The Android `requestr-sdk` (`RequestrObserver.flush()`, see
     *    `sdk/src/main/java/com/requestr/sdk/RequestrObserver.kt`): a flat `headers` map (request
     *    headers only — the SDK never sees response headers), `requestBody`/`responseBody` as
     *    Base64 (always, when present — see `observeDetailed`'s `Base64.encodeToString`), a
     *    single `bodySize` covering request+response combined, and an `isDecrypted` flag (true
     *    for every in-process HTTP stack it instruments — there is nothing to decrypt, it already
     *    sees plaintext).
     * 2. The MV3 browser extension (`browser-extension/src/sdk/observer.mjs`'s `normalizeEvent`):
     *    separate `requestHeaders`/`responseHeaders` maps, separate `requestBody`/`responseBody`
     *    strings each with their own `requestBodyEncoding`/`responseBodyEncoding` (`"none"`,
     *    `"utf8"`, or `"base64"` — see `interceptor-main.mjs`'s `encodingForContentType`), and no
     *    `isDecrypted` field at all (browser traffic is never MITM'd by this app, so it is always
     *    honestly `wasDecrypted = true` — the extension observes the page's own plaintext
     *    fetch()/XHR calls, same rationale as the SDK).
     *
     * FIX 1: previously this only read the SDK shape's `headers` field (discarding
     * `requestHeaders`/`responseHeaders`/`requestBody`/`responseBody` entirely for BOTH
     * producers), never read `isDecrypted`, and mis-assigned the SDK's *combined* `bodySize`
     * entirely to `responseSizeBytes` while leaving `requestSizeBytes` always zero. Now each
     * producer's real fields are read, bodies are decoded per their declared/implied encoding,
     * and request/response sizes are computed independently from the actual bytes.
     *
     * Backward compatible with the original minimal shape (`headers` + `bodySize`, no bodies at
     * all): when `requestHeaders`/`responseHeaders` are absent, `headers` is used as the request
     * headers (matching the original behavior exactly), and when no body fields are present at
     * all, `bodySize` alone still populates `responseSizeBytes` as it always did, so an older
     * caller sending only the original fields is unaffected.
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
            val database = db() ?: return err(500, "db unavailable")
            // S2 task 15: SDK ingestion is a NanoHTTPD handler, not a relay -- back-pressured
            // queue path, awaited so `received` reports rows that actually landed.
            val queue = com.example.data.persistence.PersistenceQueue.forDatabase(database)
            val sessionId = sdkSessionId()
            var inserted = 0
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val url = e.optString("url", "")
                if (url.isEmpty()) continue
                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                val path = runCatching { java.net.URI(url).path }.getOrNull() ?: ""
                val statusCodeRaw = e.optInt("statusCode", -1)

                // MV3 shape: separate requestHeaders/responseHeaders. SDK (and the older
                // minimal) shape: a single flat `headers` map, which the SDK only ever
                // populates with *request* headers -- it has no response-header visibility.
                val requestHeaders = jsonObjectToMap(e.optJSONObject("requestHeaders") ?: e.optJSONObject("headers"))
                val responseHeaders = jsonObjectToMap(e.optJSONObject("responseHeaders"))

                val decodedRequestBody = decodeBody(e.optNullableString("requestBody"), e.optNullableString("requestBodyEncoding"))
                val decodedResponseBody = decodeBody(e.optNullableString("responseBody"), e.optNullableString("responseBodyEncoding"))

                // Sizes: prefer each side's own decoded body length (works for both producers,
                // since both now carry real per-side bodies) and fall back to the legacy
                // combined `bodySize` -- attributed to responseSizeBytes only, exactly as the
                // original (pre-fix) behavior did -- when no body fields are present at all
                // (the original minimal contract this endpoint has always accepted).
                val requestSizeBytes = decodedRequestBody?.byteCount ?: 0L
                val responseSizeBytes = decodedResponseBody?.byteCount
                    ?: if (decodedRequestBody == null) e.optLong("bodySize", 0) else 0L

                val entity = com.example.data.local.CapturedTrafficEntity(
                    sessionId = sessionId,
                    method = e.optString("method", "GET"),
                    url = url,
                    host = host,
                    path = path,
                    statusCode = if (statusCodeRaw >= 0) statusCodeRaw else null,
                    protocol = "sdk",
                    requestHeaders = requestHeaders,
                    responseHeaders = responseHeaders,
                    requestBody = decodedRequestBody?.text,
                    responseBody = decodedResponseBody?.text,
                    requestSizeBytes = requestSizeBytes,
                    responseSizeBytes = responseSizeBytes,
                    latencyMs = e.optLong("durationMs", 0),
                    timestamp = e.optLong("timestamp", System.currentTimeMillis()),
                    // The SDK sends a real isDecrypted flag (always true today -- every stack it
                    // instruments observes in-process plaintext); the MV3 extension has no such
                    // field at all, since browser traffic here is likewise never MITM'd -- true
                    // is the honest default for both, matching pre-fix behavior when absent.
                    wasDecrypted = if (e.has("isDecrypted")) e.optBoolean("isDecrypted", true) else true,
                    // Task 12: neither producer has a real connection identity to report (the
                    // SDK never sees a TCP 5-tuple or a CONNECT target; the extension observes
                    // browser fetch()/XHR calls, not a socket) — null is the honest value here,
                    // not a heuristic. See ConnectionLineage.httpSteps(), which only attaches
                    // rows carrying a real connectionId.
                    connectionId = null,
                    // S2 task 15: honest provenance instead of UNKNOWN.
                    captureMethod = com.example.data.local.CaptureRowMethod.SDK_OBSERVER,
                )
                queue.insertTransactionAndAwait(entity)
                inserted++
            }
            ok("""{"received":$inserted}""")
        } catch (e: Exception) {
            err(400, e.message ?: "bad request")
        }
    }

    /**
     * At most this many lines from a single `/api/keylog` request body are ever handed to
     * [com.example.interception.tls.TlsKeyLogRegistry] -- independent of (and in addition to)
     * that registry's own `MAX_ENTRIES`/TTL bounds. Mirrors [com.requestr.sdk.RequestrObserver]'s
     * `MAX_PENDING_EVENTS` -- same rationale: one oversized or malicious POST body must not be
     * able to spend unbounded CPU walking a huge line list before any per-line validation gets a
     * chance to reject anything.
     */
    private const val MAX_KEYLOG_LINES_PER_REQUEST = 2000

    /**
     * `POST /api/keylog` — Task 13 / CAT-2d: the authenticated, cross-process PRIMARY producer
     * path for TLS 1.3 / QUIC key material. This is what lets an SDK-embedded third-party host
     * app (which never shares a process, let alone a classpath, with requestr's own `:app`
     * module) feed real key material into [com.example.interception.tls.TlsKeyLogRegistry] --
     * the SDK's previous sole mechanism, `KeyLogRegistryBridge`'s `Class.forName` reflection (see
     * that class's kdoc in the `:sdk` module), is a permanent no-op outside of requestr's own
     * process and is now demoted to an optional same-process fast path behind this HTTP path.
     *
     * Gated by the exact same [isAuthorized] pairing-Bearer-token check `/api/observe`/`/api/rules`
     * already use (401 on missing/invalid, enforced by the [route] dispatcher before this is ever
     * called) -- a bearer of this material can decrypt matching TLS/QUIC traffic, at least as
     * sensitive as observed plaintext traffic itself.
     *
     * Accepts either body shape:
     *  - `{"lines": ["LABEL <64-hex client_random> <hex secret>", ...]}` (what
     *    `KeyLogProducer`'s HTTP path posts), or
     *  - a raw newline-delimited NSS keylog text body, so a user-supplied `SSLKEYLOGFILE` can be
     *    piped in directly (e.g. `curl --data-binary @keylog.txt`) without any JSON wrapping.
     *
     * Per-line validation (`LABEL <64-hex client_random> <hex secret>`, a recognised
     * [com.example.interception.tls.KeyLogLabel], well-formed hex) is
     * [com.example.interception.tls.TlsKeyLogRegistry.importNssKeyLog]'s own frozen contract --
     * malformed lines are skipped there, never thrown, and this handler forwards its accepted
     * count verbatim. **Bounded** by [MAX_KEYLOG_LINES_PER_REQUEST] -- see its kdoc.
     */
    private fun receiveKeylogLines(body: String): Triple<Int, String, String> {
        return try {
            // Prefer the JSON `{"lines":[...]}` shape; a body that isn't valid JSON at all (e.g.
            // a raw NSS keylog file piped straight through) falls back to plain newline
            // splitting rather than failing the request.
            val linesArray = runCatching { JSONObject(body).optJSONArray("lines") }.getOrNull()
            val rawLines: List<String> = if (linesArray != null) {
                (0 until linesArray.length()).map { linesArray.optString(it, "") }
            } else {
                body.lineSequence().toList()
            }
            val bounded = rawLines.asSequence().take(MAX_KEYLOG_LINES_PER_REQUEST)
            val accepted = com.example.interception.tls.TlsKeyLogRegistry.importNssKeyLog(bounded)
            ok("""{"accepted":$accepted}""")
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
     *
     * FIX D: delegates to [com.example.data.local.CaptureSessionRegistry], the one shared,
     * correctly-cached implementation of this pattern (see its kdoc for why the three previous
     * copies -- this one included -- needed consolidating).
     */
    private suspend fun sdkSessionId(): Long {
        val database = db() ?: return 0L
        return com.example.data.local.CaptureSessionRegistry.openSessionIdFor(
            database, com.example.data.local.CaptureTier.SDK, port,
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
