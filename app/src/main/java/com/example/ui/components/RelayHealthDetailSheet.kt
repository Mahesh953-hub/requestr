package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.RelayDropReason
import com.example.data.models.RelayHealthState
import com.example.data.models.activeFlowCount
import com.example.data.models.dropReasons
import com.example.data.models.flowTablePressure
import com.example.data.models.totalDropped
import com.example.interception.tun.IcmpRelay
import com.example.interception.tun.TunRelay
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber

/**
 * Task 14 / Lane D, surface 1 detail: the expanded relay-health bottom sheet. Extracted from the
 * private composable that previously lived inline in InterceptScreen.kt and augmented to cover the
 * full approved `relay-health-*-detail` mockup grid — healthy / degraded / down — with a
 * dropped-packets-by-reason breakdown, flow-table pressure, and an address-family summary.
 *
 * Every figure is a real [TunRelay.RelayStats] counter. Where the mockup shows a datum this relay
 * does not track (per-family relayed/dropped split, NAT mapping count, throughput *rate*), the sheet
 * either omits it or states the honest aggregate, never a fabricated number. The down state ends
 * with a "Start capture" CTA plus a "Capture setup" ghost button, exactly as the mockup specifies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayHealthDetailSheet(
    state: RelayHealthState,
    onDismiss: () -> Unit,
    onStartCapture: () -> Unit,
    onCaptureSetup: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("VPN relay health", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val statusLabel = when (state) {
                is RelayHealthState.Down -> "NOT ACTIVE"
                is RelayHealthState.Degraded -> "DEGRADED"
                is RelayHealthState.Healthy -> "HEALTHY"
            }
            val statusColor = when (state) {
                is RelayHealthState.Down -> 0xFF64748B
                is RelayHealthState.Degraded -> 0xFFF59E0B
                is RelayHealthState.Healthy -> 0xFF10B981
            }
            val relayPrefix = if (state is RelayHealthState.Down) "RELAY DOWN" else "RELAY UP"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relayPrefix,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                BadgePill(text = statusLabel, color = statusColor)
            }

            Spacer(modifier = Modifier.height(12.dp))
            val dropped = state.stats.totalDropped()
            DetailKV("Relayed", "${state.stats.relayedFlows} · ${formatByteCount(state.stats.relayBytesDown + state.stats.relayBytesUp)}")
            DetailKV("Dropped", if (state is RelayHealthState.Down) "0 · no packets seen" else "$dropped")

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("ACTIVE FLOWS BY TRANSPORT")
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RelayMetricTile("TCP flows", state.stats.activeTcpV4 + state.stats.activeTcpV6)
                RelayMetricTile("UDP flows", state.stats.activeUdpV4 + state.stats.activeUdpV6)
                RelayMetricTile("ICMP", state.icmpUnrelayableCount)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "↓${formatByteCount(state.stats.relayBytesDown)} down   ↑${formatByteCount(state.stats.relayBytesUp)} up",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("DROPPED PACKETS BY REASON")
            Spacer(modifier = Modifier.height(6.dp))
            val reasons = state.stats.dropReasons()
            if (reasons.isEmpty()) {
                Text(
                    text = if (state is RelayHealthState.Down)
                        "No drop counters yet — the relay never received a packet, so there is nothing to attribute."
                    else "No drops attributed yet — counters are zero.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxCount = reasons.maxOf { it.count }.coerceAtLeast(1)
                reasons.forEach { reason ->
                    DropReasonRow(reason, fraction = reason.count.toFloat() / maxCount)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("ADDRESS FAMILY")
            Spacer(modifier = Modifier.height(6.dp))
            DetailKV("IPv4", "${state.stats.activeTcpV4 + state.stats.activeUdpV4} active flows")
            DetailKV("IPv6", "${state.stats.activeTcpV6 + state.stats.activeUdpV6} active flows")

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("FLOW TABLE PRESSURE")
            Spacer(modifier = Modifier.height(6.dp))
            DetailKV("Flow table", "${state.stats.flowTablePressure()} / ${TunRelay.MAX_FLOWS}")
            DetailKV("Evictions (LRU)", "${state.stats.evictionCount}")

            if (state is RelayHealthState.Degraded) {
                Spacer(modifier = Modifier.height(14.dp))
                WarningNote(
                    text = "Drops are real packet loss. The flow table is at ${state.stats.flowTablePressure()} / ${TunRelay.MAX_FLOWS} — " +
                        "new connections are evicted (LRU) until an entry frees. Narrow the app filter or raise the cap.",
                    color = InterceptorIdleAmber,
                )
            }

            if (state is RelayHealthState.Down) {
                Spacer(modifier = Modifier.height(14.dp))
                WarningNote(
                    text = "Nothing is being relayed because nothing is capturing. Start a capture on the VPN_TUN tier to bring the relay up. " +
                        "Device traffic keeps flowing normally while requestr is idle.",
                    color = InterceptorActiveGreen,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onStartCapture, modifier = Modifier.weight(1f)) {
                        Text("Start capture")
                    }
                    OutlinedButton(onClick = onCaptureSetup, modifier = Modifier.weight(1f)) {
                        Text("Capture setup")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RelayMetricTile(label: String, count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = "$count",
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DropReasonRow(reason: RelayDropReason, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = reason.label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${reason.count}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(InterceptorIdleAmber),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailKV(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WarningNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(10.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true, name = "Degraded detail — light")
@Composable
private fun RelayHealthDetailSheetDegradedPreview() {
    RelayHealthDetailSheet(
        state = RelayHealthState.Degraded(
            pumpRunning = true,
            stats = TunRelay.RelayStats(
                relayedFlows = 129_004,
                relayBytesUp = 96_000,
                relayBytesDown = 1_200_000,
                relayErrors = 4921,
                activeTcpV4 = 480,
                activeTcpV6 = 6,
                activeUdpV4 = 22,
                activeUdpV6 = 2,
                evictionCount = 1204,
                dropUpstreamWrite = 1684,
                dropUpstreamOpen = 512,
                dropProtectFail = 2412,
                dropUnrelayableProto = 313,
            ),
            icmpUnrelayableCount = 2,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.PING_SOCKET,
        ),
        onDismiss = {},
        onStartCapture = {},
        onCaptureSetup = {},
    )
}

@Preview(showBackground = true, name = "Down detail — light")
@Composable
private fun RelayHealthDetailSheetDownPreview() {
    RelayHealthDetailSheet(
        state = RelayHealthState.Down(
            pumpRunning = false,
            stats = TunRelay.RelayStats(),
            icmpUnrelayableCount = 0,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.NONE_YET,
        ),
        onDismiss = {},
        onStartCapture = {},
        onCaptureSetup = {},
    )
}
