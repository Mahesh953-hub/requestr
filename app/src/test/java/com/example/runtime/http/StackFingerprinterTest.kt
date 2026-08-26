package com.example.runtime.http

import com.example.interception.http.Http2Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StackFingerprinter.classify] -- the multi-signal, confidence-scored
 * replacement for [HttpStackDetector.detectFromHeaders]'s single-signal User-Agent match.
 *
 * `app/build.gradle.kts` already sets `testOptions { unitTests { isReturnDefaultValues = true } }`
 * for this module, so this file deliberately stays plain-JVM (no Robolectric, no Android
 * Context) -- [StackFingerprinter.classify] itself needs neither.
 */
class StackFingerprinterTest {

    private fun signals(
        ja3Hash: String? = null,
        alpn: String? = null,
        http2Settings: List<Pair<Int, Int>>? = null,
        pseudoHeaderOrder: List<String>? = null,
        requestHeaderOrder: List<String>? = null,
    ) = StackSignalStore.Signals(
        ja3Hash = ja3Hash,
        alpn = alpn,
        http2Settings = http2Settings,
        pseudoHeaderOrder = pseudoHeaderOrder,
        requestHeaderOrder = requestHeaderOrder,
    )

    @Test
    fun `OkHttp default SETTINGS multiset resolves to OkHttp`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = emptyMap(),
            connectionSignals = signals(
                http2Settings = listOf(Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE to 16777216),
            ),
            calibration = null,
        )
        assertEquals(HttpStack.OKHTTP, verdict.stack)
        assertTrue(verdict.signals.any { it.name == "h2-settings" })
    }

    @Test
    fun `different SETTINGS multiset does not resolve to OkHttp`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = emptyMap(),
            connectionSignals = signals(
                http2Settings = listOf(Http2Parser.SETTINGS_MAX_CONCURRENT_STREAMS to 100),
            ),
            calibration = null,
        )
        assertNotEquals(HttpStack.OKHTTP, verdict.stack)
    }

    @Test
    fun `Dalvik UA with If-None-Match and Volley content-type resolves to Volley`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = mapOf(
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 13; Pixel Build/TQ3A.230805.001)",
                "If-None-Match" to "\"abc123\"",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            connectionSignals = null,
            calibration = null,
        )
        assertEquals(HttpStack.VOLLEY, verdict.stack)
        assertTrue(
            "reason should name the cache-validation signal, was: ${verdict.reason}",
            verdict.reason.contains("cache-validation"),
        )
    }

    @Test
    fun `Dalvik UA without cache-validators or Volley content-type resolves to HttpURLConnection`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = mapOf(
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 13; Pixel Build/TQ3A.230805.001)",
            ),
            connectionSignals = null,
            calibration = null,
        )
        assertEquals(HttpStack.HTTP_URL_CONNECTION, verdict.stack)
    }

    @Test
    fun `custom User-Agent with calibrated JA3 match resolves to the calibrated stack, not UNKNOWN`() {
        val calibration = StackFingerprinter.CalibrationTable(
            entries = listOf(
                StackFingerprinter.CalibrationEntry(
                    stack = HttpStack.CRONET,
                    ja3Hash = "deadbeef",
                    alpn = "h2,http/1.1",
                    http2Settings = null,
                    requestHeaderOrder = null,
                ),
            ),
        )
        val verdict = StackFingerprinter.classify(
            requestHeaders = mapOf("User-Agent" to "MyCustomAgent/1.0"),
            connectionSignals = signals(ja3Hash = "deadbeef"),
            calibration = calibration,
        )
        assertEquals(HttpStack.CRONET, verdict.stack)
        assertEquals(StackFingerprinter.Confidence.DETECTED, verdict.confidence)
    }

    @Test
    fun `zero signals resolves to UNKNOWN with a non-empty diagnosable reason`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = emptyMap(),
            connectionSignals = null,
            calibration = null,
        )
        assertEquals(HttpStack.UNKNOWN, verdict.stack)
        assertEquals(StackFingerprinter.Confidence.UNKNOWN, verdict.confidence)
        assertTrue(verdict.reason.isNotEmpty())
        assertTrue(verdict.reason.contains("JA3"))
        assertTrue(verdict.reason.contains("SETTINGS"))
    }

    @Test
    fun `pseudo-header order from a real decoded HEADERS frame is recorded as a corroborating signal`() {
        // RFC 7541 Appendix C.4.1 first request, Huffman-coded (same block verified in
        // GrpcParserTests): decodes to :method GET, :scheme http, :path /, :authority
        // www.example.com.
        val block = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")
        val frame = Http2Parser.H2Frame(block.size, Http2Parser.FRAME_HEADERS, 0x04, 1, block)
        val headers = Http2Parser.decodeHeaders(frame, block)
        val order = headers.pseudoHeaders.keys.toList()
        assertTrue(order.isNotEmpty())

        val verdict = StackFingerprinter.classify(
            requestHeaders = emptyMap(),
            connectionSignals = signals(pseudoHeaderOrder = order),
            calibration = null,
        )
        assertTrue(verdict.signals.any { it.name == "pseudo-header-order" })
    }

    @Test
    fun `runtime adapter events are always DETECTED regardless of other signals`() {
        val verdict = StackFingerprinter.classify(
            requestHeaders = emptyMap(),
            connectionSignals = null,
            calibration = null,
            fromRuntimeAdapter = true,
            knownRuntimeStack = HttpStack.CRONET,
        )
        assertEquals(HttpStack.CRONET, verdict.stack)
        assertEquals(StackFingerprinter.Confidence.DETECTED, verdict.confidence)
        assertTrue(verdict.reason.contains("runtime adapter"))
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ── representative-verdict ranking (v6.1 Domain B) ────────────────────────

    private fun verdictWith(
        confidence: StackFingerprinter.Confidence,
        signalCount: Int,
        stack: HttpStack = HttpStack.OKHTTP,
    ) = StackFingerprinter.StackVerdict(
        stack = stack,
        confidence = confidence,
        reason = "reason",
        signals = (0 until signalCount).map { StackFingerprinter.StackSignal("signal-$it", "detail") },
    )

    @Test
    fun `any real verdict beats having nothing retained yet`() {
        assertTrue(
            StackFingerprinter.isBetterEvidenced(
                verdictWith(StackFingerprinter.Confidence.UNKNOWN, signalCount = 0),
                incumbent = null,
            ),
        )
    }

    @Test
    fun `stronger confidence wins regardless of signal count`() {
        val detectedWithOneSignal = verdictWith(StackFingerprinter.Confidence.DETECTED, signalCount = 1)
        val likelyWithFourSignals = verdictWith(StackFingerprinter.Confidence.LIKELY, signalCount = 4)
        assertTrue(StackFingerprinter.isBetterEvidenced(detectedWithOneSignal, likelyWithFourSignals))
        assertFalse(StackFingerprinter.isBetterEvidenced(likelyWithFourSignals, detectedWithOneSignal))
    }

    @Test
    fun `equal confidence breaks on how many signals actually fired`() {
        val threeSignals = verdictWith(StackFingerprinter.Confidence.LIKELY, signalCount = 3)
        val twoSignals = verdictWith(StackFingerprinter.Confidence.LIKELY, signalCount = 2)
        assertTrue(StackFingerprinter.isBetterEvidenced(threeSignals, twoSignals))
        assertFalse(StackFingerprinter.isBetterEvidenced(twoSignals, threeSignals))
    }

    @Test
    fun `an equally evidenced verdict does not displace the incumbent`() {
        // Keeps the retained verdict stable across rows instead of flip-flopping between ties.
        val incumbent = verdictWith(StackFingerprinter.Confidence.POSSIBLE, signalCount = 2)
        val tie = verdictWith(StackFingerprinter.Confidence.POSSIBLE, signalCount = 2)
        assertFalse(StackFingerprinter.isBetterEvidenced(tie, incumbent))
    }
}
