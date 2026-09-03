package com.example.interception.tun

import android.util.Log
import com.example.interception.bridge.BridgeEventListener
import com.example.interception.bridge.BridgeSupervisor
import com.example.interception.net.SocketProtector

/**
 * Kotlin-side lifecycle wrapper for the native tun2proxy bridge. Manages
 * [BridgeSupervisor] event lifecycle and fd protection through [SocketProtector].
 */
object Tun2ProxyBridge {
    private const val TAG = "Tun2ProxyBridge"

    const val STOP_QUIESCENCE_TIMEOUT_MS = 300L

    @Volatile
    var supervisor: BridgeSupervisor? = null
        private set

    sealed class StartResult {
        object Started : StartResult()
        data class Unavailable(val reason: String) : StartResult()
        data class Failed(val code: Int, val message: String) : StartResult()

        /**
         * The native `start()` call returned `ERR_TIMEOUT` and a subsequent [stop] +
         * [awaitStoppedOrTimeout]-style confirmation attempt made from inside [start] itself
         * ALSO could not confirm the native thread has quiesced. The tun fd, MITM engine, and
         * every registered callback (event listener + protect callback) are deliberately left
         * untouched and still registered -- the native side may still be mid-transition and
         * could call back into them at any moment. The caller must NOT close/reuse the tun fd
         * and must NOT fall back to starting a second capture path (e.g. legacy TunRelay) while
         * indeterminate; it should fail the capture attempt for safety and let the user retry.
         */
        data class Indeterminate(val message: String) : StartResult()
    }

    /**
     * Starts the bridge. Registers event listener and protect callback (if required)
     * before starting native tun2proxy. Fails closed if protect registration fails
     * when config requires protection.
     */
    fun start(config: Tun2ProxyConfig, tunFd: Int): StartResult {
        if (!NativeTun2Proxy.isAvailable()) {
            val reason = NativeTun2Proxy.loadFailureReason()?.message ?: "librequestr_tun2proxy.so not loaded"
            Log.w(TAG, "Native tun2proxy bridge unavailable: $reason")
            return StartResult.Unavailable(reason)
        }

        val sup = BridgeSupervisor()
        supervisor = sup

        // Register event listener — check return code
        val eventRc = NativeTun2Proxy.registerEventListener(sup)
        if (eventRc != NativeTun2Proxy.OK) {
            sup.close()
            supervisor = null
            return StartResult.Failed(eventRc, "Failed to register native event listener")
        }

        // Register protect callback if protection is required
        if (config.protectAvailable) {
            val protectRc = NativeTun2Proxy.registerProtectCallback(NativeFdProtector)
            if (protectRc != NativeTun2Proxy.OK) {
                NativeTun2Proxy.unregisterEventListener()
                sup.close()
                supervisor = null
                return StartResult.Failed(protectRc, "Failed to register native protect callback — required by config")
            }
        }

        val rc = NativeTun2Proxy.start(config.toJson(), tunFd)
        if (rc == NativeTun2Proxy.OK) {
            return StartResult.Started
        }

        if (rc == NativeTun2Proxy.ERR_TIMEOUT) {
            // Ambiguous: the native thread may still be transitioning to RUNNING (or may
            // never have started at all). Unregistering callbacks now would be unsafe if it
            // is still running -- a late native callback into a GC'd/cleared listener/protector
            // is worse than a leaked registration. Attempt to confirm quiescence first.
            NativeTun2Proxy.stop()
            val awaitRc = NativeTun2Proxy.awaitStopped(STOP_QUIESCENCE_TIMEOUT_MS)
            if (awaitRc == NativeTun2Proxy.OK || awaitRc == NativeTun2Proxy.ERR_NOT_RUNNING) {
                // Confirmed stopped -- safe to unregister and report a normal failure so the
                // caller can fall back (e.g. to the legacy TunRelay path).
                NativeTun2Proxy.unregisterEventListener()
                if (config.protectAvailable) {
                    NativeTun2Proxy.unregisterProtectCallback()
                }
                sup.close()
                supervisor = null
                val message = "bridge readiness timed out; confirmed stopped, safe to fall back"
                Log.w(TAG, "Native tun2proxy bridge failed to start: code=$rc message=$message")
                return StartResult.Failed(rc, message)
            }

            // Still indeterminate -- do NOT touch any registration or the supervisor. The
            // caller must not reuse/close the tun fd nor start a second capture path.
            val message = "bridge readiness timed out; unable to confirm shutdown"
            Log.e(TAG, "Native tun2proxy bridge indeterminate after start timeout: $message")
            return StartResult.Indeterminate(message)
        }

        // Start failed — clean up registrations
        NativeTun2Proxy.unregisterEventListener()
        if (config.protectAvailable) {
            NativeTun2Proxy.unregisterProtectCallback()
        }
        sup.close()
        supervisor = null

        val message = when (rc) {
            NativeTun2Proxy.ERR_RUNTIME_FAILED ->
                NativeTun2Proxy.lastError() ?: "tun2proxy runtime rejected the bridge before reaching RUNNING"
            else -> NativeTun2Proxy.lastError() ?: "unknown native tun2proxy error (code $rc)"
        }
        Log.w(TAG, "Native tun2proxy bridge failed to start: code=$rc message=$message")
        return StartResult.Failed(rc, message)
    }

