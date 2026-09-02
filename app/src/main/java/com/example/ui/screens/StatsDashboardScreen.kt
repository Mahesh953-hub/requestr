package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.CapturedTrafficItem
import com.example.ui.components.formatByteCount

/**
 * C5: read-only aggregate dashboard over the currently captured traffic ([CaptureStats] /
 * [computeCaptureStats], pure and unit-tested in CapturedTrafficHelpersTest.kt) -- total
 * requests, error rate, method mix, status-code distribution, size/latency summary and the
 * busiest hosts. Recomputed via [remember] whenever [items] changes, so it always reflects the
 * live Room-backed capture list, not a stale snapshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsDashboardBottomSheet(
    items: List<CapturedTrafficItem>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stats = remember(items) { computeCaptureStats(items) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Traffic stats",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (stats.total == 0) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nothing captured yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(label = "Total", value = stats.total.toString())
                        StatTile(label = "Errors (4xx/5xx)", value = "${stats.errorCount}")
                        StatTile(label = "Total size", value = formatByteCount(stats.totalBytes))
                    }
                }

                item {
                    SectionLabel("Latency")
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(label = "Min", value = "${stats.minLatencyMs} ms")
                        StatTile(label = "Avg", value = "%.0f ms".format(stats.averageLatencyMs))
                        StatTile(label = "Max", value = "${stats.maxLatencyMs} ms")
                    }
                }

                item {
                    SectionLabel("Average size / request")
                    Text(
                        text = "%.0f B".format(stats.averageBytesPerRequest),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                item {
                    SectionLabel("Status codes")
                }
                items(stats.byStatusClass.entries.sortedBy { it.key }.toList()) { (statusClass, count) ->
                    StatBar(label = statusClass, count = count, total = stats.total)
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    SectionLabel("Methods")
                }
                items(stats.byMethod.entries.sortedByDescending { it.value }.toList()) { (method, count) ->
                    StatBar(label = method, count = count, total = stats.total)
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    SectionLabel("Top hosts")
                }
                items(stats.topHosts) { (host, count) ->
                    StatBar(label = host, count = count, total = stats.total)
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatBar(label: String, count: Int, total: Int) {
    val fraction = if (total > 0) count.toFloat() / total.toFloat() else 0f
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(110.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "$count", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
