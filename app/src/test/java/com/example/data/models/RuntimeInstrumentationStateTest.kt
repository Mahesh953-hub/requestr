package com.example.data.models

import com.example.browser.BrowserStorageObserver
import com.example.browser.CdpClient
import com.example.browser.ServiceWorkerObserver
import com.example.runtime.InstrumentationState
import com.example.runtime.http.HttpStack
import com.example.runtime.http.StackFingerprinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.1 Phase A / Domain B: unit tests for the runtime-instrumentation / CDP UI state derivations
 * that drive [com.example.ui.components.RuntimeInstrumentationCard] and
 * [com.example.ui.components.CdpCard].
 *
 * Plain JVM — every function under test is pure over real engine types, with no Compose, Android, or
 * live socket involved. The assertions deliberately pin the *honesty* rules as much as the mapping:
 * an UNKNOWN verdict must not carry a percentage, an absent signal family must be labelled NOT
 * CAPTURED with a reason, and a dropped CDP connection must surface last-known targets as stale
 * rather than as attached.
 */
class RuntimeInstrumentationStateTest {

    // ── adapter rows / card health ────────────────────────────────────────────

    @Test
    fun `adapter with drained events is CAPTURING`() {
        val row = toAdapterRow("OkHttp", "requestr outbound · OkHttpProxyInterceptor · queued=0", 1284)
        assertEquals(AdapterCaptureState.CAPTURING, row.captureState)
        assertEquals(1284, row.eventCount)
        assertNull(row.unavailableReason)
    }

    @Test
    fun `adapter reporting a failure is UNAVAILABLE and keeps its own reason text`() {
        val reason = "failed to start: org.chromium.net.CronetEngine not on classpath"
        val row = toAdapterRow("Cronet", reason, 0)
        assertEquals(AdapterCaptureState.UNAVAILABLE, row.captureState)
        assertEquals(reason, row.unavailableReason)
    }

    @Test
    fun `registered adapter with no events yet is IDLE`() {
        val row = toAdapterRow("WebView", "not started", 0)
        assertEquals(AdapterCaptureState.IDLE, row.captureState)
        assertNull(row.unavailableReason)
    }

    @Test
    fun `active engine with one unavailable adapter is DEGRADED`() {
        val rows = listOf(
            toAdapterRow("OkHttp", "active", 12),
            toAdapterRow("Cronet", "failed to start: no provider", 0),
            toAdapterRow("WebView", "not started", 0),
        )
        assertEquals(
            InstrumentationHealth.DEGRADED,
            instrumentationHealthOf(InstrumentationState.ACTIVE, rows),
        )
    }

    @Test
    fun `active engine with no unavailable adapter is HEALTHY`() {
        val rows = listOf(
            toAdapterRow("OkHttp", "active", 12),
            toAdapterRow("WebView", "not started", 0),
        )
        assertEquals(
            InstrumentationHealth.HEALTHY,
            instrumentationHealthOf(InstrumentationState.ACTIVE, rows),
        )
    }

    @Test
    fun `engine lifecycle states map to FAILED and INACTIVE regardless of adapters`() {
        val rows = listOf(toAdapterRow("OkHttp", "active", 5))
        assertEquals(InstrumentationHealth.FAILED, instrumentationHealthOf(InstrumentationState.FAILED, rows))
        assertEquals(InstrumentationHealth.INACTIVE, instrumentationHealthOf(InstrumentationState.STOPPED, rows))
        assertEquals(InstrumentationHealth.INACTIVE, instrumentationHealthOf(InstrumentationState.NOT_STARTED, rows))
    }

    // ── fingerprint verdicts ─────────────────────────────────────────────────

    private fun verdict(
        stack: HttpStack,
        confidence: StackFingerprinter.Confidence,
        vararg signals: Pair<String, String>,
        reason: String = "reason",
    ) = StackFingerprinter.StackVerdict(
        stack = stack,
        confidence = confidence,
        reason = reason,
        signals = signals.map { StackFingerprinter.StackSignal(it.first, it.second) },
    )

