package com.example.interception.tls

import com.example.interception.LazyUpstreamConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * S1 task 8, `MutualTlsTest`: the upstream leg presents a client certificate when a per-domain
 * policy configures one, presents none otherwise, and a wrong alias is a named failure. Uses a
 * real loopback TLS server with `needClientAuth = true`.
 */
class MutualTlsTest {

    @get:Rule val tmp = TemporaryFolder()

    companion object {
        init {
            // Same reason as MitmProxyEngineMitmHandshakeTest: the desktop Conscrypt build does
            // not reliably handle STARTTLS-style upgrades on plain sockets; use SunJSSE.
            java.security.Security.removeProvider("Conscrypt")
        }
    }

    @Before
    fun removeConscryptAgain() {
        // Other Robolectric-backed classes sharing this test JVM re-register Conscrypt between
        // test methods (see MitmProxyEngineLazyUpstreamTest.setUp); the layered client socket
        // then hangs mid-handshake and the server never sees the client certificate.
        java.security.Security.removeProvider("Conscrypt")
    }

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    private class Server(val socket: SSLServerSocket, val peerChain: AtomicReference<Array<out java.security.cert.Certificate>?>, val done: CountDownLatch, val error: AtomicReference<Throwable?>)

    private fun startServer(): Server {
        val kp = CertTestFixtures.rsaKeyPair()
        val cert = CertTestFixtures.remoteLeaf("localhost", sans = listOf("localhost", "127.0.0.1"), keyPair = kp)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("s", kp.private, "".toCharArray(), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trustAll, SecureRandom()) }
        val server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.needClientAuth = true
        val peer = AtomicReference<Array<out java.security.cert.Certificate>?>(null)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Thread {
            try {
                val s = server.accept() as SSLSocket
                s.soTimeout = 10_000
                s.startHandshake()
                peer.set(s.session.peerCertificates)
                // Release the waiter BEFORE close(): SunJSSE's SSLSocket.close() waits for the
                // peer's close_notify for up to soTimeout (10 s here) when the client has not
                // closed yet, which would otherwise race the test's own 10 s await.
                done.countDown()
                s.close()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }.also { it.isDaemon = true }.start()
        return Server(server, peer, done, error)
    }

    private fun storeWithClientBundle(alias: String = "client"): Pair<ClientCertStore, X509Certificate> {
        val store = ClientCertStore(tmp.newFolder("tls"))
        val kp = CertTestFixtures.rsaKeyPair()
        val clientCert = CertTestFixtures.remoteLeaf("client.device", sans = null, keyPair = kp)
        val imported = store.importMaterial("bundle", CertTestFixtures.p12(alias, "secret".toCharArray(), clientCert, kp), "secret".toCharArray())
        assertTrue(imported.isSuccess)
        return store to clientCert
    }

    @Test
    fun `client certificate is presented when a policy configures one`() {
        val (store, clientCert) = storeWithClientBundle()
        val policy = DomainCertPolicy("localhost", DomainCertMode.AUTO, clientP12Ref = "bundle", clientP12Alias = "client")
        val server = startServer()
        try {
            val upstream = LazyUpstreamConnection(
                "localhost", server.socket.localPort,
                keyManagers = store.clientKeyManagers(policy),
            )
            val connected = upstream.connect()
            assertTrue(connected.sslSocket.isConnected)
            assertTrue(server.done.await(10, TimeUnit.SECONDS))
            assertNull(server.error.get())
            val peer = server.peerChain.get()
            assertNotNull("server must have received the client certificate", peer)
            assertEquals(clientCert, peer!!.first())
            connected.sslSocket.close()
        } finally {
            server.socket.close()
        }
    }

    @Test
    fun `no client certificate is presented when no policy configures one`() {
        val server = startServer()
        try {
            val upstream = LazyUpstreamConnection("localhost", server.socket.localPort)
            var clientFailed = false
            try {
                val c = upstream.connect()
                // Some JSSE versions only surface the server's rejection on the first read.
                c.input.read()
            } catch (_: Exception) {
                clientFailed = true
            }
            assertTrue(server.done.await(10, TimeUnit.SECONDS))
            // Either side may be the one to notice, but the server must not have a client chain.
            assertTrue(clientFailed || server.error.get() != null)
            assertNull(server.peerChain.get())
        } finally {
            server.socket.close()
        }
    }

    @Test
    fun `wrong alias is reported as a named failure and missing bundle too`() {
        val (store, _) = storeWithClientBundle(alias = "real")
        try {
            store.clientKeyManagers(DomainCertPolicy("localhost", clientP12Ref = "bundle", clientP12Alias = "typo"))
            fail("expected ClientCertException")
        } catch (e: ClientCertException) {
            assertTrue(e.message!!.contains("typo"))
            assertTrue(e.message!!.contains("real"))
        }
        try {
            store.clientKeyManagers(DomainCertPolicy("localhost", clientP12Ref = "nope"))
            fail("expected ClientCertException")
        } catch (e: ClientCertException) {
            assertTrue(e.message!!.contains("nope"))
        }
        assertNull(store.clientKeyManagers(DomainCertPolicy("localhost")))
        // An alias-less policy on an existing bundle works and picks the only key entry.
        assertNotNull(store.clientKeyManagers(DomainCertPolicy("localhost", clientP12Ref = "bundle")))
    }

    @Test
    fun `AcceptAllRecording accepts a self-signed upstream but records that it is untrusted`() {
        val server = startServer()
        try {
            val (store, _) = storeWithClientBundle()
            val recorded = AtomicReference<UpstreamTrustPolicy.Outcome?>(null)
            val upstream = LazyUpstreamConnection(
                "localhost", server.socket.localPort,
                keyManagers = store.clientKeyManagers(DomainCertPolicy("localhost", clientP12Ref = "bundle")),
                trustPolicy = UpstreamTrustPolicy.AcceptAllRecording { recorded.set(it) },
            )
            upstream.connect().sslSocket.close()
            assertTrue(server.done.await(10, TimeUnit.SECONDS))
            val outcome = recorded.get()
            assertNotNull(outcome)
            assertEquals("localhost", outcome!!.host)
            assertTrue("self-signed loopback cert must be recorded as untrusted", !outcome.trusted)
            assertNotNull(outcome.reason)
        } finally {
            server.socket.close()
        }
    }

    @Test
    fun `Validate policy fails the handshake against a self-signed upstream`() {
        val server = startServer()
        try {
            val (store, _) = storeWithClientBundle()
            val upstream = LazyUpstreamConnection(
                "localhost", server.socket.localPort,
                keyManagers = store.clientKeyManagers(DomainCertPolicy("localhost", clientP12Ref = "bundle")),
                trustPolicy = UpstreamTrustPolicy.Validate(),
            )
            try {
                upstream.connect()
                fail("expected the upstream handshake to fail")
            } catch (e: javax.net.ssl.SSLException) {
                assertTrue(e.message?.contains("localhost") == true || e.cause != null)
            }
        } finally {
            server.socket.close()
        }
    }
}
