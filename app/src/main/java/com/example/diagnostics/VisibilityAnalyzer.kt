package com.example.diagnostics

import android.content.Context
import com.example.correlation.graph.LineageStep
import com.example.data.local.RequestrDatabase
import com.example.interception.tls.TlsHandshakeState

/**
 * D3: answers "what fraction of this session's traffic did requestr actually observe/decrypt,
 * and why not the rest" by computing real percentages from the rows the capture engine already
 * persisted -- no fabricated/sample numbers. An app that made zero connections legitimately
 * produces an [ObservationReport] with everything at 0/0, which [VisibilityAnalyzer] reports
 * honestly as "no data" rather than synthesizing a fake baseline.
 *
 * Known, honest limitation carried over from the schema: TLS 1.3 encrypts alerts once handshake
 * keys are established (see [com.example.interception.tls.TlsDecoder.parseAlert]'s doc), so a
 * FAILED-classified [TlsHandshakeState] only ever reflects what [CaptureEngine]/[MitmProxyEngine]
 * could actually observe -- a pre-key fatal alert, an observed RST/close before ESTABLISHED, or
 * an [javax.net.ssl.SSLHandshakeException] on a MITM leg. A handshake that fails silently on a
 * plane requestr never terminates and never sees a plaintext signal for is indistinguishable
 * from one that simply never started; that gap is real, not fabricated away.
 *
 * W6/DEC-4: [ConnectionLineage] attaches HTTP rows by an exact `connectionId` join rather than a
 * host+timestamp window, which means an HTTP row whose producer had no connection identity to
 * report belongs to no lineage at all. Rather than let those rows silently deflate the
 * per-connection coverage fractions, they are counted in
 * [ObservationReport.unattachedHttpRows] and rendered in the Settings "OBSERVATION REPORT" card.
 */
object VisibilityAnalyzer {