    @Test
    fun `tally shows CACHE as the fifth chip when the cache signal fired`() {
        val row = toVerdictRow(
            HttpStack.OKHTTP,
            verdict(
                HttpStack.OKHTTP,
                StackFingerprinter.Confidence.DETECTED,
                "ja3" to "matched calibrated OKHTTP (abc)",
                "alpn" to "offered h2,http/1.1 (h2)",
                "h2-settings" to "OkHttp default multiset",
                "header-order" to "matched calibrated OKHTTP",
                "cache-validation" to "Volley-shaped cache validators present",
            ),
            requestCount = 312,
        )
        assertEquals(
            listOf(
                FingerprintSignalFamily.JA3,
                FingerprintSignalFamily.ALPN,
                FingerprintSignalFamily.H2,
                FingerprintSignalFamily.HDR,
                FingerprintSignalFamily.CACHE,
            ),
            row.tally.map { it.family },
        )
        assertTrue(row.tally.all { it.present })
        assertEquals(100, row.signalMatchPercent)
        assertEquals(312, row.requestCount)
    }

    @Test
    fun `tally substitutes UA for CACHE when only the User-Agent signal fired`() {
        val row = toVerdictRow(
            HttpStack.HTTP_URL_CONNECTION,
            verdict(
                HttpStack.HTTP_URL_CONNECTION,
                StackFingerprinter.Confidence.POSSIBLE,
                "header-order" to "matched calibrated HTTP_URL_CONNECTION",
                "user-agent" to "matched HttpURLConnection default format",
            ),
            requestCount = 9,
        )
        assertEquals(
            listOf(
                FingerprintSignalFamily.JA3,
                FingerprintSignalFamily.ALPN,
                FingerprintSignalFamily.H2,
                FingerprintSignalFamily.HDR,
                FingerprintSignalFamily.UA,
            ),
            row.tally.map { it.family },
        )
        assertEquals(listOf(false, false, false, true, true), row.tally.map { it.present })
        assertEquals(40, row.signalMatchPercent)
    }

    @Test
    fun `UNKNOWN verdict carries no percentage, no tally and no fabricated evidence`() {
        val row = toVerdictRow(
            HttpStack.KTOR,
            verdict(
                HttpStack.KTOR,
                StackFingerprinter.Confidence.UNKNOWN,
                reason = "zero discriminating signals collected (no TLS row / JA3; User-Agent missing or custom)",
            ),
            requestCount = 4,
        )
        assertNull(row.signalMatchPercent)
        assertTrue(row.tally.isEmpty())
        assertTrue(row.evidence.isEmpty())
        assertTrue(row.reason.contains("zero discriminating signals"))
    }

    @Test
    fun `evidence marks captured signals MATCH or PARTIAL and absent families NOT CAPTURED`() {
        val evidence = evidenceFor(
            verdict(
                HttpStack.OKHTTP,
                StackFingerprinter.Confidence.LIKELY,
                "ja3" to "matched calibrated OKHTTP (e7d7c3e8)",
                "h2-settings" to "non-OkHttp multiset [(4, 100)]",
            ),
        ).associateBy { it.family }

        assertEquals(EvidenceVerdict.MATCH, evidence[FingerprintSignalFamily.JA3]?.verdict)
        assertEquals(EvidenceVerdict.PARTIAL, evidence[FingerprintSignalFamily.H2]?.verdict)
        assertEquals(EvidenceVerdict.ABSENT, evidence[FingerprintSignalFamily.ALPN]?.verdict)
        assertEquals(EvidenceVerdict.ABSENT, evidence[FingerprintSignalFamily.HDR]?.verdict)
        // Absent families must explain themselves rather than render a blank/placeholder value.
        assertNull(evidence[FingerprintSignalFamily.ALPN]?.detail)
        assertTrue(evidence[FingerprintSignalFamily.ALPN]?.note?.isNotBlank() == true)
        // The raw JA3 ClientHello string is genuinely not retained -- that must be stated, not faked.
        assertTrue(
            evidence[FingerprintSignalFamily.JA3]?.unavailableDetail?.contains("not retained") == true,
        )
    }

