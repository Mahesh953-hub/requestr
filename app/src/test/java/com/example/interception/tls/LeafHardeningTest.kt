package com.example.interception.tls

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * S1 task 8, `LeafHardeningTest`: every leaf [CertificateEngine.mintLeaf] produces carries the
 * hardened extension set and a random, positive, fixed-length serial; validity never exceeds 90
 * days; two leaves minted in the same millisecond never share a serial.
 */
class LeafHardeningTest {

    private val ca = CertTestFixtures.fakeCa()
    private val fixedNow = 1_800_000_000_000L
    private val engine = CertificateEngine(caProvider = { ca }, clock = { fixedNow })

    private fun mint(profile: LeafProfile = LeafProfile.forHost("secure.example.com", LeafProfile.Source.SNI, fixedNow)): X509Certificate =
        engine.mintLeaf(ca, profile, engine.sessionKeyPair)

    private fun extOctets(cert: X509Certificate, oid: String): ByteArray {
        val raw = cert.getExtensionValue(oid) ?: error("extension $oid missing")
        return ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(raw)).octets
    }

    @Test
    fun `leaf is EC P-256 signed by the CA`() {
        val leaf = mint()
        assertEquals("EC", leaf.publicKey.algorithm)
        leaf.verify(ca.certificate.publicKey)
        assertEquals(ca.certificate.subjectX500Principal, leaf.issuerX500Principal)
    }

    @Test
    fun `basicConstraints CA=false and keyUsage are critical, EKU is serverAuth`() {
        val leaf = mint()
        val critical = leaf.criticalExtensionOIDs
        assertTrue(critical.contains(Extension.basicConstraints.id))
        assertTrue(critical.contains(Extension.keyUsage.id))
        assertEquals(-1, leaf.basicConstraints)
        assertTrue("digitalSignature", leaf.keyUsage[0])
        assertTrue("keyAgreement for an EC leaf", leaf.keyUsage[4])
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), leaf.extendedKeyUsage)
        assertFalse(critical.contains(Extension.extendedKeyUsage.id))
        assertFalse(critical.contains(Extension.subjectAlternativeName.id))
    }

    @Test
    fun `SKI matches the leaf key and AKI matches the CA key`() {
        val leaf = mint()
        val utils = JcaX509ExtensionUtils()
        val ski = SubjectKeyIdentifier.getInstance(extOctets(leaf, Extension.subjectKeyIdentifier.id))
        assertArrayEquals(utils.createSubjectKeyIdentifier(leaf.publicKey).keyIdentifier, ski.keyIdentifier)
        val aki = AuthorityKeyIdentifier.getInstance(extOctets(leaf, Extension.authorityKeyIdentifier.id))
        assertArrayEquals(utils.createSubjectKeyIdentifier(ca.certificate.publicKey).keyIdentifier, aki.keyIdentifier)
    }

    @Test
    fun `serial is positive, fixed length, random and distinct within the same millisecond`() {
        val serials = (1..64).map { mint().serialNumber }
        serials.forEach { s ->
            assertTrue("positive", s.signum() > 0)
            assertEquals("exactly 16 octets (reserved high bit set)", CertificateEngine.SERIAL_BYTES * 8 - 1, s.bitLength())
            assertTrue(s.testBit(CertificateEngine.SERIAL_BYTES * 8 - 2))
        }
        assertEquals("no two serials collide", serials.size, serials.toSet().size)
        // Two leaves minted with the same frozen clock (same "millisecond") differ.
        assertNotEquals(mint().serialNumber, mint().serialNumber)
        // randomSerial with a seeded RNG is deterministic, proving the serial is RNG-driven.
        // Pin the SUN provider: Conscrypt (when registered by a sibling test class) also offers
        // "SHA1PRNG" but ignores setSeed, which would make this determinism check flaky.
        val a = CertificateEngine.randomSerial(SecureRandom.getInstance("SHA1PRNG", "SUN").apply { setSeed(42) })
        val b = CertificateEngine.randomSerial(SecureRandom.getInstance("SHA1PRNG", "SUN").apply { setSeed(42) })
        assertEquals(a, b)
    }

    @Test
    fun `validity is clamped to at most 90 days and never opens in the future`() {
        val longProfile = LeafProfile(
            subject = org.bouncycastle.asn1.x500.X500Name("CN=long.example"),
            subjectAltNames = com.example.interception.HostSanGenerator.build("long.example"),
            notBefore = Date(fixedNow + 5 * 86_400_000L), // profile claims a future start
            notAfter = Date(fixedNow + 400L * 86_400_000L), // ... and a 400-day lifetime
            source = LeafProfile.Source.UPSTREAM_SNIFF,
        )
        val leaf = mint(longProfile)
        assertTrue(leaf.notBefore.time <= fixedNow)
        assertTrue(leaf.notAfter.time - fixedNow <= LeafProfile.MAX_VALIDITY_MS)
        leaf.checkValidity(Date(fixedNow))

        val synthetic = mint()
        assertEquals(fixedNow - LeafProfile.SKEW_MS, synthetic.notBefore.time)
        assertEquals(fixedNow + LeafProfile.MAX_VALIDITY_MS, synthetic.notAfter.time)
    }

    @Test
    fun `fromRemote never outlives the remote leaf`() {
        val remote = CertTestFixtures.remoteLeaf(
            "short.example", notAfter = Date(fixedNow + 3 * 86_400_000L), notBefore = Date(fixedNow - 10 * 86_400_000L),
        )
        val profile = LeafProfile.fromRemote(remote, "short.example", fixedNow)
        assertEquals(remote.notAfter.time, profile.notAfter.time)
        assertEquals(fixedNow - LeafProfile.SKEW_MS, profile.notBefore.time)
        assertNotNull(profile.fingerprint)
        assertEquals(32, profile.fingerprint.length)
    }

    @Test
    fun `RSA mode still mints a verifiable leaf with keyEncipherment`() {
        val rsaEngine = CertificateEngine(caProvider = { ca }, keyAlgorithm = CertificateEngine.KeyAlgorithm.RSA_2048, clock = { fixedNow })
        val leaf = rsaEngine.mintLeaf(ca, LeafProfile.forHost("rsa.example", LeafProfile.Source.SNI, fixedNow), rsaEngine.sessionKeyPair)
        assertEquals("RSA", leaf.publicKey.algorithm)
        assertTrue("keyEncipherment", leaf.keyUsage[2])
        leaf.verify(ca.certificate.publicKey)
    }

    @Test
    fun `wildcard SANs come from HostSanGenerator`() {
        val leaf = mint(LeafProfile.forHost("api.shop.example.co.uk", LeafProfile.Source.SNI, fixedNow))
        val sans = CertTestFixtures.dnsSans(leaf)
        assertTrue(sans.contains("api.shop.example.co.uk"))
        assertTrue(sans.any { it.startsWith("*.") })
    }
}
