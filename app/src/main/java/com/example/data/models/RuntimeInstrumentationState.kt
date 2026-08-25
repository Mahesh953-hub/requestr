package com.example.data.models

import com.example.browser.BrowserStorageObserver
import com.example.browser.CdpClient
import com.example.browser.ServiceWorkerObserver
import com.example.runtime.InstrumentationState
import com.example.runtime.http.HttpStack
import com.example.runtime.http.StackFingerprinter

/**
 * v6.1 Phase A / Domain B: the authoritative UI state contract for the Settings
 * "RUNTIME INSTRUMENTATION" and "BROWSER BRIDGE (CDP)" cards, matching the approved
 * `runtime-instrumentation-*.html` / `runtime-cdp-*.html` mockups.
 *
 * Every field here is either a real value read off a live engine
 * ([com.example.runtime.RuntimeInstrumentationEngine], [com.example.browser.BrowserBridgeManager],
 * [com.example.runtime.webview.WebViewInstrumentation], [StackFingerprinter]) or an explicit
 * `null`/empty with an accompanying honest reason string. Nothing in this file invents a value the
 * backend cannot actually observe: where the mockups show a figure requestr does not measure (JA3
 * raw ClientHello string, IndexedDB schema version, per-request CDP activity replay), the model
 * carries the absence plus the explanation the UI renders instead of a plausible-looking sample.
 *
 * The derivations live here as pure functions ([toAdapterRow], [instrumentationHealthOf],
 * [toCdpPhase], [toVerdictRow], [toCdpStorageState], …) so they are unit-testable without Compose
 * or Android, exactly like [RelayHealthState]/[Http3DetailState] in this package. UI composables
 * never recompute state locally — they render what
 * [com.example.runtime.RuntimeInstrumentationStateProvider] hands them.
 */

// ─── Runtime instrumentation (adapters + offscreen WebView) ────────────────────

/** Per-adapter capture state (mockup `.pill` + `.vrow` left rail colour). */
enum class AdapterCaptureState(val label: String, val color: Long) {
    CAPTURING("CAPTURING", 0xFF10B981),
    IDLE("IDLE", 0xFF64748B),
    UNAVAILABLE("UNAVAILABLE", 0xFFEF4444),
}

/** Card-level health pill: HEALTHY / DEGRADED / FAILED / INACTIVE. */
enum class InstrumentationHealth(val label: String, val color: Long) {
    HEALTHY("HEALTHY", 0xFF10B981),
    DEGRADED("DEGRADED", 0xFFF59E0B),
    FAILED("FAILED", 0xFFEF4444),
    INACTIVE("INACTIVE", 0xFF64748B),
}

/**
 * One adapter row. [statusText] is the adapter's own one-line
 * [com.example.runtime.InstrumentationAdapter.status] string, rendered verbatim;
 * [unavailableReason] is only non-null when the adapter genuinely reported a failure, and is the
 * same real text (never a canned explanation).
 */
data class AdapterRowState(
    val name: String,
    val captureState: AdapterCaptureState,
    val eventCount: Int,
    val statusText: String,
    val unavailableReason: String?,
)

/**
 * One request the offscreen WebView genuinely captured, for the mockup's "RECENT CAPTURES" peek.
 * [path] is derived from the captured URL; [statusCode] is null for a failed/exception event.
 */
data class WebViewCapturedRequest(
    val method: String?,
    val path: String,
    val statusCode: Int?,
    val source: String,
    val atMs: Long,
)

/**
 * Offscreen WebView capture control state. [requestedUrl] is the URL the user actually submitted
 * (null when no capture session was ever started this process), [startedAtMs] the real submit
 * timestamp, and [responseBytes] the summed real response sizes — no synthetic totals.
 */
data class WebViewCaptureState(
    val adapterAvailable: Boolean,
    val capturing: Boolean,
    val requestedUrl: String?,
    val startedAtMs: Long?,
    val requestCount: Int,
    val responseBytes: Long,
    val recentCaptures: List<WebViewCapturedRequest>,
    val statusText: String,
)