    @Test
    fun `a runtime-adapter observation adds its own evidence section`() {
        val evidence = evidenceFor(
            verdict(
                HttpStack.CRONET,
                StackFingerprinter.Confidence.DETECTED,
                "runtime-adapter" to "observed directly by the Cronet runtime adapter",
            ),
        ).associateBy { it.family }
        assertEquals(EvidenceVerdict.MATCH, evidence[FingerprintSignalFamily.ADAPTER]?.verdict)
    }

    // ── CDP phase ────────────────────────────────────────────────────────────

    @Test
    fun `phase is RECONNECTING only while a retry loop is actually running`() {
        assertEquals(CdpConnectionPhase.FAILED, toCdpPhase(CdpClient.State.FAILED, retryActive = false))
        assertEquals(CdpConnectionPhase.RECONNECTING, toCdpPhase(CdpClient.State.FAILED, retryActive = true))
        assertEquals(CdpConnectionPhase.CONNECTING, toCdpPhase(CdpClient.State.CONNECTING, retryActive = false))
        assertEquals(CdpConnectionPhase.RECONNECTING, toCdpPhase(CdpClient.State.CONNECTING, retryActive = true))
        assertEquals(CdpConnectionPhase.CONNECTED, toCdpPhase(CdpClient.State.CONNECTED, retryActive = false))
        assertEquals(CdpConnectionPhase.DISCONNECTED, toCdpPhase(CdpClient.State.DISCONNECTED, retryActive = false))
    }

    @Test
    fun `an exhausted retry loop is FAILED even though attempts were made`() {
        // The attempt counter is retained after exhaustion for reporting, so it must not be what keeps
        // the card in RECONNECTING -- only a live retry loop may do that.
        assertEquals(CdpConnectionPhase.FAILED, toCdpPhase(CdpClient.State.FAILED, retryActive = false))
    }

    @Test
    fun `aggregated localStorage changes are globally bounded across targets`() {
        fun snapshotWith(prefix: String, count: Int, baseMs: Long) =
            BrowserStorageObserver.StorageSnapshot(
                recentEvents = (0 until count).map { i ->
                    BrowserStorageObserver.DomStorageEvent(
                        key = "$prefix$i",
                        kind = BrowserStorageObserver.DomStorageEventKind.ADDED,
                        oldValue = null,
                        newValue = "v",
                        atMs = baseMs + i,
                    )
                },
            )
        val perTargetCap = BrowserStorageObserver.MAX_RETAINED_EVENTS
        val state = toCdpStorageState(
            mapOf(
                "S1" to snapshotWith("a", perTargetCap, 1_000L),
                "S2" to snapshotWith("b", perTargetCap, 5_000L),
                "S3" to snapshotWith("c", perTargetCap, 9_000L),
            ),
        )!!
        assertEquals(MAX_AGGREGATED_LOCAL_STORAGE_CHANGES, state.localStorageChanges.size)
        // Newest-first ordering must survive the cap, so the retained window is the most recent one.
        assertTrue(state.localStorageChanges.all { it.key?.startsWith("c") == true })
        assertEquals(
            state.localStorageChanges.map { it.atMs }.sortedDescending(),
            state.localStorageChanges.map { it.atMs },
        )
    }

    // ── storage aggregation ──────────────────────────────────────────────────

    private fun cookie(name: String, domain: String) = BrowserStorageObserver.CookieInfo(
        name = name,
        value = "v",
        domain = domain,
        path = "/",
        httpOnly = false,
        secure = true,
        sameSite = null,
    )

    @Test
    fun `no attached target yields a null storage section rather than zeroes`() {
        assertNull(toCdpStorageState(emptyMap()))
    }

