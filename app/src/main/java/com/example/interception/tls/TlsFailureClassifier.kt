package com.example.interception.tls

import com.example.interception.tcp.TcpHandshakePhase

/**
 * Task 11: the real reason a TLS handshake reached [TlsHandshakeState.FAILED], if it did.
 *
 * Pulled out of [com.example.interception.CaptureEngine] so the mapping from (handshake phase,
 * parsed alert, existing-row state) to a FAILED verdict is testable without Room or sockets.
 */
data class TlsOutcome(val reason: String)

/**
 * Classifies whether a TLS stream should be marked [TlsHandshakeState.FAILED] and why.
 *
 * Returns `null` when there is nothing to report — either because the handshake already
 * succeeded (a later RST or a `close_notify` warning after [hasEstablishedRow] is true is
 * normal connection teardown, not a handshake failure) or because [hasClientHello] is false,
 * meaning this stream never started a TLS handshake at all. **A stream that never sent a
 * ClientHello must stay untouched** — the current schema has no way to distinguish "never
 * attempted" from "failed", so this function deliberately refuses to invent a FAILED verdict
 * for it.
 *
 * A fatal TLS alert (RFC 8446 §6, [TlsAlert.isFatal]) is checked first and always wins: it is
 * the most specific signal available, and per [TlsDecoder.parseAlert]'s doc it can only ever be
 * observed pre-key (i.e. during the handshake), so a fatal alert is unambiguous.
 */
internal fun classifyTlsOutcome(
    phase: TcpHandshakePhase,
    alert: TlsAlert?,
    hasEstablishedRow: Boolean,
    hasClientHello: Boolean = true,
): TlsOutcome? {
    if (!hasClientHello) return null

    if (alert != null && alert.isFatal) {
        return TlsOutcome("alert: ${alert.descriptionName}")
    }

    // Handshake already reached ESTABLISHED -- a subsequent RST/FIN/close_notify is ordinary
    // connection teardown, not a handshake failure.
    if (hasEstablishedRow) return null

    return when (phase) {
        TcpHandshakePhase.RESET -> TlsOutcome("connection reset during handshake")
        TcpHandshakePhase.CLOSED -> TlsOutcome("closed before handshake completed")
        else -> null
    }
}