/** Whole-card state for `RuntimeInstrumentationCard`. */
data class RuntimeInstrumentationState(
    val engineState: InstrumentationState,
    val health: InstrumentationHealth,
    val registeredAdapterCount: Int,
    val adapters: List<AdapterRowState>,
    val webViewCapture: WebViewCaptureState,
) {
    val capturingCount: Int get() = adapters.count { it.captureState == AdapterCaptureState.CAPTURING }
    val idleCount: Int get() = adapters.count { it.captureState == AdapterCaptureState.IDLE }
    val unavailableCount: Int get() = adapters.count { it.captureState == AdapterCaptureState.UNAVAILABLE }
}

/**
 * Derives an adapter row honestly from the engine's real per-adapter status string and drained
 * event count. An adapter that has drained events is CAPTURING; one whose own status text reports
 * a failure is UNAVAILABLE (and that text becomes [AdapterRowState.unavailableReason]); anything
 * else — registered, started, nothing captured yet — is IDLE. This is the same rule the previous
 * inline Settings block used, lifted out so it is testable and shared.
 */
fun toAdapterRow(name: String, statusText: String, eventCount: Int): AdapterRowState {
    val failed = statusText.contains("failed", ignoreCase = true) ||
        statusText.contains("not on classpath", ignoreCase = true)
    val state = when {
        eventCount > 0 -> AdapterCaptureState.CAPTURING
        failed -> AdapterCaptureState.UNAVAILABLE
        else -> AdapterCaptureState.IDLE
    }
    return AdapterRowState(
        name = name,
        captureState = state,
        eventCount = eventCount,
        statusText = statusText,
        unavailableReason = statusText.takeIf { state == AdapterCaptureState.UNAVAILABLE },
    )
}

/**
 * Card-level health: FAILED/INACTIVE follow the engine's own lifecycle state; an ACTIVE engine is
 * DEGRADED as soon as any single adapter is UNAVAILABLE (the `cronet-unavailable` mockup's state)
 * and HEALTHY only when none is.
 */
fun instrumentationHealthOf(
    engineState: InstrumentationState,
    adapters: List<AdapterRowState>,
): InstrumentationHealth = when (engineState) {
    InstrumentationState.FAILED -> InstrumentationHealth.FAILED
    InstrumentationState.NOT_STARTED, InstrumentationState.STOPPED -> InstrumentationHealth.INACTIVE
    InstrumentationState.INITIALIZING -> InstrumentationHealth.DEGRADED
    InstrumentationState.ACTIVE ->
        if (adapters.any { it.captureState == AdapterCaptureState.UNAVAILABLE }) {
            InstrumentationHealth.DEGRADED
        } else {
            InstrumentationHealth.HEALTHY
        }
}

// ─── HTTP stack fingerprinting ─────────────────────────────────────────────────

/**
 * The five signal families the mockup tallies as ✓/✗ per verdict row. `UA` is the mockup's
 * substitute slot: a row whose cache-validation signal is absent but whose User-Agent signal fired
 * shows `UA` in place of `CACHE` (see [signalTallyFor]).
 */
enum class FingerprintSignalFamily(val shortLabel: String, val title: String) {
    JA3("JA3", "JA3 TLS FINGERPRINT"),
    ALPN("ALPN", "ALPN OFFERS"),
    H2("H2", "HTTP/2 SETTINGS"),
    HDR("HDR", "HEADER ORDERING (HPACK)"),
    CACHE("CACHE", "CACHE-VALIDATION SHAPE"),
    UA("UA", "USER-AGENT"),
    ADAPTER("ADAPTER", "RUNTIME ADAPTER"),
}

/** One ✓/✗ chip in a verdict row's per-signal tally. */
data class FingerprintSignalTally(val family: FingerprintSignalFamily, val present: Boolean)

/** Per-signal evidence verdict pill in the drill-in panel. */
enum class EvidenceVerdict(val label: String, val color: Long) {
    MATCH("MATCH", 0xFF10B981),
    PARTIAL("PARTIAL", 0xFFF59E0B),
    ABSENT("NOT CAPTURED", 0xFF64748B),
}

