package com.example.diagnostics

import android.content.Context
import com.example.data.local.RequestrDatabase
import com.example.interception.tls.TlsHandshakeState

/**
 * D3: answers "what fraction of this session's traffic did requestr actually observe/decrypt,
 * and why not the rest" by computing real percentages from the rows the capture engine already
 * persisted -- no fabricated/sample numbers. An app that made zero connections legitimately
 * produces an [ObservationReport] with everything at 0/0, which [VisibilityAnalyzer] reports
 * honestly as "no data" rather than synthesizing a fake baseline.
 *
 * Known, documented limitation carried over from the schema (see [TlsSessionEntity.handshakeState]
 * doc): [TlsHandshakeState.FAILED] exists as an enum value but nothing in the codebase ever sets
 * it -- every persisted TLS row is in [TlsHandshakeState.CLIENT_HELLO] or
 * [TlsHandshakeState.ESTABLISHED]. So "TLS handshakes observed" only ever reports
 * decrypted-vs-SNI-only, never "attempted and failed"; that reason bucket is reported as a fixed
 * 0 with an explanation, not omitted or guessed at.
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

        // "Coverage": of every TCP/QUIC connection, how many got at least as far as a TLS
        // handshake we saw, and of those how many got all the way to a decoded HTTP transaction
        // (matched the same way ConnectionLineage does -- host + timestamp window). This reuses
        // ConnectionLineage rather than re-deriving the matching rule a second time.
        var connectionsWithTls = 0
        var connectionsWithHttp = 0
        for (c in tcp) {
            val lineage = com.example.correlation.graph.ConnectionLineage.build(context, sessionId, c.connectionId)
            val sawTls = lineage.steps.any { it.plane == com.example.correlation.ObservationEvent.ObserverSource.MITM && it.success } ||
                lineage.steps.any { it.label.startsWith("TLS handshake") }
            val sawHttp = lineage.steps.any { it.label.startsWith("No HTTP").not() && (it.plane == com.example.correlation.ObservationEvent.ObserverSource.PROXY || it.plane == com.example.correlation.ObservationEvent.ObserverSource.MITM) && it.success }
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
            add("TlsHandshakeState.FAILED is defined in the schema but nothing in the codebase ever sets it, so a failed/reset TLS handshake is indistinguishable here from one that simply never started -- $tlsFailedReported reported as FAILED is expected to always read 0.")
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
