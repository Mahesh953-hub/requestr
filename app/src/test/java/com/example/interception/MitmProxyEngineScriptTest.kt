package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptorRuleEntity
import com.example.data.persistence.RecordingCaptureSink
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.GatewayConfig
import com.example.interception.rules.GatewayMode
import com.example.interception.rules.InterceptorRule
import com.example.interception.rules.MirrorConfig
import com.example.interception.rules.RuleConfig
import com.example.interception.rules.RuleMatcherSpec
import com.example.interception.rules.RuleMetadataHeaders
import com.example.interception.rules.RuleSnapshot
import com.example.interception.rules.RuleSnapshotCompiler
import com.example.interception.rules.RuleStage
import com.example.interception.rules.ScriptConfig
import com.example.interception.rules.stages.ScriptStage
import com.example.scripting.host.ScriptContract
import com.example.scripting.host.ScriptHostClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * S4 task 32 / gate G20, end to end through a real MITM tunnel: SCRIPT rules run through the
 * host client. A mutating lifecycle script is byte-correct on both legs and threads
 * `context.shared` from `onRequest` into `onResponse`; a script whose host hangs is cut off at
 * the deadline, the flow proceeds unmodified with the error badge, and an unrelated concurrent
 * flow is never delayed; an aborting hook answers the client with the synthetic 499.
 *
 * The host transport is a fake that executes envelopes in-process (exactly what
 * `ScriptHostService` does on the other side of the binder) and can be told to hang.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MitmProxyEngineScriptTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: MitmProxyEngine
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

    /** In-process "remote" host: runs envelopes like the service does; hangs for scripts whose id is in [hangFor]. */
    private class FakeHostTransport : ScriptHostClient.Transport {
        val hangFor = CopyOnWriteArrayList<String>()
        val kills = AtomicInteger(0)
        val calls = AtomicInteger(0)
        private val never = CountDownLatch(1)
        override fun call(requestJson: String, deadlineMs: Long): String? {
            calls.incrementAndGet()
            val request = ScriptContract.decodeRequest(requestJson)
            if (request.scriptId in hangFor) {
                never.await(deadlineMs, TimeUnit.MILLISECONDS)
                return null
            }
            return ScriptContract.encodeResponse(ScriptContract.execute(request, pid = 777))
        }
        override fun kill(reason: String) { kills.incrementAndGet() }
    }

    private val transport = FakeHostTransport()
    private val client = ScriptHostClient(transport)

    companion object {
        private const val SCRIPTED_HOST = "scripted.example"
        private const val FREE_HOST = "free.example"
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
        pool.shutdownNow()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
        ScriptStage.runner = ScriptStage.ScriptRunner { ScriptStage.runInProcess(it) }
    }

    private var nextId = 1L

    private fun rule(config: RuleConfig, name: String, vararg hosts: String) = InterceptorRule(
        id = nextId++, name = name, stage = config.stage, matcher = RuleMatcherSpec(hostGlobs = hosts.toList()), config = config,
    )

    private fun snapshot(originPort: Int, vararg extra: InterceptorRule): RuleSnapshot = RuleSnapshotCompiler.compile(
        (listOf(
            rule(GatewayConfig(GatewayMode.FORCE_DECRYPT), "decrypt", SCRIPTED_HOST, FREE_HOST),
            rule(MirrorConfig("localhost", originPort), "to-origin", SCRIPTED_HOST, FREE_HOST),
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
                                origin.requests += "$path|host=${headers["host"]}|edited=${headers["x-edited"]}|se=${headers[RuleMetadataHeaders.SCRIPT_ERROR]}|len=$len|body=${String(body, 0, read)}"
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

    private fun awaitRows(sink: RecordingCaptureSink, n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.rows.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(n, sink.rows.size)
    }

    private fun startEngine(sink: RecordingCaptureSink, snap: RuleSnapshot): Int {
        engine = MitmProxyEngine(sink = sink, caTrustGate = CaTrustGate(probe = { true }), ruleSnapshot = { snap }, scriptHostClient = { client })
        return engine.start(context, 0)
    }

    private fun roundTrip(ssl: SSLSocket, host: String, path: String, body: String = ""): Triple<Int, Map<String, String>, String> {
        send(ssl, host, path, body)
        return readOneResponse(ssl.inputStream.bufferedReader())
    }

    @Test
    fun `a mutating lifecycle script is byte-correct on both legs and shared context survives request to response`() {
        val origin = startOrigin { path -> "origin:$path" }
        val sink = RecordingCaptureSink()
        val script = """
            function onRequest(context, request) {
              request.headers.set('x-edited', 'yes');
              request.body = request.body + '+script';
              context.shared.reqPath = request.url;
            }
            function onResponse(context, response) {
              response.headers.set('x-from-request', context.shared.reqPath);
              response.body = response.body.toUpperCase();
            }
        """.trimIndent()
        val snap = snapshot(origin.serverSocket.localPort, rule(ScriptConfig(script), "mutate", SCRIPTED_HOST))
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, SCRIPTED_HOST)

        val (status, headers, body) = roundTrip(ssl, SCRIPTED_HOST, "/things", body = "hello")
        assertEquals(200, status)
        assertEquals("ORIGIN:/THINGS", body)
        assertEquals("https://$SCRIPTED_HOST/things", headers["x-from-request"])
        assertEquals(body.length.toString(), headers["content-length"])
        assertNull(headers[RuleMetadataHeaders.SCRIPT_ERROR])

        val seen = origin.requests.single()
        assertTrue(seen, seen.contains("edited=yes"))
        assertTrue(seen, seen.contains("len=12|body=hello+script"))
        assertEquals(2, transport.calls.get())
        assertEquals(0, transport.kills.get())

        awaitRows(sink, 1)
        val row = sink.rows.single()
        val stages = sink.appliedRules.filter { it.trafficId == row.id && it.ruleName == "mutate" }
        assertEquals(setOf("REQUEST", "RESPONSE"), stages.map { it.phase }.toSet())
        assertTrue(stages.all { it.outcome == "APPLIED" })
    }

    @Test
    fun `a hanging script host is cut off at the deadline, the flow proceeds unmodified and a concurrent flow is not delayed`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        transport.hangFor += "hangs"
        val snap = snapshot(
            origin.serverSocket.localPort,
            rule(ScriptConfig("pm.request.headers.set('x-edited', 'yes');", timeoutMs = 1_500L), "hangs", SCRIPTED_HOST),
        )
        val proxyPort = startEngine(sink, snap)
        val slowSsl = openMitmTunnel(proxyPort, SCRIPTED_HOST)
        val freeSsl = openMitmTunnel(proxyPort, FREE_HOST)

        val t0 = System.currentTimeMillis()
        val slow: Future<Triple<Int, Map<String, String>, String>> = pool.submit<Triple<Int, Map<String, String>, String>> { roundTrip(slowSsl, SCRIPTED_HOST, "/slow", "a") }
        Thread.sleep(200) // the slow flow is inside the hanging host call now
        val free = roundTrip(freeSsl, FREE_HOST, "/free", "b")
        val freeMs = System.currentTimeMillis() - t0
        assertEquals(200, free.first)
        assertEquals("origin:/free", free.third)
        assertTrue("free flow took ${freeMs}ms while another script hung", freeMs < 1_400)

        val (status, _, body) = slow.get(15, TimeUnit.SECONDS)
        val slowMs = System.currentTimeMillis() - t0
        assertEquals(200, status)
        assertEquals("origin:/slow", body)
        assertTrue("slow flow released at ${slowMs}ms", slowMs >= 1_400 && slowMs < 10_000)
        // The rule's script hung on the request leg AND on the response leg: one kill each,
        // both cut at the deadline, and the flow still completed with the origin's bytes.
        assertEquals(2, transport.kills.get())
        assertTrue("slow flow ${slowMs}ms must reflect two deadlines", slowMs >= 2_800)
        // The origin saw the request unmodified: the script never got to run.
        val seen = origin.requests.single { it.startsWith("/slow") }
        assertTrue(seen, seen.contains("edited=null"))
        assertTrue(seen, seen.contains("se=null"))

        awaitRows(sink, 2)
        val slowRow = sink.rows.single { it.entity.url.endsWith("/slow") }
        assertTrue(slowRow.entity.requestHeaders[RuleMetadataHeaders.SCRIPT_ERROR]!!.contains("deadline"))
        val failed = sink.appliedRules.filter { it.trafficId == slowRow.id && it.ruleName == "hangs" }
        assertEquals(setOf("REQUEST", "RESPONSE"), failed.map { it.phase }.toSet())
        assertTrue(failed.all { it.outcome == "FAILED" })
    }

    @Test
    fun `an aborting onRequest hook answers 499 without dialing the origin`() {
        val origin = startOrigin()
        val sink = RecordingCaptureSink()
        val snap = snapshot(origin.serverSocket.localPort, rule(ScriptConfig("function onRequest(c, r) { if (r.url.indexOf('/drop') >= 0) return null; }"), "dropper", SCRIPTED_HOST))
        val proxyPort = startEngine(sink, snap)
        val ssl = openMitmTunnel(proxyPort, SCRIPTED_HOST)
        val (status, headers, body) = roundTrip(ssl, SCRIPTED_HOST, "/drop", "x")
        assertEquals(ScriptStage.ABORT_STATUS, status)
        assertTrue(headers[RuleMetadataHeaders.SCRIPT_ERROR]!!.contains("rule=\"dropper\""))
        assertTrue(body.contains("aborted by script"))
        Thread.sleep(200)
        assertEquals(0, origin.acceptCount.get())
        awaitRows(sink, 1)
        assertEquals(ScriptStage.ABORT_STATUS, sink.rows.single().entity.statusCode)
    }
}
