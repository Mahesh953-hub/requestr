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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.example.data.models.TlsFailureDetail
import com.example.data.models.TlsHandshakeStage
import com.example.data.models.TlsRemedyType
import com.example.data.models.TlsStageState
import com.example.data.models.toTlsFailureDetail
import com.example.data.local.TlsSessionEntity

/**
 * Task 14 / Lane D, surface 2: expanded TLS handshake-failure detail sheet for the two approved
 * `tls-failed-detail-*` mockups (unknown_ca → Install CA remedy, handshake_failure → Extend cipher
 * set remedy). Renders a plain-language headline + explanation, a category/"sent by" attribution,
 * the inferred 3-step handshake-stage rail, the raw alert record, session facts read off the entity,
 * and the remedy block with numbered steps plus a primary (filled) and optional secondary (ghost)
 * action button.
 *
 * Opened by tapping a FAILED row in the Intercept screen's TLS tab; the detail is produced by
 * [toTlsFailureDetail] from the real [TlsSessionEntity].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TlsFailureDetailSheet(
    detail: TlsFailureDetail,
    onDismiss: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
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
                Text("TLS handshake failed", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = buildString {
                    append(detail.sniHost ?: detail.connectionId)
                    append(" · session ${detail.connectionId}")
                    // Item 4 (verification pass): detail.failedAt is real, already-flowing data
                    // (TlsSessionEntity.failedAt) that was captured but never rendered here --
                    // the mockup's header subtitle includes it.
                    detail.failedAt?.let {
                        append(" · ")
                        append(java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)))
                    }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgePill(text = "FAILED", color = 0xFFEF4444)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = detail.failureReason,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFFEF4444),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = detail.explanation,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))
            DetailKV("Category", detail.category)
            DetailKV("Alert sent by", detail.alertSentBy)

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("HANDSHAKE STAGE")
            Spacer(modifier = Modifier.height(8.dp))
            detail.stages.forEachIndexed { index, stage ->
                StageRow(stage, isLast = index == detail.stages.lastIndex)
            }

            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel("RAW ALERT")
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                Text(
                    text = detail.failureReason,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel("SESSION FACTS")
            Spacer(modifier = Modifier.height(6.dp))
            DetailKV("SNI host", detail.sniHost ?: "—")
            DetailKV("Offered", detail.offeredVersion ?: "—")
            DetailKV("Cipher / ALPN", listOfNotNull(detail.cipherSuite, detail.alpn).joinToString(" · ").ifEmpty { "not negotiated" })
            DetailKV("JA3", detail.ja3Hash ?: "—")
            DetailKV("Handshake", "${detail.handshakeDurationMs} ms")

            Spacer(modifier = Modifier.height(16.dp))
            SectionLabel("WHAT YOU CAN DO")
            Spacer(modifier = Modifier.height(6.dp))
            detail.remedy.steps.forEachIndexed { i, step ->
                NumberedStep(number = i + 1, text = step)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onPrimaryAction, modifier = Modifier.weight(1f)) {
                    Text(detail.remedy.primaryLabel)
                }
                if (detail.remedy.secondaryLabel != null && onSecondaryAction != null) {
                    OutlinedButton(onClick = onSecondaryAction, modifier = Modifier.weight(1f)) {
                        Text(detail.remedy.secondaryLabel)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StageRow(stage: TlsHandshakeStage, isLast: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.width(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (dotColor, dotBorder) = when (stage.state) {
                TlsStageState.DONE -> Color(0xFF10B981) to false
                TlsStageState.FAILED -> Color(0xFFEF4444) to false
                TlsStageState.NOT_REACHED -> MaterialTheme.colorScheme.outline to true
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (dotBorder) MaterialTheme.colorScheme.surface else dotColor)
                    .padding(0.dp)
            ) {
                if (dotBorder) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(1.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 12.dp)) {
            Text(
                text = stage.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (stage.state == TlsStageState.NOT_REACHED) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stage.detail,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
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

@Preview(showBackground = true, name = "unknown_ca detail — light")
@Composable
private fun TlsFailureDetailSheetUnknownCaPreview() {
    TlsFailureDetailSheet(
        detail = toTlsFailureDetail(
            TlsSessionEntity(
                tlsSessionId = "tls-8f42c1",
                sessionId = 1,
                connectionId = "tls-8f42c1",
                sniHost = "pay.stripe.com",
                version = "TLSv1.2",
                cipherSuite = null,
                negotiatedProtocol = null,
                ja3Hash = "cd08e31494f9531f560d64c695473da9",
                handshakeState = "FAILED",
                wasDecrypted = false,
                peerCertSubject = null,
                peerCertIssuer = null,
                handshakeDurationMs = 62,
                failureReason = "alert: unknown_ca",
                failedAt = 1724274251000,
            )
        )!!,
        onDismiss = {},
        onPrimaryAction = {},
        onSecondaryAction = {},
    )
}

@Preview(showBackground = true, name = "handshake_failure detail — light")
@Composable
private fun TlsFailureDetailSheetHandshakeFailurePreview() {
    TlsFailureDetailSheet(
        detail = toTlsFailureDetail(
            TlsSessionEntity(
                tlsSessionId = "tls-8f42c1",
                sessionId = 1,
                connectionId = "tls-8f42c1",
                sniHost = "clients4.google.com",
                version = "TLSv1.3",
                cipherSuite = null,
                negotiatedProtocol = null,
                ja3Hash = "51c64c77e60f3980eea90869b68c58a8",
                handshakeState = "FAILED",
                wasDecrypted = false,
                peerCertSubject = null,
                peerCertIssuer = null,
                handshakeDurationMs = 38,
                failureReason = "alert: handshake_failure",
                failedAt = 1724274251000,
            )
        )!!,
        onDismiss = {},
        onPrimaryAction = {},
        onSecondaryAction = {},
    )
}
