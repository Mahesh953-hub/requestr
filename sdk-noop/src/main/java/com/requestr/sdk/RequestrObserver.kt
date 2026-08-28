package com.requestr.sdk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call

/**
 * No-op counterpart to the real `:sdk` module's `RequestrObserver` -- see that module's kdoc for
 * the full contract this mirrors, call-site for call-site. Every method here is a deliberate
 * no-op: no batching, no HTTP calls to requestr's Local API, nothing retained beyond the trivial
 * state needed for [lastFlushStatus]/[consumeTiming] to keep returning well-typed, harmless
 * values. See this module's README for why [RequestrObserver] is one of the classes intentionally
 * mirrored (it's part of the documented public embedding surface, `RequestrObserver.observe(...)`
 * is called out by name in `:sdk`'s own kdoc).
 */
object RequestrObserver {
    private val _lastFlushStatus = MutableStateFlow<FlushStatus>(FlushStatus.Idle)

    /** Always [FlushStatus.Idle] -- this module never attempts a flush, so it never leaves that
     * state. See class kdoc. */
    val lastFlushStatus: StateFlow<FlushStatus> = _lastFlushStatus.asStateFlow()

    fun init(ctx: Context, pairingToken: String, apiBaseUrl: String = "http://127.0.0.1:9876") {
        // Intentionally empty -- see class kdoc.
    }

    fun observe(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        statusCode: Int? = null,
        bodySize: Long = 0,
        durationMs: Long = 0,
    ) {
        // Intentionally empty -- see class kdoc.
    }

    fun observeDetailed(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        statusCode: Int? = null,
        requestBody: ByteArray? = null,
        responseBody: ByteArray? = null,
        durationMs: Long = 0,
        httpStack: String? = null,
        tlsVersion: String? = null,
        tlsCipher: String? = null,
        serverCertSubject: String? = null,
        isDecrypted: Boolean = true,
        stackFrames: List<String> = emptyList(),
        timing: TimingBreakdown? = null,
    ) {
        // Intentionally empty -- see class kdoc.
    }

    fun recordTiming(call: Call, phase: String, ms: Long) {
        // Intentionally empty -- see class kdoc.
    }

    fun recordTiming(url: String, phase: String, ms: Long) {
        // Intentionally empty -- see class kdoc.
    }

    /** Always `null` -- nothing is ever recorded for [call] to consume. See class kdoc. */
    fun consumeTiming(call: Call): TimingBreakdown? = null

    fun flush() {
        // Intentionally empty -- see class kdoc.
    }

    /** Always `false` -- this module never talks to requestr's Local API at all. See class
     * kdoc. */
    fun isApiReachable(): Boolean = false
}

/** Identical shape to the real `:sdk` module's `FlushStatus` -- see there for full kdoc on each
 * case. Kept byte-for-byte compatible so any host-app code that pattern-matches on this type
 * keeps compiling unchanged whichever module is selected. */
sealed class FlushStatus {
    object Idle : FlushStatus()
    object Success : FlushStatus()
    data class Unauthorized(val httpCode: Int) : FlushStatus()
    data class ServerError(val httpCode: Int) : FlushStatus()
    data class NetworkError(val message: String?) : FlushStatus()
}

/** Identical shape to the real `:sdk` module's `TimingBreakdown`. */
class TimingBreakdown(
    var dnsMs: Long? = null,
    var connectMs: Long? = null,
    var tlsMs: Long? = null,
)

/** Identical shape to the real `:sdk` module's `ObserverEvent`. Never actually constructed by
 * this module (nothing here ever calls [RequestrObserver.enqueue] because there is no such
 * function here), kept only so the type itself remains source-compatible for any host-app code
 * that references it directly. */
data class ObserverEvent(
    val timestamp: Long,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val statusCode: Int?,
    val bodySize: Long,
    val durationMs: Long,
    val source: String,
    val requestBodyBase64: String? = null,
    val responseBodyBase64: String? = null,
    val httpStack: String? = null,
    val tlsVersion: String? = null,
    val tlsCipher: String? = null,
    val serverCertSubject: String? = null,
    val isDecrypted: Boolean = true,
    val stackFrames: List<String> = emptyList(),
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
)
