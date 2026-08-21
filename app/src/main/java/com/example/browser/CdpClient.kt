package com.example.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1: Chrome DevTools Protocol (CDP) client.
 *
 * Connects to a running Chromium/Chrome instance's remote-debugging endpoint
 * (default `localhost:9222`), the same port `--remote-debugging-port=9222`
 * exposes. This talks the *real* CDP wire protocol over a plain WebSocket:
 *
 *  1. GET http://host:port/json/version (or /json/list) to discover a page
 *     target's `webSocketDebuggerUrl` — Chrome does not expose a single
 *     "browser-wide" socket that also gets you Page/Network domain events;
 *     those are per-target sockets. This client attaches to the first
 *     page-type target it finds (matches what a typical single-tab "point
 *     requestr at my browser" workflow needs, and is an explicit, documented
 *     scope limit — see class doc below).
 *  2. Opens a WebSocket to that `webSocketDebuggerUrl`.
 *  3. Sends JSON-RPC-shaped CDP commands: `{"id":N,"method":"Domain.method","params":{...}}`
 *  4. Dispatches incoming frames: either a `{"id":N,"result":...}` response to a
 *     pending command (matched by id), or a `{"method":"Domain.event","params":...}`
 *     unsolicited event, which is routed to any registered event listener for
 *     that method.
 *
 * Honest state machine, deliberately mirroring [com.example.runtime.frida.FridaClient]:
 * DISCONNECTED → CONNECTING → CONNECTED → FAILED. Never reports CONNECTED without a real
 * open WebSocket to a real target.
 *
 * Scope caveat: this client attaches to exactly one target (the first page target
 * discovered via `/json/list`). Multi-tab / multi-target fan-out, target
 * auto-attach (`Target.setAutoAttach`), and reconnect-with-backoff are not
 * implemented — a dropped socket transitions to FAILED and requires the user to
 * reconnect from Settings. This is enough to prove genuine CDP wiring end-to-end
 * without pretending to be a production-grade multi-target CDP client.
 */
class CdpClient(
    private val host: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "CdpClient"
        private const val HTTP_TIMEOUT_S = 5L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val stateFlow: StateFlow<State> = _state.asStateFlow()
    val state: State get() = _state.value

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Human-readable target info (title/url) once attached, for the Settings UI. */
    private val _targetInfo = MutableStateFlow<String?>(null)
    val targetInfo: StateFlow<String?> = _targetInfo.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // CDP sockets are long-lived, no read timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject?, String?) -> Unit>()

    /** method name -> listeners invoked with the event's `params` object. */
    private val eventListeners = ConcurrentHashMap<String, MutableList<(JSONObject) -> Unit>>()

    /** Register a listener for a CDP event, e.g. "Network.requestWillBeSent". */
    fun on(method: String, listener: (JSONObject) -> Unit) {
        eventListeners.getOrPut(method) { mutableListOf() }.add(listener)
    }

    /** Connect: discover a page target via /json/list, then open its WebSocket. */
    fun connect(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) {
            onResult(_state.value == State.CONNECTED, null)
            return
        }
        _state.value = State.CONNECTING
        _lastError.value = null
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val target = discoverPageTarget()
                if (target == null) {
                    fail("No page target found at http://$host:$port/json/list — is Chrome running with --remote-debugging-port=$port?")
                    onResult(false, _lastError.value)
                    return@launch
                }
                _targetInfo.value = "${target.optString("title", "(untitled)")} — ${target.optString("url", "")}"
                val wsUrl = target.optString("webSocketDebuggerUrl", "")
                if (wsUrl.isEmpty()) {
                    fail("Target has no webSocketDebuggerUrl")
                    onResult(false, _lastError.value)
                    return@launch
                }
                openSocket(wsUrl, onResult)
            } catch (e: Exception) {
                fail("Connect failed: ${e.message}")
                onResult(false, _lastError.value)
            }
        }
    }

    private fun discoverPageTarget(): JSONObject? {
        val req = Request.Builder().url("http://$host:$port/json/list").build()
        val response: Response = httpClient.newCall(req).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string() ?: return null
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") == "page" && obj.optString("webSocketDebuggerUrl").isNotEmpty()) {
                    return obj
                }
            }
            // Fall back to any target that at least has a debugger URL.
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("webSocketDebuggerUrl").isNotEmpty()) return obj
            }
        }
        return null
    }

    private fun openSocket(wsUrl: String, onResult: (Boolean, String?) -> Unit) {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.CONNECTED
                Log.i(TAG, "CDP connected: $wsUrl")
                onResult(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatch(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "CDP closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = if (code == 1000) State.DISCONNECTED else State.FAILED
                if (code != 1000) _lastError.value = "Socket closed: $code $reason"
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("Socket failure: ${t.message}")
                onResult(false, _lastError.value)
            }
        })
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        _lastError.value = message
        _state.value = State.FAILED
    }

    private fun dispatch(text: String) {
        try {
            val json = JSONObject(text)
            if (json.has("id")) {
                val id = json.getInt("id")
                val callback = pending.remove(id) ?: return
                if (json.has("error")) {
                    callback(null, json.getJSONObject("error").optString("message", "CDP error"))
                } else {
                    callback(json.optJSONObject("result") ?: JSONObject(), null)
                }
            } else if (json.has("method")) {
                val method = json.getString("method")
                val params = json.optJSONObject("params") ?: JSONObject()
                eventListeners[method]?.forEach { listener ->
                    try { listener(params) } catch (e: Exception) {
                        Log.w(TAG, "Event listener for $method threw: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse CDP frame: ${e.message}")
        }
    }

    /**
     * Send a CDP command, e.g. send("Network.enable", JSONObject()) { result, error -> ... }.
     * Fire-and-forget if [callback] is left default and the caller doesn't care about the result.
     */
    fun send(method: String, params: JSONObject = JSONObject(), callback: (JSONObject?, String?) -> Unit = { _, _ -> }) {
        val socket = webSocket
        if (socket == null || _state.value != State.CONNECTED) {
            callback(null, "Not connected")
            return
        }
        val id = nextId.getAndIncrement()
        pending[id] = callback
        val payload = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        socket.send(payload.toString())
    }

    fun disconnect() {
        connectJob?.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        pending.clear()
        _state.value = State.DISCONNECTED
        _targetInfo.value = null
    }
}
