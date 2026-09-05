package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.local.InterceptorRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.interception.rules.RuleApiJson
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Master plan §2.16 — "Live NanoHTTPD-style browser dashboard for `LocalApiServer`":
 * `RoRoIntercept`'s `WebServerService.kt` (NanoHTTPD, 2s auto-refresh, color-coded methods,
 * in-browser rules CRUD, request/response detail view, served over LAN) mirrored as a
 * separate, human-browsable dashboard.
 *
 * **Deliberately a standalone server on its own port, not a route bolted onto
 * [LocalApiServer].** [LocalApiServer] is documented and bound as "listens on localhost only",
 * and this class defaults to the same posture: it binds `127.0.0.1` unless the caller
 * explicitly opts into LAN exposure via [start]'s [bindToLan] parameter.
 *
 * **Security (review P0):** every route — including the `/` HTML page and the rules CRUD
 * mutations — is gated behind the same pairing token as [LocalApiServer]: the request must
 * present `Authorization: Bearer <pairing-token>` (or, for a browser top-level navigation that
 * cannot set arbitrary headers, the identical token as the `token` query parameter). No wildcard
 * CORS header is ever emitted. Without this, any peer on the LAN could read captured request/
 * response bodies (tokens, cookies) and mutate intercept rules.
 *
 * Not auto-started — a developer debugging tool in the same spirit as mitmproxy's/Charles' web
 * UI, wired up from [com.example.ui.screens.CaptureController.startDashboard]/`stopDashboard`.
 */
object LanDashboardServer {
    private const val TAG = "LanDashboard"
    const val DEFAULT_PORT = 9877

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    val isRunning: Boolean get() = _isRunning.value

    @Volatile var port: Int = DEFAULT_PORT
        private set

    /** Volatile cache of the pairing token, populated by [ensurePairingToken] before serving. */
    @Volatile private var pairingToken: String? = null

