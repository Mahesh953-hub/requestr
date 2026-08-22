package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the CA-private-key-at-rest fix: splitting the old shared `"requestr-ca-local"` literal
 * into a per-install internal-keystore secret and a still-public export password, plus the
 * legacy-file migration between them.
 *
 * [CertificateAuthority.passwordProviderForTest] stands in for the real `AndroidKeyStore`-backed
 * [CertificateAuthority.SecureKeyStoreCaPasswordProvider] -- Robolectric has no `AndroidKeyStore`
 * provider (`SecureKeyStoreTest` documents `KeyStore.getInstance("AndroidKeyStore")` throwing
 * `NoSuchProviderException`), so the real Keystore-backed provider and the on-device migration are
 * verified separately on a physical device; these tests exercise the migration/atomic-write logic
 * itself, which is provider-independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CertificateAuthorityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** In-memory stand-in for the AndroidKeyStore-backed provider. */
    private class FakePasswordProvider : CertificateAuthority.CaKeystorePasswordProvider {
        var stored: CharArray? = null
        var rotateCount = 0

        override suspend fun read(context: Context): CharArray? = stored?.copyOf()

        override suspend fun rotate(context: Context): CharArray {
            rotateCount++
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).toCharArray()
            stored = encoded.copyOf()
            return encoded
        }
    }

    private lateinit var fakeProvider: FakePasswordProvider

    @Before
    fun setUp() {
        fakeProvider = FakePasswordProvider()
        CertificateAuthority.passwordProviderForTest = fakeProvider
        keystoreFile().delete()
    }

    @After
    fun tearDown() {
        CertificateAuthority.passwordProviderForTest = null
        keystoreFile().delete()
        File(keystoreFile().parentFile, "${CertificateAuthority.KEYSTORE_FILE}.tmp").delete()
    }

    private fun keystoreFile() = File(context.filesDir, CertificateAuthority.KEYSTORE_FILE)

    // ── (a) fresh install ────────────────────────────────────────────

    @Test
    fun `fresh install generates a random per-install password and a loadable keystore`() = runBlocking {
        val material = CertificateAuthority.getOrCreate(context)
        assertNotNull(material.certificate)

        assertTrue("rotate() must have been called to mint a per-install password", fakeProvider.rotateCount >= 1)
        val password = fakeProvider.stored
        assertNotNull("a per-install password must be persisted after generation", password)
        assertTrue("the generated password must have real entropy, not a short/empty value", password!!.size >= 20)

        assertTrue("keystore file must exist on disk after generation", keystoreFile().exists())

        // Round-trips fine under the per-install password without falling back to the legacy one.
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile().inputStream().use { ks.load(it, password) }
        assertNotNull(ks.getCertificate(CertificateAuthority.KEYSTORE_ALIAS))
    }

    @Test
    fun `two fresh installs get different random passwords`() = runBlocking {
        CertificateAuthority.getOrCreate(context)
        val first = fakeProvider.stored!!.copyOf()

        keystoreFile().delete()
        fakeProvider.stored = null
        CertificateAuthority.getOrCreate(context)
        val second = fakeProvider.stored!!.copyOf()

        assertNotEquals(String(first), String(second))
    }

    // ── (b) legacy migration ────────────────────────────────────────

    private fun legacyEncode(context: Context): CertificateAuthority.CaMaterial {
        // Build a keystore the way pre-fix installs did: everything under the shared literal.
        val legacyPassword = "requestr-ca-local".toCharArray()
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, java.security.SecureRandom())
        val keyPair = generator.generateKeyPair()

        val now = java.util.Date()
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=legacy test CA")
        val serial = java.math.BigInteger.valueOf(1)
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            subject, serial, now, java.util.Date(now.time + 1000L * 60 * 60 * 24 * 365), subject, keyPair.public,
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
            org.bouncycastle.asn1.x509.BasicConstraints(true),
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(CertificateAuthority.KEYSTORE_ALIAS, keyPair.private, legacyPassword, arrayOf(cert))
        keystoreFile().outputStream().use { ks.store(it, legacyPassword) }

        return CertificateAuthority.CaMaterial(cert, keyPair)
    }

    @Test
    fun `a legacy-password keystore is read transparently and re-encrypted under a new per-install password`() = runBlocking {
        val legacyMaterial = legacyEncode(context)
        // No per-install password exists yet -- mirrors an existing install upgrading into this fix.
        fakeProvider.stored = null

        val loaded = CertificateAuthority.getOrCreate(context)

        assertArrayEquals(
            "CA private key bytes must be identical before and after migration",
            legacyMaterial.keyPair.private.encoded,
            loaded.keyPair.private.encoded,
        )
        assertEquals(legacyMaterial.certificate.serialNumber, loaded.certificate.serialNumber)

        val newPassword = fakeProvider.stored
        assertNotNull("migration must mint and store a fresh per-install password", newPassword)

        // The file on disk must now open under the NEW password, not the legacy one anymore.
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile().inputStream().use { ks.load(it, newPassword) }
        assertNotNull(ks.getCertificate(CertificateAuthority.KEYSTORE_ALIAS))

        // A second load must not need the legacy fallback again (still succeeds either way,
        // but confirms the re-write actually took effect rather than a no-op).
        val reloaded = CertificateAuthority.getOrCreate(context)
        assertArrayEquals(legacyMaterial.keyPair.private.encoded, reloaded.keyPair.private.encoded)
    }

    // ── (c) export path ──────────────────────────────────────────────

    @Test
    fun `exportP12 still produces a PKCS12 openable with the documented public export password`() = runBlocking {
        val material = CertificateAuthority.getOrCreate(context)
        val p12Bytes = CertificateAuthority.exportP12(context)

        val exportPassword = "requestr-ca-local".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(java.io.ByteArrayInputStream(p12Bytes), exportPassword)

        val exportedCert = ks.getCertificate(CertificateAuthority.KEYSTORE_ALIAS)
        assertEquals(material.certificate, exportedCert)
        val exportedKey = ks.getKey(CertificateAuthority.KEYSTORE_ALIAS, exportPassword)
        assertArrayEquals(material.keyPair.private.encoded, exportedKey.encoded)
    }

    // ── (d) crash-safety ──────────────────────────────────────────────

    @Test
    fun `an interrupted rewrite does not destroy the original keystore`() = runBlocking {
        legacyEncode(context)
        val originalBytes = keystoreFile().readBytes()

        // Force the atomic-write verification step to fail, simulating a crash/kill between
        // "temp file written" and "temp file verified readable" -- by making rotate() hand back
        // a password that changes identity by the time persistAtomic tries to verify with it is
        // impractical to simulate directly, so instead we simulate the more common real failure:
        // the temp file cannot be created at all (parent dir unwritable-for-new-files scenario is
        // emulated by pre-occupying the temp path as a directory, which File.outputStream() on a
        // FileOutputStream target cannot write through).
        val tempPath = File(keystoreFile().parentFile, "${CertificateAuthority.KEYSTORE_FILE}.tmp")
        tempPath.delete()
        tempPath.mkdir() // now the temp path exists as a directory, so writing to it fails

        fakeProvider.stored = null
        val loaded = CertificateAuthority.getOrCreate(context)
        // Load must still have succeeded via the legacy fallback despite the failed migration.
        assertNotNull(loaded.certificate)

        // Original file on disk must be byte-for-byte untouched since the rename never happened.
        assertArrayEquals(
            "the original keystore must survive an interrupted/failed migration write untouched",
            originalBytes,
            keystoreFile().readBytes(),
        )

        tempPath.delete()
        Unit
    }
}
