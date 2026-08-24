package com.example.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.Http3RowState

/**
 * Task 14 / Lane D: row-level HTTP/3 / QUIC indicator for the Intercept traffic list, matching the
 * `http3-row-indicators-*` mockups. Renders a coloured [BadgePill] (green decoded / amber observed /
 * gray detected) beside the existing protocol badge, plus a one-line QUIC subtitle beneath the row.
 *
 * The pill is **additive** — it never edits the shared [ProtocolBadge] (owned by Lane A). The state
 * is derived by the caller from [com.example.data.models.CapturedTrafficItem]'s `protocolHint` and
 * whether a status line decoded, so no decode status is ever inferred inside this composable.
 */
@Composable
fun Http3RowIndicator(
    state: Http3RowState,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (state) {
        Http3RowState.DECODED -> "DECODED" to 0xFF10B981L
        Http3RowState.OBSERVED -> "OBSERVED" to 0xFFF59E0BL
        Http3RowState.DETECTED -> "DETECTED" to 0xFF64748BL
    }
    BadgePill(text = label, color = color, modifier = modifier)
}

/**
 * The QUIC-specific subtitle line shown beneath an HTTP/3 / QUIC traffic row. Uses the row's
 * resolved ALPN where present and a decode-state phrase; QUIC version is intentionally omitted
 * because the traffic-row model does not carry it (showing it would be fabricated).
 */
@Composable
fun Http3RowSubtitle(
    state: Http3RowState,
    alpn: String?,
    modifier: Modifier = Modifier,
) {
    val phrase = when (state) {
        Http3RowState.DECODED -> "headers + body decoded"
        Http3RowState.OBSERVED -> "no TLS keys for this connection"
        Http3RowState.DETECTED -> "QUIC detected · no HTTP/3 frames decoded"
    }
    val text = if (!alpn.isNullOrBlank()) "alpn=$alpn · $phrase" else phrase
    Text(
        text = text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Derives the row indicator state from a traffic row's protocol hint and whether it decoded. */
fun http3RowState(protocolHint: com.example.data.models.ProtocolHint, statusCode: Int): Http3RowState? =
    when (protocolHint) {
        com.example.data.models.ProtocolHint.HTTP3 -> if (statusCode > 0) Http3RowState.DECODED else Http3RowState.OBSERVED
        com.example.data.models.ProtocolHint.QUIC -> Http3RowState.DETECTED
        else -> null
    }

@Preview(showBackground = true, name = "H3 row indicator — light")
@Composable
private fun Http3RowIndicatorPreview() {
    androidx.compose.foundation.layout.Column {
        Http3RowIndicator(Http3RowState.DECODED)
        Spacer(modifier = Modifier.width(6.dp))
        Http3RowSubtitle(Http3RowState.DECODED, alpn = "h3")
        Spacer(modifier = Modifier.width(6.dp))
        Http3RowIndicator(Http3RowState.OBSERVED)
        Http3RowSubtitle(Http3RowState.OBSERVED, alpn = "h3")
        Spacer(modifier = Modifier.width(6.dp))
        Http3RowIndicator(Http3RowState.DETECTED)
        Http3RowSubtitle(Http3RowState.DETECTED, alpn = null)
    }
}
