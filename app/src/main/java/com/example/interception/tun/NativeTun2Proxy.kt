package com.example.interception.tun

import android.util.Log

/**
 * Thin JNI façade over the native tun2proxy bridge crate (`native/tun2proxy-bridge`, Gap #1
 * Approach A). Every `external fun` here matches a `#[no_mangle] extern "system"` export in
 * `native/tun2proxy-bridge/src/jni_bridge.rs` named
 * `Java_com_example_interception_tun_NativeTun2Proxy_<name>` -- renaming this object, moving
 * it to a different package, or changing a signature here requires updating that file too.
 *
 * [System.loadLibrary] is attempted exactly once, from this object's static initializer.
 * Devices/build variants that never packaged `librequestr_tun2proxy.so` (or an emulator/ABI
 * combination cargo-ndk didn't build for) get a real, observable [UnsatisfiedLinkError] here
 * instead of a crash the first time a native method is actually invoked -- [isAvailable] lets
 * callers check that upfront and choose an explicit fallback rather than pretending the native
 * bridge started.
 */
object NativeTun2Proxy {
    private const val TAG = "NativeTun2Proxy"
    const val LIBRARY_NAME = "requestr_tun2proxy"

    // Mirrors native/tun2proxy-bridge/src/lib.rs's ERR_*/OK constants exactly.
    const val OK = 0
    const val ERR_ALREADY_RUNNING = -1
    const val ERR_INVALID_CONFIG = -2
    const val ERR_LOCK = -3
    const val ERR_NOT_RUNNING = -4
    const val ERR_NULL_PTR = -5
    const val ERR_RUNTIME_FAILED = -6
    const val ERR_TIMEOUT = -7

    // Mirrors native/tun2proxy-bridge/src/state.rs's BridgeState discriminants exactly.
    const val STATE_IDLE = 0
    const val STATE_STARTING = 1
    const val STATE_RUNNING = 2
    const val STATE_STOPPING = 3
    const val STATE_FAILED = 4

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailure: Throwable? = null

    init {
        loaded = try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (e: UnsatisfiedLinkError) {
            loadFailure = e
            Log.w(TAG, "lib$LIBRARY_NAME.so not available; native tun2proxy bridge disabled: ${e.message}")
            false
        } catch (e: Throwable) {
            // Never let a native-library-load problem crash the whole process at class-init
            // time -- callers must be able to observe isAvailable()==false and fall back.
            loadFailure = e
            Log.w(TAG, "Unexpected error loading lib$LIBRARY_NAME.so: ${e.message}")
            false
        }
    }

    /** True once `librequestr_tun2proxy.so` has loaded successfully for this process. */
    fun isAvailable(): Boolean = loaded

    /** The library load failure, if [isAvailable] is false; null once loaded successfully. */
    fun loadFailureReason(): Throwable? = loadFailure

    /**
     * Starts the native bridge against [tunFd] with [configJson] (see
     * [Tun2ProxyConfig.toJson]). Returns [OK] or a negative `ERR_*` code -- see
     * `native/tun2proxy-bridge/src/lib.rs::requestr_tun2proxy_start` for the exact contract.
     * Callers must check [isAvailable] first; this returns [ERR_NOT_RUNNING] without touching
     * JNI at all when the library never loaded, rather than every call site having to catch
     * [UnsatisfiedLinkError] individually.
     */
    fun start(configJson: String, tunFd: Int): Int {
        if (!loaded) return ERR_NOT_RUNNING
        return runCatching { nativeStart(configJson, tunFd) }.getOrElse {
            Log.w(TAG, "nativeStart threw unexpectedly: ${it.message}")
            ERR_LOCK
        }
    }

    fun stop(): Int {
        if (!loaded) return ERR_NOT_RUNNING
        return runCatching { nativeStop() }.getOrElse {
            Log.w(TAG, "nativeStop threw unexpectedly: ${it.message}")
            ERR_LOCK
        }
    }

    /**
     * Blocks up to [timeoutMs] for the native bridge thread to finish, then joins it. Returns
     * [OK] once the thread is provably done and joined, [ERR_NOT_RUNNING] when there was no
     * thread to wait on, or [ERR_TIMEOUT] when the bound elapsed first (ambiguous — the caller
     * must escalate and must NOT close the tun fd yet). This is the "confirmed shutdown" half of
     * P1 bug #2; call after [stop] and before closing the tun `ParcelFileDescriptor`.
     */
    fun awaitStopped(timeoutMs: Long): Int {
        if (!loaded) return ERR_NOT_RUNNING
        return runCatching { nativeAwaitStopped(timeoutMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
            .getOrElse {
                Log.w(TAG, "nativeAwaitStopped threw unexpectedly: ${it.message}")
                ERR_LOCK
            }
    }

    /** One of the `STATE_*` constants above. [STATE_IDLE] if the library never loaded. */
    fun state(): Int {
        if (!loaded) return STATE_IDLE
        return runCatching { nativeState() }.getOrDefault(STATE_IDLE)
    }

    /** The last recorded native error message, or null if none / library unavailable. */
    fun lastError(): String? {
        if (!loaded) return loadFailure?.message
        return runCatching { nativeLastError() }.getOrNull()
    }

    /**
     * Cumulative (tx, rx) byte counters reported by tun2proxy's own traffic-status callback, or
     * null if the bridge is unavailable or has never started. Never fabricates a nonzero value
     * -- a bridge that started but has not moved a byte yet reports `(0, 0)`, not null.
     *
     * Known limitation: `-1L` is used as the JNI-side failure sentinel (see
     * `jni_bridge.rs::nativeTrafficStats`), so a session that has moved exactly
     * 0xFFFFFFFF bytes in *both* directions (~4 GiB each) would be misreported as
     * unavailable. Accepted as out of scope for the packed-`Long` wire format.
     */
    fun trafficStats(): Pair<Long, Long>? {
        if (!loaded) return null
        val packed = runCatching { nativeTrafficStats() }.getOrDefault(-1L)
        if (packed == -1L) return null
        val tx = (packed ushr 32) and 0xffffffffL
        val rx = packed and 0xffffffffL
        return tx to rx
    }

    @JvmStatic
    private external fun nativeStart(configJson: String, tunFd: Int): Int

    @JvmStatic
    private external fun nativeStop(): Int

    @JvmStatic
    private external fun nativeAwaitStopped(timeoutMs: Int): Int

    @JvmStatic
    private external fun nativeState(): Int

    @JvmStatic
    private external fun nativeLastError(): String?

    @JvmStatic
    private external fun nativeTrafficStats(): Long
}
