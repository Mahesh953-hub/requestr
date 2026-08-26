package com.example.browser

import android.content.Context
import android.util.Log
import com.example.correlation.CorrelationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        /**
         * v6.1 Domain B: jobs mirroring this target's observer `StateFlow`s into the manager-level
         * per-target maps below, so the Settings card sees *every* attached tab's storage and
         * service workers rather than only the first-attached one. Cancelled on detach.
         */
        val mirrorJobs: List<Job> = emptyList(),
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
        /** Request-stage MODIFY_REQUEST enforcement only (v6.1: redirects moved to [redirected]). */
        val modified: Int = 0,
        val responseModified: Int = 0,
        val bodiesCaptured: Int = 0,
        /** Request-stage REDIRECT enforcement, previously folded into [modified]. */
        val redirected: Int = 0,
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
            redirected = adapters.sumOf { it.redirectedCount },
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

    /**
     * v6.1 Domain B: **stable** mirrors of the current [CdpClient]'s per-connection flows.
     *
     * These used to be `get()`-style delegates that returned whichever flow the *current* client
     * instance happened to own -- which meant a Compose `collectAsState()` kept observing a dead
     * client's flow across a reconnect (the client object is replaced on every [connect]) and
     * silently stopped updating. They are now manager-owned `StateFlow`s that survive client
     * replacement, mirrored by [mirrorClientState] jobs, so the Settings card observes one stable
     * identity for the whole app lifetime.
     */
    private val _targets = MutableStateFlow<Map<String, CdpClient.TargetInfo>>(emptyMap())
    val targets: StateFlow<Map<String, CdpClient.TargetInfo>> = _targets.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _nextRetryDelayMs = MutableStateFlow(0L)
    val nextRetryDelayMs: StateFlow<Long> = _nextRetryDelayMs.asStateFlow()

    /** Mirror of [CdpClient.lastError] -- the real socket/discovery failure text, or null. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Mirror of [CdpClient.retryActive]: true only while a retry loop is genuinely pending. This is
     * the authoritative input for "is this a reconnect or a terminal failure" -- a non-zero
     * [reconnectAttempt] alone cannot answer that, because the count is retained after exhaustion so
     * the UI can report how many attempts were made.
     */
    private val _retryActive = MutableStateFlow(false)
    val retryActive: StateFlow<Boolean> = _retryActive.asStateFlow()

    /**
     * Last-known targets and their per-target event counts, retained across a dropped connection so
     * the reconnecting state can honestly label them "stale · refreshes on reconnect" instead of
     * either hiding them or pretending they are still attached. Cleared only on an explicit
     * [connect] or [disconnect].
     */
    private val _lastKnownTargets = MutableStateFlow<Map<String, CdpClient.TargetInfo>>(emptyMap())
    val lastKnownTargets: StateFlow<Map<String, CdpClient.TargetInfo>> = _lastKnownTargets.asStateFlow()

    private val _lastKnownEventsByTarget = MutableStateFlow<Map<String, Int>>(emptyMap())
    val lastKnownEventsByTarget: StateFlow<Map<String, Int>> = _lastKnownEventsByTarget.asStateFlow()

    /** `System.currentTimeMillis()` of the last real CONNECTED transition; null when never connected. */
    private val _connectedSinceMs = MutableStateFlow<Long?>(null)
    val connectedSinceMs: StateFlow<Long?> = _connectedSinceMs.asStateFlow()

    /** `System.currentTimeMillis()` of the last real connection drop; null when not currently down. */
    private val _connectionLostAtMs = MutableStateFlow<Long?>(null)
    val connectionLostAtMs: StateFlow<Long?> = _connectionLostAtMs.asStateFlow()

    /** The `host:port` of the endpoint the current/last [connect] call targeted. */
    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    /** sessionId -> that target's own storage snapshot, for every attached target (not just the first). */
    private val _storageByTarget = MutableStateFlow<Map<String, BrowserStorageObserver.StorageSnapshot>>(emptyMap())
    val storageByTarget: StateFlow<Map<String, BrowserStorageObserver.StorageSnapshot>> = _storageByTarget.asStateFlow()

    /** sessionId -> that target's own service-worker registrations, for every attached target. */
    private val _serviceWorkersByTarget =
        MutableStateFlow<Map<String, Map<String, ServiceWorkerObserver.WorkerRegistration>>>(emptyMap())
    val serviceWorkersByTarget: StateFlow<Map<String, Map<String, ServiceWorkerObserver.WorkerRegistration>>> =
        _serviceWorkersByTarget.asStateFlow()

    private val clientMirrorJobs = mutableListOf<Job>()

    /**
     * Session ids with live adapters, maintained on attach/detach. The per-target observer mirror
     * jobs below consult this before publishing, so a snapshot emitted by an observer that is being
     * torn down cannot re-add a detached session to [storageByTarget]/[serviceWorkersByTarget] after
     * [detachAdaptersFor] removed it. Concurrent because attach/detach run on CDP callback threads
     * while the mirrors run on this object's own scope.
     */
    private val activeSessionIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Mirrors [newClient]'s own flows into this manager's stable flows for as long as it lives. */
    private fun mirrorClientState(newClient: CdpClient) {
        clientMirrorJobs.forEach { it.cancel() }
        clientMirrorJobs.clear()
        clientMirrorJobs += scope.launch {
            newClient.targets.collect { live ->
                _targets.value = live
                if (live.isNotEmpty()) _lastKnownTargets.value = live
            }
        }
        clientMirrorJobs += scope.launch { newClient.reconnectAttempt.collect { _reconnectAttempt.value = it } }
        clientMirrorJobs += scope.launch { newClient.nextRetryDelayMs.collect { _nextRetryDelayMs.value = it } }
        clientMirrorJobs += scope.launch { newClient.lastError.collect { _lastError.value = it } }
        clientMirrorJobs += scope.launch { newClient.retryActive.collect { _retryActive.value = it } }
        clientMirrorJobs += scope.launch {
            newClient.stateFlow.collect { state ->
                _connectionState.value = state
                // The status line is part of the connection state, not just of the connect() call:
                // before this, a socket that dropped after a successful connect left the card showing
                // a stale "Connected: host:port" while the phase pill already said RECONNECTING.
                // CdpClient publishes lastError before the state transition, so reading it here is
                // ordered, not a race.
                when (state) {
                    CdpClient.State.CONNECTED -> {
                        if (_connectedSinceMs.value == null) _connectedSinceMs.value = System.currentTimeMillis()
                        _connectionLostAtMs.value = null
                        _statusMessage.value = "Connected: ${_endpoint.value ?: "CDP endpoint"}"
                    }
                    CdpClient.State.FAILED -> {
                        if (_connectionLostAtMs.value == null) _connectionLostAtMs.value = System.currentTimeMillis()
                        _connectedSinceMs.value = null
                        _statusMessage.value = newClient.lastError.value ?: "Connection lost"
                    }
                    CdpClient.State.DISCONNECTED -> {
                        _connectedSinceMs.value = null
                        _statusMessage.value = "Disconnected"
                    }
                    CdpClient.State.CONNECTING -> {
                        if (newClient.retryActive.value) {
                            _statusMessage.value = "Reconnecting to ${_endpoint.value ?: "CDP endpoint"}…"
                        }
                    }
                }
            }
        }
    }

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

        // A previous client can still be alive here -- a FAILED one whose retry loop is mid-flight is
        // the common case, since connect() only short-circuits on CONNECTING/CONNECTED. Replacing the
        // reference without disconnecting leaked that client: its retry loop kept reopening sockets and
        // its adapters kept enforcing rules against a connection nothing observed any more.
        teardownCurrentClient()

        _connectionState.value = CdpClient.State.CONNECTING
        _statusMessage.value = "Connecting to $host:$port..."
        _endpoint.value = "$host:$port"
        _lastKnownTargets.value = emptyMap()
        _lastKnownEventsByTarget.value = emptyMap()
        _connectionLostAtMs.value = null

        val newClient = CdpClient(host, port)
        client = newClient

        newClient.onTargetAttached = { info -> attachAdaptersFor(newClient, info) }
        newClient.onTargetDetached = { sessionId -> detachAdaptersFor(sessionId) }

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
        // Mirrored after connect() so the first observed client state is the CONNECTING that
        // connect() sets synchronously, not the client's pre-connect DISCONNECTED default.
        mirrorClientState(newClient)
    }

    private fun attachAdaptersFor(cdpClient: CdpClient, info: CdpClient.TargetInfo) {
        val sessionId = info.sessionId
        if (perTargetAdapters.containsKey(sessionId)) return

        val netAdapter = CdpNetworkAdapter(cdpClient, sessionId) { httpEvent ->
            CorrelationEngine.ingest(httpEvent)
            // update {} rather than value = value + ...: several targets ingest concurrently on
            // separate CDP dispatch threads, and read-modify-write on the plain value dropped counts.
            _eventsIngested.update { it + 1 }
            _eventsIngestedByTarget.update { current ->
                current + (sessionId to ((current[sessionId] ?: 0) + 1))
            }
            _lastKnownEventsByTarget.update { current ->
                current + (sessionId to ((current[sessionId] ?: 0) + 1))
            }
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

        // v6.1 Domain B: mirror this target's own observer state into the manager-level per-target
        // maps, so the Settings card aggregates every attached tab instead of only the first one.
        // Registered as active *before* the mirrors start so the initial snapshot is not dropped, and
        // every publish re-checks membership so a snapshot racing with detach cannot resurrect a
        // session detachAdaptersFor already removed.
        activeSessionIds.add(sessionId)
        val mirrorJobs = listOf(
            scope.launch {
                storage.stateFlow.collect { snapshot ->
                    if (sessionId !in activeSessionIds) return@collect
                    _storageByTarget.update { it + (sessionId to snapshot) }
                }
            },
            scope.launch {
                swObserver.stateFlow.collect { registrations ->
                    if (sessionId !in activeSessionIds) return@collect
                    _serviceWorkersByTarget.update { it + (sessionId to registrations) }
                }
            },
        )

        perTargetAdapters[sessionId] = TargetAdapters(netAdapter, fa, swObserver, storage, mirrorJobs)
        Log.i(TAG, "Adapters attached for target $sessionId (${info.type} ${info.url})")
    }

    private fun detachAdaptersFor(sessionId: String) {
        // Deregistered first so any in-flight observer emission is rejected by the mirror guard rather
        // than landing after the removals below.
        activeSessionIds.remove(sessionId)
        val adapters = perTargetAdapters.remove(sessionId) ?: return
        adapters.mirrorJobs.forEach { it.cancel() }
        adapters.fetchAdapter?.stop()
        adapters.serviceWorkerObserver.stop()
        adapters.storageObserver.stop()
        _eventsIngestedByTarget.update { it - sessionId }
        _storageByTarget.update { it - sessionId }
        _serviceWorkersByTarget.update { it - sessionId }
        recomputeFetchCounters()
        Log.i(TAG, "Adapters detached for target $sessionId")
    }

    fun disconnect() {
        teardownCurrentClient()
        _connectionState.value = CdpClient.State.DISCONNECTED
        _statusMessage.value = "Disconnected"
        // An explicit disconnect is not a dropped connection: nothing is stale, nothing is retried.
        _lastKnownTargets.value = emptyMap()
        _lastKnownEventsByTarget.value = emptyMap()
        _connectedSinceMs.value = null
        _connectionLostAtMs.value = null
    }

    /**
     * Detaches every target, stops the observer mirrors, and disconnects + releases the current
     * [CdpClient] (which cancels its connect/retry jobs and closes the socket). Shared by [disconnect]
     * and [connect] so a client is never abandoned while still running.
     */
    private fun teardownCurrentClient() {
        perTargetAdapters.keys.toList().forEach { detachAdaptersFor(it) }
        activeSessionIds.clear()
        clientMirrorJobs.forEach { it.cancel() }
        clientMirrorJobs.clear()
        client?.disconnect()
        client = null
        _eventsIngestedByTarget.value = emptyMap()
        _storageByTarget.value = emptyMap()
        _serviceWorkersByTarget.value = emptyMap()
        _fetchCounters.value = FetchCounters()
        _targets.value = emptyMap()
        _reconnectAttempt.value = 0
        _nextRetryDelayMs.value = 0L
        _retryActive.value = false
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