    /**
     * Signals the bridge to stop. Does NOT unregister callbacks yet — they remain
     * active until [awaitStoppedOrTimeout] confirms quiescence, so native egress
     * tasks that are still running can still call protect/events safely.
     */
    fun stop(): Int = NativeTun2Proxy.stop()

    /**
     * Waits for native thread to finish, then unregisters callbacks and cleans up.
     * Call after [stop]. On timeout, callbacks are NOT cleared — caller must handle.
     */
    fun awaitStoppedOrTimeout(timeoutMs: Long = STOP_QUIESCENCE_TIMEOUT_MS): Int {
        val rc = NativeTun2Proxy.awaitStopped(timeoutMs)
        if (rc == NativeTun2Proxy.OK || rc == NativeTun2Proxy.ERR_NOT_RUNNING) {
            // Confirmed quiescent — safe to unregister
            finalize()
        }
        // On ERR_TIMEOUT: do NOT finalize — native thread may still use callbacks
        return rc
    }

    /** Unconditionally unregister all callbacks, stop the dispatch worker, and clear supervisor. */
    fun finalize() {
        NativeTun2Proxy.unregisterEventListener()
        NativeTun2Proxy.unregisterProtectCallback()
        supervisor?.close()
        supervisor?.clearListeners()
        supervisor = null
    }

    fun state(): Int = NativeTun2Proxy.state()

    fun isRunning(): Boolean {
        val s = state()
        return s == NativeTun2Proxy.STATE_RUNNING || s == NativeTun2Proxy.STATE_STARTING
    }

    fun trafficStats(): Pair<Long, Long>? = NativeTun2Proxy.trafficStats()

    fun addEventListener(listener: BridgeEventListener) {
        supervisor?.addListener(listener)
    }

    fun removeEventListener(listener: BridgeEventListener) {
        supervisor?.removeListener(listener)
    }

    /**
     * Adapter that delegates fd protection to [SocketProtector]. In VPN bridge mode,
     * protection is required — Unavailable returns false (fail closed) since the
     * VPN backing should be installed before the bridge starts.
     */
    private object NativeFdProtector {
        @JvmStatic
        @Suppress("unused")
        fun protectFd(fd: Int): Boolean {
            return when (SocketProtector.protectFd(fd)) {
                SocketProtector.ProtectResult.Protected -> true
                SocketProtector.ProtectResult.Unavailable -> false // fail closed in bridge mode
                SocketProtector.ProtectResult.Failed -> false
            }
        }
    }
}
