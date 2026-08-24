package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.Http3DetailState
import com.example.interception.PacketPump

/**
 * Task 14 / Lane D, surface 3: HTTP/3 / QUIC detail bottom sheet for the three approved
 * `http3-detail-*` mockups — decoded, undecrypted (observed, no keys), and QUIC-only (detected, no
 * ALPN). Switches exhaustively over [Http3DetailState].
 *
 * The decoded state reports the real decoded-exchange count and connection facts read off the live
 * summary; it does not render header key/value pairs, because the decoder (held privately in
 * PacketPump, consumed read-only by this lane) exposes only the exchange *count* via
 * [PacketPump.QuicLiveSummary], not the exchanges themselves — fabricating header content would
 * violate the plan's "no decoded state without authoritative data" rule. The undecrypted and
 * QUIC-only states end with the mockups' numbered remedy steps and dual action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Http3DetailSheet(
    state: Http3DetailState,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
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
                Text("HTTP/3 detail", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (state) {
                is Http3DetailState.Decoded -> DecodedBody(state)
                is Http3DetailState.Undecrypted -> UndecryptedBody(state, onPrimaryAction, onSecondaryAction)
                is Http3DetailState.QuicOnly -> QuicOnlyBody(state, onPrimaryAction, onSecondaryAction)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DecodedBody(state: Http3DetailState.Decoded) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BadgePill(text = "HTTP/3", color = 0xFF10B981)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (state.partial) "Partially decoded" else "Fully decoded",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    InfoNote(
        "TLS key material was available for this connection, so QUIC STREAM frames were reassembled " +
            "and QPACK headers decoded. ${state.decodedHttp3Exchanges} HTTP/3 exchange(s) decoded.",
        Color(0xFF10B981),
    )

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("QUIC CONNECTION")
    Spacer(modifier = Modifier.height(6.dp))
    DetailKV("QUIC version", state.quicVersion?.let { "0x%08X".format(it) } ?: "unknown")
    DetailKV("ALPN", state.alpn ?: "unknown")
    DetailKV("SNI", state.sniHost ?: "unknown")
    DetailKV("5-tuple", state.fiveTuple)
    state.cipherSuite?.let { DetailKV("Cipher suite", it) }

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("DECODED")
    Spacer(modifier = Modifier.height(6.dp))
    DetailKV("HTTP/3 exchanges", "${state.decodedHttp3Exchanges}")
    if (state.keyUpdatesPromoted > 0) DetailKV("Key updates", "${state.keyUpdatesPromoted}")
    if (state.resolvedKeyLabels.isNotEmpty()) DetailKV("Keys resolved", state.resolvedKeyLabels.joinToString(", "))
}

@Composable
private fun UndecryptedBody(
    state: Http3DetailState.Undecrypted,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BadgePill(text = "OBSERVED", color = 0xFFF59E0B)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Observed, not decoded — no keys", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(modifier = Modifier.height(8.dp))
    InfoNote(
        "A real HTTP/3 connection, and requestr read every packet. This is not a decode error: QUIC " +
            "payloads are encrypted with handshake-derived keys, and no key material exists for this " +
            "connection, so frames stay opaque.",
        Color(0xFFF59E0B),
    )

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("QUIC CONNECTION")
    Spacer(modifier = Modifier.height(6.dp))
    DetailKV("QUIC version", state.quicVersion?.let { "0x%08X".format(it) } ?: "unknown")
    DetailKV("ALPN", state.alpn ?: "unknown")
    DetailKV("SNI", state.sniHost ?: "unknown")
    DetailKV("5-tuple", state.fiveTuple)
    DetailKV("Observed", "${formatByteCount(state.bytesReceived)}")
    DetailKV("Decoded", "Initial packet only")
    DetailKV("Keys", if (state.missingKeyLabels.isNotEmpty()) state.missingKeyLabels.joinToString(", ") + " · no 1-RTT keys" else "no 1-RTT keys")

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("TO MAKE THIS DECODABLE")
    Spacer(modifier = Modifier.height(6.dp))
    NumberedStep(1, "Install the requestr CA and let the MITM tier terminate this connection, so the keys are ours.")
    NumberedStep(2, "Or block UDP/443 for this app so it downgrades to HTTP/2 over TCP, already decrypted by the MITM tier.")
    Spacer(modifier = Modifier.height(14.dp))
    ActionRow("Force TCP fallback", "Install requestr CA", onPrimaryAction, onSecondaryAction)
}

@Composable
private fun QuicOnlyBody(
    state: Http3DetailState.QuicOnly,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BadgePill(text = "DETECTED", color = 0xFF64748B)
        Spacer(modifier = Modifier.width(8.dp))
        Text("QUIC detected, HTTP/3 not confirmed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(modifier = Modifier.height(8.dp))
    InfoNote(
        "One QUIC datagram matched a known version, but no ClientHello ALPN was recovered — so " +
            "requestr will not claim this is HTTP/3. It stays QUIC until a decodable Initial packet " +
            "proves otherwise.",
        Color(0xFF64748B),
    )

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("QUIC CONNECTION")
    Spacer(modifier = Modifier.height(6.dp))
    DetailKV("QUIC version", state.quicVersion?.let { "0x%08X".format(it) } ?: "unknown")
    DetailKV("ALPN", "unknown · CRYPTO not reassembled")
    DetailKV("SNI", state.sniHost ?: "unknown · taken from DNS answer")
    DetailKV("5-tuple", state.fiveTuple)
    DetailKV("Observed", "${formatByteCount(state.bytesReceived)}")
    if (state.retriesAccepted > 0 || state.retriesRejected > 0) {
        DetailKV("Retry packets", "${state.retriesAccepted} accepted · ${state.retriesRejected} rejected")
    }

    Spacer(modifier = Modifier.height(14.dp))
    SectionLabel("TO CONFIRM HTTP/3")
    Spacer(modifier = Modifier.height(6.dp))
    NumberedStep(1, "Keep capturing — the next handshake on this host usually arrives in a single Initial packet and yields SNI + ALPN.")
    NumberedStep(2, "The relay must pass full 1,500-byte datagrams; a lowered tun MTU truncates coalesced Initial packets.")
    Spacer(modifier = Modifier.height(14.dp))
    ActionRow("Check relay MTU", "Connections sheet", onPrimaryAction, onSecondaryAction)
}

@Composable
private fun ActionRow(primary: String, secondary: String, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text(primary) }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) { Text(secondary) }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = "$number",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailKV(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun InfoNote(text: String, color: Color) {
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

@Preview(showBackground = true, name = "H3 decoded detail — light")
@Composable
private fun Http3DetailSheetDecodedPreview() {
    Http3DetailSheet(
        state = Http3DetailState.Decoded(
            connectionId = "8f2a41c0d95b77e3",
            sniHost = "api.openai.com",
            quicVersion = 1,
            alpn = "h3",
            fiveTuple = "10.0.0.2:52014 → 104.18.6.192:443",
            cipherSuite = "TLS_AES_128_GCM_SHA256",
            decodedHttp3Exchanges = 2,
            keyUpdatesPromoted = 0,
            resolvedKeyLabels = listOf("CLIENT_HANDSHAKE_TRAFFIC_SECRET", "CLIENT_TRAFFIC_SECRET_0"),
            partial = false,
        ),
        onDismiss = {},
        onPrimaryAction = {},
        onSecondaryAction = {},
    )
}

@Preview(showBackground = true, name = "H3 undecrypted detail — light")
@Composable
private fun Http3DetailSheetUndecryptedPreview() {
    Http3DetailSheet(
        state = Http3DetailState.Undecrypted(
            connectionId = "b41d7ee209fa3c58",
            sniHost = "rr3---sn-8pxuuxa.googlevideo.com",
            quicVersion = 1,
            alpn = "h3",
            fiveTuple = "10.0.0.2:49711 → 172.217.16.42:443",
            bytesReceived = 22_400_000,
            missingKeyLabels = listOf("CLIENT_TRAFFIC_SECRET_0"),
            keyMaterialMissing = true,
            cipherSuiteUnavailable = false,
        ),
        onDismiss = {},
        onPrimaryAction = {},
        onSecondaryAction = {},
    )
}

@Preview(showBackground = true, name = "H3 QUIC-only detail — light")
@Composable
private fun Http3DetailSheetQuicOnlyPreview() {
    Http3DetailSheet(
        state = Http3DetailState.QuicOnly(
            connectionId = "7c02be5f",
            sniHost = "chat.signal.org",
            quicVersion = 1,
            fiveTuple = "10.0.0.2:51288 → 13.248.212.111:443",
            bytesReceived = 1232,
            retriesAccepted = 0,
            retriesRejected = 0,
        ),
        onDismiss = {},
        onPrimaryAction = {},
        onSecondaryAction = {},
    )
}
