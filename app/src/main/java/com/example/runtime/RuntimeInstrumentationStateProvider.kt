package com.example.runtime

import android.content.Context
import com.example.api.LocalApiServer
import com.example.browser.BrowserBridgeManager
import com.example.browser.CdpClient
import com.example.data.models.AdapterRowState
import com.example.data.models.CdpCardState
import com.example.data.models.CdpConnectionPhase
import com.example.data.models.CdpReconnectState
import com.example.data.models.CdpRuleEnforcementState
import com.example.data.models.RuntimeInstrumentationState
import com.example.data.models.StackFingerprintState
import com.example.data.models.WebViewCaptureState
import com.example.data.models.WebViewCapturedRequest
import com.example.data.models.instrumentationHealthOf
import com.example.data.models.toAdapterRow
import com.example.data.models.toCdpPhase
import com.example.data.models.toCdpStorageState
import com.example.data.models.toServiceWorkerRows
import com.example.data.models.toTargetRows
import com.example.data.models.toVerdictRow
import com.example.runtime.http.HttpStack
import com.example.runtime.http.HttpStackDetector
import com.example.runtime.http.StackFingerprinter
import com.example.runtime.webview.WebViewInstrumentation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * v6.1 Phase A / Domain B: the single authoritative source of UI state for the Settings
 * "RUNTIME INSTRUMENTATION" and "BROWSER BRIDGE (CDP)" cards.
 *
 * Everything the two cards render is derived **here**, from the engines that already own the real
 * work — [RuntimeInstrumentationEngine], [WebViewInstrumentation], [BrowserBridgeManager] /
 * [CdpClient], [HttpStackDetector]/[StackFingerprinter]. The composables are pure renderers: they do
 * not read engine singletons, do not recompute state, and cannot disagree with each other about
 * whether an adapter is capturing or a CDP session is reconnecting.
 *
 * This provider deliberately owns **no** lifecycle. It never starts or stops an engine: the
 * instrumentation engine is started by `MainActivity`, the WebView instance is owned by
 * [com.example.runtime.http.WebViewAdapter], and the CDP client is owned by [BrowserBridgeManager].
 * This object only observes and maps, so wiring it into a screen can never change capture behaviour.
 */
object RuntimeInstrumentationStateProvider {

    /**
     * Live runtime-instrumentation state: engine lifecycle + per-adapter rows + the offscreen
     * WebView capture session. Combined from the four real flows the engines expose, so a UI
     * collector sees one consistent snapshot rather than four independently-arriving values.
     */
    val runtimeState: Flow<RuntimeInstrumentationState> = combine(
        RuntimeInstrumentationEngine.stateFlow,
        RuntimeInstrumentationEngine.adapterStatuses,
        RuntimeInstrumentationEngine.eventCounts,
        WebViewInstrumentation.captureStatus,
    ) { engineState, statuses, counts, capture ->
        buildRuntimeState(
            engineState = engineState,
            adapterStatuses = statuses,
            eventCounts = counts,
            capture = capture,
            webViewAdapterActive = WebViewInstrumentation.isActive(),
            webViewStatusText = WebViewInstrumentation.statusText(),
            registrationOrder = RuntimeInstrumentationEngine.adapterNames(),
        )
    }

    /**
     * Live CDP card state. Nine real flows are folded in — connection state, status text, endpoint,
     * correlated-event count, attached targets, last-known (stale) targets and their counts,
     * reconnect attempt/backoff, Fetch counters, and per-target storage/service-worker snapshots.
     */
    val cdpState: Flow<CdpCardState> = combine(
        combine(
            BrowserBridgeManager.connectionState,
            BrowserBridgeManager.reconnectAttempt,
            BrowserBridgeManager.nextRetryDelayMs,
            BrowserBridgeManager.lastError,
            BrowserBridgeManager.statusMessage,
        ) { state, attempt, retryDelay, lastError, status ->
            ConnectionSlice(state, attempt, retryDelay, lastError, status)
        }.combine(BrowserBridgeManager.retryActive) { slice, retryActive ->
            slice.copy(retryActive = retryActive)
        },
        combine(
            BrowserBridgeManager.targets,
            BrowserBridgeManager.eventsIngestedByTarget,
            BrowserBridgeManager.lastKnownTargets,
            BrowserBridgeManager.lastKnownEventsByTarget,
            BrowserBridgeManager.eventsIngested,
        ) { targets, eventsByTarget, lastTargets, lastEvents, eventsIngested ->
            TargetSlice(targets, eventsByTarget, lastTargets, lastEvents, eventsIngested)
        },
        combine(
            BrowserBridgeManager.fetchCounters,
            BrowserBridgeManager.storageByTarget,
            BrowserBridgeManager.serviceWorkersByTarget,
            BrowserBridgeManager.connectedSinceMs,
            BrowserBridgeManager.connectionLostAtMs,
        ) { counters, storage, workers, connectedSince, lostAt ->
            ObservationSlice(counters, storage, workers, connectedSince, lostAt)
        },
        BrowserBridgeManager.endpoint,
    ) { connection, targetSlice, observation, endpoint ->
        buildCdpState(connection, targetSlice, observation, endpoint)
    }

