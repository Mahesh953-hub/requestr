package com.example.runtime

import com.example.browser.BrowserBridgeManager
import com.example.browser.BrowserStorageObserver
import com.example.browser.CdpClient
import com.example.data.models.AdapterCaptureState
import com.example.data.models.CdpConnectionPhase
import com.example.data.models.InstrumentationHealth
import com.example.runtime.http.HttpStack
import com.example.runtime.http.StackFingerprinter
import com.example.runtime.webview.WebViewInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.1 Phase A / Domain B: unit tests for [RuntimeInstrumentationStateProvider]'s pure mapping
 * layer — the single place UI state is derived, so these assertions are what keep the two Settings
 * cards honest.
 *
 * Plain JVM: only the provider's `internal` pure builders are exercised, so no engine is started, no
 * WebView is constructed, and no socket is opened.
 */
class RuntimeInstrumentationStateProviderTest {

    private fun connection(
        state: CdpClient.State,
        attempt: Int = 0,
        retryDelayMs: Long = 0L,
        lastError: String? = null,
        status: String = "status",
        retryActive: Boolean = false,
    ) = RuntimeInstrumentationStateProvider.ConnectionSlice(
        state = state,
        reconnectAttempt = attempt,
        nextRetryDelayMs = retryDelayMs,
        lastError = lastError,
        statusMessage = status,
        retryActive = retryActive,
    )

    private fun target(sessionId: String, title: String, url: String) =
        CdpClient.TargetInfo(sessionId, "T-$sessionId", "page", title, url)

    private fun targets(
        live: Map<String, CdpClient.TargetInfo> = emptyMap(),
        liveEvents: Map<String, Int> = emptyMap(),
        lastKnown: Map<String, CdpClient.TargetInfo> = emptyMap(),
        lastKnownEvents: Map<String, Int> = emptyMap(),
        eventsIngested: Int = 0,
    ) = RuntimeInstrumentationStateProvider.TargetSlice(live, liveEvents, lastKnown, lastKnownEvents, eventsIngested)

    private fun observation(
        counters: BrowserBridgeManager.FetchCounters = BrowserBridgeManager.FetchCounters(),
        storage: Map<String, BrowserStorageObserver.StorageSnapshot> = emptyMap(),
        connectedSinceMs: Long? = null,
        connectionLostAtMs: Long? = null,
    ) = RuntimeInstrumentationStateProvider.ObservationSlice(
        counters = counters,
        storageByTarget = storage,
        serviceWorkersByTarget = emptyMap(),
        connectedSinceMs = connectedSinceMs,
        connectionLostAtMs = connectionLostAtMs,
    )

    // ── runtime instrumentation ──────────────────────────────────────────────

    @Test
    fun `adapter rows follow engine registration order, not map iteration order`() {
        val state = RuntimeInstrumentationStateProvider.buildRuntimeState(
            engineState = InstrumentationState.ACTIVE,
            adapterStatuses = mapOf(
                "WebView" to "not started",
                "Cronet" to "engine 120.0 · proxy=127.0.0.1 · queued=0",
                "OkHttp" to "requestr outbound · OkHttpProxyInterceptor · queued=0",
            ),
            eventCounts = mapOf("OkHttp" to 1284, "Cronet" to 36),
            capture = WebViewInstrumentation.CaptureStatus(),
            webViewAdapterActive = true,
            webViewStatusText = "loaded via com.google.android.webview 120 · bridge=ready",
            registrationOrder = listOf("OkHttp", "Cronet", "WebView"),
        )
        assertEquals(listOf("OkHttp", "Cronet", "WebView"), state.adapters.map { it.name })
        assertEquals(3, state.registeredAdapterCount)
        assertEquals(2, state.capturingCount)
        assertEquals(1, state.idleCount)
        assertEquals(InstrumentationHealth.HEALTHY, state.health)
        assertEquals(AdapterCaptureState.IDLE, state.adapters.last().captureState)
    }