    /**
     * Starts the dashboard on [port]. Binds `127.0.0.1` unless [bindToLan] is true (an explicit
     * opt-in that exposes the server to other devices on the local network). Auth still applies
     * in both cases; [bindToLan] only widens the *network reachability*, never the access control.
     */
    fun start(context: Context, port: Int = DEFAULT_PORT, bindToLan: Boolean = false) {
        if (_isRunning.value) return
        appContext = context.applicationContext
        this.port = port
        _isRunning.value = true

        serverJob = scope.launch {
            try {
                ensurePairingToken()
                val bindHost = if (bindToLan) "0.0.0.0" else "127.0.0.1"
                serverSocket = ServerSocket(port, 10, InetAddress.getByName(bindHost))
                Log.i(TAG, "LAN dashboard started on $bindHost:$port (lan=$bindToLan)")
                while (isActive && _isRunning.value) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (_isRunning.value) Log.w(TAG, "Accept interrupted")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dashboard server error: ${e.message}")
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
        Log.i(TAG, "LAN dashboard stopped")
    }

    private fun db() = appContext?.let { RequestrDatabase.get(it) }

    private fun settings(): SettingsDataStore? = appContext?.let { SettingsDataStore.get(it) }

    /** Loads the persisted pairing token (generating + persisting a fresh 256-bit one if none). */
    private suspend fun ensurePairingToken() {
        val ds = settings() ?: return
        val existing = ds.pairingToken.first()
        if (existing != null) {
            pairingToken = existing
            return
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        ds.setPairingToken(fresh)
        pairingToken = fresh
    }

    /**
     * Validates the bearer token. Accepts `Authorization: Bearer <token>` (case-insensitive
     * scheme, exact-match token) or the identical token via the `token` query parameter — the
     * latter exists only because a browser top-level navigation (`http://host:port/?token=…`)
     * cannot set an arbitrary header, and the HTML page then re-sends the token as the real
     * `Authorization: Bearer` header on every subsequent fetch. Same token as [LocalApiServer].
     */
    private fun isAuthorized(authHeader: String?, queryToken: String?): Boolean {
        val expected = pairingToken ?: return false
        val bearer = authHeader?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
        return bearer == expected || queryToken == expected
    }

    private suspend fun handleClient(socket: java.net.Socket) {
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
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.startsWith("Authorization:", ignoreCase = true)) {
                        authHeader = hdr.substringAfter(":").trim()
                    }
                    if (hdr.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val queryToken = splitQuery(rawPath).second["token"]
                if (!isAuthorized(authHeader, queryToken)) {
                    val resp = err(401, "missing or invalid token (Authorization: Bearer or ?token=)")
                    val bodyBytes = resp.third.toByteArray(Charsets.UTF_8)
                    val header = buildString {
                        append("HTTP/1.1 ${resp.first} ${statusText(resp.first)}\r\n")
                        append("Content-Type: ${resp.second}\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = s.getOutputStream()
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.write(bodyBytes)
                    out.flush()
                    return
                }

                val (status, contentType, resp) = route(method, rawPath, body)
                val bodyBytes = resp.toByteArray(Charsets.UTF_8)
                val header = buildString {
                    append("HTTP/1.1 $status ${statusText(status)}\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = s.getOutputStream()
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
            }
        } catch (_: Exception) { }
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 404 -> "Not Found"; 500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun ok(json: String) = Triple(200, "application/json", json)
    private fun err(code: Int, msg: String) = Triple(code, "application/json", """{"error":"${msg.replace("\"", "'")}"}""")

    private fun splitQuery(rawPath: String): Pair<String, Map<String, String>> {
        val qIdx = rawPath.indexOf('?')
        if (qIdx < 0) return rawPath to emptyMap()
        val path = rawPath.substring(0, qIdx)
        val params = mutableMapOf<String, String>()
        for (pair in rawPath.substring(qIdx + 1).split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            fun decode(v: String) = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            params[decode(key)] = decode(value)
        }
        return path to params
    }

    private suspend fun route(method: String, rawPath: String, body: String): Triple<Int, String, String> {
        val (path, query) = splitQuery(rawPath)
        return try {
            when {
                // Inject the validated token into the HTML so both auth paths work: a browser
                // that entered via `?token=…` gets it from location.search, and a non-browser
                // client that authenticated via `Authorization: Bearer` gets it baked in here.
                path == "/" && method == "GET" ->
                    Triple(200, "text/html; charset=utf-8", dashboardHtml(pairingToken.orEmpty()))

                path == "/dash/captures" && method == "GET" -> dashCaptures(query)
                path.startsWith("/dash/captures/") && method == "GET" ->
                    dashCapture(path.removePrefix("/dash/captures/"))

                path == "/dash/rules" && method == "GET" -> dashListRules()
                path == "/dash/rules" && method == "POST" -> dashCreateRule(body)
                path.startsWith("/dash/rules/") && method == "PUT" -> dashUpdateRule(path.removePrefix("/dash/rules/"), body)
                path.startsWith("/dash/rules/") && method == "DELETE" -> dashDeleteRule(path.removePrefix("/dash/rules/"))

                path == "/dash/stats" && method == "GET" -> dashStats()

                else -> err(404, "not found: $path")
            }
        } catch (e: Exception) {
            err(500, e.message ?: "internal error")
        }
    }

    private suspend fun dashCaptures(query: Map<String, String>): Triple<Int, String, String> {
        val dao = db()?.capturedTrafficDao() ?: return ok("[]")
        val limit = (query["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val cursorRaw = query["cursor"]
        var beforeTs: Long? = null
        var beforeId: Long? = null
        if (!cursorRaw.isNullOrBlank()) {
            val parts = cursorRaw.split(':')
            beforeTs = parts.getOrNull(0)?.toLongOrNull()
            beforeId = parts.getOrNull(1)?.toLongOrNull()
        }
        val rows = dao.getPageBeforeCursor(beforeTs, beforeId, limit)
        val arr = JSONArray()
        for (row in rows) {
            arr.put(
                JSONObject()
                    .put("id", row.id)
                    .put("method", row.method)
                    .put("url", row.url)
                    .put("host", row.host)
                    .put("status", row.statusCode ?: JSONObject.NULL)
                    .put("latencyMs", row.latencyMs)
                    .put("timestamp", row.timestamp)
                    .put("wasDecrypted", row.wasDecrypted),
            )
        }
        val nextCursor = if (rows.size >= limit) rows.lastOrNull()?.let { "${it.timestamp}:${it.id}" } else null
        return ok(JSONObject().put("items", arr).put("nextCursor", nextCursor ?: JSONObject.NULL).toString())
    }

    private suspend fun dashCapture(idRaw: String): Triple<Int, String, String> {
        val id = idRaw.toLongOrNull() ?: return err(400, "invalid id")
        val dao = db()?.capturedTrafficDao() ?: return err(500, "db unavailable")
        val row = dao.getById(id) ?: return err(404, "not found")
        return ok(
            JSONObject()
                .put("id", row.id)
                .put("method", row.method)
                .put("url", row.url)
                .put("host", row.host)
                .put("status", row.statusCode ?: JSONObject.NULL)
                .put("requestHeaders", JSONObject(row.requestHeaders))
                .put("responseHeaders", JSONObject(row.responseHeaders))
                .put("requestBody", row.requestBody ?: JSONObject.NULL)
                .put("responseBody", row.responseBody ?: JSONObject.NULL)
                .put("latencyMs", row.latencyMs)
                .put("timestamp", row.timestamp)
                .toString(),
        )
    }

    // S4 task 34: the rules CRUD mirrors LocalApiServer's typed `schemaVersion: 2` envelope
    // (RuleApiJson). v1 flat bodies from the in-page form are accepted and converted; the list
    // response carries the v1 projection fields the page renders (domain/pattern/action).

    private suspend fun dashListRules(): Triple<Int, String, String> {
        val database = db() ?: return ok(RuleApiJson.envelope(emptyList()).toString())
        return ok(RuleApiJson.envelope(database.interceptorRuleDao().getAllOrdered(), database.ruleFolderDao().getAll()).toString())
    }

    private suspend fun dashCreateRule(body: String): Triple<Int, String, String> {
        val dao = db()?.interceptorRuleDao() ?: return err(500, "db unavailable")
        val json = JSONObject(body)
        return when (val parsed = RuleApiJson.parseNewRule(json, sortOrder = dao.maxSortOrder() + 1, enabledRules = dao.countEnabled())) {
            is RuleApiJson.Parsed.Invalid -> err(400, parsed.message)
            is RuleApiJson.Parsed.Ok -> {
                val id = dao.insert(InterceptorRuleEntity.fromDomain(parsed.rule))
                ok(RuleApiJson.ruleToJson(parsed.rule.copy(id = id)).toString())
            }
        }
    }

    private suspend fun dashUpdateRule(idRaw: String, body: String): Triple<Int, String, String> {
        val id = idRaw.toLongOrNull() ?: return err(400, "invalid id")
        val dao = db()?.interceptorRuleDao() ?: return err(500, "db unavailable")
        val existing = dao.getById(id) ?: return err(404, "rule not found")
        val json = JSONObject(body)
        val enabledOthers = dao.countEnabled() - (if (existing.enabled) 1 else 0)
        return when (val parsed = RuleApiJson.applyUpdate(existing.toDomain(), json, enabledRules = enabledOthers)) {
            is RuleApiJson.Parsed.Invalid -> err(400, parsed.message)
            is RuleApiJson.Parsed.Ok -> {
                val updated = InterceptorRuleEntity.fromDomain(parsed.rule.copy(id = id))
                dao.update(updated)
                ok(RuleApiJson.ruleToJson(updated).toString())
            }
        }
    }

    private suspend fun dashDeleteRule(idRaw: String): Triple<Int, String, String> {
        val id = idRaw.toLongOrNull() ?: return err(400, "invalid id")
        val dao = db()?.interceptorRuleDao() ?: return err(500, "db unavailable")
        dao.deleteById(id)
        return ok("""{"status":"deleted","id":$id}""")
    }

    /** Aggregate counters for the dashboard's header strip. */
    private suspend fun dashStats(): Triple<Int, String, String> {
        val dao = db()?.capturedTrafficDao() ?: return ok("""{"total":0}""")
        val rows = dao.getPageBeforeCursor(null, null, 2000)
        val byMethod = rows.groupingBy { it.method }.eachCount()
        val errorCount = rows.count { (it.statusCode ?: 0) >= 400 }
        val obj = JSONObject()
            .put("total", rows.size)
            .put("errorCount", errorCount)
            .put("byMethod", JSONObject(byMethod as Map<*, *>))
        return ok(obj.toString())
    }

    /**
     * Self-contained dashboard HTML/JS: no external assets (the dashboard must render even
     * offline on the LAN with no CDN reachable), 2-second `setInterval` refresh, method-colored
     * rows (mirroring `RoRoIntercept`'s `WebServerService` color table), a click-through
     * request/response detail panel, and a rules CRUD form.
     */
    private fun dashboardHtml(token: String): String = DASHBOARD_HTML_TEMPLATE.replace("__REQUESTR_TOKEN__", token)

    private val DASHBOARD_HTML_TEMPLATE = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>requestr LAN dashboard</title>
<style>
body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#0f172a;color:#e2e8f0}
header{padding:12px 16px;background:#111827;border-bottom:1px solid #1f2937;display:flex;gap:16px;align-items:center}
h1{font-size:15px;margin:0;color:#f8fafc}
.stat{font-size:12px;color:#94a3b8}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:6px 10px;text-align:left;border-bottom:1px solid #1f2937}
tr:hover{background:#1e293b;cursor:pointer}
.m{padding:2px 6px;border-radius:4px;font-weight:600;color:#0f172a}
.mGET{background:#38bdf8}.mPOST{background:#34d399}.mPUT{background:#fbbf24}
.mDELETE{background:#f87171}.mPATCH{background:#a78bfa}.mOTHER{background:#94a3b8}
.status2{color:#34d399}.status3{color:#fbbf24}.status4{color:#f87171}.status5{color:#f87171}
#panel{position:fixed;right:0;top:0;bottom:0;width:38%;min-width:320px;background:#0b1220;
  border-left:1px solid #1f2937;overflow:auto;padding:14px;display:none;font-size:12px;white-space:pre-wrap;word-break:break-all}
section{padding:12px 16px}
input,select,button{background:#1e293b;color:#e2e8f0;border:1px solid #334155;border-radius:4px;padding:5px 8px;font-size:12px}
button{cursor:pointer}
button:hover{background:#334155}
.ruleRow{display:flex;gap:6px;align-items:center;margin-bottom:6px;flex-wrap:wrap}
</style></head>
<body>
<header>
  <h1>requestr &middot; LAN dashboard</h1>
  <span class="stat" id="stat">loading…</span>
</header>
<section>
  <h3>Live captures (auto-refresh 2s)</h3>
  <table id="capTable"><thead><tr><th>Method</th><th>Status</th><th>Host</th><th>URL</th><th>ms</th></tr></thead>
  <tbody id="capBody"></tbody></table>
</section>
<section>
  <h3>Rules</h3>
  <div id="rules"></div>
  <div class="ruleRow">
    <input id="rName" placeholder="name">
    <input id="rDomain" placeholder="domain substring">
    <input id="rPattern" placeholder="url regex">
    <select id="rAction">
      <option>LOG_ONLY</option><option>BLOCK</option><option>REDIRECT</option>
      <option>MODIFY_REQUEST</option><option>MODIFY_RESPONSE</option>
      <option>FORCE_MITM</option><option>MOCK_RESPONSE</option>
    </select>
    <button onclick="createRule()">Add rule</button>
  </div>
</section>
<div id="panel"></div>
<script>
const TOKEN = new URLSearchParams(location.search).get('token') || '__REQUESTR_TOKEN__';
const api = (path, opts) => {
  opts = opts || {};
  opts.headers = Object.assign({}, opts.headers, TOKEN ? {'Authorization': 'Bearer ' + TOKEN} : {});
  return fetch(path, opts);
};
const methodClass = m => ['GET','POST','PUT','DELETE','PATCH'].includes(m) ? 'm'+m : 'mOTHER';
const statusClass = s => s ? 'status'+String(s)[0] : '';

async function refreshCaptures() {
  const res = await api('/dash/captures?limit=200');
  const data = await res.json();
  const body = document.getElementById('capBody');
  body.innerHTML = '';
  for (const row of data.items) {
    const tr = document.createElement('tr');
    tr.onclick = () => showDetail(row.id);
    tr.innerHTML = `<td><span class="m ${'$'}{methodClass(row.method)}">${'$'}{row.method}</span></td>` +
      `<td class="${'$'}{statusClass(row.status)}">${'$'}{row.status ?? '-'}</td>` +
      `<td>${'$'}{row.host}</td><td>${'$'}{row.url}</td><td>${'$'}{row.latencyMs}</td>`;
    body.appendChild(tr);
  }
  const stats = await (await api('/dash/stats')).json();
  document.getElementById('stat').textContent =
    `${'$'}{stats.total} captured - ${'$'}{stats.errorCount} errors (4xx/5xx)`;
}

async function showDetail(id) {
  const res = await api('/dash/captures/' + id);
  const d = await res.json();
  const panel = document.getElementById('panel');
  panel.style.display = 'block';
  panel.textContent = JSON.stringify(d, null, 2);
}

async function refreshRules() {
  const res = await api('/dash/rules');
  const json = await res.json();
  const rules = Array.isArray(json.rules) ? json.rules : Array.isArray(json) ? json : [];
  const div = document.getElementById('rules');
  div.innerHTML = '';
  for (const r of rules) {
    const row = document.createElement('div');
    row.className = 'ruleRow';
    row.innerHTML = `<b>${'$'}{r.name || '(unnamed)'}</b> <span>${'$'}{r.stage || ''}</span> <span>${'$'}{r.action}</span> ` +
      `<span>domain=${'$'}{r.domain}</span> <span>pattern=${'$'}{r.pattern}</span> ` +
      `<button data-id="${'$'}{r.id}" data-en="${'$'}{r.enabled}">${'$'}{r.enabled ? 'Disable' : 'Enable'}</button> ` +
      `<button data-del="${'$'}{r.id}">Delete</button>`;
    row.querySelector('button[data-id]').onclick = (e) => toggleRule(r.id, !r.enabled);
    row.querySelector('button[data-del]').onclick = (e) => deleteRule(r.id);
    div.appendChild(row);
  }
}

async function createRule() {
  const body = {
    name: document.getElementById('rName').value,
    domain: document.getElementById('rDomain').value,
    pattern: document.getElementById('rPattern').value,
    action: document.getElementById('rAction').value,
    enabled: true,
  };
  await api('/dash/rules', { method: 'POST', body: JSON.stringify(body) });
  refreshRules();
}

async function toggleRule(id, enabled) {
  await api('/dash/rules/' + id, { method: 'PUT', body: JSON.stringify({ enabled }) });
  refreshRules();
}

async function deleteRule(id) {
  await api('/dash/rules/' + id, { method: 'DELETE' });
  refreshRules();
}

refreshCaptures(); refreshRules();
setInterval(refreshCaptures, 2000);
</script>
</body></html>
    """.trimIndent()
}