    /** Intermediate slices, only so [combine] stays within its typed arities. */
    internal data class ConnectionSlice(
        val state: CdpClient.State,
        val reconnectAttempt: Int,
        val nextRetryDelayMs: Long,
        val lastError: String?,
        val statusMessage: String,
        /** Whether a retry loop is genuinely pending; see [com.example.browser.CdpClient.retryActive]. */
        val retryActive: Boolean = false,
    )

    internal data class TargetSlice(
        val targets: Map<String, CdpClient.TargetInfo>,
        val eventsByTarget: Map<String, Int>,
        val lastKnownTargets: Map<String, CdpClient.TargetInfo>,
        val lastKnownEventsByTarget: Map<String, Int>,
        val eventsIngested: Int,
    )

    internal data class ObservationSlice(
        val counters: BrowserBridgeManager.FetchCounters,
        val storageByTarget: Map<String, com.example.browser.BrowserStorageObserver.StorageSnapshot>,
        val serviceWorkersByTarget: Map<String, Map<String, com.example.browser.ServiceWorkerObserver.WorkerRegistration>>,
        val connectedSinceMs: Long?,
        val connectionLostAtMs: Long?,
    )

    /**
     * Pure mapping of the runtime-instrumentation engine's real state into the UI contract. Adapter
     * rows keep the engine's registration order where possible so the card's row order is stable
     * across recompositions instead of following hash-map iteration order.
     */
    internal fun buildRuntimeState(
        engineState: InstrumentationState,
        adapterStatuses: Map<String, String>,
        eventCounts: Map<String, Int>,
        capture: WebViewInstrumentation.CaptureStatus,
        webViewAdapterActive: Boolean,
        webViewStatusText: String,
        registrationOrder: List<String>,
    ): RuntimeInstrumentationState {
        val orderedNames = registrationOrder.filter { it in adapterStatuses } +
            adapterStatuses.keys.filterNot { it in registrationOrder }
        val rows: List<AdapterRowState> = orderedNames.map { name ->
            toAdapterRow(
                name = name,
                statusText = adapterStatuses[name] ?: "",
                eventCount = eventCounts[name] ?: 0,
            )
        }
        return RuntimeInstrumentationState(
            engineState = engineState,
            health = instrumentationHealthOf(engineState, rows),
            registeredAdapterCount = maxOf(registrationOrder.size, rows.size),
            adapters = rows,
            webViewCapture = WebViewCaptureState(
                adapterAvailable = webViewAdapterActive,
                // A capture session only exists when a live WebView is actually hosting it: without
                // this, a URL submitted while the adapter was down (or after the WebView was torn
                // down) left the control claiming "Capturing" with nothing behind it.
                capturing = capture.requestedUrl != null && webViewAdapterActive,
                requestedUrl = capture.requestedUrl,
                startedAtMs = capture.startedAtMs,
                requestCount = capture.requestCount,
                responseBytes = capture.responseBytes,
                recentCaptures = capture.recentCaptures.map { it.toUiModel() },
                statusText = webViewStatusText,
            ),
        )
    }

    private fun WebViewInstrumentation.CapturedSummary.toUiModel() = WebViewCapturedRequest(
        method = method,
        path = url?.let { raw ->
            runCatching {
                val uri = java.net.URI(raw)
                val path = uri.path.orEmpty().ifEmpty { "/" }
                if (uri.query.isNullOrEmpty()) path else "$path?${uri.query}"
            }.getOrDefault(raw)
        } ?: "(unknown url)",
        statusCode = statusCode,
        source = source,
        atMs = atMs,
    )

