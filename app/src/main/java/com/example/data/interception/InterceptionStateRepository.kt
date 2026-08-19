package com.example.data.interception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the one authoritative [InterceptionState] and [CapabilityState] for the whole app.
 *
 * Contract for consumers:
 *  - UI observes [state] / [capabilities] and renders exactly what it reports. It must never
 *    substitute an optimistic default.
 *  - Only the capture engine and the probe layer call the mutators below.
 *
 * Contract for producers:
 *  - Counters must reflect real captured traffic. Do not simulate progress.
 *
 * This replaces the previous `TrafficInterceptorRepository` behaviour, which defaulted
 * interception to ON with a hardcoded 1420-packet count and always-granted capability flags.
 */
object InterceptionStateRepository {

    private val _state = MutableStateFlow(InterceptionState())

    /** Bound by the Intercept FAB colour and the wordmark dot — single source of truth. */
    val state: StateFlow<InterceptionState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(CapabilityState())

    /** Bound by the capability chips. All fields UNKNOWN until a real probe runs. */
    val capabilities: StateFlow<CapabilityState> = _capabilities.asStateFlow()

    // ---- Lifecycle transitions (engine-owned) ----

    /** Marks the install as configured-but-stopped, e.g. after CA install completes. */
    fun markConfiguredIdle(port: Int) {
        _state.update {
            it.copy(
                status = InterceptionStatus.CONFIGURED_IDLE,
                tier = ActiveTier.NONE,
                port = port,
                activeConnections = 0,
                errorText = null,
            )
        }
    }

    /** Pre-flight passed; awaiting VPN consent / user confirm. Renders the FAB yellow. */
    fun markArmed(tier: ActiveTier, port: Int, method: CaptureMethod = CaptureMethod.VPN_TUN) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ARMED,
                tier = tier,
                captureMethod = method,
                port = port,
                errorText = null,
            )
        }
    }

    /** Capture genuinely began. Renders the FAB and logo dot green. */
    fun markActive(sessionId: Long, tier: ActiveTier, port: Int, method: CaptureMethod = CaptureMethod.VPN_TUN) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ACTIVE,
                tier = tier,
                captureMethod = method,
                port = port,
                sessionId = sessionId,
                startedAt = System.currentTimeMillis(),
                packetCount = 0,
                bytesTransferred = 0L,
                activeConnections = 0,
                errorText = null,
                watcherStatus = WatcherStatus.IDLE,
            )
        }
    }

    /** Capture stopped cleanly. Counters from the finished session are retained for display. */
    fun markStopped() {
        _state.update {
            it.copy(
                status = InterceptionStatus.CONFIGURED_IDLE,
                tier = ActiveTier.NONE,
                activeConnections = 0,
                sessionId = null,
                startedAt = null,
                errorText = null,
            )
        }
    }

    fun markError(message: String) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ERROR,
                tier = ActiveTier.NONE,
                activeConnections = 0,
                errorText = message,
            )
        }
    }

    // ---- Live counters (engine-owned; must be real) ----

    fun updateCounters(
        packetCount: Int? = null,
        bytesTransferred: Long? = null,
        activeConnections: Int? = null,
    ) {
        _state.update {
            it.copy(
                packetCount = packetCount ?: it.packetCount,
                bytesTransferred = bytesTransferred ?: it.bytesTransferred,
                activeConnections = activeConnections ?: it.activeConnections,
            )
        }
    }

    /** Incremental variant for the packet pump. */
    fun addPackets(packets: Int, bytes: Long) {
        _state.update {
            it.copy(
                packetCount = it.packetCount + packets,
                bytesTransferred = it.bytesTransferred + bytes,
            )
        }
    }

    // ---- Capability probes (probe-layer owned) ----

    fun setProbing(isProbing: Boolean) {
        _capabilities.update { it.copy(isProbing = isProbing) }
    }

    fun updateCapabilities(
        root: RootState? = null,
        shizuku: ShizukuState? = null,
        ca: CaState? = null,
        frida: FridaState? = null,
    ) {
        _capabilities.update {
            it.copy(
                root = root ?: it.root,
                shizuku = shizuku ?: it.shizuku,
                ca = ca ?: it.ca,
                frida = frida ?: it.frida,
                lastProbedAt = System.currentTimeMillis(),
            )
        }
    }

    // ---- Watcher (engine-owned) ----

    fun setWatcherStatus(status: WatcherStatus) {
        _state.update { it.copy(watcherStatus = status) }
    }

    /** Test/reset hook. Returns everything to genuine defaults. */
    fun resetAll() {
        _state.value = InterceptionState()
        _capabilities.value = CapabilityState()
    }
}
