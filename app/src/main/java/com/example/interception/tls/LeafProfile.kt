package com.example.interception.tls

import com.example.interception.HostSanGenerator
import com.example.interception.RemoteCertificateMirror
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.GeneralNames
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date

/**
 * S1 task 8: the *shape* of a leaf certificate [CertificateEngine] is about to mint -- subject,
 * SANs and validity window -- independent of the key and the issuing CA. There are exactly two
 * ways to build one:
 *
 * - [forHost]: a synthetic profile for a DNS name or IP literal (`CN=host` plus
 *   [HostSanGenerator]'s PSL-backed SAN set). This is the SNI / CONNECT-authority path.
 * - [fromRemote]: a profile cloned from the real upstream leaf (subject and *all* SANs copied
 *   verbatim, validity clamped). This absorbs `RemoteCertificateMirror.profileFrom` and is the
 *   default for IP-literal and SNI-less connects, where a `CN=1.2.3.4` leaf would be rejected by
 *   any client that actually validates hostnames.
 *
 * Both paths clamp validity to at most [MAX_VALIDITY_MS] (90 days) -- one issuer, one policy.
 *
 * [fingerprint] is a stable digest of the profile's identity-relevant fields; it participates in
 * the certificate cache key so a changed upstream cert or a changed override yields a fresh leaf
 * instead of a stale cached one.
 */
data class LeafProfile(
    val subject: X500Name,
    val subjectAltNames: GeneralNames,
    val notBefore: Date,
    val notAfter: Date,
    val source: Source,
) {
    /** Where the identity this profile describes came from (surfaced in diagnostics). */
    enum class Source {
        /** TLS ClientHello `server_name` -- the authoritative host when present. */
        SNI,
        /** Original destination (transparent redirect / `SO_ORIGINAL_DST`). */
        ORIGINAL_DST,
        /** `CONNECT host:port` authority / SOCKS destination. */
        CONNECT_AUTHORITY,
        /** Subject + SANs cloned from the real upstream leaf. */
        UPSTREAM_SNIFF,
        /** Upstream unreachable and no DNS name available: `CN=ip` with an iPAddress SAN. */
        IP_FALLBACK,
    }

    val fingerprint: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(subject.encoded)
        md.update(subjectAltNames.encoded)
        md.update(notBefore.time.toString().toByteArray())
        md.update(notAfter.time.toString().toByteArray())
        md.digest().joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    companion object {
        const val MAX_VALIDITY_MS: Long = 90L * 24 * 60 * 60 * 1000
        /** Same one-day clock-skew allowance the root CA uses for its own `notBefore`. */
        const val SKEW_MS: Long = 24L * 60 * 60 * 1000

        /**
         * Synthetic profile for [host]: `CN=host` and [HostSanGenerator.build]'s SAN set (exact
         * name + PSL-aware wildcard, or an `iPAddress` SAN for a literal). Validity is exactly
         * [SKEW_MS] back to [MAX_VALIDITY_MS] forward from [now].
         */
        fun forHost(host: String, source: Source, now: Long = System.currentTimeMillis()): LeafProfile {
            val h = host.trim().trimEnd('.').lowercase()
            return LeafProfile(
                subject = X500Name("CN=${escapeCn(h)}"),
                subjectAltNames = HostSanGenerator.build(h),
                notBefore = Date(now - SKEW_MS),
                notAfter = Date(now + MAX_VALIDITY_MS),
                source = source,
            )
        }

        /**
         * Profile cloned from the real upstream leaf [remote]. The subject and SAN extension are
         * copied byte-for-byte (a leaf relying purely on SANs, with no CN, is still valid). If the
         * remote has no SAN extension at all, fall back to [HostSanGenerator] for [fallbackHost]
         * so the leaf is still hostname-verifiable. Validity: never before the remote's own
         * `notBefore` (or one day ago, whichever is later), never after the remote's `notAfter`,
         * and never more than [MAX_VALIDITY_MS] out.
         */
        fun fromRemote(
            remote: X509Certificate,
            fallbackHost: String,
            now: Long = System.currentTimeMillis(),
        ): LeafProfile {
            val notBefore = Date(maxOf(remote.notBefore.time, now - SKEW_MS))
            val notAfter = Date(
                minOf(remote.notAfter.time, now + MAX_VALIDITY_MS).coerceAtLeast(notBefore.time + 60_000),
            )
            return LeafProfile(
                subject = X500Name.getInstance(remote.subjectX500Principal.encoded),
                subjectAltNames = RemoteCertificateMirror.extractSubjectAltNames(remote)
                    ?: HostSanGenerator.build(fallbackHost),
                notBefore = notBefore,
                notAfter = notAfter,
                source = Source.UPSTREAM_SNIFF,
            )
        }

        /** RFC 4514 escaping for the few characters that would otherwise break an `X500Name` string. */
        private fun escapeCn(v: String): String = buildString {
            v.forEachIndexed { i, c ->
                when {
                    c == ',' || c == '+' || c == '"' || c == '\\' || c == '<' || c == '>' || c == ';' -> append('\\').append(c)
                    c == '#' && i == 0 -> append("\\#")
                    c == ' ' && (i == 0 || i == v.lastIndex) -> append("\\ ")
                    else -> append(c)
                }
            }
        }
    }
}
