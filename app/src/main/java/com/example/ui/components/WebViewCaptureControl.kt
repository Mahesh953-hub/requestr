package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.WebViewCaptureState
import com.example.data.models.WebViewCapturedRequest

/**
 * v6.1 Phase A / Domain B: the "OFFSCREEN WEBVIEW CAPTURE" control from
 * `runtime-instrumentation-healthy-*.html` (idle) and `runtime-instrumentation-webview-active-*.html`
 * (active session with live status, elapsed time, request count, and a RECENT CAPTURES peek).
 *
 * The URL field + Start/Stop pair drive [com.example.runtime.webview.WebViewInstrumentation]'s real
 * offscreen WebView. Engine lifecycle stays where it belongs: Start loads a URL and Stop ends the
 * *capture session* (navigating away and clearing session telemetry) — neither creates nor destroys
 * the WebView instance, which is owned by `WebViewAdapter` under the instrumentation engine.
 *
 * Every figure shown is measured: elapsed time from the real submit timestamp, request count and
 * byte total accumulated from actually-captured events, and the peek rows are genuine captured
 * requests. With no session started the control says exactly that instead of showing a zeroed
 * "session".
 */
@Composable
fun WebViewCaptureControl(
    state: WebViewCaptureState,
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        RuntimeSubLabel("OFFSCREEN WEBVIEW CAPTURE")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = if (state.capturing) state.requestedUrl.orEmpty() else urlInput,
                onValueChange = onUrlInputChange,
                enabled = !state.capturing,
                label = { Text("https://example.com", fontSize = 11.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f).componentId("webview-capture-url"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (state.capturing) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.componentId("webview-capture-stop"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = onStart,
                    // No live WebView means nothing can capture, so Start is disabled rather than
                    // accepting a URL that would silently go nowhere.
                    enabled = urlInput.isNotBlank() && state.adapterAvailable,
                    modifier = Modifier.componentId("webview-capture-start"),
                ) {
                    Text("Start", fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeStatusDot(color = if (state.capturing) RuntimeGreen else RuntimeGray)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusLine(state),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.adapterAvailable) {
            Text(
                "WebView adapter is not running, so capture is unavailable — ${state.statusText}",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.recentCaptures.isNotEmpty()) {
            RuntimeFlatCard(modifier = Modifier.padding(top = 8.dp)) {
                RuntimeSpreadRow {
                    Text(
                        "RECENT CAPTURES",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "real captured events",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.recentCaptures.forEach { CaptureRow(it) }
            }
        }
    }
}

@Composable
private fun CaptureRow(capture: WebViewCapturedRequest) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            capture.method ?: "?",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            capture.path,
            modifier = Modifier.weight(1f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            capture.statusCode?.toString() ?: "err",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (capture.statusCode == null) RuntimeRed else RuntimeGreen,
        )
        Text(
            capture.source,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The mockup's one-line status. Elapsed time and byte totals are only rendered when they are
 * genuinely known (a real submit timestamp / real response sizes); otherwise they are simply absent
 * rather than shown as `00:00` / `0 B`.
 */
internal fun statusLine(state: WebViewCaptureState): String {
    if (!state.adapterAvailable) {
        return "Unavailable · no offscreen WebView is running, so nothing can be captured"
    }
    if (!state.capturing) {
        return "Idle · no capture session · enter a URL and press Start to capture page fetch/XHR through the proxy"
    }
    val elapsed = state.startedAtMs?.let { formatElapsed(System.currentTimeMillis() - it) }
    val bytes = state.responseBytes.takeIf { it > 0 }?.let { formatBytes(it) }
    return buildString {
        append("Capturing")
        if (elapsed != null) append(" · $elapsed elapsed")
        append(" · ${state.requestCount} requests")
        if (bytes != null) append(" · $bytes")
    }
}

internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