/**
 * One expandable evidence section. [detail] is the fingerprinter's own real evidence string for
 * that signal; [note] explains the verdict (for [EvidenceVerdict.ABSENT] it explains what would
 * have had to be captured). [unavailableDetail] carries the honest "requestr does not retain this"
 * note for values the mockup shows but the backend never stores (e.g. the raw JA3 ClientHello
 * string — only its hash survives [com.example.runtime.http.StackSignalStore]).
 */
data class FingerprintSignalEvidence(
    val family: FingerprintSignalFamily,
    val verdict: EvidenceVerdict,
    val detail: String?,
    val note: String,
    val unavailableDetail: String? = null,
)

/**
 * One candidate-stack verdict row. [signalMatchPercent] is deliberately NOT a probability: it is
 * the measured share of the five displayed signal families that fired for this candidate, which is
 * a real quantity derived from real evidence. It is null for an UNKNOWN verdict, where the mockup
 * shows "—" and the row falls back to [reason] (the fingerprinter's own explanation of which
 * signals were missing) instead of a number.
 */
data class StackVerdictRow(
    val stack: HttpStack,
    val confidence: StackFingerprinter.Confidence,
    val signalMatchPercent: Int?,
    val requestCount: Int,
    val tally: List<FingerprintSignalTally>,
    val reason: String,
    val evidence: List<FingerprintSignalEvidence>,
)

/**
 * Fingerprint panel state. [unavailableReason] is non-null exactly when there is nothing real to
 * classify yet (no capture session, session still loading, zero captured requests) — the panel then
 * renders that sentence instead of an empty verdict list.
 */
data class StackFingerprintState(
    val sessionId: Long?,
    val requestsFingerprinted: Int,
    val calibrated: Boolean,
    val verdicts: List<StackVerdictRow>,
    val unavailableReason: String?,
) {
    companion object {
        /** Honest placeholder for "we have not classified anything yet", never a sample verdict. */
        fun unavailable(reason: String, sessionId: Long? = null) = StackFingerprintState(
            sessionId = sessionId,
            requestsFingerprinted = 0,
            calibrated = false,
            verdicts = emptyList(),
            unavailableReason = reason,
        )
    }
}

/** Maps a [StackFingerprinter.StackSignal.name] onto its display family. */
fun signalFamilyOf(signalName: String): FingerprintSignalFamily? = when (signalName) {
    "ja3" -> FingerprintSignalFamily.JA3
    "alpn" -> FingerprintSignalFamily.ALPN
    "h2-settings" -> FingerprintSignalFamily.H2
    "header-order", "pseudo-header-order" -> FingerprintSignalFamily.HDR
    "cache-validation" -> FingerprintSignalFamily.CACHE
    "user-agent" -> FingerprintSignalFamily.UA
    "runtime-adapter" -> FingerprintSignalFamily.ADAPTER
    else -> null
}

/**
 * The mockup's five-chip tally. Rule (deterministic, applied identically to every row): the four
 * wire-level families JA3/ALPN/H2/HDR always occupy the first four chips; the fifth is `CACHE`
 * unless the cache-validation signal is absent *and* the User-Agent signal fired, in which case the
 * fifth chip is `UA`. This reproduces every tally line in the approved mockups without inventing a
 * chip that has no backing signal.
 */
fun signalTallyFor(verdict: StackFingerprinter.StackVerdict): List<FingerprintSignalTally> {
    val present = verdict.signals.mapNotNull { signalFamilyOf(it.name) }.toSet()
    val fifth = if (FingerprintSignalFamily.CACHE !in present && FingerprintSignalFamily.UA in present) {
        FingerprintSignalFamily.UA
    } else {
        FingerprintSignalFamily.CACHE
    }
    val families = listOf(
        FingerprintSignalFamily.JA3,
        FingerprintSignalFamily.ALPN,
        FingerprintSignalFamily.H2,
        FingerprintSignalFamily.HDR,
        fifth,
    )
    return families.map { FingerprintSignalTally(it, it in present) }
}

