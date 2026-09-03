package com.example.interception.tls

import com.example.interception.CertificateAuthority
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/** Pure-JVM certificate fixtures for the S1 task 8 suites (no Android, no Robolectric). */
internal object CertTestFixtures {

    fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits, SecureRandom()) }.genKeyPair()

    /** A throwaway self-signed CA that looks like the real requestr root (CA=true, keyCertSign). */
    fun fakeCa(cn: String = "requestr Test Root"): CertificateAuthority.CaMaterial {
        val kp = rsaKeyPair()
        val subject = X500Name("CN=$cn, O=requestr, OU=Interception")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), subject, kp.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature))
        }
        val cert = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)),
        )
        return CertificateAuthority.CaMaterial(cert, kp)
    }

    /** A self-signed "remote" leaf with an optional SAN list (null = no SAN extension at all). */
    fun remoteLeaf(
        cn: String,
        sans: List<String>? = listOf(cn),
        notBefore: Date = Date(System.currentTimeMillis() - 86_400_000L),
        notAfter: Date = Date(System.currentTimeMillis() + 300L * 86_400_000L),
        keyPair: KeyPair = rsaKeyPair(),
    ): X509Certificate {
        val subject = X500Name("CN=$cn, O=Remote Inc")
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(7), notBefore, notAfter, subject, keyPair.public).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            if (sans != null) {
                addExtension(
                    Extension.subjectAlternativeName, false,
                    GeneralNames(
                        sans.map {
                            if (CertificateEngine.looksLikeIpLiteral(it)) GeneralName(GeneralName.iPAddress, it)
                            else GeneralName(GeneralName.dNSName, it)
                        }.toTypedArray(),
                    ),
                )
            }
        }
        return JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
    }

    /** In-memory PKCS#12 with one key entry under [alias]. */
    fun p12(alias: String, password: CharArray, cert: X509Certificate, keyPair: KeyPair): ByteArray {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        return java.io.ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()
    }

    fun dnsSans(cert: X509Certificate): List<String> =
        cert.subjectAlternativeNames?.filter { it[0] == 2 }?.map { it[1] as String } ?: emptyList()

    fun ipSans(cert: X509Certificate): List<String> =
        cert.subjectAlternativeNames?.filter { it[0] == 7 }?.map { it[1] as String } ?: emptyList()
}
