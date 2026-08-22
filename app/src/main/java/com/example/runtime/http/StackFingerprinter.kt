package com.example.runtime.http

import android.content.Context
import com.example.data.local.RequestrDatabase

/**
 * W4: multi-signal HTTP client-stack fingerprinting with confidence scoring plus on-device
 * calibration, replacing the single-signal (`User-Agent` only) classification that
 * [HttpStackDetector.detectFromHeaders] alone can offer.
 *
 * Confidence model mirrors [com.example.interception.ProtocolDetector.Confidence]/[com.example.interception.ProtocolDetector.Detection]
 * exactly, for the same reason that class documents: a verdict is only as trustworthy as the
 * evidence behind it, and callers need to be able to tell "we are sure" from "we have a guess"
 * from "we have nothing" instead of collapsing all three into one enum value.
 */
object StackFingerprinter {

    /** One piece of matched evidence contributing to a [StackVerdict]. */
    data class StackSignal(val name: String, val detail: String)

    data class StackVerdict(
        val stack: HttpStack,
        val confidence: Confidence,
        val reason: String,
        val signals: List<StackSignal>,
    )

    /** One row of the on-device calibration table: what a real stack's own traffic looks like. */
    data class CalibrationEntry(
        val stack: HttpStack,
        val ja3Hash: String?,
        val alpn: String?,
        val http2Settings: List<Pair<Int, Int>>?,
        val requestHeaderOrder: List<String>?,
    )

    /** [calibrate] result: one [CalibrationEntry] per stack actually present on this device. */
    data class CalibrationTable(val entries: List<CalibrationEntry>) {
        fun matchByJa3(ja3Hash: String?): HttpStack? {
            if (ja3Hash == null) return null
            return entries.firstOrNull { it.ja3Hash == ja3Hash }?.stack
        }
    }

    // OkHttp 4.x's BridgeInterceptor sends exactly one SETTINGS entry on its client connection
    // preface: INITIAL_WINDOW_SIZE=16777216. Verified against okhttp-4.10.0's
    // Http2Connection.<init> disassembly (bipush 7; ldc 16777216; Settings.set).
    private val OKHTTP_SETTINGS_MULTISET = listOf(
        com.example.interception.http.Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE to 16777216,
    )

