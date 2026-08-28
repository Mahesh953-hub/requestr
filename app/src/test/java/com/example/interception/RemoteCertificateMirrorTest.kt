package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [RemoteCertificateMirror]'s pure profile-building logic and
 * [CertificateAuthority.issueLeafFromProfile], which together implement remote-certificate
 * mirroring without any dependency on a real network fetch or a real target host -- a
 * self-signed "remote" certificate built in-test stands in for [RemoteCertificateMirror.fetchRemoteLeaf]'s
 * real output (that network call itself is out of scope for a deterministic unit test, same
 * reasoning as [CertificateAuthorityTest]'s AndroidKeyStore seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RemoteCertificateMirrorTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakePasswordProvider : CertificateAuthority.CaKeystorePasswordProvider {
        var stored: CharArray? = null
        override suspend fun read(context: Context): CharArray? = stored?.copyOf()
        override suspend fun rotate(context: Context): CharArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).toCharArray()
            stored = encoded.copyOf()
            return encoded
        }
    }

    @Before
    fun setUp() {
        CertificateAuthority.passwordProviderForTest = FakePasswordProvider()
        keystoreFile().delete()
    }

    @After
    fun tearDown() {
        CertificateAuthority.passwordProviderForTest = null
        keystoreFile().delete()
        File(keystoreFile().parentFile, "${CertificateAuthority.KEYSTORE_FILE}.tmp").delete()
    }

    private fun keystoreFile() = File(context.filesDir, CertificateAuthority.KEYSTORE_FILE)

    /** Builds a self-signed cert shaped like a real leaf, standing in for a fetched remote cert. */
    private fun selfSignedLeaf(
        cn: String = "example.com",
        sanHosts: List<String> = listOf("example.com", "www.example.com"),
        notBefore: Date = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000),
        notAfter: Date = Date(System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000),
    ): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val subject = X500Name("CN=$cn, O=Example Inc")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(42), notBefore, notAfter, subject, keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(sanHosts.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    // ── profileFrom / extractSubjectAltNames ──────────────────────────

    @Test
    fun `profileFrom mirrors the remote subject and SANs`() {
        val remote = selfSignedLeaf(cn = "api.example.com", sanHosts = listOf("api.example.com", "cdn.example.com"))
        val profile = RemoteCertificateMirror.profileFrom(remote)

        val mirroredPrincipal = javax.security.auth.x500.X500Principal(profile.subject.encoded)
        assertEquals(remote.subjectX500Principal.name, mirroredPrincipal.name)
        val sanValues = profile.subjectAltNames!!.names.map { it.name.toString() }
        assertTrue(sanValues.contains("api.example.com"))
        assertTrue(sanValues.contains("cdn.example.com"))
    }

    @Test
    fun `profileFrom clamps notAfter to the remote's own expiry when it is sooner than the policy cap`() {
        val soon = Date(System.currentTimeMillis() + 5L * 24 * 60 * 60 * 1000) // 5 days out
        val remote = selfSignedLeaf(notAfter = soon)
        val profile = RemoteCertificateMirror.profileFrom(remote, maxValidityDays = 365)
        assertTrue(profile.notAfter.time <= soon.time + 1000)
    }

    @Test
    fun `profileFrom caps notAfter at the policy limit when the remote cert outlives it`() {
        val farFuture = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years
        val remote = selfSignedLeaf(notAfter = farFuture)
        val profile = RemoteCertificateMirror.profileFrom(remote, maxValidityDays = 365)
        val oneYearFromNow = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        assertTrue(profile.notAfter.time <= oneYearFromNow + 60_000)
    }

    @Test
    fun `profileFrom never produces notBefore after notAfter even for an already-expired remote cert`() {
        val past = Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000)
        val remote = selfSignedLeaf(
            notBefore = Date(past.time - 10_000),
            notAfter = past,
        )
        val profile = RemoteCertificateMirror.profileFrom(remote)
        assertTrue(profile.notBefore.time < profile.notAfter.time)
    }

    @Test
    fun `extractSubjectAltNames returns null for a cert with no SAN extension`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val subject = X500Name("CN=no-san.example")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(1), Date(), Date(System.currentTimeMillis() + 100000), subject, keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        assertEquals(null, RemoteCertificateMirror.extractSubjectAltNames(cert))
    }

    // ── CertificateAuthority.issueLeafFromProfile ─────────────────────

    @Test
    fun `issueLeafFromProfile issues a leaf signed by our own CA carrying the mirrored subject and SANs`() = runBlocking {
        val remote = selfSignedLeaf(cn = "mirrored.example", sanHosts = listOf("mirrored.example"))
        val profile = RemoteCertificateMirror.profileFrom(remote)

        val ca = CertificateAuthority.getOrCreate(context)
        val (leaf, _) = CertificateAuthority.issueLeafFromProfile(context, "mirrored.example", profile)

        // Signed by requestr's own CA, not the "remote" self-signed key.
        leaf.verify(ca.certificate.publicKey)
        assertEquals(ca.certificate.subjectX500Principal, leaf.issuerX500Principal)
        assertEquals(remote.subjectX500Principal, leaf.subjectX500Principal)

        val basicConstraints = leaf.basicConstraints
        assertEquals(-1, basicConstraints) // -1 == not a CA, matches BasicConstraints(false)
    }

    @Test
    fun `issueLeafFromProfile falls back to HostSanGenerator when the profile has no SANs`() = runBlocking {
        val profile = RemoteCertificateMirror.MirroredProfile(
            subject = X500Name("CN=nosans.example"),
            subjectAltNames = null,
            notBefore = Date(System.currentTimeMillis() - 1000),
            notAfter = Date(System.currentTimeMillis() + 100_000_000),
        )
        CertificateAuthority.getOrCreate(context)
        val (leaf, _) = CertificateAuthority.issueLeafFromProfile(context, "nosans.example", profile)
        assertNotNull(leaf)
        // SAN extension must still be present (from the HostSanGenerator fallback), not omitted.
        assertNotNull(leaf.getExtensionValue(Extension.subjectAlternativeName.id))
    }
}