    /** Pure mapping of the browser bridge's real state into the CDP card contract. */
    internal fun buildCdpState(
        connection: ConnectionSlice,
        targetSlice: TargetSlice,
        observation: ObservationSlice,
        endpoint: String?,
    ): CdpCardState {
        val phase = toCdpPhase(connection.state, connection.retryActive)
        // A target is only genuinely attached while the socket is CONNECTED: flattened CDP sessions die
        // with the connection (see CdpClient.dropAllTargets), so anything shown in any other phase is
        // last-known data. Last-known rows are surfaced *only* while a retry is genuinely pending,
        // because "stale · refreshes on reconnect" is only true then -- a terminal FAILED (retry loop
        // exhausted, or an initial connect that never succeeded) falls back to the empty state rather
        // than implying a refresh that will never come.
        val connected = phase == CdpConnectionPhase.CONNECTED
        val liveTargets = targetSlice.targets
        val staleSource = if (liveTargets.isNotEmpty()) liveTargets else targetSlice.lastKnownTargets
        val staleEvents = if (liveTargets.isNotEmpty()) {
            targetSlice.eventsByTarget
        } else {
            targetSlice.lastKnownEventsByTarget
        }
        val stale = phase == CdpConnectionPhase.RECONNECTING && staleSource.isNotEmpty()
        val rows = when {
            connected -> toTargetRows(liveTargets, targetSlice.eventsByTarget, stale = false)
            stale -> toTargetRows(staleSource, staleEvents, stale = true)
            else -> emptyList()
        }
        return CdpCardState(
            phase = phase,
            endpoint = endpoint,
            statusMessage = connection.statusMessage,
            connectedSinceMs = observation.connectedSinceMs,
            reconnectAttemptsMade = connection.reconnectAttempt,
            eventsCorrelated = targetSlice.eventsIngested,
            targets = rows.sortedByDescending { it.requestCount },
            targetsAreStale = stale,
            rules = CdpRuleEnforcementState(
                allowed = observation.counters.allowed,
                blockedRequestStage = observation.counters.blocked,
                modifiedRequestStage = observation.counters.modified,
                modifiedResponseStage = observation.counters.responseModified,
                redirectedRequestStage = observation.counters.redirected,
                bodiesCaptured = observation.counters.bodiesCaptured,
            ),
            storage = toCdpStorageState(observation.storageByTarget),
            serviceWorkers = toServiceWorkerRows(observation.serviceWorkersByTarget),
            reconnect = if (phase == CdpConnectionPhase.RECONNECTING) {
                CdpReconnectState(
                    attempt = connection.reconnectAttempt,
                    maxAttempts = CdpClient.MAX_RECONNECT_ATTEMPTS,
                    nextRetryDelayMs = connection.nextRetryDelayMs,
                    backoffScheduleMs = CdpClient.backoffScheduleMs(),
                    lastError = connection.lastError,
                    connectionLostAtMs = observation.connectionLostAtMs,
                )
            } else {
                null
            },
        )
    }

    /**
     * One-shot load of the fingerprint panel's state for [sessionId].
     *
     * Suspending (not a flow) because it is a real Room read plus an optional on-device calibration
     * pass, exactly as the previous inline Settings block did — this only moves that work behind the
     * provider so the composable holds no analysis logic. Returns an honest
     * [StackFingerprintState.unavailable] whenever there is genuinely nothing to classify.
     */
    suspend fun loadFingerprintState(context: Context, sessionId: Long?): StackFingerprintState {
        if (sessionId == null) {
            return StackFingerprintState.unavailable(
                "No active or recorded capture session yet — start a capture to fingerprint real requests.",
            )
        }
        // calibrate() probes LocalApiServer's own loopback route; with no server running there is
        // nothing to probe against, so the table stays honestly uncalibrated rather than faked.
        val calibration: StackFingerprinter.CalibrationTable? = if (LocalApiServer.isRunning) {
            runCatching { StackFingerprinter.calibrate(context, LocalApiServer.port) }.getOrNull()
        } else {
            null
        }
        val report = runCatching { HttpStackDetector.analyzeSession(context, sessionId, calibration) }
            .getOrNull()
            ?: return StackFingerprintState.unavailable(
                "Could not read session $sessionId from the capture database.",
                sessionId = sessionId,
            )
        if (report.totalRequests == 0) {
            return StackFingerprintState.unavailable(
                "Session $sessionId has no captured requests yet — nothing to fingerprint.",
                sessionId = sessionId,
            )
        }
        return StackFingerprintState(
            sessionId = sessionId,
            requestsFingerprinted = report.totalRequests,
            calibrated = report.calibrated,
            verdicts = toVerdictRows(report.countsByStack, report.representativeVerdicts),
            unavailableReason = null,
        )
    }

    /**
     * Orders verdict rows the way the mockups do — strongest confidence first, then most requests —
     * and only emits a row for a stack that genuinely appeared in the session. A stack with a count
     * but (impossibly) no retained verdict is skipped rather than rendered with invented evidence.
     */
    internal fun toVerdictRows(
        countsByStack: Map<HttpStack, Int>,
        representativeVerdicts: Map<HttpStack, StackFingerprinter.StackVerdict>,
    ) = countsByStack.entries
        .mapNotNull { (stack, count) ->
            representativeVerdicts[stack]?.let { toVerdictRow(stack, it, count) }
        }
        .sortedWith(compareBy({ it.confidence.ordinal }, { -it.requestCount }))
}