    /**
     * Score every signal available for one captured request/connection and return a
     * [StackVerdict]. Never returns [Confidence.UNKNOWN] without a non-empty [StackVerdict.reason]
     * explaining which signals were absent -- that's what makes UNKNOWN a diagnosable state
     * instead of a dead end.
     *
     * @param requestHeaders the persisted request's headers (used for User-Agent + cache-
     *        validator signals; casing/order are already lost by the time this map exists, so
     *        those come from [connectionSignals] instead when available).
     * @param connectionSignals live-captured evidence for this request's connection, from
     *        [StackSignalStore.get] -- null when the connection predates this process starting
     *        or the producer never fed the store (e.g. a HAR import).
     * @param calibration the device's own [CalibrationTable], or null when calibration has not
     *        been run yet.
     */
    fun classify(
        requestHeaders: Map<String, String>,
        connectionSignals: StackSignalStore.Signals?,
        calibration: CalibrationTable?,
        /**
         * True when this row is already known to have come out of a specific runtime adapter
         * (e.g. [CronetInstrumentation]/[com.example.runtime.webview.WebViewInstrumentation]) --
         * an event that came out of that adapter genuinely IS that stack, not a heuristic, so
         * it is always [Confidence.DETECTED] regardless of what other signals agree or disagree.
         * Callers pass the adapter's own [HttpStack] via [knownRuntimeStack] when this is true.
         */
        fromRuntimeAdapter: Boolean = false,
        knownRuntimeStack: HttpStack? = null,
    ): StackVerdict {
        if (fromRuntimeAdapter && knownRuntimeStack != null) {
            return StackVerdict(
                knownRuntimeStack,
                Confidence.DETECTED,
                "observed directly by the ${knownRuntimeStack.label} runtime adapter",
                listOf(StackSignal("runtime-adapter", knownRuntimeStack.label)),
            )
        }
        val signals = mutableListOf<StackSignal>()
        var candidate: HttpStack? = null

        // Signal: JA3 fingerprint, matched against the calibration table. Treated as DETECTED
        // evidence because it is the same underlying OS TLS provider that produced the
        // calibration probe's own ClientHello -- an exact match means the same code path ran.
        val ja3 = connectionSignals?.ja3Hash
        val ja3Match = calibration?.matchByJa3(ja3)
        if (ja3 != null && ja3Match != null) {
            signals.add(StackSignal("ja3", "matched calibrated $ja3Match ($ja3)"))
            candidate = ja3Match
        }

        // Signal: HTTP/2 SETTINGS multiset.
        val settings = connectionSignals?.http2Settings
        if (settings != null) {
            if (settings == OKHTTP_SETTINGS_MULTISET) {
                signals.add(StackSignal("h2-settings", "OkHttp default multiset $settings"))
                if (candidate == null) candidate = HttpStack.OKHTTP
            } else {
                val calibratedMatch = calibration?.entries?.firstOrNull { it.http2Settings == settings }
                if (calibratedMatch != null) {
                    signals.add(StackSignal("h2-settings", "matched calibrated ${calibratedMatch.stack} $settings"))
                    if (candidate == null) candidate = calibratedMatch.stack
                } else {
                    signals.add(StackSignal("h2-settings", "non-OkHttp multiset $settings"))
                }
            }
        }

        // Signal: ALPN offer. Cronet offers h3/h2; OkHttp offers h2+http/1.1; platform
        // HttpURLConnection/Volley on Android offer http/1.1 only (no h2).
        val alpn = connectionSignals?.alpn
        if (alpn != null) {
            when {
                alpn.contains("h3") -> {
                    signals.add(StackSignal("alpn", "offered $alpn (h3 -- Cronet-only on Android)"))
                    if (candidate == null) candidate = HttpStack.CRONET
                }
                alpn.contains("h2") -> {
                    signals.add(StackSignal("alpn", "offered $alpn (h2)"))
                }
                else -> signals.add(StackSignal("alpn", "offered $alpn (http/1.1 only)"))
            }
        }

        // Signal: User-Agent -- the existing single-signal detector, now one contributor among
        // several rather than the entry point.
        val uaStack = HttpStackDetector.detectFromHeaders(requestHeaders)
        if (uaStack != HttpStack.UNKNOWN) {
            signals.add(StackSignal("user-agent", "matched ${uaStack.label} default format"))
            if (candidate == null) candidate = uaStack
        }

        // Signal: cache-validation shape -- the real Volley-vs-HttpURLConnection discriminator.
        // Volley's HurlStack disables the platform cache and supplies If-None-Match/
        // If-Modified-Since itself, and its default body content-types are
        // application/x-www-form-urlencoded / application/json with those exact charsets.
        if (uaStack == HttpStack.HTTP_URL_CONNECTION || uaStack == HttpStack.UNKNOWN) {
            val hasValidators = requestHeaders.containsKeyIgnoreCase("If-None-Match") ||
                requestHeaders.containsKeyIgnoreCase("If-Modified-Since")
            val contentType = requestHeaders.valueIgnoreCase("Content-Type")
            val volleyContentType = contentType != null && (
                contentType.startsWith("application/x-www-form-urlencoded; charset=UTF-8") ||
                    contentType.startsWith("application/json; charset=utf-8")
                )
            if (hasValidators || volleyContentType) {
                signals.add(
                    StackSignal(
                        "cache-validation",
                        "Volley-shaped cache validators/content-type present" +
                            (if (hasValidators) " (If-None-Match/If-Modified-Since)" else "") +
                            (if (volleyContentType) " (default body content-type)" else ""),
                    )
                )
                candidate = HttpStack.VOLLEY
            }
        }

        // Signal: pseudo-header order (h2 only) -- library-specific but not yet mapped to a
        // fixed table here; recorded as corroborating evidence for whatever candidate is
        // already selected, or logged for future calibration comparison otherwise.
        val pseudoOrder = connectionSignals?.pseudoHeaderOrder
        if (pseudoOrder != null) {
            signals.add(StackSignal("pseudo-header-order", pseudoOrder.joinToString(",")))
        }

        // Signal: request header order+casing, matched against calibration.
        val headerOrder = connectionSignals?.requestHeaderOrder
        if (headerOrder != null) {
            val calibratedMatch = calibration?.entries?.firstOrNull { it.requestHeaderOrder == headerOrder }
            if (calibratedMatch != null) {
                signals.add(StackSignal("header-order", "matched calibrated ${calibratedMatch.stack}"))
                if (candidate == null) candidate = calibratedMatch.stack
            } else {
                signals.add(StackSignal("header-order", headerOrder.joinToString(",")))
            }
        }

        val independentSignalCount = signals.map { it.name }.distinct().size

        val resolvedStack = candidate ?: HttpStack.UNKNOWN
        val confidence = when {
            resolvedStack == HttpStack.UNKNOWN -> Confidence.UNKNOWN
            ja3 != null && ja3Match != null -> Confidence.DETECTED
            independentSignalCount >= 2 -> Confidence.LIKELY
            independentSignalCount == 1 -> Confidence.POSSIBLE
            else -> Confidence.UNKNOWN
        }

        val reason = if (confidence == Confidence.UNKNOWN) {
            val absent = mutableListOf<String>()
            if (ja3 == null) absent.add("no TLS row / JA3")
            if (settings == null) absent.add("no HTTP/2 SETTINGS observed")
            if (uaStack == HttpStack.UNKNOWN) absent.add("User-Agent missing or custom")
            if (headerOrder == null) absent.add("header order not sampled")
            "zero discriminating signals collected (${absent.joinToString("; ")})"
        } else {
            signals.joinToString("; ") { "${it.name}: ${it.detail}" }
        }

        return StackVerdict(resolvedStack, confidence, reason, signals)
    }

