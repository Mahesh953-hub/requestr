package com.example.interception.tun

import com.example.interception.tcp.TcpHandshakePhase
import com.example.interception.tcp.TcpReassembler
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Drives [TunRelay] end-to-end against real loopback sockets: a [ServerSocket]/[Socket] stand-in
 * for "the real destination" the relay opens on SYN, a [DatagramSocket] stand-in for UDP, a
 * [ByteArrayOutputStream]-backed fake [TunSink] instead of a real tun fd, and [NoOpProtectHook]
 * since there is no real `VpnService` in a JVM unit test.
 *
 * Replies emitted into the fake sink are parsed back through [TcpReassembler.feed] — the
 * project's own parser is the strongest available oracle for "is this a valid datagram" per the
 * plan's testing note for task 2.
 */
class TunRelayTest {

    private val runningRelays = mutableListOf<Pair<TunRelay, Job>>()

    @After
    fun tearDown() {
        runningRelays.forEach { (relay, job) ->
            relay.stop()
            job.cancel()
        }
        runningRelays.clear()
    }

    private fun startRelay(): Pair<TunRelay, ByteArrayCapturingSink> {
        val sink = ByteArrayCapturingSink()
        val relay = TunRelay(NoOpProtectHook, TunWriter(sink))
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch { relay.run(scope) }
        runningRelays.add(relay to job)
        return relay to sink
    }

    /** Collects every datagram written back into "tun" so tests can inspect replies. */
    private class ByteArrayCapturingSink : TunSink {
        val datagrams = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        override fun write(datagram: ByteArray) {
            datagrams.add(datagram)
        }
    }

