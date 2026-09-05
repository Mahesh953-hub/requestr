package com.example.interception

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

/**
 * Robolectric-backed: starts a real [HttpProxyEngine], drives a real CONNECT request through a
 * real [Socket], and asserts the response + persisted [com.example.data.local.ConnectAttemptEntity]
 * both reflect [ConnectControl]'s decision -- proving the engine's CONNECT branch is actually
 * wired to ConnectControl end-to-end, not just compiling against it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class HttpProxyEngineConnectTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var engine: HttpProxyEngine

    @Before
    fun setUp() {
        engine = HttpProxyEngine()
    }

    @After
    fun tearDown() {
        com.example.interception.rules.LegacyRuleTestSupport.clear()
        // HttpProxyEngine resolves RequestrDatabase.get(context) itself (the real production
        // singleton, not a test-owned instance), so tearDown must not close it here -- Room's
        // close() cancels the database's internal coroutine scope, which can race with the
        // engine's own still-in-flight background insert coroutine and surface a spurious
        // JobCancellationException in this test rather than in production. Stopping the engine
        // is sufficient cleanup; each test asserts against a unique targetHost so residual rows
        // from earlier tests in the same process cannot cause a false match.
        engine.stop()
    }

    private fun sendConnectAndReadStatusLine(port: Int, target: String): String {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        socket.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        val statusLine = StringBuilder()
        val input = socket.getInputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\r'.code) break
            statusLine.append(b.toChar())
        }
        socket.close()
        return statusLine.toString()
    }

    /** Polls up to 5s for a persisted [com.example.data.local.ConnectAttemptEntity] to appear --
     * the engine's persist runs on a background coroutine after the relay finishes, so a fixed
     * sleep is either flaky (too short) or slow (too long, always). */
    private fun pollForConnectAttempt(port: Int, host: String) = runBlocking {
        val deadline = System.currentTimeMillis() + 5000
        var match: com.example.data.local.ConnectAttemptEntity? = null
        while (match == null && System.currentTimeMillis() < deadline) {
            match = RequestrDatabase.get(context).connectAttemptDao().observeAll().first()
                .firstOrNull { it.targetHost == host && it.targetPort == port }
            if (match == null) kotlinx.coroutines.delay(100)
        }
        match
    }

    @Test
    fun `CONNECT with no matching rule is allowed opaque and persisted as ALLOWED_OPAQUE`() {
        // Dial a real local upstream instead of the network -- AllowOpaque's relay() opens a
        // genuine Socket(host, port), which must not depend on outbound network access in a
        // sandboxed test run.
        val upstream = java.net.ServerSocket(0)
        val upstreamPort = upstream.localPort
        val upstreamThread = Thread { runCatching { upstream.accept() }.getOrNull()?.close() }
        upstreamThread.start()

        val port = engine.start(context, 0)
        val statusLine = sendConnectAndReadStatusLine(port, "127.0.0.1:$upstreamPort")
        assertTrue(statusLine.contains("200"))

        upstreamThread.join(5000)
        val match = pollForConnectAttempt(port = upstreamPort, host = "127.0.0.1")
        upstream.close()
        assertEquals("ALLOWED_OPAQUE", match?.disposition)
    }

    @Test
    fun `CONNECT to a BLOCK-ruled host is refused and persisted as BLOCKED`() {
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "test", domain = "blocked.example", action = RuleAction.BLOCK)
            )
            com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
        }
        val port = engine.start(context, 0)
        val statusLine = sendConnectAndReadStatusLine(port, "blocked.example:443")
        assertTrue(statusLine.contains("403"))

        val match = pollForConnectAttempt(port = 443, host = "blocked.example")
        assertEquals("BLOCKED", match?.disposition)
    }

    @Test
    fun `CONNECT to a FORCE_MITM-ruled host is honestly refused, not silently opaque`() {
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "test", domain = "mitm.example", action = RuleAction.FORCE_MITM)
            )
            com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
        }
        val port = engine.start(context, 0)
        val statusLine = sendConnectAndReadStatusLine(port, "mitm.example:443")
        assertTrue(statusLine.contains("403"))

        val match = pollForConnectAttempt(port = 443, host = "mitm.example")
        assertEquals("BLOCKED", match?.disposition)
        assertEquals("MITM not available in HTTP_PROXY tier", match?.failureReason)
    }

    private fun readExactly(socket: Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = socket.getInputStream().read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    /** Minimal one-shot HTTP/1.1 origin server that echoes the request line back in its body. */
    private fun startEchoUpstream(): Pair<java.net.ServerSocket, Thread> {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    s.soTimeout = 5000
                    val requestLine = HttpProxyShared.readLine(s.getInputStream()) ?: return@use
                    while (true) {
                        val line = HttpProxyShared.readLine(s.getInputStream()) ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "echo: $requestLine".toByteArray()
                    s.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body,
                    )
                    s.getOutputStream().flush()
                }
            }
        }
        thread.start()
        return server to thread
    }

    @Test
    fun `absolute-form GET is forwarded through the gateway instead of answered with 400`() {
        val (upstream, upstreamThread) = startEchoUpstream()
        val port = engine.start(context, 0)
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        socket.getOutputStream().write(
            "GET http://127.0.0.1:${upstream.localPort}/hello?x=1 HTTP/1.1\r\nHost: 127.0.0.1:${upstream.localPort}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        val response = String(socket.getInputStream().readBytes())
        socket.close()
        upstreamThread.join(5000)
        upstream.close()

        assertTrue("expected 200 from upstream, got: $response", response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("echo: GET /hello?x=1 HTTP/1.1"))
    }

    @Test
    fun `SOCKS5 CONNECT to a BLOCK-ruled host is refused with reply 0x02 and persisted as BLOCKED`() {
        runBlocking {
            RequestrDatabase.get(context).interceptRuleDao().insert(
                InterceptRuleEntity(name = "test", domain = "socks-blocked.example", action = RuleAction.BLOCK)
            )
            com.example.interception.rules.LegacyRuleTestSupport.syncSnapshotFromLegacy(RequestrDatabase.get(context))
        }
        val port = engine.start(context, 0)
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val method = readExactly(socket, 2)
        assertEquals(0x00, method[1].toInt())
        val host = "socks-blocked.example".toByteArray()
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host + byteArrayOf(0x01, 0xBB.toByte()))
        out.flush()
        val reply = readExactly(socket, 10)
        socket.close()
        assertEquals(10, reply.size)
        assertEquals(0x05, reply[0].toInt())
        assertEquals("SOCKS5 REP must be 0x02 (not allowed by ruleset)", 0x02, reply[1].toInt())

        val match = pollForConnectAttempt(port = 443, host = "socks-blocked.example")
        assertEquals("BLOCKED", match?.disposition)
    }
}
