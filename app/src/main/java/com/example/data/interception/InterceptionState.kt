package com.example.data.interception

/**
 * Lifecycle of interception. Defaults to [NEVER_CONFIGURED] on a fresh install so the Home card
 * shows a quiet "Set up Interception" CTA rather than a fake green ACTIVE banner
 * (APP_v2.md Bug 1.5).
 */
enum class InterceptionStatus {
    /** Never set up on this install. */
    NEVER_CONFIGURED,

    /** Set up before, currently stopped. */
    CONFIGURED_IDLE,

    /** Pre-flight passed, awaiting user confirmation / VPN consent. Drives the YELLOW FAB. */
    ARMED,

    /** Genuinely capturing. Drives the GREEN FAB and the green logo dot. */
    ACTIVE,

    /** Start failed or the engine died. */
    ERROR,
}

/** Which engine is actually running. Surfaced to the user so the tier is never a mystery. */
enum class ActiveTier { NONE, VPN, PROXY, SHIZUKU, ROOT }

/**
 * Concrete capture transport. Each value corresponds to a specific engine implementation.
 * The tier (VPN/SHIZUKU/ROOT) determines availability; this enum determines the actual
 * method used within that tier.
 */
enum class CaptureMethod(val displayName: String) {
    VPN_TUN("VPN Tun"),
    HTTP_PROXY("HTTP Proxy"),
    MITM_PROXY("MITM Proxy"),
    ROOT_IPTABLES("Root iptables"),
    ROOT_FRIDA("Root Frida"),
    SHIZUKU_TUN("Shizuku Tun"),
}

/**
 * The one true [ActiveTier] for a [CaptureMethod] whose engine is *only* ever driven through
 * [com.example.interception.PreflightController.startCapture] -- every engine below must derive
 * its `markActive(tier = ...)` argument from this function instead of restating a separately
 * hardcoded literal, so a copy-paste-from-a-sibling mistake can no longer compile and silently
 * diverge from what [com.example.interception.PreflightController.run] itself resolved.
 *
 * Audit fix: [com.example.interception.HttpProxyEngine] used to hardcode `ActiveTier.VPN` in its
 * own `markActive()` call even though [com.example.interception.PreflightController.run] resolves
 * `ActiveTier.PROXY` for `HTTP_PROXY` -- the identical "one fact, two writers, no guarantee they
 * agree" defect [CaptureLifecycleOwner] exists to prevent for lifecycle ownership, just for
 * [ActiveTier] instead, and just as capable of resurrecting that same class of stop-routing bug:
 * a stale published tier is exactly the input [com.example.interception.CaptureController] and
 * [com.example.interception.PreflightController.stopCapture] read.
 *
 * [CaptureMethod.ROOT_FRIDA] is deliberately excluded -- it has no single true tier. Driven
 * through [com.example.interception.PreflightController.startCapture] it is a VPN-tier fallback
 * ([com.example.interception.RequestrVpnService] starts instead of any Frida-specific engine, as
 * Frida requires an external frida-server this app cannot itself guarantee), but
 * [com.example.interception.FridaCaptureEngine]'s own standalone lifecycle -- never wired into
 * [com.example.interception.PreflightController] at all -- genuinely runs at [ActiveTier.ROOT].
 * Both call sites state their own true value explicitly for that reason; calling this function
 * for [CaptureMethod.ROOT_FRIDA] throws rather than silently picking one.
 */
fun CaptureMethod.engineTier(): ActiveTier = when (this) {
    CaptureMethod.VPN_TUN -> ActiveTier.VPN
    CaptureMethod.MITM_PROXY -> ActiveTier.PROXY
    CaptureMethod.HTTP_PROXY -> ActiveTier.PROXY
    CaptureMethod.ROOT_IPTABLES -> ActiveTier.ROOT
    CaptureMethod.SHIZUKU_TUN -> ActiveTier.SHIZUKU
    CaptureMethod.ROOT_FRIDA -> error(
        "CaptureMethod.ROOT_FRIDA has no single true ActiveTier -- state it explicitly at the " +
            "call site instead (see engineTier()'s KDoc)",
    )
}

