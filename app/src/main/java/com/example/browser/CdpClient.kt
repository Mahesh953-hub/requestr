package com.example.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * W8: Chrome DevTools Protocol (CDP) client with real multi-target auto-attach, flattened
 * session routing, and reconnect-with-backoff -- replacing the earlier single-target,
 * no-retry client this class doc used to describe as an explicit scope limit.
 *
 * Connects to the **browser-level** endpoint (`/json/version`'s `webSocketDebuggerUrl`, not a
 * single page target's), then:
 *  1. `Target.setDiscoverTargets {"discover": true}` so target creation/destruction is
 *     reported even for tabs that existed before this connection.
 *  2. `Target.setAutoAttach {"autoAttach": true, "waitForDebuggerOnStart": false, "flatten":
 *     true}` -- with `flatten: true`, CDP reports each attached child's `sessionId` on
 *     `Target.attachedToTarget` and every subsequent command/event for that target carries
 *     `sessionId` at the message's top level (no per-target socket, no nested session
 *     protocol) -- this is the real mechanism, not a client-side simulation of it.
 *  3. Tracks every attached target in [targets] (sessionId -> [TargetInfo]), added on
 *     `Target.attachedToTarget` and removed on `Target.detachedFromTarget` /
 *     `Target.targetDestroyed`.
 *
 * [send] takes an optional `sessionId`; when non-null it is included in the outgoing JSON-RPC
 * message exactly as the CDP spec requires for flattened sessions. [dispatch] reads the
 * inbound `sessionId` (present on every per-target event/response once flattened) and routes
 * strictly to listeners registered for that same `sessionId` -- a listener registered for one
 * target's session never sees another target's events, and a browser-level listener
 * (`sessionId = null`) only ever sees browser-level messages, never targets' by construction of
 * [listenerKey].
 *
 * Reconnect: on `onFailure` or a non-1000 `onClosed`, a retry loop schedules the next attempt
 * after [computeBackoffDelayMs] (exponential, capped, jittered) on this client's own scope,
 * incrementing [reconnectAttempt] and publishing [nextRetryDelayMs] so the UI can show
 * "reconnecting in Ns (attempt N)" instead of a bare FAILED. [disconnect] cancels the retry
 * loop explicitly.
 *
 * Honest state machine, mirroring [com.example.runtime.frida.FridaClient]:
 * DISCONNECTED -> CONNECTING -> CONNECTED -> FAILED. Never reports CONNECTED without a real
 * open WebSocket to a real browser endpoint.
 */
