package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import java.io.BufferedReader
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
 * §1.5 MUST-FIX end-to-end coverage: [MitmProxyEngine] must never dial/handshake the real
 * upstream server until a rule actually needs it, must connect at most once per client
 * connection, and must reuse that connection across every later request on the same CONNECT
 * tunnel -- including a mocked request followed by a real one.
 *
 * Modeled on [MitmProxyEngineMitmHandshakeTest]: drives a real CONNECT through a real
 * [MitmProxyEngine] over loopback sockets, but the fake upstream here is a real
 * [SSLServerSocket] whose accept-count is the ground truth for "did the engine actually try to
 * reach the real server". [MitmProxyEngineMockResponseTest] proves the same "never dials
 * upstream" property at the [MitmProxyEngine.handleParsedRequest]-unit level (a `connectionFactory`
 * that throws if invoked); this file proves it end-to-end through the full CONNECT/TLS pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineLazyUpstreamTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: MitmProxyEngine

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

    private val fakeProvider = FakePasswordProvider()

    companion object {
        init {
            // Same rationale as MitmProxyEngineMitmHandshakeTest: Conscrypt's desktop build
            // does not support TLS over a plain socket after HTTP CONNECT data has already
            // been exchanged, so remove it in favour of SunJSSE for this JVM.
            java.security.Security.removeProvider("Conscrypt")
        }
    }

    @Before
    fun setUp() {
        // Not just once at class-load time (companion `init`): when this class runs in the same
        // Gradle test JVM as other Robolectric-backed tests (real, given other agents' test
        // classes share this JVM/sandbox), something re-registers the Conscrypt provider between
        // test methods, and its desktop build hangs doing TLS over a plain socket after HTTP
        // CONNECT data has already been exchanged. Removing it fresh before every test method
        // (idempotent -- a no-op if already absent) is what actually makes this reliable.
        java.security.Security.removeProvider("Conscrypt")

        CertificateAuthority.passwordProviderForTest = fakeProvider
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()

        // RequestrDatabase.get(context) is a process-wide singleton backed by a real on-disk
        // "requestr.db" -- rows inserted by one test method are still there for the next test
        // method in this class (Robolectric does not recreate it per-@Test). Start every test
        // from a clean rules table so an earlier test's MOCK_RESPONSE rule can never leak into
        // and shadow a later test's rule matching.
        runBlocking {
            RequestrDatabase.get(context).openHelper.writableDatabase.execSQL("DELETE FROM intercept_rules")
        }

        // RuleEnforcer.checkRules is shared, first-match-wins, unconditional-per-call: both
        // ConnectControl.evaluate (CONNECT phase, on the synthetic "https://host:port/" URL)
        // and MitmProxyEngine.handleParsedRequest (decrypted-request phase, on the *real*
        // request URL, which HttpParser.tryParseRequest always builds without a port -- see
        // "$scheme://$host$pathAndQuery") call it with the same `host`. A plain `domain =
        // "localhost"` FORCE_MITM rule would therefore ALSO win at the decrypted-request phase
        // for every request (domain matching only looks at `host`, not the URL/path), silently
        // shadowing every MOCK_RESPONSE rule below it in the table. Scoping this rule by
        // `pattern` to the CONNECT phase's exact synthetic root URL (never produced by a real
        // decrypted request, which always has a real path and no port) keeps it doing its one
        // job -- forcing MITM at CONNECT time -- without also winning the decrypted-phase match
        // that the MOCK_RESPONSE rules in these tests need to win instead.
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "mitm local", pattern = "^https://localhost:\\d+/$", action = RuleAction.FORCE_MITM)
            )
        }
    }

    @After
    fun tearDown() {
        engine.stop()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    private fun insertRule(
        name: String,
        domain: String = "",
        pattern: String = "",
        action: RuleAction,
        replacementValue: String? = null,
    ) = runBlocking {
        RequestrDatabase.get(context).interceptRuleDao().insert(
            InterceptRuleEntity(name = name, domain = domain, pattern = pattern, action = action, replacementValue = replacementValue)
        )
    }

    private fun generateSelfSignedCert(host: String): Pair<X509Certificate, KeyPair> {
        val bcProvider = BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", bcProvider)
            .apply { initialize(2048, SecureRandom()) }
            .genKeyPair()
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val now = Date()
        val expiry = Date(now.time + 365L * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=$host")
        val builder = JcaX509v3CertificateBuilder(subject, serial, now, expiry, subject, keyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, host))),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))
        return cert to keyPair
    }

    private class UpstreamServer(val serverSocket: SSLServerSocket, val thread: Thread, val acceptCount: AtomicInteger)

    /**
     * Real TLS server standing in for "the real upstream". Loops on [SSLServerSocket.accept]
     * (so a test can prove accept-count stays 0 for a fully-mocked connection, or exactly 1 even
     * across several requests on the same reused connection) and, per accepted socket, serves
     * HTTP/1.1 request/response pairs back-to-back on that one socket until the client closes it.
     */
    private fun startUpstreamTlsServer(): UpstreamServer {
        val (cert, keyPair) = generateSelfSignedCert("localhost")
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("upstream", keyPair.private, "".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, "".toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val acceptCount = AtomicInteger(0)

        val thread = Thread {
            runCatching {
                while (true) {
                    val socket = serverSocket.accept() as SSLSocket
                    acceptCount.incrementAndGet()
                    socket.use {
                        val reader = it.inputStream.bufferedReader()
                        while (true) {
                            val requestLine = reader.readLine() ?: break
                            val path = requestLine.split(" ").getOrNull(1) ?: "/"
                            var line = reader.readLine()
                            while (line != null && line.isNotEmpty()) {
                                line = reader.readLine()
                            }
                            val body = "real-response-for:$path"
                            it.outputStream.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain\r\n" +
                                        "Content-Length: ${body.length}\r\n\r\n" +
                                        body).toByteArray()
                            )
                            it.outputStream.flush()
                        }
                    }
                }
            }
        }
        thread.start()
        return UpstreamServer(serverSocket, thread, acceptCount)
    }

    private fun caTrustingSslContext(): SSLContext {
        val ca = runBlocking { CertificateAuthority.getOrCreate(context).certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, SecureRandom())
        }
    }

    /** Opens a CONNECT tunnel through [engine] to `localhost:$upstreamPort` and upgrades to TLS. */
    private fun openMitmTunnel(proxyPort: Int, upstreamPort: Int): SSLSocket {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 30000
        val rawOut = socket.getOutputStream()
        rawOut.write(
            ("CONNECT localhost:$upstreamPort HTTP/1.1\r\n" +
                    "Host: localhost:$upstreamPort\r\n\r\n").toByteArray()
        )
        rawOut.flush()

        val rawIn = socket.getInputStream()
        val responseBytes = java.io.ByteArrayOutputStream()
        var lastFour = 0L
        while (true) {
            val b = rawIn.read()
            if (b == -1) break
            responseBytes.write(b)
            lastFour = ((lastFour shl 8) and 0xFFFFFFFF) or b.toLong()
            if (lastFour == 0x0D0A0D0AL) break
        }
        val statusLine = responseBytes.toString(Charsets.ISO_8859_1).lineSequence().firstOrNull { it.isNotBlank() }
        assertNotNull("proxy must respond to CONNECT", statusLine)
        assertTrue("CONNECT must be accepted", statusLine!!.contains("200"))

        val sslContext = caTrustingSslContext()
        return sslContext.socketFactory
            .createSocket(socket, "localhost", upstreamPort, true) as SSLSocket
    }

    /** Reads one full HTTP/1.1 response (status line + headers + Content-Length body) off [reader]. */
    private fun readOneResponse(reader: BufferedReader): Pair<Int, String> {
        val statusLine = reader.readLine() ?: throw IllegalStateException("EOF waiting for status line")
        val status = statusLine.split(" ")[1].toInt()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toInt()
            }
        }
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return status to String(buf, 0, read)
    }

    private fun sendRequest(sslSocket: SSLSocket, path: String, upstreamPort: Int) {
        sslSocket.outputStream.write(
            "GET $path HTTP/1.1\r\nHost: localhost:$upstreamPort\r\n\r\n".toByteArray()
        )
        sslSocket.outputStream.flush()
    }

    @Test
    fun `fully mocked connection never dials the real upstream`() {
        // Pattern (not domain): matches any decrypted request to localhost ("https://localhost/...")
        // but not the CONNECT-phase synthetic URL ("https://localhost:PORT/", note the port),
        // so it wins the decrypted-phase match for every request without shadowing FORCE_MITM's
        // CONNECT-phase decision (see setUp's comment).
        insertRule(
            "mock all", pattern = "^https://localhost/", action = RuleAction.MOCK_RESPONSE,
            replacementValue = """{"statusCode":200,"bodyReplace":"mocked-body"}""",
        )

        val upstream = startUpstreamTlsServer()
        val upstreamPort = upstream.serverSocket.localPort

        val captured = mutableListOf<CapturedTrafficEntity>()
        val latch = CountDownLatch(1)
        engine = MitmProxyEngine(
            onDecryptedTransaction = { entity -> captured.add(entity); latch.countDown() },
            caTrustGate = CaTrustGate(probe = { true }),
        )
        val proxyPort = engine.start(context, 0)

        val sslSocket = openMitmTunnel(proxyPort, upstreamPort)
        sendRequest(sslSocket, "/mock-path", upstreamPort)

        assertTrue("decrypted (mocked) transaction was not emitted", latch.await(5, TimeUnit.SECONDS))
        val (status, body) = readOneResponse(sslSocket.inputStream.bufferedReader())
        assertEquals(200, status)
        assertEquals("mocked-body", body)
        assertTrue("mocked transaction must be marked decrypted", captured.first().wasDecrypted)

        // The whole point of the fix: no real TCP connection was ever attempted upstream.
        assertEquals("upstream must never be dialed for an all-MOCK_RESPONSE connection", 0, upstream.acceptCount.get())

        sslSocket.close()
        upstream.serverSocket.close()
        upstream.thread.join(5000)
    }

    @Test
    fun `non-mocked requests connect upstream once and reuse the connection`() {
        // No MOCK_RESPONSE rule -- only the FORCE_MITM rule from setUp -- so every request
        // falls through to the default LOG_ONLY action and forwards to the real upstream.
        val upstream = startUpstreamTlsServer()
        val upstreamPort = upstream.serverSocket.localPort

        val captured = mutableListOf<CapturedTrafficEntity>()
        engine = MitmProxyEngine(
            onDecryptedTransaction = { entity -> synchronized(captured) { captured.add(entity) } },
            caTrustGate = CaTrustGate(probe = { true }),
        )
        val proxyPort = engine.start(context, 0)

        val sslSocket = openMitmTunnel(proxyPort, upstreamPort)
        val reader = sslSocket.inputStream.bufferedReader()

        sendRequest(sslSocket, "/first", upstreamPort)
        val (status1, body1) = readOneResponse(reader)
        assertEquals(200, status1)
        assertEquals("real-response-for:/first", body1)

        sendRequest(sslSocket, "/second", upstreamPort)
        val (status2, body2) = readOneResponse(reader)
        assertEquals(200, status2)
        assertEquals("real-response-for:/second", body2)

        // Two real requests over the same CONNECT tunnel must reuse one upstream connection,
        // not open a second one.
        assertEquals("two requests on one client connection must reuse one upstream connect", 1, upstream.acceptCount.get())

        sslSocket.close()
        upstream.serverSocket.close()
        upstream.thread.join(5000)
    }

    @Test
    fun `a mocked request followed by a non-mocked request on the same connection lazily connects only for the second`() {
        // Pattern-scoped MOCK_RESPONSE: only requests whose URL matches /mock-path get mocked;
        // everything else (here, /real-path) falls through to the default LOG_ONLY action and
        // needs the real upstream. Both requests share one CONNECT tunnel to one host:port, so
        // this proves the lazy connect can trigger mid-connection, on a later request, after an
        // earlier request on the very same connection never needed it.
        insertRule(
            "mock one path", pattern = "/mock-path", action = RuleAction.MOCK_RESPONSE,
            replacementValue = """{"statusCode":200,"bodyReplace":"mocked-body"}""",
        )

        val upstream = startUpstreamTlsServer()
        val upstreamPort = upstream.serverSocket.localPort

        val captured = mutableListOf<CapturedTrafficEntity>()
        engine = MitmProxyEngine(
            onDecryptedTransaction = { entity -> synchronized(captured) { captured.add(entity) } },
            caTrustGate = CaTrustGate(probe = { true }),
        )
        val proxyPort = engine.start(context, 0)

        val sslSocket = openMitmTunnel(proxyPort, upstreamPort)
        val reader = sslSocket.inputStream.bufferedReader()

        sendRequest(sslSocket, "/mock-path", upstreamPort)
        val (mockStatus, mockBody) = readOneResponse(reader)
        assertEquals(200, mockStatus)
        assertEquals("mocked-body", mockBody)
        assertEquals(
            "no upstream connect must happen for the first (mocked) request on this connection",
            0, upstream.acceptCount.get(),
        )

        sendRequest(sslSocket, "/real-path", upstreamPort)
        val (realStatus, realBody) = readOneResponse(reader)
        assertEquals(200, realStatus)
        assertEquals("real-response-for:/real-path", realBody)
        assertEquals(
            "the second (non-mocked) request must lazily connect upstream exactly once",
            1, upstream.acceptCount.get(),
        )

        sslSocket.close()
        upstream.serverSocket.close()
        upstream.thread.join(5000)
    }
}