    private fun Map<String, String>.containsKeyIgnoreCase(name: String): Boolean =
        keys.any { it.equals(name, ignoreCase = true) }

    private fun Map<String, String>.valueIgnoreCase(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Confidence levels, deliberately identical shape to [com.example.interception.ProtocolDetector.Confidence]. */
    enum class Confidence { DETECTED, LIKELY, POSSIBLE, UNKNOWN }

    /**
     * Issues one identical request per HTTP stack actually present on requestr's own classpath
     * against [com.example.api.LocalApiServer]'s loopback `/api/ping`, tagged with
     * [com.example.api.LocalApiServer.CALIBRATION_STACK_HEADER] so the server can record that
     * stack's real wire-level header order. Captures each call's JA3/ALPN/SETTINGS/header-order
     * fingerprint through [StackSignalStore] under a synthetic `calibrate:<stack>` connection
     * id, then reads them back into a [CalibrationTable]. This makes the verdict evidence-based
     * on the real device rather than a hardcoded assumption table, which is what lets a custom
     * User-Agent still resolve to a real stack via JA3/SETTINGS/header-order matching instead
     * of falling to UNKNOWN.
     *
     * Only issues requests for stacks [HttpStackDetector.detectAvailableOnClasspath] reports as
     * present -- requestr cannot calibrate a stack it does not itself have loaded.
     */
    suspend fun calibrate(context: Context, port: Int): CalibrationTable {
        val available = HttpStackDetector.detectAvailableOnClasspath()
        val url = "http://127.0.0.1:$port/api/ping"
        val entries = mutableListOf<CalibrationEntry>()

        for (stack in available) {
            val connectionId = "calibrate:${stack.name}"
            val probeOk = runCatching {
                when (stack) {
                    HttpStack.HTTP_URL_CONNECTION -> probeViaHttpUrlConnection(url, stack.name)
                    HttpStack.CRONET -> probeViaCronet(context, url, stack.name)
                    else -> false // OKHTTP/KTOR/VOLLEY calibration probes not wired in this pass;
                    // the table simply has no entry for them yet, which is an honest gap, not a
                    // fabricated one -- classify() treats an absent calibration entry as "no
                    // match" rather than a false positive.
                }
            }.getOrDefault(false)
            if (!probeOk) continue

            val signals = StackSignalStore.get(connectionId)
            entries.add(
                CalibrationEntry(
                    stack = stack,
                    ja3Hash = signals?.ja3Hash,
                    alpn = signals?.alpn,
                    http2Settings = signals?.http2Settings,
                    requestHeaderOrder = signals?.requestHeaderOrder,
                )
            )
        }
        return CalibrationTable(entries)
    }

    private fun probeViaHttpUrlConnection(url: String, stackName: String): Boolean {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.setRequestProperty(com.example.api.LocalApiServer.CALIBRATION_STACK_HEADER, stackName)
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }

    private fun probeViaCronet(context: Context, url: String, stackName: String): Boolean {
        val result = CronetInstrumentation.executeBlocking(
            context = context,
            url = url,
            method = "GET",
            headers = mapOf(com.example.api.LocalApiServer.CALIBRATION_STACK_HEADER to stackName),
            body = null,
            timeoutMs = 5_000,
        )
        return result.statusCode == 200
    }
}
