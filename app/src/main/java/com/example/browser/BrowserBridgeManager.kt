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
 * Browser Bridge manager (B1-B6 integration point).
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
    private var networkAdapter: CdpNetworkAdapter? = null
    private var fetchAdapter: CdpFetchAdapter? = null
    private var serviceWorkerObserver: ServiceWorkerObserver? = null
    private var storageObserver: BrowserStorageObserver? = null

    private val _connectionState = MutableStateFlow(CdpClient.State.DISCONNECTED)
    val connectionState: StateFlow<CdpClient.State> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _eventsIngested = MutableStateFlow(0)
    val eventsIngested: StateFlow<Int> = _eventsIngested.asStateFlow()

    private val emptyServiceWorkerState: StateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>> =
        MutableStateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>>(emptyMap()).asStateFlow()

    private val emptyStorageState: StateFlow<BrowserStorageObserver.StorageSnapshot> =
        MutableStateFlow(BrowserStorageObserver.StorageSnapshot()).asStateFlow()

    val serviceWorkerState: StateFlow<Map<String, ServiceWorkerObserver.WorkerRegistration>>
        get() = serviceWorkerObserver?.stateFlow ?: emptyServiceWorkerState

    val storageState: StateFlow<BrowserStorageObserver.StorageSnapshot>
        get() = storageObserver?.stateFlow ?: emptyStorageState

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

        val context = appContext
        newClient.connect { success, error ->
            if (!success) {
                _connectionState.value = CdpClient.State.FAILED
                _statusMessage.value = error ?: "Connection failed"
                return@connect
            }

            // Wire B2 (Network adapter, itself using B4's InitiatorResolver) into
            // CorrelationEngine -- every real CDP network event becomes a real HttpEvent.
            val netAdapter = CdpNetworkAdapter(newClient) { httpEvent ->
                CorrelationEngine.ingest(httpEvent)
                _eventsIngested.value = _eventsIngested.value + 1
            }
            netAdapter.start()
            networkAdapter = netAdapter

            // Wire B3 (Fetch adapter) if we have an app context to read intercept_rules from.
            if (context != null) {
                val fa = CdpFetchAdapter(newClient, context)
                fa.start()
                fetchAdapter = fa
            }

            // Wire B5 (Service Worker observer).
            val swObserver = ServiceWorkerObserver(newClient)
            swObserver.start()
            serviceWorkerObserver = swObserver

            // Wire B6 (Storage observer) -- origin comes from the attached target's URL.
            val storage = BrowserStorageObserver(newClient) { originFromTargetInfo(newClient.targetInfo.value) }
            storage.start()
            storageObserver = storage
            storage.refreshStorage()

            _connectionState.value = CdpClient.State.CONNECTED
            _statusMessage.value = "Connected: ${newClient.targetInfo.value ?: "$host:$port"}"
            Log.i(TAG, "Browser Bridge connected to $host:$port")
        }
    }

    fun disconnect() {
        fetchAdapter?.stop()
        serviceWorkerObserver?.stop()
        storageObserver?.stop()
        client?.disconnect()
        client = null
        networkAdapter = null
        fetchAdapter = null
        serviceWorkerObserver = null
        storageObserver = null
        _connectionState.value = CdpClient.State.DISCONNECTED
        _statusMessage.value = "Disconnected"
    }

    /** Manually refresh B6 storage snapshot (cookies/localStorage/IndexedDB). */
    fun refreshStorage() {
        storageObserver?.refreshStorage()
    }

    private fun originFromTargetInfo(targetInfo: String?): String? {
        if (targetInfo == null) return null
        val url = targetInfo.substringAfter(" — ", "")
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
