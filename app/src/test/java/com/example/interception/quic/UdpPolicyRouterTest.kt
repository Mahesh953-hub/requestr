package com.example.interception.quic

import com.example.interception.gateway.local.ClientOrigin
import com.example.interception.gateway.local.Destination
import com.example.interception.gateway.local.Socks5Handler
import com.example.interception.net.SocketProtector
import com.example.interception.uid.UidAttributor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G25 (D6): the three UDP/QUIC branches. Pure JVM: the attributor and package lookup are
 * injected; the relay half of the router is exercised against a loopback UDP echo "origin".
 */
class UdpPolicyRouterTest {

    private val origin = ClientOrigin("10.0.0.7", 51000, "udp")
    private val quicDst = InetSocketAddress("93.184.216.34", 443)
    private val resolved = UidAttributor.Attribution.Resolved(10123, UidAttributor.Source.BRIDGE_INFO)
    private val unattributed = UidAttributor.Attribution.Unattributed(listOf("no bridge origin"))

    private class Teed(val len: Int, val dst: InetSocketAddress, val outbound: Boolean, val decision: UdpPolicyRouter.Decision)

    private val teed = CopyOnWriteArrayList<Teed>()
    private val tee = UdpPolicyRouter.DatagramTee { p, off, len, _, dst, outbound, decision ->
        teed += Teed(len, dst, outbound, decision)
        check(p.size >= off + len)
    }

    private fun router(
        scope: UdpPolicyRouter.CaptureScope,
        observe: Boolean = false,
        attribution: UidAttributor.Attribution = resolved,
        packages: Map<Int, List<String>> = mapOf(10123 to listOf("com.example.app")),
    ) = UdpPolicyRouter(
        attributor = UidAttributor(
            ownerUidLookup = { _, _, _ -> (attribution as? UidAttributor.Attribution.Resolved)?.uid },
            procNetLookup = { _, _ -> null },
            ownerLookupSupported = true,
        ),
        scope = { scope },
        observeOverride = { observe },
        packagesForUid = { uid -> packages[uid].orEmpty() },
        tee = tee,
        protect = { SocketProtector.ProtectResult.Unavailable },
        idleTimeoutMs = 5_000,
    )

    private val selectedScope = UdpPolicyRouter.CaptureScope(allApps = false, selectedPackages = setOf("com.example.app"))

    // --- decide(): the pure policy ------------------------------------------------------------

    @Test
    fun `out of scope app passes untouched with no tee`() {
        val d = router(UdpPolicyRouter.CaptureScope(allApps = false, selectedPackages = setOf("other.app")))
            .decide(quicDst, resolved)
        assertEquals(UdpPolicyRouter.Action.PASS_UNTOUCHED, d.action)
        assertEquals(UdpPolicyRouter.Branch.OUT_OF_SCOPE, d.branch)
        assertFalse(d.teed)
        assertTrue(d.forwarded)
    }

    @Test
    fun `bypassed app passes untouched even when selected`() {
        val d = router(selectedScope.copy(bypassPackages = setOf("com.example.app"))).decide(quicDst, resolved)
        assertEquals(UdpPolicyRouter.Branch.BYPASSED, d.branch)
        assertEquals(UdpPolicyRouter.Action.PASS_UNTOUCHED, d.action)
    }

    @Test
    fun `unattributable datagram is treated as out of scope`() {
        val d = router(selectedScope, attribution = unattributed).decide(quicDst, unattributed)
        assertEquals(UdpPolicyRouter.Branch.UNATTRIBUTED, d.branch)
        assertEquals(UdpPolicyRouter.Action.PASS_UNTOUCHED, d.action)
        assertTrue(d.reason, d.reason.contains("no bridge origin"))
    }

    @Test
    fun `in scope default policy tees then drops UDP 443 with a blocked reason`() {
        val d = router(selectedScope).decide(quicDst, resolved)
        assertEquals(UdpPolicyRouter.Action.TEE_AND_DROP, d.action)
        assertEquals(UdpPolicyRouter.Branch.BLOCKED, d.branch)
        assertEquals(10123, d.uid)
        assertEquals("com.example.app", d.packageName)
        assertTrue(d.reason, d.reason.contains("falls back to HTTP/2"))
    }

    @Test
    fun `in scope with Observe QUIC on tees then passes`() {
        val d = router(selectedScope, observe = true).decide(quicDst, resolved)
        assertEquals(UdpPolicyRouter.Action.TEE_AND_PASS, d.action)
        assertEquals(UdpPolicyRouter.Branch.OBSERVED, d.branch)
        assertTrue(d.reason, d.reason.contains("decoded only if session keys"))
    }

    @Test
    fun `all apps mode does not require attribution to be in scope`() {
        val d = router(UdpPolicyRouter.CaptureScope(allApps = true), attribution = unattributed).decide(quicDst, unattributed)
        assertEquals(UdpPolicyRouter.Action.TEE_AND_DROP, d.action)
        assertEquals(UdpPolicyRouter.Branch.BLOCKED, d.branch)
    }