    @Test
    fun `an adapter the engine never registered is still surfaced from its status map`() {
        val state = RuntimeInstrumentationStateProvider.buildRuntimeState(
            engineState = InstrumentationState.ACTIVE,
            adapterStatuses = mapOf("SdkObserver" to "active"),
            eventCounts = emptyMap(),
            capture = WebViewInstrumentation.CaptureStatus(),
            webViewAdapterActive = false,
            webViewStatusText = "not started",
            registrationOrder = emptyList(),
        )
        assertEquals(listOf("SdkObserver"), state.adapters.map { it.name })
        assertEquals(1, state.registeredAdapterCount)
    }

    @Test
    fun `no WebView capture session reports capturing false with no url or timestamp`() {
        val state = RuntimeInstrumentationStateProvider.buildRuntimeState(
            engineState = InstrumentationState.ACTIVE,
            adapterStatuses = mapOf("WebView" to "not started"),
            eventCounts = emptyMap(),
            capture = WebViewInstrumentation.CaptureStatus(),
            webViewAdapterActive = true,
            webViewStatusText = "bridge=ready",
            registrationOrder = listOf("WebView"),
        )
        val capture = state.webViewCapture
        assertFalse(capture.capturing)
        assertNull(capture.requestedUrl)
        assertNull(capture.startedAtMs)
        assertEquals(0, capture.requestCount)
        assertTrue(capture.recentCaptures.isEmpty())
    }

    @Test
    fun `an active WebView session carries its real url, counters and recent captures`() {
        val state = RuntimeInstrumentationStateProvider.buildRuntimeState(
            engineState = InstrumentationState.ACTIVE,
            adapterStatuses = mapOf("WebView" to "bridge=ready"),
            eventCounts = mapOf("WebView" to 47),
            capture = WebViewInstrumentation.CaptureStatus(
                requestedUrl = "https://dashboard.stripe.com/payments",
                startedAtMs = 1_000L,
                requestCount = 47,
                responseBytes = 1_258_291L,
                recentCaptures = listOf(
                    WebViewInstrumentation.CapturedSummary(
                        method = "GET",
                        url = "https://dashboard.stripe.com/api/v1/payment_intents?limit=10",
                        statusCode = 200,
                        source = WebViewInstrumentation.SOURCE_INTERCEPT,
                        atMs = 1_500L,
                    ),
                ),
            ),
            webViewAdapterActive = true,
            webViewStatusText = "bridge=ready",
            registrationOrder = listOf("WebView"),
        )
        val capture = state.webViewCapture
        assertTrue(capture.capturing)
        assertEquals("https://dashboard.stripe.com/payments", capture.requestedUrl)
        assertEquals(47, capture.requestCount)
        assertEquals(1_258_291L, capture.responseBytes)
        assertEquals("/api/v1/payment_intents?limit=10", capture.recentCaptures.single().path)
        assertEquals(200, capture.recentCaptures.single().statusCode)
    }

    // ── CDP card ─────────────────────────────────────────────────────────────

