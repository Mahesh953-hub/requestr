package com.example.interception.gateway

import android.os.Build
import com.example.interception.gateway.local.ClientOrigin
import com.example.interception.gateway.local.Destination
import com.example.interception.gateway.local.EntryKind
import com.example.interception.gateway.local.GatewayRejectReason
import com.example.interception.gateway.local.GatewaySession
import com.example.interception.gateway.local.Socks5Credentials
import com.example.interception.quic.QuicWireFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives a real [LocalGateway] over loopback sockets with a recording [GatewaySessionHandler]
 * and asserts every [com.example.interception.gateway.local.ProtocolClassifier] branch lands in
 * the right handler with the right [GatewaySession] -- plus the drop paths (unknown protocol,
 * slow-loris first byte, unsupported SOCKS command) are counted by reason, not silently closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LocalGatewayDispatchTest {

    private class RecordingHandler : GatewaySessionHandler {
        val tunnels = CopyOnWriteArrayList<GatewaySession>()
        val plaintexts = CopyOnWriteArrayList<GatewaySession>()
        /** First bytes the handler could read off the session socket (replay proof). */
        val firstBytes = CopyOnWriteArrayList<ByteArray>()
        val latch = CountDownLatch(1)

        override suspend fun handleTunnel(session: GatewaySession) {
            tunnels += session
            session.replyEstablished()
            captureAndClose(session)
        }

        override suspend fun handlePlaintext(session: GatewaySession) {
            plaintexts += session
            captureAndClose(session)
        }

        private fun captureAndClose(session: GatewaySession) {
            runCatching {
                session.socket.soTimeout = 2000
                val buf = ByteArray(256)
                val n = session.socket.getInputStream().read(buf)
                firstBytes += if (n > 0) buf.copyOf(n) else ByteArray(0)
            }.onFailure { firstBytes += ByteArray(0) }
            runCatching { session.socket.close() }
            latch.countDown()
        }
    }

    private lateinit var handler: RecordingHandler
    private lateinit var gateway: LocalGateway
    private val rejected = CopyOnWriteArrayList<Pair<GatewayRejectReason, String>>()
    private val rejectLatch = CountDownLatch(1)
    private var port = 0

    @Before
    fun setUp() {
        handler = RecordingHandler()
        gateway = LocalGateway(
            handler = handler,
            scope = CoroutineScope(Dispatchers.IO),
            socks5Credentials = Socks5Credentials("requestr", "pw"),
            firstByteTimeoutMs = 500,
            onRejected = { r, d -> rejected += r to d; rejectLatch.countDown() },
        )
        port = gateway.start(0)
        assertTrue(port > 0)
    }

    @After
    fun tearDown() {
        gateway.stop()
    }

    private fun connect(): Socket = Socket("127.0.0.1", port).also { it.soTimeout = 3000 }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun lp(s: String) = bytes(s.length) + s.toByteArray()

    private fun readExactly(s: Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.getInputStream().read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    private fun awaitHandler() = assertTrue("handler not invoked", handler.latch.await(5, TimeUnit.SECONDS))
    private fun awaitReject() = assertTrue("no rejection recorded", rejectLatch.await(5, TimeUnit.SECONDS))

    @Test
    fun `socks5 with plus-info lands in handleTunnel with origin and gets a success reply`() {
        connect().use { c ->
            val out = c.getOutputStream()
            out.write(bytes(0x05, 0x01, 0x02)); out.flush()
            assertArrayEquals(bytes(0x05, 0x02), readExactly(c, 2))
            out.write(bytes(0x01) + lp("requestr|tcp|10.8.0.2|43210") + lp("pw")); out.flush()
            assertArrayEquals(bytes(0x01, 0x00), readExactly(c, 2))
            out.write(bytes(0x05, 0x01, 0x00, 0x03) + lp("api.example.com") + bytes(0x01, 0xBB)); out.flush()
            val reply = readExactly(c, 10)
            assertEquals(0x00, reply[1].toInt())
            out.write("PAYLOAD".toByteArray()); out.flush()
            awaitHandler()
        }
        val session = handler.tunnels.single()
        assertEquals(EntryKind.SOCKS5, session.entry)
        assertEquals(Destination("api.example.com", 443), session.destination)
        assertEquals(ClientOrigin("10.8.0.2", 43210, "tcp"), session.origin)
        assertEquals("PAYLOAD", String(handler.firstBytes.single()))
        assertTrue(handler.plaintexts.isEmpty())
    }

    @Test
    fun `socks5 udp associate is refused with 0x07 and counted`() {
        connect().use { c ->
            val out = c.getOutputStream()
            out.write(bytes(0x05, 0x01, 0x02)); out.flush()
            readExactly(c, 2)
            out.write(bytes(0x01) + lp("requestr") + lp("pw")); out.flush()
            readExactly(c, 2)
            out.write(bytes(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            val reply = readExactly(c, 10)
            assertEquals(0x07, reply[1].toInt())
            awaitReject()
        }
        assertEquals(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, rejected.single().first)
        assertEquals(1L, gateway.rejectCounts[GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED])
        assertTrue(handler.tunnels.isEmpty())
    }

    @Test
    fun `socks5 with wrong password never reaches the handler`() {
        connect().use { c ->
            val out = c.getOutputStream()
            out.write(bytes(0x05, 0x01, 0x02)); out.flush()
            readExactly(c, 2)
            out.write(bytes(0x01) + lp("requestr") + lp("WRONG")); out.flush()
            assertArrayEquals(bytes(0x01, 0x01), readExactly(c, 2))
            awaitReject()
        }
        assertEquals(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, rejected.single().first)
        assertTrue(handler.tunnels.isEmpty())
    }

    @Test
    fun `socks4a connect lands in handleTunnel`() {
        connect().use { c ->
            c.getOutputStream().write(bytes(0x04, 0x01, 0x00, 0x50, 0, 0, 0, 1) + bytes(0) + "legacy.example".toByteArray() + bytes(0))
            c.getOutputStream().flush()
            val reply = readExactly(c, 8)
            assertEquals(0x5A, reply[1].toInt())
            awaitHandler()
        }
        val session = handler.tunnels.single()
        assertEquals(EntryKind.SOCKS4, session.entry)
        assertEquals(Destination("legacy.example", 80), session.destination)
    }

    @Test
    fun `http CONNECT lands in handleTunnel with the head consumed and payload replayed`() {
        connect().use { c ->
            c.getOutputStream().write("CONNECT secure.example:8443 HTTP/1.1\r\nHost: secure.example:8443\r\nProxy-Connection: keep-alive\r\n\r\n\u0016\u0003\u0001".toByteArray(Charsets.ISO_8859_1))
            c.getOutputStream().flush()
            val status = String(readExactly(c, "HTTP/1.1 200 Connection Established\r\n\r\n".length))
            assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", status)
            awaitHandler()
        }
        val session = handler.tunnels.single()
        assertEquals(EntryKind.HTTP_CONNECT, session.entry)
        assertEquals(Destination("secure.example", 8443), session.destination)
        assertEquals("secure.example:8443", session.rawTarget)
        assertNull(session.origin)
        assertArrayEquals(bytes(0x16, 0x03, 0x01), handler.firstBytes.single())
    }

    @Test
    fun `absolute-form GET is a plaintext session with the whole head replayed -- no 400`() {
        val head = "GET http://plain.example/path?q=1 HTTP/1.1\r\nHost: plain.example\r\n\r\n"
        connect().use { c ->
            c.getOutputStream().write(head.toByteArray()); c.getOutputStream().flush()
            awaitHandler()
        }
        val session = handler.plaintexts.single()
        assertEquals(EntryKind.HTTP_ABSOLUTE, session.entry)
        assertEquals(Destination("plain.example", 80), session.destination)
        assertEquals(head, String(handler.firstBytes.single()))
        assertTrue(handler.tunnels.isEmpty())
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `truncated first read is completed before classifying`() {
        val head = "GET http://slow.example/ HTTP/1.1\r\nHost: slow.example\r\n\r\n"
        connect().use { c ->
            c.getOutputStream().write("GE".toByteArray()); c.getOutputStream().flush()
            Thread.sleep(100)
            c.getOutputStream().write("T htt".toByteArray()); c.getOutputStream().flush()
            Thread.sleep(100)
            c.getOutputStream().write(head.substring(7).toByteArray()); c.getOutputStream().flush()
            awaitHandler()
        }
        val session = handler.plaintexts.single()
        assertEquals(EntryKind.HTTP_ABSOLUTE, session.entry)
        assertEquals(head, String(handler.firstBytes.single()))
    }

    @Test
    fun `origin-form GET with Host is a transparent plaintext session`() {
        connect().use { c ->
            c.getOutputStream().write("POST /submit HTTP/1.1\r\nHost: svc.internal:8081\r\nContent-Length: 0\r\n\r\n".toByteArray())
            c.getOutputStream().flush()
            awaitHandler()
        }
        val session = handler.plaintexts.single()
        assertEquals(EntryKind.TRANSPARENT_HTTP, session.entry)
        assertEquals(Destination("svc.internal", 8081), session.destination)
    }

    @Test
    fun `transparent TLS is routed by SNI with the ClientHello replayed intact`() {
        val hello = QuicWireFixtures.clientHello(ByteArray(32) { 0x42 }, "sni.example", "h2")
        val record = QuicWireFixtures.bytes(22, 0x03, 0x03, (hello.size shr 8) and 0xFF, hello.size and 0xFF) + hello
        connect().use { c ->
            c.getOutputStream().write(record); c.getOutputStream().flush()
            awaitHandler()
        }
        val session = handler.tunnels.single()
        assertEquals(EntryKind.TRANSPARENT_TLS, session.entry)
        assertEquals(Destination("sni.example", 443), session.destination)
        val seen = handler.firstBytes.single()
        assertArrayEquals(record.copyOf(seen.size), seen)
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun `transparent TLS without SNI uses the original-dst resolver when provided`() {
        gateway.stop()
        handler = RecordingHandler()
        gateway = LocalGateway(
            handler = handler,
            originalDstResolver = { Destination("10.1.2.3", 8443) },
            firstByteTimeoutMs = 500,
        )
        port = gateway.start(0)
        val hello = QuicWireFixtures.clientHello(ByteArray(32) { 0x42 }, "", "h2")
        val record = QuicWireFixtures.bytes(22, 0x03, 0x03, (hello.size shr 8) and 0xFF, hello.size and 0xFF) + hello
        connect().use { c ->
            c.getOutputStream().write(record); c.getOutputStream().flush()
            awaitHandler()
        }
        assertEquals(Destination("10.1.2.3", 8443), handler.tunnels.single().destination)
    }

    @Test
    fun `unknown protocol is dropped and counted`() {
        connect().use { c ->
            c.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray()); c.getOutputStream().flush()
            awaitReject()
            assertEquals(-1, c.getInputStream().read())
        }
        assertEquals(GatewayRejectReason.UNKNOWN_PROTOCOL, rejected.single().first)
        assertEquals(1L, gateway.rejectCounts[GatewayRejectReason.UNKNOWN_PROTOCOL])
        assertTrue(handler.tunnels.isEmpty() && handler.plaintexts.isEmpty())
    }

    @Test
    fun `slow-loris client that never sends a byte is dropped after the first-byte timeout`() {
        connect().use { c ->
            awaitReject()
            assertEquals(-1, c.getInputStream().read())
        }
        assertEquals(GatewayRejectReason.FIRST_BYTE_TIMEOUT, rejected.single().first)
    }

    @Test
    fun `malformed CONNECT target gets a 400 and is counted`() {
        connect().use { c ->
            c.getOutputStream().write("CONNECT example.com:abc HTTP/1.1\r\n\r\n".toByteArray()); c.getOutputStream().flush()
            awaitReject()
            val reply = String(c.getInputStream().readBytes())
            assertTrue(reply.startsWith("HTTP/1.1 400"))
        }
        assertEquals(GatewayRejectReason.HTTP_MALFORMED, rejected.single().first)
    }

    @Test
    fun `stop closes the listener`() {
        gateway.stop()
        val refused = runCatching { Socket("127.0.0.1", port).close() }.isFailure
        assertTrue(refused)
        assertNotNull(gateway.rejectCounts)
    }
}
