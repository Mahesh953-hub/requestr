package com.example.correlation.graph

import android.content.Context
import com.example.correlation.ObservationEvent
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.DnsQueryEntity
import com.example.data.local.QuicConnectionEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TlsSessionEntity

/**
 * D2: reconstructs the DNS -> TCP/QUIC -> TLS -> HTTP causal chain for one connection, by reading
 * back what the capture engine actually persisted to Room -- not a synthetic/demo chain.
 *
 * This is deliberately a *read-path* over existing tables rather than a new write path: DNS
 * (v5.7, see [com.example.data.local.DnsQueryEntity]), TCP/QUIC (M1) and TLS (M2) rows are
 * already written by [com.example.interception.PacketPump] / [com.example.interception.CaptureEngine]
 * / [com.example.interception.MitmProxyEngine]. [com.example.data.local.CapturedTrafficEntity] (HTTP)
 * has no `connectionId` column, so the HTTP step is matched by host + a timestamp window instead
 * of an exact key join -- the same heuristic [com.example.correlation.CorrelationEngine] already
 * uses for cross-plane correlation, not a new invented convention.
 *
 * Honest limitations, surfaced as failed/missing [LineageStep]s rather than fabricated:
 *  - The DNS step is only found if the destination IP was actually resolved via a UDP:53 query
 *    this capture session observed. Hardcoded IPs, OS-level DNS cache hits from before capture
 *    started, and DoH/DoT (this parser only listens on port 53) will show as "no DNS resolution
 *    found" -- a real gap, not a bug to hide.
 *  - [com.example.interception.tls.TlsHandshakeState.FAILED] is defined but never actually set
 *    anywhere in the codebase today, so a TLS step's `success` flag can only ever be false when
 *    the step itself is entirely missing (no TLS row found) -- there is no genuine "handshake
 *    attempted and failed" signal to surface yet.
 *  - The HTTP step is a best-effort host+time-window match; on a connection that is TLS'd but
 *    where HTTP parsing didn't run (e.g. non-HTTP protocol over TLS, or MITM inspection skipped
 *    it), it will legitimately come back empty.
 */
object ConnectionLineage {

    /** How long after a TLS/TCP handshake we still consider an HTTP transaction "part of it". */
    private const val HTTP_WINDOW_MS = 30_000L

    /**
     * D1: exposes the same DNS/TLS host-resolution logic [build] uses internally, so
     * [ProvenanceGraph] can group connections by the host they actually dialed without
     * duplicating the DNS-answer / TLS-SNI matching rules.
     */
    suspend fun resolveHostFor(context: Context, sessionId: Long, connectionId: String): String? {
        val db = RequestrDatabase.get(context)
        val tcp = db.tcpConnectionDao().getById(connectionId)
        if (tcp != null) {
            val dnsRow = findDnsAnswerFor(db, sessionId, tcp.destIp, tcp.establishedAt)
            val tls = findTlsFor(db, sessionId, connectionId, dnsRow?.hostname)
            return tls?.sniHost ?: dnsRow?.hostname ?: tcp.destIp
        }
        val quic = db.quicConnectionDao().getById(connectionId)
        if (quic != null) {
            val dnsRow = findDnsAnswerFor(db, sessionId, quic.destIp, quic.establishedAt)
            return quic.sniHost ?: dnsRow?.hostname ?: quic.destIp
        }
        return null
    }

    suspend fun build(context: Context, sessionId: Long, connectionId: String): LineageChain {
        val db = RequestrDatabase.get(context)
        val tcp = db.tcpConnectionDao().getById(connectionId)
        if (tcp != null) return buildForTcp(db, sessionId, connectionId, tcp)

        val quic = db.quicConnectionDao().getById(connectionId)
        if (quic != null) return buildForQuic(db, sessionId, connectionId, quic)

        return LineageChain(
            connectionId = connectionId,
            steps = listOf(
                LineageStep(
                    plane = ObservationEvent.ObserverSource.PACKET,
                    label = "Connection not found",
                    detail = "No tcp_connections or quic_connections row exists for connectionId=$connectionId in session=$sessionId.",
                    timestamp = System.currentTimeMillis(),
                    success = false,
                )
            ),
        )
    }