    @Test
    fun `DNS 53 is exempt in every mode and non 443 UDP is forwarded untouched`() {
        for (observe in listOf(false, true)) {
            for (scope in listOf(selectedScope, UdpPolicyRouter.CaptureScope(allApps = true))) {
                val dns = router(scope, observe).decide(InetSocketAddress("198.18.0.2", 53), resolved)
                assertEquals(UdpPolicyRouter.Branch.DNS, dns.branch)
                assertEquals(UdpPolicyRouter.Action.PASS_UNTOUCHED, dns.action)
                val ntp = router(scope, observe).decide(InetSocketAddress("93.184.216.34", 123), resolved)
                assertEquals(UdpPolicyRouter.Branch.NON_QUIC, ntp.branch)
                assertEquals(UdpPolicyRouter.Action.PASS_UNTOUCHED, ntp.action)
            }
        }
    }

    // --- header codec ---------------------------------------------------------------------------

    @Test
    fun `socks5 udp header parses v4 v6 and domain and rejects fragments`() {
        val v4 = byteArrayOf(0, 0, 0, 1, 10, 0, 0, 1, 0x01, 0xBB.toByte(), 9, 9)
        val p = UdpPolicyRouter.parseHeader(v4, v4.size)!!
        assertEquals(InetSocketAddress("10.0.0.1", 443), p.destination)
        assertEquals(10, p.headerLength)

        val v6 = byteArrayOf(0, 0, 0, 4) + InetAddress.getByName("fd00::1").address + byteArrayOf(0, 53)
        val p6 = UdpPolicyRouter.parseHeader(v6, v6.size)!!
        assertEquals(53, p6.destination.port)
        assertEquals(22, p6.headerLength)

        val name = "localhost".toByteArray()
        val dom = byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf(0x1F, 0x90.toByte()) + byteArrayOf(1, 2, 3)
        val pd = UdpPolicyRouter.parseHeader(dom, dom.size)!!
        assertEquals(8080, pd.destination.port)
        assertTrue(pd.destination.address.isLoopbackAddress)
        assertEquals(dom.size - 3, pd.headerLength)

        val frag = byteArrayOf(0, 0, 1, 1, 10, 0, 0, 1, 0x01, 0xBB.toByte(), 9)
        assertNull(UdpPolicyRouter.parseHeader(frag, frag.size))
        assertNull(UdpPolicyRouter.parseHeader(v4, 5))

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, 0x00, 0x35),
            UdpPolicyRouter.encodeHeader(InetSocketAddress("127.0.0.1", 53)),
        )
    }

    // --- relay: the three branches end to end over loopback -------------------------------------

    private val jobs = ArrayList<Job>()
    private val closeables = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        closeables.forEach { runCatching { it.close() } }
    }

    private class Echo(val socket: DatagramSocket, val received: CopyOnWriteArrayList<ByteArray>) {
        val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)
    }

    private fun echoServer(): Echo {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { closeables += it }
        val received = CopyOnWriteArrayList<ByteArray>()
        Thread {
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: Exception) { break }
                received += p.data.copyOfRange(p.offset, p.offset + p.length)
                runCatching { socket.send(DatagramPacket("echo:".toByteArray() + received.last(), 5 + p.length, p.socketAddress)) }
            }
        }.apply { isDaemon = true; start() }
        return Echo(socket, received)
    }

    private class Assoc(val control: Socket, val relayAddress: InetSocketAddress, val clientUdp: DatagramSocket)

    /** Runs the router's association handler over a real loopback control socket and reads its reply. */
    private fun associate(router: UdpPolicyRouter): Assoc {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { closeables += it }
        val client = Socket(server.inetAddress, server.localPort).also { closeables += it }
        val accepted = server.accept().also { closeables += it }
        jobs += CoroutineScope(Dispatchers.IO).launch {
            router.handleUdpAssociate(accepted, Destination("0.0.0.0", 0), origin)
        }
        client.soTimeout = 5_000
        val reply = ByteArray(10)
        java.io.DataInputStream(client.getInputStream()).readFully(reply)
        assertEquals(Socks5Handler.VERSION, reply[0].toInt())
        assertEquals(Socks5Handler.REP_SUCCEEDED, reply[1].toInt())
        assertEquals(Socks5Handler.ATYP_IPV4, reply[3].toInt())
        val port = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
        assertTrue(port > 0)
        val udp = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { closeables += it }
        udp.soTimeout = 1_500
        return Assoc(client, InetSocketAddress(InetAddress.getLoopbackAddress(), port), udp)
    }

    private fun sendVia(a: Assoc, dst: InetSocketAddress, payload: ByteArray) {
        val frame = UdpPolicyRouter.encodeHeader(dst) + payload
        a.clientUdp.send(DatagramPacket(frame, frame.size, a.relayAddress))
    }

    private fun receiveVia(a: Assoc): Pair<InetSocketAddress, ByteArray>? {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        return try {
            a.clientUdp.receive(p)
            val parsed = UdpPolicyRouter.parseHeader(buf, p.length)!!
            parsed.destination to buf.copyOfRange(parsed.headerLength, p.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** The echo origin cannot bind 443 on loopback, so its ephemeral port is declared a QUIC port. */
    private fun quicRouter(scope: UdpPolicyRouter.CaptureScope, observe: Boolean, echoPort: Int): UdpPolicyRouter =
        UdpPolicyRouter(
            attributor = UidAttributor(
                ownerUidLookup = { _, _, _ -> resolved.uid },
                procNetLookup = { _, _ -> null },
                ownerLookupSupported = true,
            ),
            scope = { scope },
            observeOverride = { observe },
            packagesForUid = { uid -> if (uid == 10123) listOf("com.example.app") else emptyList() },
            tee = tee,
            protect = { SocketProtector.ProtectResult.Unavailable },
            idleTimeoutMs = 5_000,
            quicPorts = setOf(UdpPolicyRouter.QUIC_PORT, echoPort),
        )

    @Test
    fun `G25 out of scope UDP is relayed byte for byte with no tee and no ledger row`() = runBlocking {
        val echo = echoServer()
        val router = quicRouter(UdpPolicyRouter.CaptureScope(allApps = false, selectedPackages = setOf("other.app")), false, echo.address.port)
        val a = associate(router)
        val payload = byteArrayOf(0xC0.toByte(), 0, 0, 0, 1, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0x42)
        sendVia(a, echo.address, payload)
        val reply = withTimeout(3_000) { receiveVia(a) }
        assertNotNull("upstream reply must be relayed back", reply)
        assertArrayEquals(payload, echo.received.single())
        assertEquals(echo.address, reply!!.first)
        assertArrayEquals("echo:".toByteArray() + payload, reply.second)
        assertTrue("out-of-scope traffic must not be teed", teed.isEmpty())
        assertTrue("out-of-scope traffic must not produce a row", router.recent().isEmpty())
        assertEquals(1L, router.branchCounts()[UdpPolicyRouter.Branch.OUT_OF_SCOPE])
        a.control.close()
    }

    @Test
    fun `G25 in scope default policy tees the datagram and drops it so nothing reaches the origin`() = runBlocking {
        val echo = echoServer()
        val router = quicRouter(selectedScope, false, echo.address.port)
        val a = associate(router)
        val payload = ByteArray(1200) { it.toByte() }
        sendVia(a, echo.address, payload)
        sendVia(a, echo.address, payload)
        assertNull("blocked flow must get no reply", receiveVia(a))
        assertTrue("origin must never see a blocked datagram", echo.received.isEmpty())
        assertEquals(2, teed.size)
        assertTrue(teed.all { it.outbound && it.len == 1200 && it.decision.action == UdpPolicyRouter.Action.TEE_AND_DROP })
        val row = router.recent().single()
        assertEquals(UdpPolicyRouter.Branch.BLOCKED, row.decision.branch)
        assertEquals(2L, row.datagrams)
        assertEquals(2400L, row.bytes)
        assertEquals(origin, row.origin)
        a.control.close()
    }

    @Test
    fun `G25 Observe QUIC tees then passes and inbound replies are teed too`() = runBlocking {
        val echo = echoServer()
        val router = quicRouter(selectedScope, true, echo.address.port)
        val a = associate(router)
        val payload = "hello quic".toByteArray()
        sendVia(a, echo.address, payload)
        val reply = withTimeout(3_000) { receiveVia(a) }
        assertNotNull(reply)
        assertArrayEquals("echo:hello quic".toByteArray(), reply!!.second)
        assertEquals(1, echo.received.size)
        assertEquals(2, teed.size)
        assertTrue(teed[0].outbound)
        assertFalse(teed[1].outbound)
        assertTrue(teed.all { it.decision.action == UdpPolicyRouter.Action.TEE_AND_PASS })
        assertEquals(UdpPolicyRouter.Branch.OBSERVED, router.recent().single().decision.branch)
        a.control.close()
    }

    @Test
    fun `closing the control connection ends the association`() = runBlocking {
        val echo = echoServer()
        val router = quicRouter(UdpPolicyRouter.CaptureScope(allApps = true), true, echo.address.port)
        val a = associate(router)
        a.control.close()
        val job = jobs.last()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCompleted)
    }

    @Test
    fun `an upstream socket that cannot be protected refuses the association`() = runBlocking {
        val router = UdpPolicyRouter(
            attributor = UidAttributor(ownerLookupSupported = true),
            scope = { UdpPolicyRouter.CaptureScope(allApps = true) },
            observeOverride = { false },
            packagesForUid = { emptyList() },
            protect = { SocketProtector.ProtectResult.Failed },
        )
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { closeables += it }
        val client = Socket(server.inetAddress, server.localPort).also { closeables += it }
        val accepted = server.accept()
        router.handleUdpAssociate(accepted, Destination("0.0.0.0", 0), origin)
        val reply = ByteArray(10)
        java.io.DataInputStream(client.getInputStream()).readFully(reply)
        assertEquals(Socks5Handler.REP_GENERAL_FAILURE, reply[1].toInt())
        assertEquals(-1, client.getInputStream().read())
    }
}
