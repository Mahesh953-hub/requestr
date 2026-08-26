package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.AdapterRowState
import com.example.data.models.RuntimeInstrumentationState
import com.example.data.models.StackFingerprintState
import com.example.runtime.InstrumentationState

/**
 * v6.1 Phase A / Domain B, surface 1: the Settings "RUNTIME INSTRUMENTATION" card, ported from
 * `runtime-instrumentation-healthy-*.html` (canonical layout, evidence drill-in expanded),
 * `runtime-instrumentation-cronet-unavailable-*.html` (DEGRADED + per-adapter failure reason) and
 * `runtime-instrumentation-webview-active-*.html` (live offscreen capture session).
 *
 * Composition mirrors the mockup exactly: card header + health pill, a live one-line engine summary,
 * per-adapter `.vrow`s with CAPTURING / IDLE / UNAVAILABLE pills and real event counts, then
 * [WebViewCaptureControl], a hairline, and [StackFingerprintPanel].
 *
 * This composable is a pure renderer over [RuntimeInstrumentationState] /
 * [StackFingerprintState] as produced by
 * [com.example.runtime.RuntimeInstrumentationStateProvider]; it reads no engine singletons and
 * derives no state of its own, so it cannot disagree with the CDP card or the engines themselves.
 * An adapter that reports a failure shows that adapter's own real reason text — never a canned
 * explanation, never a sample count.
 */
@Composable
fun RuntimeInstrumentationCard(
    state: RuntimeInstrumentationState,
    fingerprint: StackFingerprintState,
    webViewUrlInput: String,
    onWebViewUrlInputChange: (String) -> Unit,
    onStartWebViewCapture: () -> Unit,
    onStopWebViewCapture: () -> Unit,
    expandedVerdictStack: String?,
    onToggleVerdictStack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().componentId("runtime-instrumentation-card"),
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            RuntimeSpreadRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Runtime",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "HTTP Stack Adapters",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                BadgePill(text = state.health.label, color = state.health.color)
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeStatusDot(color = Color(state.health.color))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    engineSummaryLine(state),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "Polling registered adapters for real request/response events. Third-party apps need " +
                    "the Requestr Observer SDK (sdk/) to report their own traffic.",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RuntimeSubLabel("ADAPTERS")
            if (state.adapters.isEmpty()) {
                Text(
                    "No adapters registered — the instrumentation engine has not been started in this process.",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.adapters.forEach { AdapterRow(it) }
            }

            WebViewCaptureControl(
                state = state.webViewCapture,
                urlInput = webViewUrlInput,
                onUrlInputChange = onWebViewUrlInputChange,
                onStart = onStartWebViewCapture,
                onStop = onStopWebViewCapture,
            )

            RuntimeHairline()

            StackFingerprintPanel(
                state = fingerprint,
                expandedStack = expandedVerdictStack,
                onToggleStack = onToggleVerdictStack,
            )
        }
    }
}

@Composable
private fun AdapterRow(adapter: AdapterRowState) {
    val stateColor = Color(adapter.captureState.color)
    RuntimeVRow(
        railColor = stateColor,
        modifier = Modifier.componentId("adapter-${adapter.name.lowercase()}"),
    ) {
        RuntimeSpreadRow {
            Text(
                adapter.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BadgePill(text = adapter.captureState.label, color = adapter.captureState.color)
                Text(
                    String.format("%,d", adapter.eventCount),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = stateColor,
                )
                Text(
                    "events",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val reason = adapter.unavailableReason
        if (reason != null) {
            // cronet-unavailable mockup's `.reason` block: the adapter's own real failure text.
            Text(
                reason,
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                adapter.statusText,
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The mockup's live summary line ("Active — 3 adapters registered · 2 capturing, 1 ready"), built
 * from real counts only: a category with a zero count is omitted rather than printed as "0 ready".
 */
internal fun engineSummaryLine(state: RuntimeInstrumentationState): String {
    val prefix = when (state.engineState) {
        InstrumentationState.ACTIVE -> "Active"
        InstrumentationState.INITIALIZING -> "Starting"
        InstrumentationState.FAILED -> "Every registered adapter failed to start"
        InstrumentationState.STOPPED -> "Stopped"
        InstrumentationState.NOT_STARTED -> "Not started"
    }
    if (state.adapters.isEmpty()) return "$prefix — no adapters registered"
    val parts = buildList {
        if (state.capturingCount > 0) add("${state.capturingCount} capturing")
        if (state.idleCount > 0) add("${state.idleCount} ready")
        if (state.unavailableCount > 0) add("${state.unavailableCount} unavailable")
    }
    return "$prefix — ${state.registeredAdapterCount} adapters registered · ${parts.joinToString(", ")}"
}