/**
 * The one true [CaptureLifecycleOwner] for a [CaptureMethod] whose engine is *only* ever driven
 * through [com.example.interception.PreflightController.startCapture]. Same rationale, and the
 * same [CaptureMethod.ROOT_FRIDA] exception, as [engineTier].
 */
fun CaptureMethod.engineLifecycleOwner(): CaptureLifecycleOwner = when (this) {
    CaptureMethod.VPN_TUN -> CaptureLifecycleOwner.VPN_SERVICE
    CaptureMethod.MITM_PROXY -> CaptureLifecycleOwner.MITM_PROXY_ENGINE
    CaptureMethod.ROOT_IPTABLES -> CaptureLifecycleOwner.ROOT_IPTABLES_ENGINE
    CaptureMethod.SHIZUKU_TUN -> CaptureLifecycleOwner.SHIZUKU_TUN_ENGINE
    CaptureMethod.HTTP_PROXY -> CaptureLifecycleOwner.HTTP_PROXY_ENGINE
    CaptureMethod.ROOT_FRIDA -> error(
        "CaptureMethod.ROOT_FRIDA has no single true CaptureLifecycleOwner -- state it " +
            "explicitly at the call site instead (see engineLifecycleOwner()'s KDoc)",
    )
}

/**
 * Which concrete engine instance owns starting/stopping this session's transport — i.e. whose
 * `.stop()` must be called to actually tear it down.
 *
 * Deliberately a *separate* field from both:
 *  - [CaptureMethod], which only attributes/labels a captured row. A native-bridge VPN session
 *    genuinely captures rows tagged [CaptureMethod.MITM_PROXY] (the decrypted-transaction path)
 *    while [com.example.interception.RequestrVpnService] still owns the whole session's
 *    lifecycle — routing a stop off `captureMethod` in that case tore down
 *    [com.example.interception.PreflightController]'s unrelated singleton MITM proxy while the
 *    real owning VPN service (and its tun interface) stayed alive, so the FAB confidently showed
 *    "stopped" while capture kept running (audit fix).
 *  - [ActiveTier], a capability-priority label surfaced to the user that is already reused
 *    non-1:1 with a lifecycle owner elsewhere (e.g. [com.example.interception.RequestrVpnService]
 *    deliberately reports `tier = ActiveTier.VPN` for a native-bridge session it attributes rows
 *    to as `CaptureMethod.MITM_PROXY` -- see [engineTier]'s KDoc), so it is not a safe routing key
 *    either.
 *
 * Every `markArmed`/`markActive` call site must state this explicitly (no default) so a newly
 * added tier can never silently inherit the wrong stop route.
 */
enum class CaptureLifecycleOwner {
    /** Nothing is active. */
    NONE,
    VPN_SERVICE,
    MITM_PROXY_ENGINE,
    HTTP_PROXY_ENGINE,
    ROOT_IPTABLES_ENGINE,
    SHIZUKU_TUN_ENGINE,
    FRIDA_ENGINE,
}

/** Root manager types detected on the device. */
enum class RootType(val displayName: String) {
    NONE("None"),
    MAGISK("Magisk"),
    KSU("KernelSU"),
    KSU_NEXT("KSU Next"),
    NAPATCH("nAPatch"),
    GENERIC_SU("Generic SU"),
}

/** Watcher health status for the active capture method. */
enum class WatcherStatus {
    /** Not watching — capture is not active. */
    IDLE,
    /** Watcher is running and the method is verified healthy. */
    HEALTHY,
    /** Watcher detected silence or errors. */
    DEGRADED,
    /** Method has been silent too long; user should investigate. */
    ERROR,
}

/**
 * Real root availability. Never defaults to granted — the previous build hardcoded this to
 * `true`, which is why the chip always read "Root (Granted)" (APP_v2.md Bug 3.1).
 */
enum class RootState { UNKNOWN, UNAVAILABLE, AVAILABLE, GRANTED, DENIED }

/** Real Shizuku binder + permission state, probed via the Shizuku SDK. */
enum class ShizukuState { UNKNOWN, NOT_INSTALLED, NOT_RUNNING, PERMISSION_DENIED, GRANTED }

