package com.example.interception.tls

import android.content.Context
import com.example.interception.CertificateAuthority
import com.example.interception.ExpiringLruCertCache
import com.example.interception.RemoteCertificateMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager

/**
 * S1 task 8: the single leaf-certificate issuer for every MITM'd TLS connection. Replaces the
 * three divergent paths that used to exist (`MitmProxyEngine.generateHostCert`,
 * `MitmProxyEngine.generateHostCertOrMirror`, `CertificateAuthority.issueLeafFromProfile`) with
 * one policy:
 *
 * **Identity order (fixed).** The caller peeks the ClientHello *before* calling [issue]; the leaf
 * identity is then chosen as SNI -> original destination -> CONNECT authority. Previously the leaf
 * was minted before the peek, so `CONNECT 1.2.3.4:443` with a perfectly good SNI still produced a
 * `CN=1.2.3.4` leaf that every hostname-verifying client rejected.
 *
 * **Upstream sniffing is the default when no name is known** -- an IP-literal identity (IP
 * CONNECT authority with no SNI, or a transparent capture with no SNI) -- and, when the
 * `remoteCertMirrorEnabled` setting asks for extra fidelity, for named hosts too: the real
 * upstream leaf's subject and SANs are cloned via [LeafProfile.fromRemote]. When the upstream is
 * unreachable the engine degrades to a synthetic `CN=host` leaf with an iPAddress/dNSName SAN.
 * A *named* CONNECT authority without SNI is **not** sniffed: the name is already known, and
 * dialing the origin before the first request would break the §1.5 guarantee that a
 * MOCK_RESPONSE-only connection never touches the real endpoint (JSSE, for one, omits SNI for
 * dotless names such as `localhost`).
 *
 * **Leaf hardening (one issuer, one policy).** EC P-256 with one keypair reused for the engine's
 * lifetime (no per-host RSA-2048 keygen on the hot path), random positive serial with a reserved
 * high bit (see [randomSerial]), critical `basicConstraints(false)`, critical `keyUsage`,
 * `extendedKeyUsage = serverAuth`, SKI, AKI derived from the CA, SANs from the profile, validity
 * clamped to <= 90 days.
 *
 * **Per-domain overrides and mTLS.** A matching [DomainCertPolicy] from [policyStore] can turn a
 * connection into [Outcome.Passthrough] (never MITM'd) or [Outcome.Custom] (an imported server
 * certificate presented verbatim), and [clientKeyManagersFor] supplies the [KeyManager]s the
 * upstream leg needs for mutual TLS.
 *
 * Every dependency with I/O or randomness is injectable so the policy is unit-testable without
 * a network, a device keystore, or a real clock.
 */