    @Test
    fun `connected state uses live targets, real counters and no reconnect block`() {
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            connection = connection(CdpClient.State.CONNECTED, status = "Connected: localhost:9222"),
            targetSlice = targets(
                live = mapOf(
                    "S1" to target("S1", "requestr", "https://app.requestr.dev/client"),
                    "S2" to target("S2", "Stripe", "https://dashboard.stripe.com"),
                ),
                liveEvents = mapOf("S1" to 128, "S2" to 312),
                eventsIngested = 440,
            ),
            observation = observation(
                counters = BrowserBridgeManager.FetchCounters(
                    allowed = 400,
                    blocked = 12,
                    modified = 3,
                    responseModified = 4,
                    bodiesCaptured = 6,
                    redirected = 3,
                ),
                connectedSinceMs = 1_000L,
            ),
            endpoint = "localhost:9222",
        )
        assertEquals(CdpConnectionPhase.CONNECTED, state.phase)
        assertFalse(state.targetsAreStale)
        assertNull(state.reconnect)
        // Highest-traffic target first, per the mockup's ordering.
        assertEquals(listOf("S2", "S1"), state.targets.map { it.sessionId })
        assertEquals(440, state.eventsCorrelated)
        assertEquals(12, state.rules.blockedRequestStage)
        assertEquals(3, state.rules.redirectedRequestStage)
        assertEquals(4, state.rules.modifiedResponseStage)
        assertEquals(7, state.rules.modifiedTotal)
    }

    @Test
    fun `reconnecting state falls back to stale last-known targets with retained counts`() {
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            connection = connection(
                CdpClient.State.FAILED,
                attempt = 3,
                retryDelayMs = 4_000L,
                lastError = "Socket closed: 1006 ",
                retryActive = true,
            ),
            targetSlice = targets(
                lastKnown = mapOf("S1" to target("S1", "requestr", "https://app.requestr.dev/client")),
                lastKnownEvents = mapOf("S1" to 128),
                eventsIngested = 513,
            ),
            observation = observation(connectionLostAtMs = 5_000L),
            endpoint = "localhost:9222",
        )
        assertEquals(CdpConnectionPhase.RECONNECTING, state.phase)
        assertTrue(state.targetsAreStale)
        assertEquals(128, state.targets.single().requestCount)
        assertTrue(state.targets.single().stale)
        val reconnect = state.reconnect
        assertNotNull(reconnect)
        assertEquals(3, reconnect!!.attempt)
        assertEquals(CdpClient.MAX_RECONNECT_ATTEMPTS, reconnect.maxAttempts)
        assertEquals(4_000L, reconnect.nextRetryDelayMs)
        // The strip must show the schedule the shipped client really uses.
        assertEquals(CdpClient.backoffScheduleMs(), reconnect.backoffScheduleMs)
        assertEquals(5_000L, reconnect.connectionLostAtMs)
        assertEquals("Socket closed: 1006 ", reconnect.lastError)
    }

    @Test
    fun `targets still reported by a dead socket are labelled stale, never attached`() {
        // Defensive: even if the client has not yet cleared its map, anything outside CONNECTED is
        // last-known data. This is what stops a dropped connection from looking live.
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            connection = connection(
                CdpClient.State.FAILED,
                attempt = 1,
                retryDelayMs = 1_000L,
                retryActive = true,
            ),
            targetSlice = targets(
                live = mapOf("S1" to target("S1", "requestr", "https://app.requestr.dev/client")),
                liveEvents = mapOf("S1" to 64),
            ),
            observation = observation(),
            endpoint = "localhost:9222",
        )
        assertEquals(CdpConnectionPhase.RECONNECTING, state.phase)
        assertTrue(state.targetsAreStale)
        assertTrue(state.targets.single().stale)
        assertEquals(64, state.targets.single().requestCount)
    }

    @Test
    fun `disconnected state shows no targets, no stale flag, no storage section`() {
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            connection = connection(CdpClient.State.DISCONNECTED, status = "Disconnected"),
            targetSlice = targets(
                lastKnown = mapOf("S1" to target("S1", "requestr", "https://app.requestr.dev/client")),
            ),
            observation = observation(),
            endpoint = null,
        )
        assertEquals(CdpConnectionPhase.DISCONNECTED, state.phase)
        assertFalse(state.targetsAreStale)
        assertTrue(state.targets.isEmpty())
        assertNull(state.storage)
        assertNull(state.reconnect)
        assertFalse(state.toggleOn)
    }

    @Test
    fun `an exhausted retry loop reports FAILED with the attempts it actually made`() {
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            // CdpClient clears retryActive and the pending delay on exhaustion, but keeps the count.
            connection = connection(
                CdpClient.State.FAILED,
                attempt = CdpClient.MAX_RECONNECT_ATTEMPTS,
                retryDelayMs = 0L,
                lastError = "Reconnect attempt 6 failed: no CDP browser endpoint",
                retryActive = false,
            ),
            targetSlice = targets(
                lastKnown = mapOf("S1" to target("S1", "requestr", "https://app.requestr.dev/client")),
                lastKnownEvents = mapOf("S1" to 12),
            ),
            observation = observation(connectionLostAtMs = 5_000L),
            endpoint = "localhost:9222",
        )
        assertEquals(CdpConnectionPhase.FAILED, state.phase)
        // No retry is pending, so no backoff panel may be shown.
        assertNull(state.reconnect)
        assertEquals(CdpClient.MAX_RECONNECT_ATTEMPTS, state.reconnectAttemptsMade)
        // A terminal failure is not a live connection, so nothing is presented as attached.
        assertFalse(state.isConnected)
        assertTrue(state.targets.isEmpty())
        assertFalse(state.targetsAreStale)
    }

    @Test
    fun `an initial connection failure reports zero attempts made`() {
        val state = RuntimeInstrumentationStateProvider.buildCdpState(
            connection = connection(
                CdpClient.State.FAILED,
                attempt = 0,
                lastError = "No CDP browser endpoint found",
                retryActive = false,
            ),
            targetSlice = targets(),
            observation = observation(),
            endpoint = "localhost:9222",
        )
        assertEquals(CdpConnectionPhase.FAILED, state.phase)
        assertEquals(0, state.reconnectAttemptsMade)
        assertNull(state.reconnect)
    }

    @Test
    fun `a requested url without a live WebView is not reported as capturing`() {
        val state = RuntimeInstrumentationStateProvider.buildRuntimeState(
            engineState = InstrumentationState.ACTIVE,
            adapterStatuses = mapOf("WebView" to "failed to start: WebView not supported"),
            eventCounts = emptyMap(),
            capture = WebViewInstrumentation.CaptureStatus(
                requestedUrl = "https://example.com",
                startedAtMs = 1_000L,
            ),
            webViewAdapterActive = false,
            webViewStatusText = "failed to start: WebView not supported",
            registrationOrder = listOf("WebView"),
        )
        val capture = state.webViewCapture
        assertFalse(capture.adapterAvailable)
        assertFalse(capture.capturing)
        // The submitted URL is still reported honestly; only the "capturing" claim is withheld.
        assertEquals("https://example.com", capture.requestedUrl)
    }

    @Test
    fun `capture status copy states unavailability instead of idleness when no WebView exists`() {
        val unavailable = com.example.data.models.WebViewCaptureState(
            adapterAvailable = false,
            capturing = false,
            requestedUrl = null,
            startedAtMs = null,
            requestCount = 0,
            responseBytes = 0,
            recentCaptures = emptyList(),
            statusText = "failed to start: WebView not supported",
        )
        val line = com.example.ui.components.statusLine(unavailable)
        assertTrue(line.startsWith("Unavailable"))
        assertTrue(line.contains("nothing can be captured"))
        assertEquals(
            "Idle · no capture session · enter a URL and press Start to capture page fetch/XHR through the proxy",
            com.example.ui.components.statusLine(unavailable.copy(adapterAvailable = true)),
        )
    }

    // ── verdict ordering ─────────────────────────────────────────────────────

    @Test
    fun `verdict rows are ordered by confidence then request count and skip stacks with no verdict`() {
        val detected = StackFingerprinter.StackVerdict(
            HttpStack.OKHTTP,
            StackFingerprinter.Confidence.DETECTED,
            "ja3 matched",
            listOf(StackFingerprinter.StackSignal("ja3", "matched calibrated OKHTTP")),
        )
        val possible = StackFingerprinter.StackVerdict(
            HttpStack.HTTP_URL_CONNECTION,
            StackFingerprinter.Confidence.POSSIBLE,
            "ua only",
            listOf(StackFingerprinter.StackSignal("user-agent", "matched HttpURLConnection default format")),
        )
        val rows = RuntimeInstrumentationStateProvider.toVerdictRows(
            countsByStack = linkedMapOf(
                HttpStack.HTTP_URL_CONNECTION to 90,
                HttpStack.OKHTTP to 10,
                // A stack counted but with no retained verdict must be skipped, never invented.
                HttpStack.VOLLEY to 5,
            ),
            representativeVerdicts = mapOf(
                HttpStack.OKHTTP to detected,
                HttpStack.HTTP_URL_CONNECTION to possible,
            ),
        )
        assertEquals(listOf(HttpStack.OKHTTP, HttpStack.HTTP_URL_CONNECTION), rows.map { it.stack })
        assertEquals(10, rows.first().requestCount)
    }
}
