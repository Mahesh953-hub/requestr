package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptorRuleEntity
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.NamePolicy
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
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
 * S4 task 28 end-to-end: a client CONNECTs to `app.example` (a name that does not resolve) and a
 * MIRROR + FORCE_DECRYPT rule pair makes requestr terminate TLS with a leaf for the name the
 * policy dictates, dial a real loopback TLS server standing in for the mirror, present the SNI the
 * policy dictates, and forward the request with the `Host:` header the policy dictates -- while
 * the bytes really reach the mirror and the response really comes back.
 *
 * Harness follows [MitmProxyEngineLazyUpstreamTest] (SunJSSE, real sockets, requestr's own CA).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineMirrorTest {

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
        private const val APP_HOST = "app.example"
        init { java.security.Security.removeProvider("Conscrypt") }
    }

    @Before
    fun setUp() {
        java.security.Security.removeProvider("Conscrypt")
        CertificateAuthority.passwordProviderForTest = fakeProvider
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    @After
    fun tearDown() {
        engine.stop()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    private var nextId = 1L

    private fun rule(config: RuleConfig, name: String) = InterceptorRule(
        id = nextId++, name = name, stage = config.stage, matcher = RuleMatcherSpec(hostGlobs = listOf(APP_HOST)), config = config,
    )

    private fun snapshot(mirror: MirrorConfig): RuleSnapshot = RuleSnapshotCompiler.compile(
        listOf(
            InterceptorRuleEntity.fromDomain(rule(GatewayConfig(GatewayMode.FORCE_DECRYPT), "decrypt-app")),
            InterceptorRuleEntity.fromDomain(rule(mirror, "to-staging")),
        ),
        emptyList(), 1L,
    )

    private fun generateSelfSignedCert(host: String): Pair<X509Certificate, KeyPair> {
        val bcProvider = BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", bcProvider).apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val now = Date()
        val subject = X500Name("CN=$host")
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(System.currentTimeMillis()), now, Date(now.time + 365L * 24 * 60 * 60 * 1000), subject, keyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, host))))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
        return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(builder.build(signer)) to keyPair
    }

    /** What the mirror observed per accepted connection: requested SNI names and each request's Host header. */
    private class MirrorServer(val serverSocket: SSLServerSocket, val thread: Thread) {
        val acceptCount = AtomicInteger(0)
        val sniNames = CopyOnWriteArrayList<String>()
        val hostHeaders = CopyOnWriteArrayList<String>()
    }

    private fun startMirrorServer(): MirrorServer {
        val (cert, keyPair) = generateSelfSignedCert("localhost")
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("mirror", keyPair.private, "".toCharArray(), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "".toCharArray()) }
        val sslContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        lateinit var server: MirrorServer
        val thread = Thread {
            runCatching {
                while (true) {
                    val socket = serverSocket.accept() as SSLSocket
                    server.acceptCount.incrementAndGet()
                    socket.use {
                        it.startHandshake()
                        (it.session as? ExtendedSSLSession)?.requestedServerNames?.forEach { name ->
                            if (name is SNIHostName) server.sniNames.add(name.asciiName)
                        }
                        val reader = it.inputStream.bufferedReader()
                        while (true) {
                            val requestLine = reader.readLine() ?: break
                            val path = requestLine.split(" ").getOrNull(1) ?: "/"
                            var line = reader.readLine()
                            while (line != null && line.isNotEmpty()) {
                                if (line.startsWith("Host:", ignoreCase = true)) server.hostHeaders.add(line.substringAfter(":").trim())
                                line = reader.readLine()
                            }
                            val body = "mirror-response-for:$path"
                            it.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
                            it.outputStream.flush()
                        }
                    }
                }
            }
        }
        server = MirrorServer(serverSocket, thread)
        thread.start()
        return server
    }

    private fun caTrustingSslContext(): SSLContext {
        val ca = runBlocking { CertificateAuthority.getOrCreate(context).certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
    }

    /** CONNECTs to `app.example:443` through [engine] and upgrades to TLS with SNI `app.example`. */
    private fun openMitmTunnel(proxyPort: Int): SSLSocket {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 30000
        socket.getOutputStream().write("CONNECT $APP_HOST:443 HTTP/1.1\r\nHost: $APP_HOST:443\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
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
        assertTrue("CONNECT must be accepted: $statusLine", statusLine!!.contains("200"))
        val ssl = caTrustingSslContext().socketFactory.createSocket(socket, APP_HOST, 443, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    private fun readOneResponse(reader: BufferedReader): Triple<Int, Map<String, String>, String> {
        val statusLine = reader.readLine() ?: throw IllegalStateException("EOF waiting for status line")
        val status = statusLine.split(" ")[1].toInt()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(":").trim().lowercase()] = line.substringAfter(":").trim()
        }
        val contentLength = headers["content-length"]?.toInt() ?: 0
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return Triple(status, headers, String(buf, 0, read))
    }

    private fun leafCommonName(ssl: SSLSocket): String {
        val leaf = ssl.session.peerCertificates.first() as X509Certificate
        return leaf.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
    }

    private fun runMirrored(mirror: MirrorConfig, path: String = "/health"): Triple<MirrorServer, SSLSocket, Triple<Int, Map<String, String>, String>> {
        val captured = CopyOnWriteArrayList<CapturedTrafficEntity>()
        val server = startMirrorServer()
        val snap = snapshot(mirror.copy(targetHost = "localhost", targetPort = server.serverSocket.localPort))
        engine = MitmProxyEngine(
            onDecryptedTransaction = { captured.add(it) },
            caTrustGate = CaTrustGate(probe = { true }),
            ruleSnapshot = { snap },
        )
        val proxyPort = engine.start(context, 0)
        val ssl = openMitmTunnel(proxyPort)
        ssl.outputStream.write("GET $path HTTP/1.1\r\nHost: $APP_HOST\r\n\r\n".toByteArray())
        ssl.outputStream.flush()
        val response = readOneResponse(ssl.inputStream.bufferedReader())
        assertEquals(200, response.first)
        assertEquals("mirror-response-for:$path", response.third)
        assertEquals("bytes must reach the mirror exactly once", 1, server.acceptCount.get())
        // Persisted row: mirror attribution header + rules badge on the captured exchange.
        val deadline = System.currentTimeMillis() + 5_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val row = captured.single()
        assertEquals("rule=\"to-staging\"; target=localhost:${server.serverSocket.localPort}", row.responseHeaders[RuleMetadataHeaders.MIRROR])
        assertEquals(APP_HOST, row.host)
        return Triple(server, ssl, response)
    }

    private fun finish(server: MirrorServer, ssl: SSLSocket) {
        ssl.close()
        server.serverSocket.close()
        server.thread.join(5000)
    }

    @Test
    fun `mirror address on every axis -- leaf for the client, SNI and Host for the mirror`() {
        val (server, ssl, response) = runMirrored(
            MirrorConfig("placeholder", hostPolicy = NamePolicy.USE_MIRROR_ADDRESS, sniPolicy = NamePolicy.USE_MIRROR_ADDRESS, cnPolicy = NamePolicy.PRESERVE),
        )
        assertEquals("client validates the name it asked for", APP_HOST, leafCommonName(ssl))
        // SunJSSE never sends server_name for a single-label host such as `localhost`, so the
        // mirror sees no SNI here (the SNI plumbing itself is covered by the LazyUpstreamConnection
        // test below with a dotted name); what matters is that the client's `app.example` did NOT
        // leak to the mirror.
        assertEquals(emptyList<String>(), server.sniNames)
        assertEquals(listOf("localhost:${server.serverSocket.localPort}"), server.hostHeaders)
        assertTrue(response.second[RuleMetadataHeaders.MIRROR]!!.startsWith("rule=\"to-staging\""))
        finish(server, ssl)
    }

    @Test
    fun `LazyUpstreamConnection presents the plan's SNI while dialling the mirror address`() {
        val server = startMirrorServer()
        engine = MitmProxyEngine(ruleSnapshot = { RuleSnapshot.EMPTY }) // only so tearDown has something to stop
        val upstream = LazyUpstreamConnection("127.0.0.1", server.serverSocket.localPort, tlsServerName = "staging.example")
        assertEquals("staging.example", upstream.serverName)
        val connected = upstream.connect()
        connected.output.write("GET /sni HTTP/1.1\r\nHost: staging.example\r\n\r\n".toByteArray())
        connected.output.flush()
        val (status, _, body) = readOneResponse(connected.input.bufferedReader())
        assertEquals(200, status)
        assertEquals("mirror-response-for:/sni", body)
        assertEquals(listOf("staging.example"), server.sniNames)
        upstream.closeQuietly()
        server.serverSocket.close()
        server.thread.join(5000)
    }

    @Test
    fun `preserve Host and SNI, mint the leaf for the mirror name`() {
        val (server, ssl, _) = runMirrored(
            MirrorConfig("placeholder", hostPolicy = NamePolicy.PRESERVE, sniPolicy = NamePolicy.PRESERVE, cnPolicy = NamePolicy.USE_MIRROR_ADDRESS),
        )
        assertEquals("localhost", leafCommonName(ssl))
        assertEquals("the mirror sees the client's SNI", listOf(APP_HOST), server.sniNames)
        assertEquals("the Host header is untouched", listOf(APP_HOST), server.hostHeaders)
        finish(server, ssl)
    }

    @Test
    fun `connect address on every axis`() {
        val (server, ssl, _) = runMirrored(
            MirrorConfig("placeholder", hostPolicy = NamePolicy.USE_CONNECT_ADDRESS, sniPolicy = NamePolicy.USE_CONNECT_ADDRESS, cnPolicy = NamePolicy.USE_CONNECT_ADDRESS),
            path = "/v2/items",
        )
        assertEquals(APP_HOST, leafCommonName(ssl))
        assertEquals(listOf(APP_HOST), server.sniNames)
        assertEquals(listOf(APP_HOST), server.hostHeaders)
        finish(server, ssl)
    }
}