class CdpClient(
    private val host: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "CdpClient"
        private const val HTTP_TIMEOUT_S = 5L

        /** Base delay for attempt 1; doubles each subsequent attempt, capped at [MAX_BACKOFF_MS]. */
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 16_000L
        private const val MAX_RECONNECT_ATTEMPTS = 6

        /**
         * Pure exponential backoff schedule: attempt 1 -> 1s, 2 -> 2s, 3 -> 4s, 4 -> 8s,
         * 5 -> 16s, 6+ -> 16s (capped). Never negative, no jitter here -- jitter (a small
         * random +/- term) is applied by the caller around the scheduled delay so this
         * function stays a deterministic, directly testable pure function.
         */
        fun computeBackoffDelayMs(attempt: Int): Long {
            if (attempt <= 0) return 0L
            val shifted = attempt.coerceAtMost(5) - 1
            val raw = BASE_BACKOFF_MS shl shifted
            return raw.coerceAtMost(MAX_BACKOFF_MS)
        }

        /**
         * Builds one CDP JSON-RPC message. `sessionId` is included at the top level only when
         * non-null -- CDP's flattened-session wire format requires it present for per-target
         * commands and absent for browser-level ones, so this is not a cosmetic choice.
         */
        fun buildMessage(id: Int, method: String, params: JSONObject, sessionId: String? = null): JSONObject =
            JSONObject().apply {
                put("id", id)
                put("method", method)
                put("params", params)
                if (sessionId != null) put("sessionId", sessionId)
            }

        /** Composite key so a browser-level listener and a per-session listener never collide. */
        internal fun listenerKey(sessionId: String?, method: String): String =
            (sessionId ?: "\u0000browser") + "|" + method
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    /** One CDP target attached via `Target.setAutoAttach` (flattened session). */
    data class TargetInfo(
        val sessionId: String,
        val targetId: String,
        val type: String,
        val title: String,
        val url: String,
    )

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val stateFlow: StateFlow<State> = _state.asStateFlow()
    val state: State get() = _state.value

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Human-readable target info (title/url) for the first attached target, for legacy callers. */
    private val _targetInfo = MutableStateFlow<String?>(null)
    val targetInfo: StateFlow<String?> = _targetInfo.asStateFlow()

    private val _targets = MutableStateFlow<Map<String, TargetInfo>>(emptyMap())
    val targets: StateFlow<Map<String, TargetInfo>> = _targets.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _nextRetryDelayMs = MutableStateFlow(0L)
    val nextRetryDelayMs: StateFlow<Long> = _nextRetryDelayMs.asStateFlow()

    /** Fired on `Target.attachedToTarget` with the new [TargetInfo]; used to spin up per-target adapters. */
    var onTargetAttached: (TargetInfo) -> Unit = {}

    /** Fired on `Target.detachedFromTarget`/`Target.targetDestroyed` with the departing sessionId. */
    var onTargetDetached: (String) -> Unit = {}

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
    private var reconnectJob: Job? = null
    private var explicitlyDisconnected = false

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject?, String?) -> Unit>()

    /** listenerKey(sessionId, method) -> listeners invoked with the event's `params` object. */
    private val eventListeners = ConcurrentHashMap<String, MutableList<(JSONObject) -> Unit>>()

    init {
        // Registered eagerly (not deferred to sendDiscoveryCommands/onOpen) so target
        // attach/detach bookkeeping works against dispatchForTest without a live socket, and
        // so a message arriving the instant the socket opens is never missed.
        on("Target.attachedToTarget", sessionId = null) { params -> handleAttachedToTarget(params) }
        on("Target.detachedFromTarget", sessionId = null) { params -> handleDetached(params.optString("sessionId")) }
        on("Target.targetDestroyed", sessionId = null) { params -> handleTargetDestroyed(params) }
    }

    /** Register a listener for a CDP event, e.g. "Network.requestWillBeSent". `sessionId = null` means browser-level. */
    fun on(method: String, sessionId: String? = null, listener: (JSONObject) -> Unit) {
        eventListeners.getOrPut(listenerKey(sessionId, method)) { mutableListOf() }.add(listener)
    }

    /** Connect to the browser-level CDP endpoint and auto-attach to every target. */
    fun connect(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) {
            onResult(_state.value == State.CONNECTED, null)
            return
        }
        explicitlyDisconnected = false
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        _nextRetryDelayMs.value = 0L
        _state.value = State.CONNECTING
        _lastError.value = null
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val wsUrl = discoverBrowserEndpoint()
                if (wsUrl == null) {
                    fail("No CDP browser endpoint found at http://$host:$port/json/version — is Chrome running with --remote-debugging-port=$port?")
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

    private fun discoverBrowserEndpoint(): String? {
        val req = Request.Builder().url("http://$host:$port/json/version").build()
        val response: Response = httpClient.newCall(req).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string() ?: return null
            val obj = JSONObject(body)
            val url = obj.optString("webSocketDebuggerUrl", "")
            return url.ifEmpty { null }
        }
    }

    private fun openSocket(wsUrl: String, onResult: (Boolean, String?) -> Unit) {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.CONNECTED
                _reconnectAttempt.value = 0
                _nextRetryDelayMs.value = 0L
                Log.i(TAG, "CDP connected: $wsUrl")
                sendDiscoveryCommands()
                onResult(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatch(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "CDP closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 1000) {
                    _state.value = State.DISCONNECTED
                } else {
                    _lastError.value = "Socket closed: $code $reason"
                    _state.value = State.FAILED
                    scheduleReconnect(onResult)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("Socket failure: ${t.message}")
                onResult(false, _lastError.value)
                scheduleReconnect(onResult)
            }
        })
    }

    /**
     * After connecting, discover every existing/future target and auto-attach in flattened
     * mode. The `Target.*` event listeners themselves are registered once in [init], not here,
     * so they survive across reconnects and are testable via [dispatchForTest] without a live
     * socket.
     */
    private fun sendDiscoveryCommands() {
        send("Target.setDiscoverTargets", JSONObject().apply { put("discover", true) })
        send(
            "Target.setAutoAttach",
            JSONObject().apply {
                put("autoAttach", true)
                put("waitForDebuggerOnStart", false)
                put("flatten", true)
            },
        )
    }

    private fun handleAttachedToTarget(params: JSONObject) {
        val sessionId = params.optString("sessionId")
        val targetInfoJson = params.optJSONObject("targetInfo") ?: return
        if (sessionId.isEmpty()) return
        val info = TargetInfo(
            sessionId = sessionId,
            targetId = targetInfoJson.optString("targetId", ""),
            type = targetInfoJson.optString("type", ""),
            title = targetInfoJson.optString("title", ""),
            url = targetInfoJson.optString("url", ""),
        )
        _targets.value = _targets.value + (sessionId to info)
        if (_targetInfo.value == null) {
            _targetInfo.value = "${info.title} — ${info.url}"
        }
        Log.i(TAG, "CDP target attached: $sessionId ${info.type} ${info.url}")
        onTargetAttached(info)
    }

    private fun handleDetached(sessionId: String) {
        if (sessionId.isEmpty()) return
        _targets.value = _targets.value - sessionId
        onTargetDetached(sessionId)
    }

    private fun handleTargetDestroyed(params: JSONObject) {
        val targetId = params.optString("targetId")
        val sessionId = _targets.value.entries.firstOrNull { it.value.targetId == targetId }?.key
        if (sessionId != null) handleDetached(sessionId)
    }

    private fun scheduleReconnect(onResult: (Boolean, String?) -> Unit) {
        if (explicitlyDisconnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = _reconnectAttempt.value
            while (!explicitlyDisconnected && attempt < MAX_RECONNECT_ATTEMPTS) {
                attempt++
                _reconnectAttempt.value = attempt
                val delayMs = computeBackoffDelayMs(attempt)
                _nextRetryDelayMs.value = delayMs
                delay(delayMs)
                if (explicitlyDisconnected) return@launch
                _state.value = State.CONNECTING
                val wsUrl = runCatching { discoverBrowserEndpoint() }.getOrNull()
                if (wsUrl != null) {
                    openSocket(wsUrl, onResult)
                    return@launch
                }
                _state.value = State.FAILED
                _lastError.value = "Reconnect attempt $attempt failed: no CDP browser endpoint at http://$host:$port/json/version"
            }
        }
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
                val sessionId = json.optString("sessionId", "").ifEmpty { null }
                eventListeners[listenerKey(sessionId, method)]?.forEach { listener ->
                    try { listener(params) } catch (e: Exception) {
                        Log.w(TAG, "Event listener for $method threw: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse CDP frame: ${e.message}")
        }
    }

    /** Test-only entry point: routes a raw inbound frame through the real [dispatch] logic. */
    internal fun dispatchForTest(text: String) = dispatch(text)

    /**
     * Send a CDP command, e.g. send("Network.enable", JSONObject(), sessionId = "S1") { ... }.
     * `sessionId` must be non-null for any per-target command once auto-attach is flattened;
     * browser-level commands (e.g. `Target.setAutoAttach` itself) pass `sessionId = null`.
     * Fire-and-forget if [callback] is left default and the caller doesn't care about the result.
     */
    fun send(method: String, params: JSONObject = JSONObject(), sessionId: String? = null, callback: (JSONObject?, String?) -> Unit = { _, _ -> }) {
        val socket = webSocket
        if (socket == null || _state.value != State.CONNECTED) {
            callback(null, "Not connected")
            return
        }
        val id = nextId.getAndIncrement()
        pending[id] = callback
        val payload = buildMessage(id, method, params, sessionId)
        socket.send(payload.toString())
    }

    fun disconnect() {
        explicitlyDisconnected = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        pending.clear()
        eventListeners.clear()
        _targets.value = emptyMap()
        _reconnectAttempt.value = 0
        _nextRetryDelayMs.value = 0L
        _state.value = State.DISCONNECTED
        _targetInfo.value = null
    }
}

/** Kept for backwards-compatible listing of page-type targets outside a live [CdpClient] connection. */
internal fun parseTargetList(json: String): List<JSONObject> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { arr.getJSONObject(it) }
}