class CertificateEngine(
    private val caProvider: suspend (Context) -> CertificateAuthority.CaMaterial = { CertificateAuthority.getOrCreate(it) },
    /** Dials `host:port` (through `SocketProtector`) and returns its leaf, or null. Blocking. */
    private val upstreamSniffer: (host: String, port: Int) -> X509Certificate? =
        { h, p -> RemoteCertificateMirror.fetchRemoteLeaf(h, p) },
    private val policyStore: ClientCertStore? = null,
    private val cache: ExpiringLruCertCache = ExpiringLruCertCache(),
    private val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.EC_P256,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class KeyAlgorithm { EC_P256, RSA_2048 }

    /** Everything the caller knows about the connection when it asks for a leaf. */
    data class Request(
        /** `CONNECT host:port` authority, SOCKS destination host, or `Host:` header value. */
        val connectAuthority: String?,
        val port: Int,
        /** ClientHello `server_name`, already peeked off the socket; null when absent. */
        val sni: String?,
        /** Original destination host from a transparent redirect, when known. */
        val originalDst: String? = null,
        /** `remoteCertMirrorEnabled`: also sniff the upstream for named hosts. */
        val preferUpstreamSniff: Boolean = false,
    )

    /** The host name (or IP literal) the leaf is issued for, and where it came from. */
    data class Identity(val host: String, val source: LeafProfile.Source) {
        val isIpLiteral: Boolean get() = looksLikeIpLiteral(host)
    }

    sealed class Outcome {
        abstract val identity: Identity
        abstract val policy: DomainCertPolicy?

        /** A leaf minted under the requestr CA. */
        class Issued(
            override val identity: Identity,
            override val policy: DomainCertPolicy?,
            val certificate: X509Certificate,
            val keyPair: KeyPair,
            val profileSource: LeafProfile.Source,
            /** True when served from the cache rather than freshly minted. */
            val fromCache: Boolean,
        ) : Outcome()

        /** An imported server certificate presented verbatim ([DomainCertMode.CUSTOM_SERVER_CERT]). */
        class Custom(
            override val identity: Identity,
            override val policy: DomainCertPolicy,
            val chain: Array<X509Certificate>,
            val privateKey: PrivateKey,
        ) : Outcome()

        /** Do not MITM this connection at all ([DomainCertMode.PASSTHROUGH]). */
        class Passthrough(override val identity: Identity, override val policy: DomainCertPolicy) : Outcome()
    }

    private val bcProvider = BouncyCastleProvider()

    /** One leaf keypair per engine lifetime -- the "per-session reused key". */
    val sessionKeyPair: KeyPair by lazy { generateKeyPair(keyAlgorithm) }

    /** SNI -> original destination -> CONNECT authority. Never null: falls back to the authority. */
    fun resolveIdentity(request: Request): Identity {
        val sni = request.sni?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() && !looksLikeIpLiteral(it) }
        if (sni != null) return Identity(sni, LeafProfile.Source.SNI)
        val dst = request.originalDst?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
        if (dst != null) return Identity(dst, LeafProfile.Source.ORIGINAL_DST)
        val authority = request.connectAuthority?.trim()?.trimEnd('.')?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "unknown.invalid"
        return Identity(authority, LeafProfile.Source.CONNECT_AUTHORITY)
    }

    /** Whether [issue] would sniff the upstream leaf for [request] (before consulting the cache). */
    fun wantsUpstreamSniff(request: Request): Boolean {
        val identity = resolveIdentity(request)
        return identity.isIpLiteral || request.preferUpstreamSniff
    }

    /**
     * Resolves the policy and returns the material for the client-facing TLS leg. Cached per
     * `(identity, port, policy mode, profile tag)` so a policy or upstream change yields a fresh
     * leaf. Runs on [Dispatchers.IO] because the sniff (and the first CA load) do blocking I/O.
     */
    suspend fun issue(context: Context, request: Request): Outcome = withContext(Dispatchers.IO) {
        val identity = resolveIdentity(request)
        val policy = policyStore?.resolve(identity.host)
        when (policy?.mode) {
            DomainCertMode.PASSTHROUGH -> return@withContext Outcome.Passthrough(identity, policy)
            DomainCertMode.CUSTOM_SERVER_CERT -> {
                val material = policy.serverCertRef?.let { policyStore.serverMaterial(it) }
                    ?: throw ClientCertException(
                        "CUSTOM_SERVER_CERT for ${policy.hostPattern} references missing material '${policy.serverCertRef}'",
                    )
                return@withContext Outcome.Custom(identity, policy, material.chain, material.privateKey)
            }
            DomainCertMode.AUTO, null -> Unit
        }

        val ca = caProvider(context)
        val sniff = wantsUpstreamSniff(request)
        val tag = if (sniff) "sniff" else "host"
        val key = "${identity.host}:${request.port}|${policy?.mode ?: DomainCertMode.AUTO}|$tag"
        var minted = false
        var source = identity.source
        val (cert, keyPair) = cache.getOrCompute(key) {
            minted = true
            val profile = buildProfile(identity, request, sniff)
            source = profile.source
            mintLeaf(ca, profile, sessionKeyPair) to sessionKeyPair
        }
        if (!minted) source = sourceFromCertificate(cert, identity)
        Outcome.Issued(identity, policy, cert, keyPair, source, fromCache = !minted)
    }

    /** [KeyManager]s for mutual TLS towards [host], or null when no policy configures a client cert. */
    fun clientKeyManagersFor(host: String): Array<KeyManager>? {
        val policy = policyStore?.resolve(host) ?: return null
        return policyStore.clientKeyManagers(policy)
    }

    /** Profile for [identity]: sniffed from upstream when [sniff] (falling back to synthetic). */
    internal fun buildProfile(identity: Identity, request: Request, sniff: Boolean): LeafProfile {
        val now = clock()
        if (sniff) {
            val dialHost = request.connectAuthority?.takeIf { it.isNotBlank() } ?: identity.host
            val remote = runCatching { upstreamSniffer(dialHost, request.port) }.getOrNull()
            if (remote != null) return LeafProfile.fromRemote(remote, identity.host, now)
            if (identity.isIpLiteral) return LeafProfile.forHost(identity.host, LeafProfile.Source.IP_FALLBACK, now)
        }
        return LeafProfile.forHost(identity.host, identity.source, now)
    }

    /**
     * Mints a hardened leaf for [profile] under [ca] using [keyPair]'s public key. Pure apart from
     * [random] (serial) and [clock] (validity clamp); exposed for [LeafHardeningTest].
     */
    fun mintLeaf(ca: CertificateAuthority.CaMaterial, profile: LeafProfile, keyPair: KeyPair): X509Certificate {
        val now = clock()
        val notBefore = Date(minOf(profile.notBefore.time, now))
        val notAfter = Date(minOf(profile.notAfter.time, now + LeafProfile.MAX_VALIDITY_MS).coerceAtLeast(notBefore.time + 60_000))
        val issuer = X500Name.getInstance(ca.certificate.subjectX500Principal.encoded)
        val extUtils = JcaX509ExtensionUtils()
        val isEc = keyPair.public.algorithm.equals("EC", ignoreCase = true)
        val keyUsage = if (isEc) KeyUsage.digitalSignature or KeyUsage.keyAgreement
        else KeyUsage.digitalSignature or KeyUsage.keyEncipherment

        val builder = JcaX509v3CertificateBuilder(
            issuer, randomSerial(random), notBefore, notAfter, profile.subject, keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(keyUsage))
            addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.public))
            addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(ca.certificate))
            addExtension(Extension.subjectAlternativeName, false, profile.subjectAltNames)
        }
        val sigAlg = if (ca.keyPair.private.algorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(sigAlg).setProvider(bcProvider).build(ca.keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun generateKeyPair(algorithm: KeyAlgorithm): KeyPair = when (algorithm) {
        KeyAlgorithm.EC_P256 -> KeyPairGenerator.getInstance("EC", bcProvider)
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()
        KeyAlgorithm.RSA_2048 -> KeyPairGenerator.getInstance("RSA", bcProvider)
            .apply { initialize(2048, random) }
            .generateKeyPair()
    }

    private fun sourceFromCertificate(cert: X509Certificate, identity: Identity): LeafProfile.Source {
        val cn = cert.subjectX500Principal.name
        return if (cn.contains("CN=${identity.host}", ignoreCase = true)) identity.source else LeafProfile.Source.UPSTREAM_SNIFF
    }

    companion object {
        /** Serial length in bytes: 16 (128 bits) -- comfortably under the 20-octet RFC 5280 cap. */
        const val SERIAL_BYTES = 16

        /**
         * Random positive serial with a reserved high bit: the top bit of the first octet is
         * cleared (so the INTEGER is positive and never needs a leading zero octet) and the next
         * bit is set (so every serial is exactly [SERIAL_BYTES] long and has >= 126 random bits).
         * Two leaves minted in the same millisecond can no longer collide, unlike the old
         * `System.currentTimeMillis()` serial.
         */
        fun randomSerial(random: SecureRandom = SecureRandom()): BigInteger {
            val bytes = ByteArray(SERIAL_BYTES).also { random.nextBytes(it) }
            bytes[0] = ((bytes[0].toInt() and 0x3f) or 0x40).toByte()
            return BigInteger(1, bytes)
        }

        fun looksLikeIpLiteral(host: String): Boolean {
            val h = host.trim().removePrefix("[").removeSuffix("]")
            if (h.isEmpty()) return false
            val v4 = h.all { it.isDigit() || it == '.' } && h.count { it == '.' } == 3
            val v6 = h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
            if (!v4 && !v6) return false
            return runCatching { InetAddress.getByName(h); true }.getOrDefault(v6)
        }
    }
}