/** Where requestr's CA actually lives, determined by enumerating the trust store. */
enum class CaState { UNKNOWN, NOT_INSTALLED, USER_STORE, SYSTEM_STORE }

/** Whether a frida-server is genuinely reachable on the expected port. */
enum class FridaState { UNKNOWN, NOT_RUNNING, RUNNING }

/**
 * Live capability snapshot. Every field starts at UNKNOWN and is only advanced by a real probe,
 * so the UI can distinguish "not checked yet" from "checked and unavailable" and style unmet
 * prerequisites distinctly instead of rendering everything as identically "on".
 */
data class CapabilityState(
    val root: RootState = RootState.UNKNOWN,
    val shizuku: ShizukuState = ShizukuState.UNKNOWN,
    val ca: CaState = CaState.UNKNOWN,
    val frida: FridaState = FridaState.UNKNOWN,
    /** True while a probe sweep is in flight, for spinner affordances. */
    val isProbing: Boolean = false,
    val lastProbedAt: Long? = null,
) {
    val isRootUsable: Boolean get() = root == RootState.GRANTED
    val isShizukuUsable: Boolean get() = shizuku == ShizukuState.GRANTED
    val isCaInstalled: Boolean get() = ca == CaState.USER_STORE || ca == CaState.SYSTEM_STORE

    /**
     * Tier that would be selected right now under AUTO, in the spec's strict priority order:
     * Root > Shizuku > VPN. VPN is the guaranteed rootless baseline (APP_v2.md Bug 3.4).
     */
    fun resolveAutoTier(): ActiveTier = when {
        isRootUsable -> ActiveTier.ROOT
        isShizukuUsable -> ActiveTier.SHIZUKU
        else -> ActiveTier.VPN
    }

    /**
     * Best capture method given current capabilities. Priority:
     * Root iptables (best coverage, transparent) > Shizuku tun > VPN tun.
     */
    fun resolveAutoCaptureMethod(): CaptureMethod = when {
        isRootUsable -> CaptureMethod.ROOT_IPTABLES
        isShizukuUsable -> CaptureMethod.SHIZUKU_TUN
        else -> CaptureMethod.VPN_TUN
    }
}

/**
 * The single source of truth for interception, shared by the Intercept FAB colour and the
 * wordmark's live dot so the two can never drift out of sync (APP_v2.md Bug 3.5 / §8).
 *
 * Every counter starts at zero. Nothing here is ever populated by sample data — only the
 * running engine writes to it.
 */
data class InterceptionState(
    val status: InterceptionStatus = InterceptionStatus.NEVER_CONFIGURED,
    val tier: ActiveTier = ActiveTier.NONE,
    val captureMethod: CaptureMethod = CaptureMethod.VPN_TUN,
    /** Which engine's `.stop()` actually owns tearing this session down. See [CaptureLifecycleOwner]. */
    val lifecycleOwner: CaptureLifecycleOwner = CaptureLifecycleOwner.NONE,
    val port: Int = 0,
    val packetCount: Int = 0,
    val bytesTransferred: Long = 0L,
    val activeConnections: Int = 0,
    val decryptedTransactionCount: Int = 0,
    val sessionId: Long? = null,
    val startedAt: Long? = null,
    val errorText: String? = null,
    val watcherStatus: WatcherStatus = WatcherStatus.IDLE,
    /** Why the watcher is DEGRADED/ERROR, when it knows (S1 task 13); null otherwise. */
    val watcherReason: String? = null,
) {
    val isActive: Boolean get() = status == InterceptionStatus.ACTIVE
    val isArmed: Boolean get() = status == InterceptionStatus.ARMED

    /** Human-readable tier label, e.g. "Using: VPN capture (no root)". */
    val tierLabel: String
        get() = when (tier) {
            ActiveTier.ROOT -> "Using: Root capture"
            ActiveTier.SHIZUKU -> "Using: Shizuku capture"
            ActiveTier.PROXY -> "Using: Proxy capture"
            ActiveTier.VPN -> "Using: VPN capture (no root)"
            ActiveTier.NONE -> ""
        }

    /** Detailed active-method label for status banners, e.g. "Root + iptables · port 8080". */
    val methodLabel: String
        get() = "${captureMethod.displayName} · port $port"
}
