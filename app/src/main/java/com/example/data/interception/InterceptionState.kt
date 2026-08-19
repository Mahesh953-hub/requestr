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
enum class ActiveTier { NONE, VPN, SHIZUKU, ROOT }

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
    val port: Int = 0,
    val packetCount: Int = 0,
    val bytesTransferred: Long = 0L,
    val activeConnections: Int = 0,
    val sessionId: Long? = null,
    val startedAt: Long? = null,
    val errorText: String? = null,
    val watcherStatus: WatcherStatus = WatcherStatus.IDLE,
) {
    val isActive: Boolean get() = status == InterceptionStatus.ACTIVE
    val isArmed: Boolean get() = status == InterceptionStatus.ARMED

    /** Human-readable tier label, e.g. "Using: VPN capture (no root)". */
    val tierLabel: String
        get() = when (tier) {
            ActiveTier.ROOT -> "Using: Root capture"
            ActiveTier.SHIZUKU -> "Using: Shizuku capture"
            ActiveTier.VPN -> "Using: VPN capture (no root)"
            ActiveTier.NONE -> ""
        }

    /** Detailed active-method label for status banners, e.g. "Root + iptables · port 8080". */
    val methodLabel: String
        get() = "${captureMethod.displayName} · port $port"
}