    suspend fun analyze(context: Context, sessionId: Long): ObservationReport {
        val db = RequestrDatabase.get(context)

        val tcp = db.tcpConnectionDao().getBySession(sessionId)
        val quic = db.quicConnectionDao().getBySession(sessionId)
        val udp = db.udpFlowDao().getBySession(sessionId)
        val tls = db.tlsSessionDao().getBySession(sessionId)
        val http = db.capturedTrafficDao().getBySession(sessionId)
        val dns = db.dnsQueryDao().getBySession(sessionId)

        val totalConnections = tcp.size + quic.size
        val tlsTotal = tls.size
        val tlsDecrypted = tls.count { it.wasDecrypted }
        val tlsSniOnly = tlsTotal - tlsDecrypted
        val tlsFailedReported = tls.count { it.handshakeState == TlsHandshakeState.FAILED.name }

        val httpTotal = http.size
        val httpDecrypted = http.count { it.wasDecrypted }
        val httpUndecoded = http.count { it.isUndecoded }
        val httpPlaintext = httpTotal - httpDecrypted - httpUndecoded

        val dnsQueries = dns.count { !it.isResponse }
        val dnsResponses = dns.count { it.isResponse }
        val dnsResolvedWithIps = dns.count { it.isResponse && it.resolvedIps.isNotEmpty() }

        // W6/DEC-4: now that ConnectionLineage joins HTTP rows on an exact `connectionId` instead
        // of a host+timestamp window, an HTTP row whose producer could not attribute a
        // connectionId is no longer quietly attached to a plausible-looking connection -- it is
        // simply not in any lineage. That is more honest, but it would silently shrink the
        // coverage numbers below with no explanation, so we count those rows explicitly and
        // surface them rather than letting them vanish.
        val unattachedHttpRows = http.count { it.connectionId.isNullOrBlank() }

        // "Coverage": of every TCP/QUIC connection, how many got at least as far as a TLS
        // handshake we saw, and of those how many got all the way to a decoded HTTP transaction
        // (matched by exact connectionId, exactly as ConnectionLineage does). This reuses
        // ConnectionLineage rather than re-deriving the matching rule a second time.
        var connectionsWithTls = 0
        var connectionsWithHttp = 0
        for (c in tcp) {
            val lineage = com.example.correlation.graph.ConnectionLineage.build(context, sessionId, c.connectionId)
            // Classify by LineageStep.kind, never by label prose: rewording a user-facing label
            // must not silently change these coverage percentages.
            val sawTls = lineage.steps.any { it.kind == LineageStep.Kind.TLS }
            val sawHttp = lineage.steps.any { it.kind == LineageStep.Kind.HTTP }
            if (sawTls) connectionsWithTls++
            if (sawHttp) connectionsWithHttp++
        }

        val limitingReasons = buildList {
            if (totalConnections == 0) {
                add("No TCP/QUIC connections recorded in this session yet -- nothing to analyze.")
            }
            if (tlsTotal > 0 && tlsSniOnly > 0) {
                add("$tlsSniOnly of $tlsTotal TLS handshake(s) are SNI/JA3-only: capture ran in VPN-only mode (no MITM), so only the ClientHello is visible, not the negotiated cipher/cert or decrypted app data.")
            }
            if (httpUndecoded > 0) {
                add("$httpUndecoded HTTP-layer row(s) are undecoded TLS placeholders (hex dump of the ClientHello only) -- real request/response content was never seen.")
            }
            if (tlsFailedReported > 0) {
                add("$tlsFailedReported of $tlsTotal TLS handshake(s) failed (fatal alert, reset, or a MITM-leg SSLHandshakeException) -- see each session's failureReason.")
            } else {
                add("No failed TLS handshakes were observed this session. A handshake that fails silently on a plane requestr never terminates (e.g. an encrypted TLS 1.3 alert with no MITM leg) is still indistinguishable from one that never started -- that gap is real, not fabricated away.")
            }
            if (unattachedHttpRows > 0) {
                add("$unattachedHttpRows of $httpTotal HTTP-layer row(s) carry no connectionId, so they cannot be attached to any DNS->TCP/TLS lineage and are excluded from the per-connection coverage figures above. Lineage joins on an exact connectionId only -- a host+timestamp window would have attached some of these to a plausible-but-unproven connection.")
            }
            if (dnsResponses > 0 && dnsResolvedWithIps < dnsResponses) {
                add("${dnsResponses - dnsResolvedWithIps} of $dnsResponses DNS response(s) carried no A/AAAA records requestr could extract (CNAME-only chain, non-address record type, or malformed answer) -- those hostnames can't be linked to a destination IP.")
            }
            add("DNS visibility is limited to plaintext UDP:53 queries seen on the tun interface; DoH/DoT-resolved hostnames, OS-level DNS-cache hits from before capture started, and hardcoded IPs are invisible to this analyzer by construction.")
        }

        return ObservationReport(
            sessionId = sessionId,
            totalConnections = totalConnections,
            connectionsWithTlsObserved = connectionsWithTls,
            connectionsWithHttpDecoded = connectionsWithHttp,
            udpFlowCount = udp.size,
            tlsTotal = tlsTotal,
            tlsDecrypted = tlsDecrypted,
            tlsSniOnly = tlsSniOnly,
            tlsFailedReported = tlsFailedReported,
            httpTotal = httpTotal,
            httpDecrypted = httpDecrypted,
            httpUndecoded = httpUndecoded,
            httpPlaintext = httpPlaintext.coerceAtLeast(0),
            unattachedHttpRows = unattachedHttpRows,
            dnsQueries = dnsQueries,
            dnsResponses = dnsResponses,
            dnsResolvedWithIps = dnsResolvedWithIps,
            limitingReasons = limitingReasons,
        )
    }
}

/**
 * Real, computed-from-Room visibility summary for one capture session. Every field is a genuine
 * count/fraction over persisted rows -- there is no synthetic/demo data path.
 */
data class ObservationReport(
    val sessionId: Long,
    val totalConnections: Int,
    val connectionsWithTlsObserved: Int,
    val connectionsWithHttpDecoded: Int,
    val udpFlowCount: Int,
    val tlsTotal: Int,
    val tlsDecrypted: Int,
    val tlsSniOnly: Int,
    val tlsFailedReported: Int,
    val httpTotal: Int,
    val httpDecrypted: Int,
    val httpUndecoded: Int,
    val httpPlaintext: Int,
    /**
     * W6/DEC-4: HTTP rows persisted with no `connectionId`, so no lineage can claim them. Counted
     * and shown rather than dropped, because lineage now requires an exact connectionId join.
     */
    val unattachedHttpRows: Int,
    val dnsQueries: Int,
    val dnsResponses: Int,
    val dnsResolvedWithIps: Int,
    val limitingReasons: List<String>,
) {
    /** Fraction of TLS handshakes whose content was actually decrypted (MITM), 0.0 when none observed. */
    val tlsDecryptedFraction: Float get() = if (tlsTotal == 0) 0f else tlsDecrypted.toFloat() / tlsTotal

    /** Fraction of connections that got at least as far as a decoded HTTP transaction. */
    val httpCoverageFraction: Float get() = if (totalConnections == 0) 0f else connectionsWithHttpDecoded.toFloat() / totalConnections

    /** Fraction of DNS responses that yielded at least one usable resolved IP. */
    val dnsResolutionFraction: Float get() = if (dnsResponses == 0) 0f else dnsResolvedWithIps.toFloat() / dnsResponses
}