    @Test
    fun `storage aggregates cookies, keys, and IndexedDB records across every target`() {
        val first = BrowserStorageObserver.StorageSnapshot(
            cookies = listOf(cookie("a", "example.com"), cookie("b", "example.com")),
            localStorage = mapOf("k1" to "v1", "k2" to "v2"),
            indexedDbDatabases = listOf("requestr-db"),
            indexedDbRecords = mapOf(
                "requestr-db/sessions" to List(100) { "k$it: v$it" },
                "requestr-db/rules" to List(4) { "k$it: v$it" },
            ),
            indexedDbHasMore = mapOf("requestr-db/sessions" to true),
            lastRefreshed = 1_000L,
        )
        val second = BrowserStorageObserver.StorageSnapshot(
            cookies = listOf(cookie("c", "stripe.com")),
            localStorage = mapOf("k3" to "v3"),
            indexedDbDatabases = listOf("n8n-config"),
            indexedDbRecords = mapOf("n8n-config/workflows" to List(7) { "k$it: v$it" }),
            lastRefreshed = 2_000L,
        )

        val state = toCdpStorageState(mapOf("S1" to first, "S2" to second))!!
        assertEquals(3, state.cookieCount)
        assertEquals(2, state.cookieDomainCount)
        assertEquals(3, state.localStorageKeyCount)
        assertEquals(2, state.indexedDbDatabases.size)
        assertEquals(111, state.indexedDbRecordCount)
        assertEquals(2_000L, state.lastRefreshedAtMs)
        // A store that reported hasMore must be labelled partial, not presented as a total.
        assertTrue(state.indexedDbTruncated)
        val requestrDb = state.indexedDbDatabases.first { it.databaseName == "requestr-db" }
        assertEquals(2, requestrDb.objectStoreCount)
        assertEquals(104, requestrDb.recordCount)
        assertTrue(requestrDb.truncated)
    }

    @Test
    fun `a database listed but never read back keeps an honest zero row`() {
        val snapshot = BrowserStorageObserver.StorageSnapshot(
            indexedDbDatabases = listOf("empty-db"),
        )
        val state = toCdpStorageState(mapOf("S1" to snapshot))!!
        val db = state.indexedDbDatabases.single()
        assertEquals("empty-db", db.databaseName)
        assertEquals(0, db.objectStoreCount)
        assertEquals(0, db.recordCount)
    }

    @Test
    fun `localStorage change events map by kind and sort newest first`() {
        val snapshot = BrowserStorageObserver.StorageSnapshot(
            recentEvents = listOf(
                BrowserStorageObserver.DomStorageEvent("auth.token", BrowserStorageObserver.DomStorageEventKind.ADDED, null, "abc", atMs = 200L),
                BrowserStorageObserver.DomStorageEvent("cart.count", BrowserStorageObserver.DomStorageEventKind.UPDATED, "3", "4", atMs = 100L),
                BrowserStorageObserver.DomStorageEvent(null, BrowserStorageObserver.DomStorageEventKind.CLEARED, null, null, atMs = 50L),
            ),
        )
        val changes = toCdpStorageState(mapOf("S1" to snapshot))!!.localStorageChanges
        assertEquals(listOf(200L, 100L, 50L), changes.map { it.atMs })
        assertEquals(LocalStorageChangeKind.SET, changes[0].kind)
        assertEquals(LocalStorageChangeKind.UPDATED, changes[1].kind)
        assertEquals("3", changes[1].oldValue)
        assertEquals("4", changes[1].newValue)
        assertEquals(LocalStorageChangeKind.CLEARED, changes[2].kind)
        assertNull(changes[2].key)
    }

    // ── targets and service workers ──────────────────────────────────────────

    @Test
    fun `target rows carry the real per-target correlated count and stale flag`() {
        val targets = mapOf(
            "S1" to CdpClient.TargetInfo("S1", "T1", "page", "requestr", "https://app.requestr.dev/client"),
            "S2" to CdpClient.TargetInfo("S2", "T2", "page", "Stripe", "https://dashboard.stripe.com"),
        )
        val rows = toTargetRows(targets, mapOf("S1" to 128), stale = true).associateBy { it.sessionId }
        assertEquals(128, rows["S1"]?.requestCount)
        assertEquals(0, rows["S2"]?.requestCount)
        assertTrue(rows.values.all { it.stale })
    }

    @Test
    fun `service workers are flattened across targets and de-duplicated by script and scope`() {
        val worker = ServiceWorkerObserver.WorkerRegistration(
            registrationId = "R1",
            scopeUrl = "https://app.requestr.dev/",
            scriptUrl = "https://app.requestr.dev/sw.js",
            status = "activated",
        )
        val rows = toServiceWorkerRows(
            mapOf(
                "S1" to mapOf("R1" to worker),
                "S2" to mapOf("R9" to worker.copy(registrationId = "R9")),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("activated", rows.single().status)
    }
}