    private fun awaitAtLeast(sink: ByteArrayCapturingSink, count: Int, timeoutMs: Long = 3000L): List<ByteArray> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sink.datagrams.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        return sink.datagrams.toList()
    }

    // ── IPv4 TCP ─────────────────────────────────────────────────────────

    @Test
    fun `synthetic SYN produces a parseable SYN-ACK and payload reaches the loopback server`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val (relay, sink) = startRelay()

            val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
            val serverIp = byteArrayOf(127, 0, 0, 1)
            val guestPort = 51000
            val serverPort = server.localPort

            val syn = IpPacketBuilder.buildIpv4Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 1000L, ack = 0L, flags = TcpReassembler.FLAG_SYN,
            )
            relay.offer(syn, syn.size, 4)

            // The relay must accept the loopback connection on its own upstream socket.
            val accepted = server.accept()
            accepted.soTimeout = 3000

            val replies = awaitAtLeast(sink, 1)
            assertTrue("expected at least a SYN-ACK", replies.isNotEmpty())

            val reassembler = TcpReassembler()
            val synAckStream = reassembler.feed(replies[0], replies[0].size, 4)
            assertNotNull(synAckStream)
            // Reassembler direction is keyed off whichever side it saw first; from the guest's
            // perspective this segment is server(127.0.0.1:serverPort) -> guest(10.111.222.5:51000).
            assertEquals("127.0.0.1", synAckStream!!.srcIp)
            assertEquals(serverPort, synAckStream.srcPort)
            assertEquals("10.111.222.5", synAckStream.dstIp)
            assertEquals(guestPort, synAckStream.dstPort)
            assertTrue(
                synAckStream.handshakePhase == TcpHandshakePhase.SYN_ACK_SEEN ||
                    synAckStream.handshakePhase == TcpHandshakePhase.ESTABLISHED,
            )

            // Now send a data segment from the guest and confirm it reaches the loopback server.
            val payload = "hello-upstream".toByteArray()
            val dataSeg = IpPacketBuilder.buildIpv4Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 1001L, ack = 1L, flags = TcpReassembler.FLAG_ACK,
                payload = payload,
            )
            relay.offer(dataSeg, dataSeg.size, 4)

            val received = ByteArray(payload.size)
            accepted.getInputStream().read(received)
            assertArrayEquals(payload, received)

            // And confirm a reply from the "server" is synthesized back into tun with correct
            // seq/ack (ack must equal 1001 + payload.size; the earlier SYN-ACK already consumed
            // sequence number 0, so the data-carrying reply's seq continues from there).
            accepted.getOutputStream().write("hi-guest".toByteArray())
            accepted.getOutputStream().flush()

            val downReplies = awaitAtLeast(sink, 2)
            val dataReply = downReplies.last()
            val stream2 = TcpReassembler().feed(dataReply, dataReply.size, 4)
            assertNotNull(stream2)
            assertEquals("hi-guest", extractTcpPayloadAscii(dataReply))
        } finally {
            server.close()
        }
    }

    @Test
    fun `flow-table eviction respects the MAX_FLOWS bound`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val (relay, _) = startRelay()
            val serverIp = byteArrayOf(127, 0, 0, 1)
            val serverPort = server.localPort

            // Open more than MAX_FLOWS distinct TCP flows (differing source port each time).
            val extra = 5
            for (i in 0 until TunRelay.MAX_FLOWS + extra) {
                val guestPort = 20000 + i
                val syn = IpPacketBuilder.buildIpv4Tcp(
                    srcIp = byteArrayOf(10, 111, 222.toByte(), 5), srcPort = guestPort,
                    dstIp = serverIp, dstPort = serverPort,
                    seq = 1L, ack = 0L, flags = TcpReassembler.FLAG_SYN,
                )
                relay.offer(syn, syn.size, 4)
                // Drain any pending accepts so upstream connect attempts don't back up the
                // ServerSocket's backlog and stall subsequent SYNs.
                server.accept().close()
            }

            val stats = relay.stats.value
            assertTrue(
                "flow count ${stats.activeTcpV4} should never exceed MAX_FLOWS=${TunRelay.MAX_FLOWS}",
                stats.activeTcpV4 <= TunRelay.MAX_FLOWS,
            )
        } finally {
            server.close()
        }
    }

    // ── IPv4 UDP ─────────────────────────────────────────────────────────

    @Test
    fun `UDP round-trips against a local DatagramSocket`() {
        val serverSocket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        try {
            val (relay, sink) = startRelay()

            val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
            val serverIp = byteArrayOf(127, 0, 0, 1)
            val guestPort = 40000
            val serverPort = serverSocket.localPort

            val payload = "dns-ish-query".toByteArray()
            val datagram = IpPacketBuilder.buildIpv4Udp(guestIp, guestPort, serverIp, serverPort, payload)
            relay.offer(datagram, datagram.size, 4)

            val recvBuf = ByteArray(1500)
            val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
            serverSocket.soTimeout = 3000
            serverSocket.receive(recvPacket)
            assertArrayEquals(payload, recvPacket.data.copyOfRange(0, recvPacket.length))

            // Reply from "server" back through the relay into the fake tun sink.
            val replyPayload = "dns-ish-reply".toByteArray()
            serverSocket.send(java.net.DatagramPacket(replyPayload, replyPayload.size, recvPacket.socketAddress))

            val replies = awaitAtLeast(sink, 1)
            assertTrue(replies.isNotEmpty())
            assertEquals("dns-ish-reply", extractUdpPayloadAscii(replies.last(), 4))
        } finally {
            serverSocket.close()
        }
    }

    // ── IPv6 (over loopback ::1) ───────────────────────────────────────

    @Test
    fun `IPv6 TCP SYN-ACK, payload delivery and reply round-trip over loopback colon-colon-1`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("::1"))
        try {
            val (relay, sink) = startRelay()

            val guestIp = ByteArray(16).also { it[15] = 5 } // ::5
            val serverIp = ByteArray(16).also { it[15] = 1 } // ::1
            val guestPort = 51001
            val serverPort = server.localPort

            val syn = IpPacketBuilder.buildIpv6Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 5000L, ack = 0L, flags = TcpReassembler.FLAG_SYN,
            )
            relay.offer(syn, syn.size, 6)

            val accepted = server.accept()
            accepted.soTimeout = 3000

            val replies = awaitAtLeast(sink, 1)
            assertTrue(replies.isNotEmpty())
            val stream = TcpReassembler().feed(replies[0], replies[0].size, 6)
            assertNotNull(stream)
            assertTrue(
                stream!!.handshakePhase == TcpHandshakePhase.SYN_ACK_SEEN ||
                    stream.handshakePhase == TcpHandshakePhase.ESTABLISHED,
            )

            val payload = "v6-hello".toByteArray()
            val dataSeg = IpPacketBuilder.buildIpv6Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 5001L, ack = 1L, flags = TcpReassembler.FLAG_ACK,
                payload = payload,
            )
            relay.offer(dataSeg, dataSeg.size, 6)

            val received = ByteArray(payload.size)
            accepted.getInputStream().read(received)
            assertArrayEquals(payload, received)
        } finally {
            server.close()
        }
    }

    @Test
    fun `IPv6 UDP round-trips over loopback colon-colon-1`() {
        val serverSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), 0))
        try {
            val (relay, sink) = startRelay()

            val guestIp = ByteArray(16).also { it[15] = 5 }
            val serverIp = ByteArray(16).also { it[15] = 1 }
            val guestPort = 40001
            val serverPort = serverSocket.localPort

            val payload = "v6-udp".toByteArray()
            val datagram = IpPacketBuilder.buildIpv6Udp(guestIp, guestPort, serverIp, serverPort, payload)
            relay.offer(datagram, datagram.size, 6)

            val recvBuf = ByteArray(1500)
            val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
            serverSocket.soTimeout = 3000
            serverSocket.receive(recvPacket)
            assertArrayEquals(payload, recvPacket.data.copyOfRange(0, recvPacket.length))
        } finally {
            serverSocket.close()
        }
    }

    // ── flow-table leak regression (FIX A) ──────────────────────────────

    /**
     * Regression test for the bug where [RelayFlow]'s old `key` computed property and
     * [TunRelay.flowKey] formatted the map key differently (the former omitted the protocol
     * segment), so `tcpFlows.remove(flow.key)`/`closeTcpFlow(flow.key, ...)` could never find the
     * entry it was supposed to delete: every closed TCP flow leaked its `tcpFlows` map entry
     * forever. [RelayFlow.mapKey] now holds the exact string [TunRelay] inserted the flow under,
     * so removal always succeeds.
     */
    @Test
    fun `closing a TCP flow via RST removes it from the flow table and the active-flow stat returns to zero`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val (relay, _) = startRelay()

            val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
            val serverIp = byteArrayOf(127, 0, 0, 1)
            val guestPort = 51010
            val serverPort = server.localPort

            val syn = IpPacketBuilder.buildIpv4Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 1000L, ack = 0L, flags = TcpReassembler.FLAG_SYN,
            )
            relay.offer(syn, syn.size, 4)
            // Keep the accepted socket open (unlike the eviction test above) so the relay's
            // upstream connection stays alive and cannot race our assertions below by getting an
            // async EOF-triggered close on the selector thread.
            val accepted = server.accept()

            // Opening a flow bumps the active-flow stat synchronously inside handleTcpFromGuest.
            assertEquals(1, relay.stats.value.activeTcpV4)
            assertEquals("flow table should hold exactly the flow just opened", 1, tcpFlowMapSize(relay))

            val rst = IpPacketBuilder.buildIpv4Tcp(
                srcIp = guestIp, srcPort = guestPort,
                dstIp = serverIp, dstPort = serverPort,
                seq = 1001L, ack = 1L, flags = TcpReassembler.FLAG_RST,
            )
            relay.offer(rst, rst.size, 4)

            // Direct proof the map entry was actually removed -- this is exactly the leak FIX A
            // closes. Before the fix this assertion failed because tcpFlows.remove(flow.key) was
            // a silent no-op (flow.key and the map's real key could never be equal).
            assertEquals("flow table must not leak the closed flow's entry", 0, tcpFlowMapSize(relay))

            // bumpFamilyCount() is only invoked synchronously when a flow opens or the periodic
            // idle-prune sweep runs, not on every individual close, so force the same refresh the
            // maintenance loop would perform and confirm the active-flow counter agrees with the
            // now-empty flow table.
            refreshFamilyCount(relay)
            assertEquals(0, relay.stats.value.activeTcpV4)
            accepted.close()
        } finally {
            server.close()
        }
    }

    /** Reflects into the private `tcpFlows` map to directly observe leaks (or their absence). */
    private fun tcpFlowMapSize(relay: TunRelay): Int {
        val field = TunRelay::class.java.getDeclaredField("tcpFlows")
        field.isAccessible = true
        return (field.get(relay) as Map<*, *>).size
    }

    /** Invokes the private `bumpFamilyCount()` to force a stats refresh, mirroring pruneIdle. */
    private fun refreshFamilyCount(relay: TunRelay) {
        val method = TunRelay::class.java.getDeclaredMethod("bumpFamilyCount")
        method.isAccessible = true
        method.invoke(relay)
    }

    // ── fragmentation ────────────────────────────────────────────────────

    @Test
    fun `fragmented inbound UDP datagram is reassembled then relayed`() {
        val serverSocket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        try {
            val (relay, _) = startRelay()

            val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
            val serverIp = byteArrayOf(127, 0, 0, 1)
            val guestPort = 40010
            val serverPort = serverSocket.localPort

            val payload = "fragmented-udp-payload-bytes".toByteArray()
            val whole = IpPacketBuilder.buildIpv4Udp(guestIp, guestPort, serverIp, serverPort, payload)

            // Split the whole datagram into two IPv4 fragments at the byte-8-aligned boundary
            // closest to the middle of the UDP payload (fragment offsets must be 8-byte aligned).
            val ihl = 20
            val udpAndPayload = whole.copyOfRange(ihl, whole.size)
            val splitAt = (udpAndPayload.size / 2 / 8) * 8
            val first = udpAndPayload.copyOfRange(0, splitAt)
            val second = udpAndPayload.copyOfRange(splitAt, udpAndPayload.size)

            fun fragment(mf: Boolean, fragOffsetBytes: Int, data: ByteArray): ByteArray {
                val totalLen = ihl + data.size
                val buf = ByteArray(totalLen)
                buf[0] = ((4 shl 4) or 5).toByte()
                buf[2] = (totalLen ushr 8).toByte()
                buf[3] = totalLen.toByte()
                buf[4] = 0x12 // identification high byte
                buf[5] = 0x34
                val flagOffsetField = (if (mf) 0x2000 else 0) or (fragOffsetBytes / 8)
                buf[6] = (flagOffsetField ushr 8).toByte()
                buf[7] = flagOffsetField.toByte()
                buf[8] = 64
                buf[9] = 17 // UDP
                System.arraycopy(guestIp, 0, buf, 12, 4)
                System.arraycopy(serverIp, 0, buf, 16, 4)
                System.arraycopy(data, 0, buf, ihl, data.size)
                return buf
            }

            val frag0 = fragment(mf = true, fragOffsetBytes = 0, data = first)
            val frag1 = fragment(mf = false, fragOffsetBytes = splitAt, data = second)

            // Offer out of order.
            relay.offer(frag1, frag1.size, 4)
            relay.offer(frag0, frag0.size, 4)

            val recvBuf = ByteArray(1500)
            val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
            serverSocket.soTimeout = 3000
            serverSocket.receive(recvPacket)
            assertArrayEquals(payload, recvPacket.data.copyOfRange(0, recvPacket.length))
        } finally {
            serverSocket.close()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Extracts the ASCII TCP payload from a built IPv4/IPv6 TCP datagram, for assertions. */
    private fun extractTcpPayloadAscii(datagram: ByteArray, family: Int = 4): String {
        val off = if (family == 4) (datagram[0].toInt() and 0xF) * 4 else 40
        val dataOffset = ((datagram[off + 12].toInt() and 0xF0) shr 4) * 4
        val payloadStart = off + dataOffset
        return String(datagram, payloadStart, datagram.size - payloadStart)
    }

    private fun extractUdpPayloadAscii(datagram: ByteArray, family: Int): String {
        val off = if (family == 4) (datagram[0].toInt() and 0xF) * 4 else 40
        val payloadStart = off + 8
        return String(datagram, payloadStart, datagram.size - payloadStart)
    }
}
