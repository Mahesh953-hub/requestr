package com.example.interception

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [CaP12Exchange.parse]/[isCaCertificate] -- no [android.content.Context]
 * involved, so plain JUnit (no Robolectric) suffices, mirroring the "pure function -> plain JUnit"
 * convention already used by `ResponseInspectorHelpersTest`.
 */
class CaP12ExchangeTest {

    private fun buildP12(alias: String, password: CharArray, isCa: Boolean): ByteArray {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()

        val now = Date()
        val subject = X500Name("CN=test cert, O=requestr-test")
        val serial = BigInteger.valueOf(System.nanoTime())
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, now, Date(now.time + 1000L * 60 * 60 * 24 * 365), subject, keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        val baos = ByteArrayOutputStream()
        ks.store(baos, password)
        return baos.toByteArray()
    }

    @Test
    fun `parses a valid CA p12 bundle`() {
        val password = "import-pw".toCharArray()
        val bytes = buildP12("some-alias", password, isCa = true)

        val result = CaP12Exchange.parse(bytes, password)

        assertTrue(result is CaP12Exchange.ParseResult.Success)
        val success = result as CaP12Exchange.ParseResult.Success
        assertTrue(isCaCertificate(success.certificate))
    }

    @Test
    fun `rejects a non-CA leaf certificate when requireCa is true`() {
        val password = "import-pw".toCharArray()
        val bytes = buildP12("leaf-alias", password, isCa = false)

        val result = CaP12Exchange.parse(bytes, password, requireCa = true)

        assertTrue(result is CaP12Exchange.ParseResult.Failure)
    }

    @Test
    fun `accepts a non-CA leaf certificate when requireCa is false`() {
        val password = "import-pw".toCharArray()
        val bytes = buildP12("leaf-alias", password, isCa = false)

        val result = CaP12Exchange.parse(bytes, password, requireCa = false)

        assertTrue(result is CaP12Exchange.ParseResult.Success)
    }

    @Test
    fun `rejects wrong password`() {
        val bytes = buildP12("alias", "correct-pw".toCharArray(), isCa = true)

        val result = CaP12Exchange.parse(bytes, "wrong-pw".toCharArray())

        assertTrue(result is CaP12Exchange.ParseResult.Failure)
    }

    @Test
    fun `rejects garbage bytes`() {
        val result = CaP12Exchange.parse(byteArrayOf(1, 2, 3, 4), "pw".toCharArray())

        assertTrue(result is CaP12Exchange.ParseResult.Failure)
    }

    @Test
    fun `isCaCertificate distinguishes CA from leaf certs`() {
        val password = "pw".toCharArray()
        val caBytes = buildP12("ca", password, isCa = true)
        val leafBytes = buildP12("leaf", password, isCa = false)

        val ca = (CaP12Exchange.parse(caBytes, password) as CaP12Exchange.ParseResult.Success).certificate
        val leaf = (CaP12Exchange.parse(leafBytes, password, requireCa = false) as CaP12Exchange.ParseResult.Success).certificate

        assertTrue(isCaCertificate(ca))
        assertFalse(isCaCertificate(leaf))
    }
}
