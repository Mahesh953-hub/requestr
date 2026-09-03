package com.example.interception.tls

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.interception.ExpiringLruCertCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S1 task 8, `DomainCertPolicyTest`: pattern precedence in [ClientCertStore.resolve], and how
 * [CertificateEngine] honours `CUSTOM_SERVER_CERT` (presented verbatim) and `PASSTHROUGH` (no
 * MITM). Robolectric only for the real `org.json` used by the store's persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class DomainCertPolicyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: ClientCertStore
    private val ca = CertTestFixtures.fakeCa()

    @Before fun setUp() { store = ClientCertStore(tmp.newFolder("tls")) }
    @After fun tearDown() { ClientCertStore.resetForTest() }

    private fun engine() = CertificateEngine(caProvider = { ca }, upstreamSniffer = { _, _ -> null }, policyStore = store, cache = ExpiringLruCertCache())

    @Test
    fun `exact beats wildcard beats catch-all, and longer wildcard suffixes win`() {
        store.upsert(DomainCertPolicy("*", DomainCertMode.AUTO))
        store.upsert(DomainCertPolicy("*.example.com", DomainCertMode.PASSTHROUGH))
        store.upsert(DomainCertPolicy("*.api.example.com", DomainCertMode.AUTO, clientP12Ref = "api-client"))
        store.upsert(DomainCertPolicy("Login.Example.COM.", DomainCertMode.CUSTOM_SERVER_CERT, serverCertRef = "login"))

        assertEquals("*", store.resolve("other.test")!!.hostPattern)
        assertEquals("*.example.com", store.resolve("www.example.com")!!.hostPattern)
        assertEquals("*.api.example.com", store.resolve("v2.api.example.com")!!.hostPattern)
        assertEquals("login.example.com", store.resolve("LOGIN.example.com")!!.hostPattern)
        // A wildcard never matches its own apex.
        assertEquals("*", store.resolve("example.com")!!.hostPattern)
        assertNull(ClientCertStore(tmp.newFolder("empty")).resolve("anything"))
    }

    @Test
    fun `policies survive a reload and remove works`() {
        store.upsert(DomainCertPolicy("a.example", DomainCertMode.PASSTHROUGH))
        store.upsert(DomainCertPolicy("b.example", DomainCertMode.AUTO, clientP12Ref = "ref", clientP12Alias = "alias"))
        val reloaded = ClientCertStore(File(tmp.root, "tls"))
        assertEquals(2, reloaded.policies().size)
        assertEquals("alias", reloaded.resolve("b.example")!!.clientP12Alias)
        assertTrue(reloaded.remove("a.example"))
        assertFalse(reloaded.remove("a.example"))
        assertNull(ClientCertStore(File(tmp.root, "tls")).resolve("a.example"))
    }

    @Test
    fun `CUSTOM_SERVER_CERT is presented verbatim`() = runBlocking {
        val kp = CertTestFixtures.rsaKeyPair()
        val imported = CertTestFixtures.remoteLeaf("custom.example", keyPair = kp)
        val result = store.importMaterial("custom", CertTestFixtures.p12("srv", "pw".toCharArray(), imported, kp), "pw".toCharArray())
        assertTrue(result.isSuccess)
        assertEquals(listOf("srv"), result.getOrThrow().aliases)
        store.upsert(DomainCertPolicy("custom.example", DomainCertMode.CUSTOM_SERVER_CERT, serverCertRef = "custom"))

        val out = engine().issue(context, CertificateEngine.Request("custom.example", 443, sni = "custom.example"))
        val custom = out as CertificateEngine.Outcome.Custom
        assertEquals(imported, custom.chain.first())
        assertEquals(kp.private, custom.privateKey)
        assertEquals("custom.example", custom.identity.host)
    }

    @Test
    fun `CUSTOM_SERVER_CERT with missing material is a named failure`() = runBlocking {
        store.upsert(DomainCertPolicy("gone.example", DomainCertMode.CUSTOM_SERVER_CERT, serverCertRef = "never-imported"))
        try {
            engine().issue(context, CertificateEngine.Request("gone.example", 443, sni = "gone.example"))
            fail("expected ClientCertException")
        } catch (e: ClientCertException) {
            assertTrue(e.message!!.contains("never-imported"))
        }
    }

    @Test
    fun `PASSTHROUGH skips MITM and AUTO mints normally`() = runBlocking {
        store.upsert(DomainCertPolicy("*.bank.example", DomainCertMode.PASSTHROUGH))
        val e = engine()
        val pass = e.issue(context, CertificateEngine.Request("1.2.3.4", 443, sni = "online.bank.example"))
        assertTrue(pass is CertificateEngine.Outcome.Passthrough)
        assertEquals("*.bank.example", pass.policy!!.hostPattern)

        val auto = e.issue(context, CertificateEngine.Request("shop.example", 443, sni = "shop.example"))
        assertTrue(auto is CertificateEngine.Outcome.Issued)
        assertNull(auto.policy)
    }

    @Test
    fun `import rejects bundles without a key entry or with the wrong password`() {
        val kp = CertTestFixtures.rsaKeyPair()
        val cert = CertTestFixtures.remoteLeaf("x.example", keyPair = kp)
        val bytes = CertTestFixtures.p12("a", "right".toCharArray(), cert, kp)
        val wrong = store.importMaterial("x", bytes, "wrong".toCharArray())
        assertTrue(wrong.isFailure)
        assertNotNull(wrong.exceptionOrNull()?.message)
        assertFalse(store.hasMaterial("x"))
        assertTrue(store.importMaterial("x", bytes, "right".toCharArray()).isSuccess)
        assertTrue(store.hasMaterial("x"))
        assertTrue(store.deleteMaterial("x"))
        assertFalse(store.hasMaterial("x"))
    }

    @Test
    fun `matches follows the documented pattern rules`() {
        assertTrue(ClientCertStore.matches("*", "anything"))
        assertTrue(ClientCertStore.matches("*.example.com", "a.b.example.com"))
        assertFalse(ClientCertStore.matches("*.example.com", "example.com"))
        assertFalse(ClientCertStore.matches("*.example.com", "notexample.com"))
        assertTrue(ClientCertStore.matches("example.com", "example.com"))
        assertFalse(ClientCertStore.matches("example.com", "www.example.com"))
    }
}


