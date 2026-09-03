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

    /**
     * S2 tasks 15/17: persistence failure table (dropped/failed writes, spill failures, migration
     * restore, quota pause). Re-exported from [com.example.data.persistence.PersistenceHealth] so
     * UI code has one entry point for capture state.
     */
    val persistence: StateFlow<com.example.data.persistence.PersistenceHealth.Snapshot>
        get() = com.example.data.persistence.PersistenceHealth.state

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

    /**
     * Pre-flight passed; awaiting VPN consent / user confirm. Renders the FAB yellow.
     *
     * [lifecycleOwner] has no default: see [CaptureLifecycleOwner]'s KDoc for why every call
     * site must state it explicitly rather than inherit a guess.
     */
    fun markArmed(
        tier: ActiveTier,
        port: Int,
        method: CaptureMethod = CaptureMethod.VPN_TUN,
        lifecycleOwner: CaptureLifecycleOwner,
    ) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ARMED,
                tier = tier,
                captureMethod = method,
                lifecycleOwner = lifecycleOwner,
                port = port,
                errorText = null,
            )
        }
    }

    /**
     * Capture genuinely began. Renders the FAB and logo dot green.
     *
     * [lifecycleOwner] has no default: see [CaptureLifecycleOwner]'s KDoc for why every call
     * site must state it explicitly rather than inherit a guess.
     */
    fun markActive(
        sessionId: Long,
        tier: ActiveTier,
        port: Int,
        method: CaptureMethod = CaptureMethod.VPN_TUN,
        lifecycleOwner: CaptureLifecycleOwner,
    ) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ACTIVE,
                tier = tier,
                captureMethod = method,
                lifecycleOwner = lifecycleOwner,
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
                lifecycleOwner = CaptureLifecycleOwner.NONE,
                activeConnections = 0,
                sessionId = null,
                startedAt = null,
                errorText = null,
            )
        }
    }

    fun addDecryptedTransactions(count: Int) {
        _state.update { it.copy(decryptedTransactionCount = it.decryptedTransactionCount + count) }
    }

    fun markError(message: String) {
        _state.update {
            it.copy(
                status = InterceptionStatus.ERROR,
                tier = ActiveTier.NONE,
                lifecycleOwner = CaptureLifecycleOwner.NONE,
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

    /**
     * [reason] (S1 task 13) is a short human-readable explanation for DEGRADED/ERROR, e.g.
     * "no packets for 15s" or the trust-diagnostics headline; cleared when the watcher
     * returns to HEALTHY/IDLE.
     */
    fun setWatcherStatus(status: WatcherStatus, reason: String? = null) {
        _state.update {
            it.copy(
                watcherStatus = status,
                watcherReason = if (status == WatcherStatus.DEGRADED || status == WatcherStatus.ERROR) reason else null,
            )
        }
    }

    /** Test/reset hook. Returns everything to genuine defaults. */
    fun resetAll() {
        _state.value = InterceptionState()
        _capabilities.value = CapabilityState()
    }

    // ---- Start/stop convergence invariants (audit fix: capture start/stop state desync) ----
    //
    // Before this, [markStopped] was only ever called by RequestrVpnService's own
    // stopCapture(reason) (its single internal exit point) -- so the VPN_TUN/ROOT_FRIDA tiers
    // converged to CONFIGURED_IDLE "for free" purely because that service happens to route every
    // stop through one place. MITM_PROXY/ROOT_IPTABLES/SHIZUKU_TUN/HTTP_PROXY have no such
    // service and PreflightController.stopCapture() never called markStopped() for them at all,
    // so after the first stop the FAB/header stayed stuck on ACTIVE/ARMED forever and capture
    // could not be restarted from the UI. Symmetrically, RootIptablesEngine/ShizukuTunEngine's
    // start() return `false` on failure with no markError() anywhere above them, and
    // MitmProxyEngine/HttpProxyEngine's `ServerSocket(port)` can throw with nothing to catch it
    // -- both leave the ARMED state [PreflightController.run] already set permanently stuck
    // instead of a clean ERROR.
    //
    // [runStop]/[runStart] are the single place both invariants are enforced, so a future tier
    // added to [com.example.interception.PreflightController]'s `when` blocks gets the
    // guarantee automatically instead of needing every branch to remember it. Deliberately pure
    // (no Context/engine dependency) so the invariant itself -- not the real engine I/O -- is
    // unit-testable.

    /**
     * Runs [block] to tear down whichever engine tier is active, then unconditionally converges
     * to [markStopped] -- even if [block] throws. Every stop path (current and future) should
     * route through this rather than calling [markStopped] itself.
     */
    fun runStop(block: () -> Unit) {
        try {
            block()
        } finally {
            markStopped()
        }
    }

    /**
     * Runs [block], which must return `true` iff the engine is now genuinely running (it may
     * also throw on a hard failure, e.g. a bind-port [java.io.IOException]). Guarantees a clean
     * [markError] transition when [block] returns `false` or throws, so a failed start can never
     * leave the state stuck at whatever [markArmed] already set. A tier whose own engine already
     * calls [markActive] on success (every current tier) or already guarantees its own
     * [markError] on failure via an owned async path (VPN_TUN/ROOT_FRIDA, via
     * RequestrVpnService's own `fail()`) is unaffected either way -- this only ever fires for a
     * tier that would otherwise have no failure path at all.
     */
    fun runStart(methodLabel: String, block: () -> Boolean): Boolean {
        val result = runCatching(block)
        val started = result.getOrDefault(false)
        if (!started) {
            val reason = result.exceptionOrNull()
                ?.let { "Capture failed to start: ${it.message ?: it::class.java.simpleName}" }
                ?: "Capture engine ($methodLabel) reported failure to start."
            markError(reason)
        }
        return started
    }
}