/**
 * Classifies one real signal detail string into an evidence verdict. A signal that matched the
 * device's own calibration table (or a library's documented default) is a MATCH; a signal that was
 * captured but is not discriminating on its own is PARTIAL. Absence is never PARTIAL — a family with
 * no signal at all becomes [EvidenceVerdict.ABSENT] in [evidenceFor].
 */
fun evidenceVerdictFor(detail: String): EvidenceVerdict = when {
    detail.contains("matched", ignoreCase = true) -> EvidenceVerdict.MATCH
    detail.contains("default", ignoreCase = true) -> EvidenceVerdict.MATCH
    detail.contains("Cronet-only", ignoreCase = true) -> EvidenceVerdict.MATCH
    detail.contains("observed directly", ignoreCase = true) -> EvidenceVerdict.MATCH
    else -> EvidenceVerdict.PARTIAL
}

/** Why a signal family could not be captured — shown verbatim under an ABSENT evidence pill. */
private fun absenceNoteFor(family: FingerprintSignalFamily): String = when (family) {
    FingerprintSignalFamily.JA3 ->
        "No TLS ClientHello was captured for this connection, so no JA3 hash exists to compare."
    FingerprintSignalFamily.ALPN ->
        "No TLS ClientHello was captured, so the ALPN offer list is unknown."
    FingerprintSignalFamily.H2 ->
        "No client HTTP/2 SETTINGS frame was observed (plaintext HTTP/1.x, or the connection predates this process)."
    FingerprintSignalFamily.HDR ->
        "Request header order/casing was not sampled live; Room does not preserve it, so it cannot be recovered."
    FingerprintSignalFamily.CACHE ->
        "No cache-validation headers or default body content-type were present on the sampled request."
    FingerprintSignalFamily.UA ->
        "The User-Agent was missing or custom, so it matches no library's default format."
    FingerprintSignalFamily.ADAPTER ->
        "This row did not come out of a runtime adapter, so there is no direct observation to cite."
}

/**
 * Extra honesty for values the mockup renders but requestr genuinely does not retain, so the
 * evidence panel can say so in place of the missing field rather than omitting it silently.
 */
private fun unavailableDetailFor(family: FingerprintSignalFamily): String? = when (family) {
    FingerprintSignalFamily.JA3 ->
        "raw ClientHello JA3 string not retained — StackSignalStore keeps only the MD5 hash"
    else -> null
}

/**
 * Builds the drill-in evidence list for one verdict: a section per signal family the mockup shows,
 * each carrying the fingerprinter's real detail string, or an ABSENT pill plus the concrete reason
 * that family produced nothing.
 */
fun evidenceFor(verdict: StackFingerprinter.StackVerdict): List<FingerprintSignalEvidence> {
    val byFamily = verdict.signals.mapNotNull { signal ->
        signalFamilyOf(signal.name)?.let { it to signal.detail }
    }.toMap()
    val families = buildList {
        add(FingerprintSignalFamily.JA3)
        add(FingerprintSignalFamily.ALPN)
        add(FingerprintSignalFamily.H2)
        add(FingerprintSignalFamily.HDR)
        add(FingerprintSignalFamily.CACHE)
        add(FingerprintSignalFamily.UA)
        if (FingerprintSignalFamily.ADAPTER in byFamily) add(FingerprintSignalFamily.ADAPTER)
    }
    return families.map { family ->
        val detail = byFamily[family]
        if (detail == null) {
            FingerprintSignalEvidence(
                family = family,
                verdict = EvidenceVerdict.ABSENT,
                detail = null,
                note = absenceNoteFor(family),
                unavailableDetail = null,
            )
        } else {
            FingerprintSignalEvidence(
                family = family,
                verdict = evidenceVerdictFor(detail),
                detail = detail,
                note = detail,
                unavailableDetail = unavailableDetailFor(family),
            )
        }
    }
}

/**
 * Maps one real [StackFingerprinter.StackVerdict] (plus how many requests in the session resolved
 * to that stack) into a UI verdict row. An UNKNOWN verdict carries no percentage and no tally — the
 * mockup's "—" plus the fingerprinter's own missing-signal reason.
 */