    private suspend fun buildForTcp(
        db: RequestrDatabase,
        sessionId: Long,
        connectionId: String,
        tcp: TcpConnectionEntity,
    ): LineageChain {
        val steps = mutableListOf<LineageStep>()

        val dnsRow = findDnsAnswerFor(db, sessionId, tcp.destIp, tcp.establishedAt)
        steps += dnsStep(dnsRow, tcp.destIp, tcp.establishedAt)

        steps += LineageStep(
            plane = ObservationEvent.ObserverSource.PACKET,
            label = "TCP ${tcp.srcIp}:${tcp.srcPort} -> ${tcp.destIp}:${tcp.destPort}",
            detail = "state=${tcp.state} sent=${tcp.bytesSent}B recv=${tcp.bytesReceived}B retransmissions=${tcp.retransmissions}",
            timestamp = tcp.establishedAt,
            success = tcp.state != "SYN_SENT",
        )

        val tls = findTlsFor(db, sessionId, connectionId, dnsRow?.hostname)
        if (tls != null) {
            steps += tlsStep(tls)
            val host = tls.sniHost ?: dnsRow?.hostname
            if (host != null) {
                steps += httpSteps(db, sessionId, host, tls.establishedAt)
            }
        } else {
            steps += LineageStep(
                plane = ObservationEvent.ObserverSource.PACKET,
                label = "No TLS handshake observed",
                detail = "Either this connection is plaintext, or a ClientHello was never captured for it (short-lived connection, TLS detection window missed).",
                timestamp = tcp.establishedAt,
                success = false,
            )
            // Plaintext HTTP fallback: match by destIp/sniHost-less host within the TCP window.
            steps += httpSteps(db, sessionId, dnsRow?.hostname ?: tcp.destIp, tcp.establishedAt)
        }

        return LineageChain(connectionId = connectionId, steps = steps)
    }

    private suspend fun buildForQuic(
        db: RequestrDatabase,
        sessionId: Long,
        connectionId: String,
        quic: QuicConnectionEntity,
    ): LineageChain {
        val steps = mutableListOf<LineageStep>()

        val dnsRow = findDnsAnswerFor(db, sessionId, quic.destIp, quic.establishedAt)
        steps += dnsStep(dnsRow, quic.destIp, quic.establishedAt)

        steps += LineageStep(
            plane = ObservationEvent.ObserverSource.QUIC,
            label = "QUIC ${quic.srcIp}:${quic.srcPort} -> ${quic.destIp}:${quic.destPort}",
            detail = "sni=${quic.sniHost ?: "unknown"} alpn=${quic.alpn ?: "unknown"} version=0x${(quic.quicVersion ?: 0).toString(16)}",
            timestamp = quic.establishedAt,
            success = quic.sniHost != null,
        )

        val host = quic.sniHost ?: dnsRow?.hostname
        if (host != null) {
            steps += httpSteps(db, sessionId, host, quic.establishedAt)
        }

        return LineageChain(connectionId = connectionId, steps = steps)
    }

    /**
     * D2's DNS join: [DnsQueryEntity.resolvedIps] is only populated on *response* rows that
     * [com.example.interception.dns.DnsParser] genuinely decoded A/AAAA records for. We take the
     * most recent one, at or before the connection was established, whose resolvedIps contains
     * the connection's destination IP.
     */
    private suspend fun findDnsAnswerFor(
        db: RequestrDatabase,
        sessionId: Long,
        destIp: String,
        beforeTs: Long,
    ): DnsQueryEntity? {
        return db.dnsQueryDao().getBySession(sessionId)
            .asSequence()
            .filter { it.isResponse && it.timestamp <= beforeTs && destIp in it.resolvedIps }
            .maxByOrNull { it.timestamp }
    }

    private fun dnsStep(dnsRow: DnsQueryEntity?, destIp: String, beforeTs: Long): LineageStep {
        return if (dnsRow != null) {
            LineageStep(
                plane = ObservationEvent.ObserverSource.DNS,
                label = "DNS resolved ${dnsRow.hostname} -> $destIp",
                detail = "type=${dnsRow.queryType} resolver=${dnsRow.resolver} answers=${dnsRow.resolvedIps.joinToString()}",
                timestamp = dnsRow.timestamp,
                success = true,
            )
        } else {
            LineageStep(
                plane = ObservationEvent.ObserverSource.DNS,
                label = "No DNS resolution found for $destIp",
                detail = "Not observed via UDP:53 in this session -- could be a hardcoded IP, an OS-level DNS cache hit from before capture started, or DoH/DoT (this capture path only sees plaintext UDP:53).",
                timestamp = beforeTs,
                success = false,
            )
        }
    }

