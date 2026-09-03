package com.example.interception

import com.example.interception.net.SocketProtector
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralNames

/**
 * Remote-certificate mirroring: a standalone helper (no [MitmProxyEngine] edits -- that file is
 * owned by the protocol agent, see [CertificateAuthority.issueMirroredLeaf]'s kdoc) that fetches
 * a target host's REAL leaf certificate over a direct, independent TLS handshake and extracts a
 * [MirroredProfile] (subject / SANs / validity window) that a per-host MITM leaf can be issued
 * to mirror -- so the leaf requestr hands the client looks structurally like the real one
 * (same CN/O, same SAN set, a validity window that doesn't exceed the real cert's) instead of
 * requestr's own generic `CN=<host>` leaf with a fixed 1-year validity
 * ([MitmProxyEngine.generateHostCert]'s current behavior). Better fidelity helps when testing
 * against clients that sanity-check leaf metadata beyond "is this chain trusted", without
 * requiring the client to actually validate the (still requestr-CA-signed) chain any differently.
 *
 * This is intentionally separate from whatever socket [MitmProxyEngine] already has open to the
 * same host mid-CONNECT: that engine terminates TLS *itself* (it needs the client's SNI before it
 * knows what to fetch, and re-using its own upstream leg from inside this helper would require
 * editing it). [fetchRemoteLeaf] instead does its own short-lived handshake purely to read the
 * peer certificate, then closes -- one extra round trip per newly-seen host, paid once (a caller
 * that keys its own leaf cache by host, as [MitmProxyEngine.certCache] already does, only pays it
 * on first sight of a host, exactly like the per-host leaf itself).
 */
object RemoteCertificateMirror {

    /** The metadata mirrored onto a new leaf; never the remote's key material (we can't have that). */
    data class MirroredProfile(
        val subject: X500Name,
        val subjectAltNames: GeneralNames?,
        val notBefore: Date,
        val notAfter: Date,
    )

    /**
     * Opens a direct TLS connection to [host]:[port] purely to read the peer's real leaf
     * certificate, then closes it. Never throws -- returns null on any failure (unreachable host,
     * handshake failure, no certificates presented, timeout). Never validates the chain (a
     * default, all-trusting [SSLContext] is used deliberately: this only reads metadata off
     * whatever cert the server presents, it does not make a trust decision).
     */
    fun fetchRemoteLeaf(
        host: String,
        port: Int = 443,
        timeoutMs: Int = 5_000,
        socketFactory: (SSLContext) -> SSLSocket = { it.socketFactory.createSocket() as SSLSocket },
        socketConnector: (SSLSocket, SocketAddress, Int) -> Unit = { socket, address, timeout ->
            socket.connect(address, timeout)
        },
    ): X509Certificate? = runCatching {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        val socket = socketFactory(context)
        socket.use { s ->
            val protectResult = SocketProtector.protectSocket(s)
            if (protectResult == SocketProtector.ProtectResult.Failed) return@runCatching null
            socketConnector(s, InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            runCatching {
                s.sslParameters = SSLParameters().apply { serverNames = listOf(SNIHostName(host)) }
            }
            s.startHandshake()
            s.session.peerCertificates?.firstOrNull() as? X509Certificate
        }
    }.getOrNull()

    /**
     * Builds a [MirroredProfile] from [remote]. Validity is clamped so a mirrored leaf can never
     * outlive the real one, and never predates "now" by more than a day (matching
     * [CertificateAuthority]'s own `notBefore` clock-skew allowance for the root CA) -- so a
     * remote cert that's already close to expiring, or one with a `notBefore` in the future,
     * doesn't produce a nonsensical or oversized local leaf window. [maxValidityDays] additionally
     * caps how far into the future the mirrored leaf can reach even if the remote cert is
     * long-lived, keeping mirrored leaves in the same rough lifetime class as
     * [MitmProxyEngine.generateHostCert]'s existing fixed 1-year leaves.
     */
    fun profileFrom(remote: X509Certificate, maxValidityDays: Long = 365): MirroredProfile {
        val now = System.currentTimeMillis()
        val notBefore = Date(maxOf(remote.notBefore.time, now - 24L * 60 * 60 * 1000))
        val cappedByRemote = remote.notAfter.time
        val cappedByPolicy = now + maxValidityDays * 24 * 60 * 60 * 1000
        val notAfter = Date(minOf(cappedByRemote, cappedByPolicy).coerceAtLeast(notBefore.time + 60_000))
        return MirroredProfile(
            subject = X500Name.getInstance(remote.subjectX500Principal.encoded),
            subjectAltNames = extractSubjectAltNames(remote),
            notBefore = notBefore,
            notAfter = notAfter,
        )
    }

    /** Re-extracts the SAN [GeneralNames] straight off the remote cert's own DER extension, or null. */
    fun extractSubjectAltNames(remote: X509Certificate): GeneralNames? = runCatching {
        val raw = remote.getExtensionValue(Extension.subjectAlternativeName.id) ?: return null
        val octets = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(raw)).octets
        GeneralNames.getInstance(ASN1Primitive.fromByteArray(octets))
    }.getOrNull()
}