fun toVerdictRow(
    stack: HttpStack,
    verdict: StackFingerprinter.StackVerdict,
    requestCount: Int,
): StackVerdictRow {
    val unknown = verdict.confidence == StackFingerprinter.Confidence.UNKNOWN
    val tally = if (unknown) emptyList() else signalTallyFor(verdict)
    val percent = if (unknown || tally.isEmpty()) {
        null
    } else {
        (tally.count { it.present } * 100) / tally.size
    }
    return StackVerdictRow(
        stack = stack,
        confidence = verdict.confidence,
        signalMatchPercent = percent,
        requestCount = requestCount,
        tally = tally,
        reason = verdict.reason,
        evidence = if (unknown) emptyList() else evidenceFor(verdict),
    )
}

/** Status-vocabulary colour for a confidence level (mockup green/amber/gray). */
fun confidenceColor(confidence: StackFingerprinter.Confidence): Long = when (confidence) {
    StackFingerprinter.Confidence.DETECTED -> 0xFF10B981
    StackFingerprinter.Confidence.LIKELY -> 0xFFF59E0B
    StackFingerprinter.Confidence.POSSIBLE -> 0xFFF59E0B
    StackFingerprinter.Confidence.UNKNOWN -> 0xFF64748B
}

// ─── Browser Bridge (CDP) ──────────────────────────────────────────────────────

/**
 * CDP card phase. RECONNECTING is a genuinely distinct state, not a relabelled FAILED: it is only
 * reported when [com.example.browser.CdpClient]'s retry loop is actually running (a non-zero
 * reconnect attempt) while the socket is down or being re-opened.
 */
enum class CdpConnectionPhase(val label: String, val color: Long) {
    DISCONNECTED("DISCONNECTED", 0xFF64748B),
    CONNECTING("CONNECTING", 0xFFF59E0B),
    CONNECTED("CONNECTED", 0xFF10B981),
    RECONNECTING("RECONNECTING", 0xFFF59E0B),
    FAILED("FAILED", 0xFFEF4444),
}

/** One attached (or last-known, when [stale]) CDP target. */
data class CdpTargetRow(
    val sessionId: String,
    val targetId: String,
    val type: String,
    val title: String,
    val url: String,
    val requestCount: Int,
    val stale: Boolean,
)

/**
 * Fetch rule-enforcement counters, split by the stage the enforcement actually happened at — the
 * mockup's three tiles. [modifiedResponseStage] is the response-stage subset shown as the Modified
 * tile's sub-line, so "7 · 4 response-stage" is two real counters, not one number split by guess.
 */
data class CdpRuleEnforcementState(
    val allowed: Int,
    val blockedRequestStage: Int,
    val modifiedRequestStage: Int,
    val modifiedResponseStage: Int,
    val redirectedRequestStage: Int,
    val bodiesCaptured: Int,
) {
    val modifiedTotal: Int get() = modifiedRequestStage + modifiedResponseStage
}

/**
 * Global cap on the merged, cross-target localStorage change stream shown on the CDP card. Each
 * [BrowserStorageObserver] already bounds its own retained events; this bounds their aggregate.
 */
const val MAX_AGGREGATED_LOCAL_STORAGE_CHANGES = 20

/** Kind of a live `DOMStorage` change event. */
enum class LocalStorageChangeKind { SET, UPDATED, REMOVED, CLEARED }

/** One bounded, real localStorage change event (`DOMStorage.domStorageItem*`). */
data class LocalStorageChange(
    val key: String?,
    val kind: LocalStorageChangeKind,
    val oldValue: String?,
    val newValue: String?,
    val atMs: Long,
)

/**
 * One IndexedDB database summary. [recordCount] is the real number of records read back via
 * `IndexedDB.requestData`, and [truncated] is true when any of its object stores reported
 * `hasMore` — so a partial read is labelled as such instead of presented as a total. Schema
 * version is deliberately absent: the observer never requests it.
 */
data class IndexedDbSummary(
    val databaseName: String,
    val objectStoreCount: Int,
    val recordCount: Int,
    val truncated: Boolean,
)

