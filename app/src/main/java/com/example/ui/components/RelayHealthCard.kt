package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.RelayHealthState
import com.example.data.models.activeFlowCount
import com.example.data.models.dropReasons
import com.example.data.models.flowTablePressure
import com.example.data.models.totalDropped
import com.example.interception.tun.IcmpRelay
import com.example.interception.tun.TunRelay
import com.example.ui.theme.InterceptorActiveGreen
import com.example.ui.theme.InterceptorIdleAmber
import com.example.ui.theme.TextMuted

/**
 * Task 14 / Lane D, surface 1 compact: the collapsed VPN-relay health card shown on the Intercept
 * screen. Extracted from the private composable that previously lived inline in InterceptScreen.kt
 * and augmented to match the approved `relay-health-*-compact` mockups — a status pill
 * ([BadgePill]), a relayed/dropped summary line, per-transport tiles, a throughput row, and a
 * flow-table pressure line with a "Details" chevron that opens [RelayHealthDetailSheet].
 *
 * Bound entirely to real [TunRelay.RelayStats] counters carried on [state]; when the pump is not
 * running the card renders the honest down state (zeroed counters, NOT ACTIVE pill) rather than
 * hiding, so the user always sees whether a relay exists.
 */
@Composable
fun RelayHealthCard(
    state: RelayHealthState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (state) {
        is RelayHealthState.Down -> TextMuted
        is RelayHealthState.Degraded -> InterceptorIdleAmber
        is RelayHealthState.Healthy -> InterceptorActiveGreen
    }
    val statusLabel = when (state) {
        is RelayHealthState.Down -> "NOT ACTIVE"
        is RelayHealthState.Degraded -> "DEGRADED"
        is RelayHealthState.Healthy -> "HEALTHY"
    }
    val relayPrefix = if (state is RelayHealthState.Down) "RELAY DOWN" else "RELAY UP"
    val totalActive = state.stats.activeFlowCount()
    val dropped = state.stats.totalDropped()

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        onClick = onExpand,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$relayPrefix · $totalActive flows",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgePill(text = statusLabel, color = statusPillColor(state))
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = summaryLine(state, dropped),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RelayMetricTile("TCP", state.stats.activeTcpV4 + state.stats.activeTcpV6)
                RelayMetricTile("UDP", state.stats.activeUdpV4 + state.stats.activeUdpV6)
                RelayMetricTile("ICMP", state.icmpUnrelayableCount)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "↓${formatByteCount(state.stats.relayBytesDown)} ↑${formatByteCount(state.stats.relayBytesUp)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Flow table ${state.stats.flowTablePressure()} / ${TunRelay.MAX_FLOWS}",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ARGB colour for the status [BadgePill], matching the mockup's healthy/degraded/down set. */
private fun statusPillColor(state: RelayHealthState): Long = when (state) {
    is RelayHealthState.Down -> 0xFF64748B
    is RelayHealthState.Degraded -> 0xFFF59E0B
    is RelayHealthState.Healthy -> 0xFF10B981
}

private fun summaryLine(state: RelayHealthState, dropped: Int): String = when (state) {
    is RelayHealthState.Down -> "No packets relayed — tun relay is not running"
    is RelayHealthState.Degraded -> "${state.stats.relayedFlows} flows relayed · $dropped dropped"
    is RelayHealthState.Healthy -> "${state.stats.relayedFlows} flows relayed · $dropped dropped"
}

@Composable
private fun RelayMetricTile(label: String, count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = "$count",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
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

@Preview(showBackground = true, name = "Healthy compact — light")
@Composable
private fun RelayHealthCardHealthyPreview() {
    RelayHealthCard(
        state = RelayHealthState.Healthy(
            pumpRunning = true,
            stats = TunRelay.RelayStats(
                relayedFlows = 52,
                relayBytesUp = 812_000,
                relayBytesDown = 4_700_000,
                activeTcpV4 = 30,
                activeTcpV6 = 8,
                activeUdpV4 = 10,
                activeUdpV6 = 2,
            ),
            icmpUnrelayableCount = 2,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.PING_SOCKET,
        ),
        onExpand = {},
    )
}

@Preview(showBackground = true, name = "Down compact — light")
@Composable
private fun RelayHealthCardDownPreview() {
    RelayHealthCard(
        state = RelayHealthState.Down(
            pumpRunning = false,
            stats = TunRelay.RelayStats(),
            icmpUnrelayableCount = 0,
            icmpLastPathUsed = IcmpRelay.LastPathUsed.NONE_YET,
        ),
        onExpand = {},
    )
}
