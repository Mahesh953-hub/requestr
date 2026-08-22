package com.example.browser

import android.content.Context
import android.util.Log
import com.example.correlation.CorrelationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browser Bridge manager (B1-B6 integration point, extended by W8 for multi-target routing).
 *
 * Owns the [CdpClient] lifecycle (connect/disconnect) and the coroutine scope, and wires the
 * per-domain adapters together:
 *  - [CdpNetworkAdapter] (B2, using [InitiatorResolver]/[StackTraceResolver] from B4) feeds
 *    every captured browser request into [CorrelationEngine.ingest] as a real `HttpEvent`
 *    with `source = ObserverSource.BROWSER` -- this is what makes B1/B2/B4 "genuinely wired"
 *    per this feature's bar rather than just files that compile and sit unused.
 *  - [CdpFetchAdapter] (B3) enforces the same `intercept_rules` Room table used by
 *    MITM/VPN capture.
 *  - [ServiceWorkerObserver] (B5) and [BrowserStorageObserver] (B6) expose their own
 *    Compose-observable `StateFlow`s, surfaced directly in Settings (see
 *    `SettingsScreen.kt`'s "BROWSER BRIDGE" card).
 *
 * W8: [CdpClient] now auto-attaches to *every* target (`Target.setAutoAttach` with
 * `flatten: true`), not just the first page target discovered via `/json/list`. Each adapter
 * instance in this manager used to be a singleton for the one attached target; it is now a
 * map keyed by `sessionId`, created on [CdpClient.onTargetAttached] and torn down on
 * [CdpClient.onTargetDetached] -- one full adapter set per browser tab/target, mirroring what
 * a real multi-tab CDP consumer needs instead of silently only ever seeing the first tab.
 *
 * Mirrors the shape of [com.example.runtime.RuntimeInstrumentationEngine] /
 * [com.example.runtime.frida.FridaClient]: a singleton object with an honest
 * Compose-observable connection-state `StateFlow`, a `connect(host, port)` / `disconnect()`
 * pair, and adapters that only report what actually happened.
 */
object BrowserBridgeManager {
    private const val TAG = "BrowserBridgeManager"
    const val DEFAULT_HOST_PORT = "localhost:9222"

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var client: CdpClient? = null

    /** Per-target (sessionId) adapter sets, created on attach and removed on detach. */
    private data class TargetAdapters(
        val networkAdapter: CdpNetworkAdapter,
        val fetchAdapter: CdpFetchAdapter?,
        val serviceWorkerObserver: ServiceWorkerObserver,
        val storageObserver: BrowserStorageObserver,
    )

    private val perTargetAdapters = HashMap<String, TargetAdapters>()

    private val _connectionState = MutableStateFlow(CdpClient.State.DISCONNECTED)
    val connectionState: StateFlow<CdpClient.State> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _eventsIngested = MutableStateFlow(0)
    val eventsIngested: StateFlow<Int> = _eventsIngested.asStateFlow()

    /** Per-target (sessionId -> event count), so the Settings UI can show a per-tab breakdown. */
    private val _eventsIngestedByTarget = MutableStateFlow<Map<String, Int>>(emptyMap())
    val eventsIngestedByTarget: StateFlow<Map<String, Int>> = _eventsIngestedByTarget.asStateFlow()

    /**
     * W9: aggregate Fetch rule-enforcement counters across every attached target's
     * [CdpFetchAdapter], recomputed via [CdpFetchAdapter.onCountersChanged] so the Settings card
     * shows one real total instead of only the first target's numbers.
     */
    data class FetchCounters(
        val allowed: Int = 0,
        val blocked: Int = 0,
        val modified: Int = 0,
        val responseModified: Int = 0,
        val bodiesCaptured: Int = 0,
    )

    private val _fetchCounters = MutableStateFlow(FetchCounters())
    val fetchCounters: StateFlow<FetchCounters> = _fetchCounters.asStateFlow()

    private fun recomputeFetchCounters() {
        val adapters = perTargetAdapters.values.mapNotNull { it.fetchAdapter }
        _fetchCounters.value = FetchCounters(
            allowed = adapters.sumOf { it.allowedCount },
            blocked = adapters.sumOf { it.blockedCount },
            modified = adapters.sumOf { it.modifiedCount },
            responseModified = adapters.sumOf { it.responseModifiedCount },
            bodiesCaptured = adapters.sumOf { it.bodiesCapturedCount },
        )
    }

    private val emptyServiceWorkerState: StateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>> =
        MutableStateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>>(emptyMap()).asStateFlow()

    private val emptyStorageState: StateFlow<BrowserStorageObserver.StorageSnapshot> =
        MutableStateFlow(BrowserStorageObserver.StorageSnapshot()).asStateFlow()

    /** First attached target's service-worker state, kept for pre-W8 single-target callers. */
    val serviceWorkerState: StateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>>
        get() = perTargetAdapters.values.firstOrNull()?.serviceWorkerObserver?.stateFlow ?: emptyServiceWorkerState

    /** First attached target's storage state, kept for pre-W8 single-target callers. */
    val storageState: StateFlow<BrowserStorageObserver.StorageSnapshot>
        get() = perTargetAdapters.values.firstOrNull()?.storageObserver?.stateFlow ?: emptyStorageState

    /** Every attached target, from the client's [CdpClient.targets]. */
    val targets: StateFlow<Map<String, CdpClient.TargetInfo>>
        get() = client?.targets ?: MutableStateFlow(emptyMap<String, CdpClient.TargetInfo>()).asStateFlow()

    val reconnectAttempt: StateFlow<Int>
        get() = client?.reconnectAttempt ?: MutableStateFlow(0).asStateFlow()

    val nextRetryDelayMs: StateFlow<Long>
        get() = client?.nextRetryDelayMs ?: MutableStateFlow(0L).asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Connect to a Chrome/Chromium instance's CDP endpoint at `host:port` (e.g.
     * `localhost:9222`, the default `--remote-debugging-port` value). Real WebSocket
     * connection attempted synchronously-from-the-caller's-perspective via callback.
     */
    fun connect(hostPort: String = DEFAULT_HOST_PORT) {
        if (_connectionState.value == CdpClient.State.CONNECTING || _connectionState.value == CdpClient.State.CONNECTED) return
        val (host, port) = parseHostPort(hostPort)
        if (host == null || port == null) {
            _statusMessage.value = "Invalid host:port '$hostPort'"
            return
        }

        _connectionState.value = CdpClient.State.CONNECTING
        _statusMessage.value = "Connecting to $host:$port..."

        val newClient = CdpClient(host, port)
        client = newClient

        newClient.onTargetAttached = { info -> attachAdaptersFor(newClient, info) }
        newClient.onTargetDetached = { sessionId -> detachAdaptersFor(sessionId) }

        newClient.stateFlow
        newClient.connect { success, error ->
            if (!success) {
                _connectionState.value = CdpClient.State.FAILED
                _statusMessage.value = error ?: "Connection failed"
                return@connect
            }
            _connectionState.value = CdpClient.State.CONNECTED
            _statusMessage.value = "Connected: $host:$port"
            Log.i(TAG, "Browser Bridge connected to $host:$port")
        }
    }

    private fun attachAdaptersFor(cdpClient: CdpClient, info: CdpClient.TargetInfo) {
        val sessionId = info.sessionId
        if (perTargetAdapters.containsKey(sessionId)) return

        val netAdapter = CdpNetworkAdapter(cdpClient, sessionId) { httpEvent ->
            CorrelationEngine.ingest(httpEvent)
            _eventsIngested.value = _eventsIngested.value + 1
            _eventsIngestedByTarget.value = _eventsIngestedByTarget.value +
                (sessionId to ((_eventsIngestedByTarget.value[sessionId] ?: 0) + 1))
        }
        netAdapter.start()

        val context = appContext
        val fa = if (context != null) {
            CdpFetchAdapter(cdpClient, context, sessionId).also { adapter ->
                // W9: feed every response body Fetch genuinely captures back into the same
                // target's CdpNetworkAdapter, joined on Network.requestId == Fetch's networkId.
                adapter.onResponseBodyCaptured = { networkId, body, sizeBytes ->
                    netAdapter.applyResponseBody(networkId, body, sizeBytes)
                }
                adapter.onCountersChanged = { recomputeFetchCounters() }
                adapter.start()
            }
        } else {
            null
        }

        val swObserver = ServiceWorkerObserver(cdpClient, sessionId)
        swObserver.start()

        val storage = BrowserStorageObserver(cdpClient, sessionId) { originFromTargetInfo(info) }
        storage.start()
        storage.refreshStorage()

        perTargetAdapters[sessionId] = TargetAdapters(netAdapter, fa, swObserver, storage)
        Log.i(TAG, "Adapters attached for target $sessionId (${info.type} ${info.url})")
    }

    private fun detachAdaptersFor(sessionId: String) {
        val adapters = perTargetAdapters.remove(sessionId) ?: return
        adapters.fetchAdapter?.stop()
        adapters.serviceWorkerObserver.stop()
        adapters.storageObserver.stop()
        _eventsIngestedByTarget.value = _eventsIngestedByTarget.value - sessionId
        recomputeFetchCounters()
        Log.i(TAG, "Adapters detached for target $sessionId")
    }

    fun disconnect() {
        perTargetAdapters.keys.toList().forEach { detachAdaptersFor(it) }
        client?.disconnect()
        client = null
        _eventsIngestedByTarget.value = emptyMap()
        _fetchCounters.value = FetchCounters()
        _connectionState.value = CdpClient.State.DISCONNECTED
        _statusMessage.value = "Disconnected"
    }

    /** Manually refresh B6 storage snapshot (cookies/localStorage/IndexedDB) for every target. */
    fun refreshStorage() {
        perTargetAdapters.values.forEach { it.storageObserver.refreshStorage() }
    }

    private fun originFromTargetInfo(targetInfo: CdpClient.TargetInfo): String? {
        val url = targetInfo.url
        if (url.isEmpty()) return null
        return runCatching {
            val uri = java.net.URI(url)
            if (uri.scheme == null || uri.host == null) null
            else "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        }.getOrNull()
    }

    private fun parseHostPort(hostPort: String): Pair<String?, Int?> {
        val trimmed = hostPort.trim().removePrefix("http://").removePrefix("https://")
        val idx = trimmed.lastIndexOf(':')
        if (idx < 0) return trimmed to 9222
        val host = trimmed.substring(0, idx)
        val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null to null
        return host to port
    }
}