/** Aggregated storage state across every attached target. */
data class CdpStorageState(
    val cookieCount: Int,
    val cookieDomainCount: Int,
    val localStorageKeyCount: Int,
    val localStorageChanges: List<LocalStorageChange>,
    val indexedDbDatabases: List<IndexedDbSummary>,
    val lastRefreshedAtMs: Long?,
    val lastError: String?,
) {
    val indexedDbRecordCount: Int get() = indexedDbDatabases.sumOf { it.recordCount }
    val indexedDbTruncated: Boolean get() = indexedDbDatabases.any { it.truncated }
}

/** One observed service-worker registration. */
data class CdpServiceWorkerRow(
    val scriptUrl: String?,
    val scopeUrl: String,
    val status: String?,
    val runningStatus: String?,
    val lastError: String?,
)

/**
 * Reconnect/backoff state. [backoffScheduleMs] is the client's real
 * [CdpClient.computeBackoffDelayMs] schedule for attempts 1..[maxAttempts] — not the mockup's
 * illustrative "1s→30s cap" ladder, which the shipped client does not implement.
 */
data class CdpReconnectState(
    val attempt: Int,
    val maxAttempts: Int,
    val nextRetryDelayMs: Long,
    val backoffScheduleMs: List<Long>,
    val lastError: String?,
    val connectionLostAtMs: Long?,
)

/** Whole-card state for `CdpCard`. */
data class CdpCardState(
    val phase: CdpConnectionPhase,
    val endpoint: String?,
    val statusMessage: String,
    val connectedSinceMs: Long?,
    /**
     * How many reconnect attempts were actually made for the current outage. Retained after the retry
     * loop exhausts so a terminal [CdpConnectionPhase.FAILED] can distinguish "gave up after N
     * attempts" from an initial connection failure where no retry ever ran.
     */
    val reconnectAttemptsMade: Int,
    val eventsCorrelated: Int,
    val targets: List<CdpTargetRow>,
    val targetsAreStale: Boolean,
    val rules: CdpRuleEnforcementState,
    val storage: CdpStorageState?,
    val serviceWorkers: List<CdpServiceWorkerRow>,
    val reconnect: CdpReconnectState?,
) {
    val isConnected: Boolean get() = phase == CdpConnectionPhase.CONNECTED
    val toggleOn: Boolean get() = phase != CdpConnectionPhase.DISCONNECTED
}

/**
 * Honest phase derivation: the client's own [CdpClient.State] plus whether its retry loop is
 * genuinely alive ([CdpClient.retryActive]).
 *
 * [CdpClient.retryActive] — not the attempt counter — is the authoritative input, because the counter
 * is deliberately retained after exhaustion so the UI can report how many attempts were made. Keying
 * off the counter made an exhausted client claim RECONNECTING forever; keying off the live loop means
 * a client that has given up reports the terminal [CdpConnectionPhase.FAILED] it is actually in.
 */
fun toCdpPhase(state: CdpClient.State, retryActive: Boolean): CdpConnectionPhase = when (state) {
    CdpClient.State.CONNECTED -> CdpConnectionPhase.CONNECTED
    CdpClient.State.DISCONNECTED -> CdpConnectionPhase.DISCONNECTED
    CdpClient.State.CONNECTING ->
        if (retryActive) CdpConnectionPhase.RECONNECTING else CdpConnectionPhase.CONNECTING
    CdpClient.State.FAILED ->
        if (retryActive) CdpConnectionPhase.RECONNECTING else CdpConnectionPhase.FAILED
}

/**
 * Aggregates every attached target's [BrowserStorageObserver.StorageSnapshot] into the card's
 * storage section. Cookie domains are counted distinctly across targets; localStorage keys are
 * counted per (target, key) because two targets can legitimately hold the same key for different
 * origins; IndexedDB `db/store` record lists are folded back into per-database summaries.
 */
