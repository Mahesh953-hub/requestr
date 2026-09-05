package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import java.io.ByteArrayInputStream
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
import org.robolectric.annotation.ConscryptMode

/**
 * Regression coverage for the MITM proxy CONNECT/header-draining and per-host certificate fixes.
 *
 * Drives a real CONNECT through a real [MitmProxyEngine], asserts the engine drains the CONNECT
 * headers (so the bytes that follow are the TLS handshake, not leftover HTTP headers), completes
 * the client-facing TLS handshake with a per-host certificate issued by the requestr CA, and
 * successfully decrypts an HTTP request/response pair end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MitmProxyEngineMitmHandshakeTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: MitmProxyEngine

    /** In-memory stand-in for the AndroidKeyStore-backed CA password provider. */
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
            // Conscrypt is installed at the highest priority in this JVM, but its desktop
            // build does not support TLS over a plain socket after HTTP CONNECT data has been
            // exchanged (startHandshake() hangs). It also conflicts with SunJSSE's EC named
            // group initialisation. Remove it so the MITM handshake test uses SunJSSE, which
            // handles the STARTTLS-over-CONNECT upgrade correctly. Robolectric's ConscryptMode
            // (ON by default) re-installs Conscrypt before EVERY test method, so this one-shot
            // removal only protects the first method to run -- the class-level
            // @ConscryptMode(OFF) above is what keeps the second method on SunJSSE too.
            java.security.Security.removeProvider("Conscrypt")
        }
    }

    @Before
    fun setUp() {
        CertificateAuthority.passwordProviderForTest = fakeProvider
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()

        // Force MITM for the local upstream so the engine takes the decrypting branch.
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "mitm local", domain = "localhost", action = RuleAction.FORCE_MITM)
            )
            com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
        }
    }

    @After
    fun tearDown() {
        com.example.interception.rules.LegacyRuleTestSupport.clear()
        engine.stop()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
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

    private fun startUpstreamTlsServer(): Pair<SSLServerSocket, Thread> {
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

        val thread = Thread {
            runCatching {
                val socket = serverSocket.accept() as SSLSocket
                socket.use {
                    val reader = it.inputStream.bufferedReader()
                    var line: String? = reader.readLine()
                    while (line != null && line.isNotEmpty()) {
                        line = reader.readLine()
                    }
                    val body = "Hello MITM!"
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
        thread.start()
        return serverSocket to thread
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

    @Test
    fun `CONNECT headers are drained and the MITM handshake decrypts a full request response`() {
        val (upstreamServer, upstreamThread) = startUpstreamTlsServer()
        val upstreamPort = upstreamServer.localPort

        val captured = mutableListOf<CapturedTrafficEntity>()
        val latch = CountDownLatch(1)
        engine = MitmProxyEngine(
            onDecryptedTransaction = { entity ->
                captured.add(entity)
                latch.countDown()
            },
            caTrustGate = CaTrustGate(probe = { true }),
        )

        val proxyPort = engine.start(context, 0)

        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 30000
        val rawOut = socket.getOutputStream()
        rawOut.write(
            ("CONNECT localhost:$upstreamPort HTTP/1.1\r\n" +
                    "Host: localhost:$upstreamPort\r\n" +
                    "Proxy-Authorization: basic dXNlcjpwYXNz\r\n" +
                    "X-Drain-Me: leftover-connect-header\r\n\r\n").toByteArray()
        )
        rawOut.flush()

        // Read the plain-text CONNECT response from the *raw* socket input so we don't
        // pull any TLS handshake bytes into a BufferedReader's internal buffer. Without
        // this, wrapping the socket in an SSLSocket would hang because the buffered bytes
        // would be lost to the TLS engine.
        val rawIn = socket.getInputStream()
        val responseBytes = java.io.ByteArrayOutputStream()
        var lastFour = 0L
        var readCount = 0
        while (true) {
            val b = rawIn.read()
            if (b == -1) break
            readCount++
            responseBytes.write(b)
            lastFour = ((lastFour shl 8) and 0xFFFFFFFF) or b.toLong()
            if (lastFour == 0x0D0A0D0AL) {
                break
            }
        }
        val responseText = responseBytes.toString(Charsets.ISO_8859_1)
        val statusLine = responseText.lineSequence().firstOrNull { it.isNotBlank() }
        assertNotNull("proxy must respond to CONNECT", statusLine)
        assertTrue("CONNECT must be accepted", statusLine!!.contains("200"))

        // The next bytes on the socket must be a valid TLS ClientHello, which proves the
        // CONNECT headers were consumed and not fed into the TLS parser as a "wrong version".
        val sslContext = caTrustingSslContext()
        val sslSocket = sslContext.socketFactory
            .createSocket(socket, "localhost", upstreamPort, true) as SSLSocket
        try {
            sslSocket.startHandshake()
        } catch (e: Exception) {
            throw e
        }

        sslSocket.outputStream.write(
            "GET /hello HTTP/1.1\r\nHost: localhost:$upstreamPort\r\n\r\n".toByteArray()
        )
        sslSocket.outputStream.flush()

        // Wait for the engine to emit the decrypted transaction.
        assertTrue("decrypted transaction was not emitted", latch.await(5, TimeUnit.SECONDS))
        val entity = captured.first()
        assertEquals("GET", entity.method)
        assertEquals("https://localhost/hello", entity.url)
        assertEquals(200, entity.statusCode)
        assertTrue("transaction must be marked as decrypted", entity.wasDecrypted)
        assertTrue("response body must be decrypted", entity.responseBody?.contains("Hello MITM!") == true)

        sslSocket.close()
        upstreamServer.close()
        upstreamThread.join(5000)
    }

    /**
     * S1 task 8 regression: the ClientHello is peeked BEFORE the leaf is issued, so the SNI --
     * not the CONNECT authority -- decides the leaf identity. `CONNECT 127.0.0.1:port` with SNI
     * `localhost` must present a `CN=localhost` leaf (the old order produced `CN=127.0.0.1`).
     */
    @Test
    fun `ClientHello peek precedes issuance so SNI beats an IP CONNECT authority`() {
        val (upstreamServer, upstreamThread) = startUpstreamTlsServer()
        val upstreamPort = upstreamServer.localPort
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "mitm ip", domain = "127.0.0.1", action = RuleAction.FORCE_MITM)
            )
            com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
        }
        engine = MitmProxyEngine(caTrustGate = CaTrustGate(probe = { true }))
        val proxyPort = engine.start(context, 0)

        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 30000
        socket.getOutputStream().apply {
            write(("CONNECT 127.0.0.1:$upstreamPort HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\n\r\n").toByteArray())
            flush()
        }
        val rawIn = socket.getInputStream()
        var lastFour = 0L
        val status = java.io.ByteArrayOutputStream()
        while (true) {
            val b = rawIn.read()
            if (b == -1) break
            status.write(b)
            lastFour = ((lastFour shl 8) and 0xFFFFFFFF) or b.toLong()
            if (lastFour == 0x0D0A0D0AL) break
        }
        assertTrue(status.toString(Charsets.ISO_8859_1.name()).contains("200"))

        val sslSocket = caTrustingSslContext().socketFactory
            .createSocket(socket, "localhost", upstreamPort, true) as SSLSocket
        sslSocket.startHandshake()
        val presented = sslSocket.session.peerCertificates.first() as X509Certificate
        assertEquals("CN=localhost", presented.subjectX500Principal.name)
        assertTrue(presented.subjectAlternativeNames.any { it[1] == "localhost" })
        // S1 task 8 leaf hardening is visible end-to-end: EC key, serverAuth EKU, <= 90 days.
        assertEquals("EC", presented.publicKey.algorithm)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), presented.extendedKeyUsage)
        assertTrue(presented.notAfter.time - System.currentTimeMillis() <= 90L * 24 * 60 * 60 * 1000)

        sslSocket.close()
        upstreamServer.close()
        upstreamThread.join(5000)
    }
}
