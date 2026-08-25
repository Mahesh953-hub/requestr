package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TrackChanges
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
import com.example.data.models.FingerprintSignalEvidence
import com.example.data.models.StackFingerprintState
import com.example.data.models.StackVerdictRow
import com.example.data.models.confidenceColor

/**
 * v6.1 Phase A / Domain B: the HTTP-stack fingerprinting block of the Settings
 * "RUNTIME INSTRUMENTATION" card (`runtime-instrumentation-healthy-*.html`, plus the
 * `cronet-unavailable` / `webview-active` variants).
 *
 * Renders one verdict row per candidate stack — DETECTED / LIKELY / POSSIBLE / UNKNOWN pill, the
 * measured signal-match figure, the five-signal ✓/✗ tally, a thin progress track — and expands one
 * row at a time into its per-signal evidence panel (JA3, ALPN, HTTP/2 SETTINGS, HPACK header order,
 * cache-validation shape).
 *
 * Honesty rules this composable enforces, straight from [StackFingerprintState]:
 *  - an UNKNOWN verdict shows "—" instead of a number, and the fingerprinter's own missing-signal
 *    reason instead of a tally it cannot back;
 *  - the percentage is labelled as a *signal-match* share, because that is exactly what it measures
 *    (matched families ÷ displayed families) — it is not a probability;
 *  - a signal family with no captured evidence gets a NOT CAPTURED pill plus the concrete reason,
 *    never a plausible-looking placeholder value;
 *  - with nothing to classify, the panel renders [StackFingerprintState.unavailableReason] verbatim.
 *
 * Pure renderer: all state comes from [com.example.runtime.RuntimeInstrumentationStateProvider].
 */
@Composable
fun StackFingerprintPanel(
    state: StackFingerprintState,
    expandedStack: String?,
    onToggleStack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().componentId("stack-fingerprint-panel")) {
        RuntimeSpreadRow {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.TrackChanges,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    "HTTP Stack Fingerprinting",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "5 signals",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Multi-signal confidence-scored verdicts from TLS/HTTP fingerprints — not User-Agent string matching.",
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val reason = state.unavailableReason
        if (reason != null) {
            Text(
                reason,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            "Session ${state.sessionId} · ${state.requestsFingerprinted} requests fingerprinted · " +
                if (state.calibrated) "calibrated on this device" else "not calibrated yet",
            modifier = Modifier.padding(top = 5.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(9.dp))
        state.verdicts.forEach { verdict ->
            val id = verdict.stack.name.lowercase()
            val expanded = expandedStack == id
            VerdictRow(verdict = verdict, expanded = expanded, onClick = { onToggleStack(id) })
            if (expanded && verdict.evidence.isNotEmpty()) {
                EvidencePanel(verdict = verdict, modifier = Modifier.componentId("verdict-$id-evidence"))
            }
        }

        Text(
            "UNKNOWN means insufficient signals captured, never a guessed stack. JA3/ALPN/H2 require a " +
                "TLS handshake; plaintext HTTP falls back to header-order + cache-shape signals. The " +
                "percentage is the share of displayed signal families that matched — measured evidence, " +
                "not a probability.",
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerdictRow(
    verdict: StackVerdictRow,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val color = Color(confidenceColor(verdict.confidence))
    RuntimeVRow(
        railColor = color,
        onClick = onClick,
        modifier = Modifier.componentId("verdict-${verdict.stack.name.lowercase()}"),
    ) {
        RuntimeSpreadRow {
            Text(
                verdict.stack.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BadgePill(text = verdict.confidence.name, color = confidenceColor(verdict.confidence))
                Text(
                    verdict.signalMatchPercent?.let { "$it%" } ?: "—",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse evidence" else "Expand evidence",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (verdict.tally.isEmpty()) {
            // UNKNOWN: the fingerprinter's own explanation of which signals were absent.
            Text(
                verdict.reason,
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                verdict.tally.forEach { chip ->
                    Row {
                        Text(
                            chip.family.shortLabel,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (chip.present) "✓" else "✗",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (chip.present) RuntimeGreen else RuntimeRed,
                        )
                    }
                }
            }
            RuntimeTrack(
                fraction = (verdict.signalMatchPercent ?: 0) / 100f,
                fillColor = color,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                "${verdict.tally.count { it.present }}/${verdict.tally.size} signal families matched · " +
                    "${verdict.requestCount} request(s) in this session",
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvidencePanel(verdict: StackVerdictRow, modifier: Modifier = Modifier) {
    RuntimeFlatCard(
        modifier = modifier.padding(bottom = 8.dp),
        railColor = Color(confidenceColor(verdict.confidence)),
    ) {
        Text(
            "SIGNAL EVIDENCE — ${verdict.stack.label}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        verdict.evidence.forEach { evidence -> EvidenceSection(evidence) }
    }
}

@Composable
private fun EvidenceSection(evidence: FingerprintSignalEvidence) {
    RuntimeSubLabel(evidence.family.title)
    if (evidence.detail != null) {
        RuntimeKvRow(key = "observed", value = evidence.detail)
    }
    evidence.unavailableDetail?.let { RuntimeKvRow(key = "not retained", value = it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BadgePill(text = evidence.verdict.label, color = evidence.verdict.color)
        Text(
            evidence.note,
            modifier = Modifier.weight(1f),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