fun toCdpStorageState(snapshots: Map<String, BrowserStorageObserver.StorageSnapshot>): CdpStorageState? {
    if (snapshots.isEmpty()) return null
    val cookies = snapshots.values.flatMap { it.cookies }
    val perDatabase = linkedMapOf<String, MutableList<Triple<String, Int, Boolean>>>()
    for (snapshot in snapshots.values) {
        for ((storeKey, records) in snapshot.indexedDbRecords) {
            val dbName = storeKey.substringBefore('/')
            val storeName = storeKey.substringAfter('/', missingDelimiterValue = "")
            val hasMore = snapshot.indexedDbHasMore[storeKey] ?: false
            perDatabase.getOrPut(dbName) { mutableListOf() }
                .add(Triple(storeName, records.size, hasMore))
        }
    }
    // Databases whose names were listed but whose stores were never read back still deserve a row,
    // with a genuine zero rather than being hidden.
    for (snapshot in snapshots.values) {
        for (dbName in snapshot.indexedDbDatabases) perDatabase.getOrPut(dbName) { mutableListOf() }
    }
    val databases = perDatabase.map { (dbName, stores) ->
        IndexedDbSummary(
            databaseName = dbName,
            objectStoreCount = stores.size,
            recordCount = stores.sumOf { it.second },
            truncated = stores.any { it.third },
        )
    }
    // Each observer bounds its own list, but N attached targets aggregate to N x that bound, so the
    // merged stream gets its own global cap -- the card shows recent activity, not a growing log.
    val changes = snapshots.values
        .flatMap { snapshot -> snapshot.recentEvents.map { it.toLocalStorageChange() } }
        .sortedByDescending { it.atMs }
        .take(MAX_AGGREGATED_LOCAL_STORAGE_CHANGES)
    return CdpStorageState(
        cookieCount = cookies.size,
        cookieDomainCount = cookies.map { it.domain }.filter { it.isNotEmpty() }.distinct().size,
        localStorageKeyCount = snapshots.values.sumOf { it.localStorage.size },
        localStorageChanges = changes,
        indexedDbDatabases = databases,
        lastRefreshedAtMs = snapshots.values.maxOfOrNull { it.lastRefreshed }?.takeIf { it > 0L },
        lastError = snapshots.values.firstNotNullOfOrNull { it.lastError },
    )
}

/** Maps the observer's own live event record onto the UI model. */
fun BrowserStorageObserver.DomStorageEvent.toLocalStorageChange(): LocalStorageChange =
    LocalStorageChange(
        key = key,
        kind = when (kind) {
            BrowserStorageObserver.DomStorageEventKind.ADDED -> LocalStorageChangeKind.SET
            BrowserStorageObserver.DomStorageEventKind.UPDATED -> LocalStorageChangeKind.UPDATED
            BrowserStorageObserver.DomStorageEventKind.REMOVED -> LocalStorageChangeKind.REMOVED
            BrowserStorageObserver.DomStorageEventKind.CLEARED -> LocalStorageChangeKind.CLEARED
        },
        oldValue = oldValue,
        newValue = newValue,
        atMs = atMs,
    )

/** Flattens every target's service-worker registrations into one de-duplicated list. */
fun toServiceWorkerRows(
    byTarget: Map<String, Map<String, ServiceWorkerObserver.WorkerRegistration>>,
): List<CdpServiceWorkerRow> = byTarget.values
    .flatMap { it.values }
    .distinctBy { (it.scriptUrl ?: "") to it.scopeUrl }
    .map {
        CdpServiceWorkerRow(
            scriptUrl = it.scriptUrl,
            scopeUrl = it.scopeUrl,
            status = it.status,
            runningStatus = it.runningStatus,
            lastError = it.lastError,
        )
    }

/** Maps attached/last-known targets plus their real per-target event counts into rows. */
fun toTargetRows(
    targets: Map<String, CdpClient.TargetInfo>,
    eventsByTarget: Map<String, Int>,
    stale: Boolean,
): List<CdpTargetRow> = targets.values.map { info ->
    CdpTargetRow(
        sessionId = info.sessionId,
        targetId = info.targetId,
        type = info.type,
        title = info.title,
        url = info.url,
        requestCount = eventsByTarget[info.sessionId] ?: 0,
        stale = stale,
    )
}
