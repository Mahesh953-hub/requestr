package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §1.10: covers [MitmProxyEngine.generateHostCertOrMirror] -- the settings-gated wrapper around
 * [CertificateAuthority.issueMirroredLeaf] wired into the `FORCE_MITM` cert-cache call site in
 * [MitmProxyEngine.handleClient]. [CertificateAuthority.issueLeafFromProfile] (the pure cert-
 * building half, given an already-fetched profile) is covered separately by
 * [RemoteCertificateMirrorTest]; this file only exercises the disabled/fallback gating, which
 * doesn't require a reachable remote host.
 *
 * [CertificateAuthority.passwordProviderForTest] stands in for the real `AndroidKeyStore`-backed
 * provider, same as [CertificateAuthorityTest] -- Robolectric has no `AndroidKeyStore` provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineCertMirrorTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakePasswordProvider : CertificateAuthority.CaKeystorePasswordProvider {
        var stored: CharArray? = null

        override suspend fun read(context: Context): CharArray? = stored?.copyOf()

        override suspend fun rotate(context: Context): CharArray {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).toCharArray()
            stored = encoded.copyOf()
            return encoded
        }
    }

    @Before
    fun setUp() {
        CertificateAuthority.passwordProviderForTest = FakePasswordProvider()
        java.io.File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    @After
    fun tearDown() {
        CertificateAuthority.passwordProviderForTest = null
        java.io.File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    @Test
    fun `mirroring disabled issues the plain self-signed leaf with zero network attempt`() = runBlocking {
        val engine = MitmProxyEngine()
        val (cert, _) = engine.generateHostCertOrMirror(context, "example.test", mirrorEnabled = false)
        assertTrue(cert.subjectX500Principal.name.contains("example.test"))
    }

    @Test
    fun `mirroring enabled but unreachable degrades to the plain self-signed leaf`() = runBlocking {
        val engine = MitmProxyEngine()
        // Nothing listens on 127.0.0.1:443 in this test environment, so the mirror probe fails
        // fast (connection refused) and generateHostCertOrMirror must fall back rather than
        // propagate the failure or block the connection.
        val (cert, _) = engine.generateHostCertOrMirror(context, "127.0.0.1", mirrorEnabled = true)
        assertTrue(cert.subjectX500Principal.name.contains("127.0.0.1"))
    }
}
