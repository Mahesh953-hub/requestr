package com.example.interception.tun

import android.util.Log

/**
 * Kotlin-side lifecycle wrapper for the native tun2proxy bridge (Gap #1 Approach A). This is
 * what [com.example.interception.RequestrVpnService] actually calls: it combines
 * [Tun2ProxyConfig] marshalling with [NativeTun2Proxy]'s raw JNI surface, and centralizes the
 * "fail loud, never fake success" contract -- [start] only ever returns [StartResult.Started]
 * once the native side genuinely reported [NativeTun2Proxy.OK].
 */
object Tun2ProxyBridge {
    private const val TAG = "Tun2ProxyBridge"

    /** Bounded wait applied by callers (see [RequestrVpnService.cleanup]) between signalling
     * [stop] and actually closing the tun `ParcelFileDescriptor`, so the native background
     * thread has a real chance to observe the cancellation and stop touching the fd first. */
    const val STOP_QUIESCENCE_TIMEOUT_MS = 300L

    sealed class StartResult {
        /** The native bridge is genuinely running against the tun fd. */
        object Started : StartResult()

        /** `librequestr_tun2proxy.so` never loaded for this process (missing from the APK for
         * this ABI/build variant, or a linker error) -- never attempted the native call. */
        data class Unavailable(val reason: String) : StartResult()

        /** The native call was made but reported a failure; [code] is one of
         * [NativeTun2Proxy]'s `ERR_*` constants. */
        data class Failed(val code: Int, val message: String) : StartResult()
    }

    /**
     * Starts the bridge against [tunFd] using [config]. Never returns [StartResult.Started]
     * without the native side having actually reported success -- see [NativeTun2Proxy.start].
     */
    fun start(config: Tun2ProxyConfig, tunFd: Int): StartResult {
        if (!NativeTun2Proxy.isAvailable()) {
            val reason = NativeTun2Proxy.loadFailureReason()?.message ?: "librequestr_tun2proxy.so not loaded"
            Log.w(TAG, "Native tun2proxy bridge unavailable: $reason")
            return StartResult.Unavailable(reason)
        }
        val rc = NativeTun2Proxy.start(config.toJson(), tunFd)
        if (rc == NativeTun2Proxy.OK) {
            return StartResult.Started
        }
        val message = when (rc) {
            NativeTun2Proxy.ERR_RUNTIME_FAILED ->
                NativeTun2Proxy.lastError() ?: "tun2proxy runtime rejected the bridge before reaching RUNNING"
            NativeTun2Proxy.ERR_TIMEOUT ->
                "bridge readiness timed out (ambiguous: thread may still be starting)"
            else -> NativeTun2Proxy.lastError() ?: "unknown native tun2proxy error (code $rc)"
        }
        Log.w(TAG, "Native tun2proxy bridge failed to start: code=$rc message=$message")
        return StartResult.Failed(rc, message)
    }

    /** Signals the bridge to stop. Returns immediately (cooperative cancellation) -- see
     * [awaitStoppedOrTimeout] to wait for it to actually quiesce before touching the tun fd. */
    fun stop(): Int = NativeTun2Proxy.stop()

    /** One of [NativeTun2Proxy]'s `STATE_*` constants. */
    fun state(): Int = NativeTun2Proxy.state()

    fun isRunning(): Boolean {
        val s = state()
        return s == NativeTun2Proxy.STATE_RUNNING || s == NativeTun2Proxy.STATE_STARTING
    }

    /** Cumulative (tx, rx) byte counters, or null if unavailable/never started. */
    fun trafficStats(): Pair<Long, Long>? = NativeTun2Proxy.trafficStats()

    /**
     * Blocks the calling thread up to [timeoutMs] for the native bridge thread to actually
     * finish, then joins it. Returns one of [NativeTun2Proxy.OK] (confirmed finished and joined —
     * safe to close the tun fd), [NativeTun2Proxy.ERR_NOT_RUNNING] (no thread to wait on — also
     * safe), or [NativeTun2Proxy.ERR_TIMEOUT] (ambiguous — the caller must escalate and MUST NOT
     * close the tun fd yet). Call after [stop] and before closing the tun `ParcelFileDescriptor`.
     */
    fun awaitStoppedOrTimeout(timeoutMs: Long = STOP_QUIESCENCE_TIMEOUT_MS): Int {
        return NativeTun2Proxy.awaitStopped(timeoutMs)
    }
}
