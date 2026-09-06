package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleEntity
import com.example.data.models.HttpMethod
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.breakpoint.BreakpointRegistry
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.BreakpointConfig
import com.example.interception.rules.BreakpointPhase
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.Modification
import com.example.interception.rules.RewriteConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ThrottleConfig
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
import java.util.concurrent.Executors
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 task 31 / gate G21, end to end through a real MITM tunnel: a BREAKPOINT rule parks exactly
 * the matching flow on its own relay thread while an unrelated flow on the same engine completes;
 * the paused flow auto-resumes with byte-correct output; Replace/Abort do what the operator asked;
 * and a breakpoint composes with rewrite + throttle in the same decision.
 *
 * Harness follows [MitmProxyEngineMirrorTest]: SunJSSE, real sockets, requestr's own CA, and a
 * MIRROR rule pointing every host at a loopback TLS origin that echoes what it received.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineBreakpointTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: MitmProxyEngine
    private lateinit var registry: BreakpointRegistry
    private val pool = Executors.newCachedThreadPool()

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
        private const val PAUSED_HOST = "paused.example"
        private const val FREE_HOST = "free.example"
        init { java.security.Security.removeProvider("Conscrypt") }
    }

    @Before
    fun setUp() {
        java.security.Security.removeProvider("Conscrypt")
        CertificateAuthority.passwordProviderForTest = fakeProvider
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
        registry = BreakpointRegistry(maxPaused = 8, slotWaitMs = 50)
    }

    @After
    fun tearDown() {
        registry.resumeAll()
        engine.stop()
        pool.shutdownNow()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    private var nextId = 1L

    private fun rule(config: RuleConfig, name: String, vararg hosts: String) = InterceptorRule(
        id = nextId++, name = name, stage = config.stage, matcher = RuleMatcherSpec(hostGlobs = hosts.toList()), config = config,
    )

    private fun snapshot(originPort: Int, vararg extra: InterceptorRule): RuleSnapshot = RuleSnapshotCompiler.compile(
        (listOf(
            rule(GatewayConfig(GatewayMode.FORCE_DECRYPT), "decrypt", PAUSED_HOST, FREE_HOST),
            rule(MirrorConfig("localhost", originPort), "to-origin", PAUSED_HOST, FREE_HOST),
        ) + extra).map { InterceptorRuleEntity.fromDomain(it) },
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

    /** Loopback origin: echoes path, Host, a marker header and the request body back in the response body. */
    private class Origin(val serverSocket: SSLServerSocket, val thread: Thread) {
        val acceptCount = AtomicInteger(0)
        val requests = CopyOnWriteArrayList<String>()
    }

    private fun startOrigin(responseBody: (path: String) -> String = { "origin:$it" }): Origin {
        val (cert, keyPair) = generateSelfSignedCert("localhost")
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("origin", keyPair.private, "".toCharArray(), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "".toCharArray()) }
        val sslContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        lateinit var origin: Origin
        val thread = Thread {
            runCatching {
                while (true) {
                    val socket = serverSocket.accept() as SSLSocket
                    origin.acceptCount.incrementAndGet()
                    Thread {
                        socket.use {
                            it.startHandshake()
                            val input = it.inputStream
                            while (true) {
                                val head = StringBuilder()
                                var last4 = 0L
                                var eof = false
                                while (true) {
                                    val b = input.read()
                                    if (b == -1) { eof = true; break }
                                    head.append(b.toChar())
                                    last4 = ((last4 shl 8) and 0xFFFFFFFFL) or b.toLong()
                                    if (last4 == 0x0D0A0D0AL) break
                                }
                                if (eof) break
                                val lines = head.toString().split("\r\n").filter { l -> l.isNotEmpty() }
                                val path = lines.first().split(" ").getOrNull(1) ?: "/"
                                val headers = lines.drop(1).associate { l -> l.substringBefore(":").trim().lowercase() to l.substringAfter(":").trim() }
                                val len = headers["content-length"]?.toIntOrNull() ?: 0
                                val body = ByteArray(len)
                                var read = 0
                                while (read < len) {
                                    val n = input.read(body, read, len - read)
                                    if (n == -1) break
                                    read += n
                                }
                                origin.requests += "$path|host=${headers["host"]}|edited=${headers["x-edited"]}|bp=${headers[RuleMetadataHeaders.BREAKPOINT]}|len=$len|body=${String(body, 0, read)}"
                                val out = responseBody(path)
                                it.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${out.length}\r\n\r\n$out").toByteArray())
                                it.outputStream.flush()
                            }
                        }
                    }.start()
                }
            }
        }
        origin = Origin(serverSocket, thread)
        thread.start()
        return origin
    }

    private fun caTrustingSslContext(): SSLContext {
        val ca = runBlocking { CertificateAuthority.getOrCreate(context).certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
    }

    private fun openMitmTunnel(proxyPort: Int, host: String): SSLSocket {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 30000
        socket.getOutputStream().write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray())
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
        val ssl = caTrustingSslContext().socketFactory.createSocket(socket, host, 443, true) as SSLSocket
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

    private fun send(ssl: SSLSocket, host: String, path: String, body: String = "", extraHeaders: String = "") {
        val head = "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Length: ${body.length}\r\n$extraHeaders\r\n"
        ssl.outputStream.write((head + body).toByteArray())
        ssl.outputStream.flush()
    }

    private fun awaitPaused(n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (registry.pausedCount < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("expected $n paused flow(s)", n, registry.pausedCount)
    }

    private fun awaitRows(sink: RecordingCaptureSink, n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.rows.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(n, sink.rows.size)
    }

    private fun startEngine(sink: RecordingCaptureSink, snap: RuleSnapshot): Int {
        engine = MitmProxyEngine(sink = sink, caTrustGate = CaTrustGate(probe = { true }), ruleSnapshot = { snap }, safetyGate = com.example.interception.rules.InterceptorSafetyGate(followSettings = false), breakpoints = registry)
        return engine.start(context, 0)
    }

    @Test
    fun `two concurrent flows -- the paused one blocks only itself and auto-resumes with correct wire bytes`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(
            origin.serverSocket.localPort,
            rule(BreakpointConfig(BreakpointPhase.REQUEST, timeoutMs = 1_500), "pause-it", PAUSED_HOST),
        )
        val proxyPort = startEngine(sink, snap)
        val pausedSsl = openMitmTunnel(proxyPort, PAUSED_HOST)
        val freeSsl = openMitmTunnel(proxyPort, FREE_HOST)

        val t0 = System.currentTimeMillis()
        send(pausedSsl, PAUSED_HOST, "/paused", body = "hello")
        awaitPaused(1)
        val paused = registry.pausedFlows.single()
        assertEquals("pause-it", paused.rule.name)
        assertEquals(FlowAuditTrail.Phase.REQUEST, paused.phase)
        assertEquals(PAUSED_HOST, paused.host)
        // Nothing reached the origin while parked.
        assertEquals(0, origin.requests.size)

        // The unrelated flow completes while the other is parked.
        send(freeSsl, FREE_HOST, "/free")
        val free = readOneResponse(freeSsl.inputStream.bufferedReader())
        val freeDoneAt = System.currentTimeMillis()
        assertEquals(200, free.first)
        assertEquals("origin:/free", free.third)
        assertTrue("free flow must not wait for the paused one (${freeDoneAt - t0}ms)", freeDoneAt - t0 < 1_400)
        assertEquals(1, registry.pausedCount)

        // Mandatory auto-resume at timeoutMs; bytes reach the origin unchanged and untainted.
        val resp = readOneResponse(pausedSsl.inputStream.bufferedReader())
        val elapsed = System.currentTimeMillis() - t0
        assertEquals(200, resp.first)
        assertEquals("origin:/paused", resp.third)
        assertTrue("auto-resume should take ~1.5s, took ${elapsed}ms", elapsed >= 1_400)
        assertEquals(0, registry.pausedCount)
        val forwarded = origin.requests.single { it.startsWith("/paused") }
        assertEquals("/paused|host=$PAUSED_HOST|edited=null|bp=null|len=5|body=hello", forwarded)

        awaitRows(sink, 2)
        val row = sink.rows.single { it.entity.host == PAUSED_HOST }
        assertEquals("rule=\"pause-it\"; action=auto-resumed", row.entity.requestHeaders[RuleMetadataHeaders.BREAKPOINT])
        val applied = sink.appliedRules.filter { it.trafficId == row.id && it.stage == RuleStage.BREAKPOINT.name }
        val bp = applied.single { it.outcome == FlowAuditTrail.Outcome.APPLIED.name }
        assertTrue(bp.detail!!, bp.detail!!.matches(Regex("paused \\d+ms; auto-resumed at 1500ms")))
        val freeRow = sink.rows.single { it.entity.host == FREE_HOST }
        assertNull(freeRow.entity.requestHeaders[RuleMetadataHeaders.BREAKPOINT])

        pausedSsl.close(); freeSsl.close()
        origin.serverSocket.close()
    }

    @Test
    fun `Replace forwards the operator's edited request with a recomputed Content-Length`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(origin.serverSocket.localPort, rule(BreakpointConfig(BreakpointPhase.REQUEST), "pause-it", PAUSED_HOST))
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, PAUSED_HOST)

        send(ssl, PAUSED_HOST, "/orig", body = "short")
        awaitPaused(1)
        val flow = registry.pausedFlows.single()
        val edited = ParsedHttpMessage(
            isRequest = true, method = HttpMethod.POST, url = "https://$PAUSED_HOST/orig", host = PAUSED_HOST,
            headers = flow.message.headers + mapOf("x-edited" to "yes", "content-length" to "1" /* stale on purpose */),
            body = "a much longer edited body",
        )
        assertTrue(registry.replace(flow.flowId, edited))

        val resp = readOneResponse(ssl.inputStream.bufferedReader())
        assertEquals(200, resp.first)
        assertEquals("origin:/orig", resp.third)
        assertEquals(
            "/orig|host=$PAUSED_HOST|edited=yes|bp=null|len=25|body=a much longer edited body",
            origin.requests.single(),
        )
        awaitRows(sink, 1)
        val row = sink.rows.single()
        assertEquals("rule=\"pause-it\"; action=replaced", row.entity.requestHeaders[RuleMetadataHeaders.BREAKPOINT])
        assertEquals("a much longer edited body", row.entity.requestBody)
        ssl.close(); origin.serverSocket.close()
    }

    @Test
    fun `Abort answers the client itself and never dials the origin`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(origin.serverSocket.localPort, rule(BreakpointConfig(BreakpointPhase.BOTH), "pause-it", PAUSED_HOST))
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, PAUSED_HOST)

        send(ssl, PAUSED_HOST, "/drop", body = "x")
        awaitPaused(1)
        assertTrue(registry.abort(registry.pausedFlows.single().flowId, "operator dropped it"))

        val resp = readOneResponse(ssl.inputStream.bufferedReader())
        assertEquals(499, resp.first)
        assertEquals("rule=\"pause-it\"; action=aborted", resp.second[RuleMetadataHeaders.BREAKPOINT])
        assertTrue(resp.third, resp.third.contains("operator dropped it"))
        assertEquals("the lazy upstream must never have been dialled", 0, origin.acceptCount.get())
        awaitRows(sink, 1)
        val row = sink.rows.single()
        assertEquals(499, row.entity.statusCode)
        val bp = sink.appliedRules.single { it.trafficId == row.id && it.stage == RuleStage.BREAKPOINT.name && it.outcome == FlowAuditTrail.Outcome.APPLIED.name }
        assertTrue(bp.detail!!, bp.detail!!.endsWith("aborted (operator dropped it)"))
        ssl.close(); origin.serverSocket.close()
    }

    @Test
    fun `response-phase breakpoint composes with rewrite and throttle in one decision`() {
        val payload = "y".repeat(64 * 1024)
        val origin = startOrigin { payload }
        val sink = RecordingCaptureSink()
        val snap = snapshot(
            origin.serverSocket.localPort,
            rule(RewriteConfig(responseModifications = listOf(Modification.SetHeader("x-rewritten", "yes"))), "add-header", PAUSED_HOST),
            rule(BreakpointConfig(BreakpointPhase.RESPONSE), "pause-it", PAUSED_HOST),
            // 64 KiB at 1 Mbit/s ~= 0.52 s of pacing on the client leg.
            rule(ThrottleConfig(downlinkBps = 1_000_000), "slow-lane", PAUSED_HOST),
        )
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, PAUSED_HOST)

        send(ssl, PAUSED_HOST, "/big")
        awaitPaused(1)
        val flow = registry.pausedFlows.single()
        assertEquals(FlowAuditTrail.Phase.RESPONSE, flow.phase)
        assertEquals(200, flow.message.statusCode)
        assertEquals("yes", flow.message.headers["x-rewritten"]) // rewrite ran BEFORE the pause point
        assertEquals(1, origin.requests.size) // request leg (no request-phase pause) already went out
        val resumedAt = System.currentTimeMillis()
        assertTrue(registry.resume(flow.flowId))

        val resp = readOneResponse(ssl.inputStream.bufferedReader())
        val transferMs = System.currentTimeMillis() - resumedAt
        assertEquals(200, resp.first)
        assertEquals(payload.length, resp.third.length)
        assertEquals("yes", resp.second["x-rewritten"])
        assertEquals("rule=\"pause-it\"; action=resumed", resp.second[RuleMetadataHeaders.BREAKPOINT])
        assertTrue("throttle must pace the resumed response (${transferMs}ms)", transferMs >= 350)

        awaitRows(sink, 1)
        val row = sink.rows.single()
        val stages = sink.appliedRules.filter { it.trafficId == row.id && it.outcome == FlowAuditTrail.Outcome.APPLIED.name }.map { it.stage }.toSet()
        assertEquals(setOf(RuleStage.MIRROR.name, RuleStage.REWRITE.name, RuleStage.BREAKPOINT.name, RuleStage.THROTTLE.name), stages)
        ssl.close(); origin.serverSocket.close()
    }

    @Test
    fun `flag off -- the stage is inert and nothing is ever parked`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(origin.serverSocket.localPort, rule(BreakpointConfig(BreakpointPhase.BOTH), "pause-it", PAUSED_HOST))
        val proxyPort = startEngine(sink, snap)
        engine.breakpointsEnabled = false
        val ssl = openMitmTunnel(proxyPort, PAUSED_HOST)
        val t0 = System.currentTimeMillis()
        send(ssl, PAUSED_HOST, "/inert")
        val resp = readOneResponse(ssl.inputStream.bufferedReader())
        assertEquals(200, resp.first)
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        assertNull(resp.second[RuleMetadataHeaders.BREAKPOINT])
        assertEquals(0, registry.pausedCount)
        awaitRows(sink, 1)
        assertTrue(sink.appliedRules.none { it.stage == RuleStage.BREAKPOINT.name && it.outcome == FlowAuditTrail.Outcome.APPLIED.name })
        ssl.close(); origin.serverSocket.close()
    }

    @Test
    fun `closing the client while paused invalidates the flow instead of leaking a slot`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(origin.serverSocket.localPort, rule(BreakpointConfig(BreakpointPhase.RESPONSE, timeoutMs = 120_000), "pause-it", PAUSED_HOST))
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, PAUSED_HOST)
        send(ssl, PAUSED_HOST, "/vanish")
        awaitPaused(1)
        val flow = registry.pausedFlows.single()
        val t0 = System.currentTimeMillis()
        ssl.close()
        // The client leg sees EOF and invalidates this connection's parked flow -- long before
        // the 120 s auto-resume and without waiting for the relay drain deadline.
        val done = pool.submit<Boolean> {
            val deadline = System.currentTimeMillis() + 10_000
            while (registry.pausedCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            registry.pausedCount == 0
        }
        assertTrue(done.get(15, TimeUnit.SECONDS))
        assertTrue("invalidation took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 10_000)
        assertTrue(flow.isSettled)
        assertEquals(8, registry.availableSlots)
        origin.serverSocket.close()
    }
}