    /**
     * TLS lookup handles both connectionId conventions in the schema:
     *  - Undecoded (VPN-only) path: `tlsSessionId == TcpStream.key == connectionId` (see
     *    [com.example.interception.CaptureEngine]).
     *  - MITM path: `tlsSessionId == "$host:$port"` (see
     *    [com.example.interception.MitmProxyEngine.persistMitmTlsSession]), which does not match
     *    the TCP 5-tuple connectionId at all -- so when the direct lookup misses, fall back to
     *    the most recent TLS session in this capture session whose SNI matches the DNS-resolved
     *    hostname for this connection.
     */
    private suspend fun findTlsFor(
        db: RequestrDatabase,
        sessionId: Long,
        connectionId: String,
        dnsHostname: String?,
    ): TlsSessionEntity? {
        db.tlsSessionDao().getById(connectionId)?.let { return it }
        if (dnsHostname != null) {
            db.tlsSessionDao().getLatestBySniHost(sessionId, dnsHostname)?.let { return it }
        }
        return null
    }

    private fun tlsStep(tls: TlsSessionEntity): LineageStep {
        return LineageStep(
            plane = if (tls.wasDecrypted) ObservationEvent.ObserverSource.MITM else ObservationEvent.ObserverSource.PACKET,
            label = "TLS handshake (${tls.sniHost ?: tls.connectionId})",
            detail = "version=${tls.version ?: "?"} cipher=${tls.cipherSuite ?: "unknown (${if (tls.wasDecrypted) "should be known" else "SNI/JA3-only, not negotiated from our view"})"} " +
                "alpn=${tls.negotiatedProtocol ?: "?"} decrypted=${tls.wasDecrypted} state=${tls.handshakeState}" +
                (tls.peerCertSubject?.let { " cert=$it" } ?: "") +
                (tls.failureReason?.let { " failureReason=$it" } ?: ""),
            timestamp = tls.establishedAt,
            // Task 11: CaptureEngine and MitmProxyEngine now both write handshakeState = FAILED
            // with a real failureReason (fatal alert, RST during handshake, close before
            // established, or an SSLHandshakeException on either MITM leg), so this is a
            // genuine outcome, not an unreachable state.
            success = tls.handshakeState != "FAILED",
        )
    }

    private suspend fun httpSteps(
        db: RequestrDatabase,
        sessionId: Long,
        host: String,
        anchorTs: Long,
    ): List<LineageStep> {
        val rows = db.capturedTrafficDao().getByHostInWindow(sessionId, host, anchorTs, anchorTs + HTTP_WINDOW_MS)
        if (rows.isEmpty()) {
            return listOf(
                LineageStep(
                    plane = ObservationEvent.ObserverSource.PROXY,
                    label = "No HTTP transaction observed for $host",
                    detail = "No captured_traffic row for host=$host within ${HTTP_WINDOW_MS}ms of the handshake -- either no HTTP traffic followed, or it wasn't decoded (non-HTTP protocol, or MITM inspection skipped it).",
                    timestamp = anchorTs,
                    success = false,
                )
            )
        }
        return rows.map { httpStep(it) }
    }

    private fun httpStep(row: CapturedTrafficEntity): LineageStep {
        return LineageStep(
            plane = if (row.wasDecrypted) ObservationEvent.ObserverSource.MITM else ObservationEvent.ObserverSource.PROXY,
            label = "${row.method} ${row.path.ifEmpty { row.url }}",
            detail = "status=${row.statusCode ?: "?"} protocol=${row.protocol.ifEmpty { "HTTP/1.1" }} " +
                "decrypted=${row.wasDecrypted} undecoded=${row.isUndecoded} reqBytes=${row.requestSizeBytes} respBytes=${row.responseSizeBytes}",
            timestamp = row.timestamp,
            success = row.statusCode == null || row.statusCode in 100..399,
        )
    }
}

/** One causal step in a connection's lineage, ordered by [timestamp]. */
data class LineageStep(
    val plane: ObservationEvent.ObserverSource,
    val label: String,
    val detail: String,
    val timestamp: Long,
    val success: Boolean,
)

/** The full DNS -> TCP/QUIC -> TLS -> HTTP chain for one connection. */
data class LineageChain(
    val connectionId: String,
    val steps: List<LineageStep>,
)
