package com.example.interception.tun

import org.junit.Assert.*
import org.junit.Test

/**
 * Task 6 test plan: the primary ping-socket path cannot be exercised in a plain JVM unit test
 * (no ping socket in the test JVM — confirmed: `android.system.Os.socket(...)` returns the
 * `isReturnDefaultValues` default of `null` under `testOptions.unitTests`, so
 * [IcmpRelay]'s `relayViaPingSocket` always falls through to the fallback path here). So these
 * tests target:
 *  - [IcmpIdMap]'s id<->id mapping round-trip and eviction directly (no socket involved at all).
 *  - [IcmpRelay]'s echo-reply synthesis via its fallback TCP-probe path against real loopback
 *    sockets, following [TunRelayTest]'s own loopback-socket conventions and using
 *    [IpPacketBuilder] as the parsing oracle, per the plan's own testing note for task 2.
 *  - Checksum-mismatch rejection (no relay path is even attempted).
 *  - Both-paths-failing incrementing [IcmpRelay.unrelayableCount] exactly once per packet.
 */
class IcmpRelayTest {

    // ── IcmpIdMap: pure mapping/eviction logic, no socket needed ──────────

    private fun entry(createdAt: Long, guestId: Int = 1, guestSeq: Int = 1) = IcmpIdMap.Entry(
        guestId = guestId,
        guestSeq = guestSeq,
        family = 4,
        guestIp = byteArrayOf(10, 0, 0, 5),
        targetIp = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34),
        createdAt = createdAt,
    )

    @Test
    fun `put then remove round-trips the exact entry`() {
        val map = IcmpIdMap(maxEntries = 8)
        val e = entry(createdAt = 1000L, guestId = 42, guestSeq = 7)
        map.put(kernelId = 5000, entry = e)
        assertEquals(1, map.size())

        val found = map.remove(5000)
        assertNotNull(found)
        assertEquals(42, found!!.guestId)
        assertEquals(7, found.guestSeq)
        assertEquals(4, found.family)
    }

    @Test
    fun `remove is one-shot -- a second remove for the same kernelId returns null`() {
        val map = IcmpIdMap(maxEntries = 8)
        map.put(kernelId = 1, entry = entry(createdAt = 1L))

        assertNotNull(map.remove(1))
        assertNull("a reply is a one-shot match, not a durable subscription", map.remove(1))
    }

    @Test
    fun `remove for a kernelId that was never recorded returns null`() {
        val map = IcmpIdMap(maxEntries = 8)
        assertNull(map.remove(999))
    }

    @Test
    fun `table evicts the single oldest entry once at maxEntries`() {
        val map = IcmpIdMap(maxEntries = 3)
        map.put(kernelId = 1, entry = entry(createdAt = 100L))
        map.put(kernelId = 2, entry = entry(createdAt = 200L))
        map.put(kernelId = 3, entry = entry(createdAt = 300L))
        assertEquals(3, map.size())

        // Table is full; inserting a fourth, newer entry must evict exactly the oldest
        // (kernelId=1, createdAt=100) and keep the table at maxEntries.
        map.put(kernelId = 4, entry = entry(createdAt = 400L))
        assertEquals(3, map.size())

        assertNull("oldest entry must have been evicted", map.remove(1))
        assertNotNull(map.remove(2))
        assertNotNull(map.remove(3))
        assertNotNull(map.remove(4))
    }

    @Test
    fun `re-putting an already-present kernelId does not trigger eviction`() {
        val map = IcmpIdMap(maxEntries = 2)
        map.put(kernelId = 1, entry = entry(createdAt = 100L))
        map.put(kernelId = 2, entry = entry(createdAt = 200L))
        assertEquals(2, map.size())

        // kernelId=1 already exists, so this must overwrite in place rather than evict anything.
        map.put(kernelId = 1, entry = entry(createdAt = 300L, guestId = 99))
        assertEquals(2, map.size())
        assertNotNull(map.remove(2))
        val overwritten = map.remove(1)
        assertNotNull(overwritten)
        assertEquals(99, overwritten!!.guestId)
    }

    // ── IcmpRelay.offer(): exercised via the fallback TCP-probe path only, since the primary
    // ping-socket path cannot run in a plain JVM unit test ────────────────

    /** Collects every datagram written back into "tun", same convention as [TunRelayTest]. */
    private class ByteArrayCapturingSink : TunSink {
        val datagrams = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        override fun write(datagram: ByteArray) {
            datagrams.add(datagram)
        }
    }

    private fun startRelay(): Pair<IcmpRelay, ByteArrayCapturingSink> {
        val sink = ByteArrayCapturingSink()
        val relay = IcmpRelay(NoOpProtectHook, TunWriter(sink))
        return relay to sink
    }

    private fun awaitTrue(timeoutMs: Long = 5000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    /** Builds a genuine, checksum-valid IPv4 ICMP echo request -- the same shape [IcmpRelay]
     * reads directly off the (simulated) tun fd. */
    private fun buildEchoRequestV4(guestIp: ByteArray, targetIp: ByteArray, id: Int, seq: Int, payload: ByteArray): ByteArray {
        val restBytes = ByteArray(4 + payload.size)
        writeU16(restBytes, 0, id)
        writeU16(restBytes, 2, seq)
        System.arraycopy(payload, 0, restBytes, 4, payload.size)
        return IpPacketBuilder.buildIpv4Icmp(guestIp, targetIp, type = 8 /* ICMP_ECHO_REQUEST_V4 */, code = 0, restBytes = restBytes)
    }

    private fun headerFor(packet: ByteArray, guestIp: ByteArray, targetIp: ByteArray) =
        TunRelay.Header(family = 4, protocol = 1, srcIp = guestIp, dstIp = targetIp, transportOffset = (packet[0].toInt() and 0xF) * 4)

    // Parsing helpers for the reply datagram -- IPv4 with no options, so the ICMP body starts
    // at a fixed offset 20, matching every datagram IpPacketBuilder/IcmpRelay produce here.
    private fun icmpType(datagram: ByteArray) = datagram[20].toInt() and 0xFF
    private fun icmpCode(datagram: ByteArray) = datagram[21].toInt() and 0xFF
    private fun icmpId(datagram: ByteArray) = ((datagram[24].toInt() and 0xFF) shl 8) or (datagram[25].toInt() and 0xFF)
    private fun icmpSeq(datagram: ByteArray) = ((datagram[26].toInt() and 0xFF) shl 8) or (datagram[27].toInt() and 0xFF)
    private fun icmpChecksumValid(datagram: ByteArray): Boolean {
        val icmpBytes = datagram.copyOfRange(20, datagram.size)
        return IpPacketBuilder.internetChecksum(icmpBytes, 0, icmpBytes.size) == 0
    }

    @Test
    fun `fallback probe against loopback with nothing listening synthesizes a valid echo reply with matching id and seq`() {
        val (relay, sink) = startRelay()
        val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
        // 127.0.0.1 with nothing listening on ports 7/80/443/53 -- connect fails with
        // ConnectException (refused), which IcmpRelay's fallback treats as "reachable", exactly
        // like InetAddress.isReachable()'s own documented TCP-connect fallback semantics.
        val targetIp = byteArrayOf(127, 0, 0, 1)
        val id = 4321
        val seq = 9
        val request = buildEchoRequestV4(guestIp, targetIp, id, seq, "ping-payload".toByteArray())

        relay.offer(request, headerFor(request, guestIp, targetIp))

        assertTrue("expected a synthesized echo reply", awaitTrue { sink.datagrams.isNotEmpty() })
        val reply = sink.datagrams.first()

        assertEquals(0, icmpType(reply)) // ICMP_ECHOREPLY
        assertEquals(0, icmpCode(reply))
        assertEquals(id, icmpId(reply))
        assertEquals(seq, icmpSeq(reply))
        assertTrue("reply must carry a valid ICMP checksum", icmpChecksumValid(reply))
        assertEquals(0, relay.unrelayableCount)
    }

    @Test
    fun `checksum-mismatch input is rejected without attempting any relay path`() {
        val (relay, sink) = startRelay()
        val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
        val targetIp = byteArrayOf(127, 0, 0, 1)
        val request = buildEchoRequestV4(guestIp, targetIp, id = 1, seq = 1, payload = "x".toByteArray())

        // Corrupt a payload byte after the checksum was already computed over the correct
        // bytes -- type/code/id/seq all stay intact so this exercises checksum verification
        // specifically, not the type/code guard earlier in handleSync.
        val corrupted = request.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()

        relay.offer(corrupted, headerFor(corrupted, guestIp, targetIp))

        assertTrue("checksum-mismatch packet must be counted unrelayable", awaitTrue { relay.unrelayableCount == 1 })
        // Give any (incorrect) in-flight relay attempt a moment to settle, then confirm nothing
        // was ever written back -- the packet must have been rejected before any socket I/O.
        Thread.sleep(200)
        assertTrue("no reply should ever be synthesized for a rejected packet", sink.datagrams.isEmpty())
        assertEquals(0, relay.outstandingCount())
    }

    @Test
    fun `both relay paths failing increments unrelayableCount exactly once and writes nothing`() {
        val (relay, sink) = startRelay()
        val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
        // 224.0.0.1 is a multicast address -- connect attempts to it fail immediately with
        // "network unreachable" on every port, so both the (JVM-unavailable) ping-socket path
        // and the TCP fallback across all of IcmpRelay's FALLBACK_PROBE_PORTS genuinely fail,
        // deterministically and fast.
        val targetIp = byteArrayOf(224.toByte(), 0, 0, 1)
        val request = buildEchoRequestV4(guestIp, targetIp, id = 55, seq = 2, payload = "y".toByteArray())

        relay.offer(request, headerFor(request, guestIp, targetIp))

        assertTrue("both paths failing must be counted exactly once", awaitTrue { relay.unrelayableCount == 1 })
        Thread.sleep(200)
        assertEquals(1, relay.unrelayableCount)
        assertTrue("no reply should be synthesized when both paths fail", sink.datagrams.isEmpty())
        assertEquals(0, relay.outstandingCount())
    }

    @Test
    fun `not-an-echo-request ICMP message is counted unrelayable and never relayed`() {
        val (relay, sink) = startRelay()
        val guestIp = byteArrayOf(10, 111, 222.toByte(), 5)
        val targetIp = byteArrayOf(127, 0, 0, 1)
        // type=0 (echo REPLY, not a request) with code=0 -- a genuine, checksum-valid ICMP
        // message this relay still has nothing useful to originate from.
        val restBytes = ByteArray(4)
        writeU16(restBytes, 0, 1)
        writeU16(restBytes, 2, 1)
        val notARequest = IpPacketBuilder.buildIpv4Icmp(guestIp, targetIp, type = 0, code = 0, restBytes = restBytes)

        relay.offer(notARequest, headerFor(notARequest, guestIp, targetIp))

        assertTrue(awaitTrue { relay.unrelayableCount == 1 })
        Thread.sleep(200)
        assertTrue(sink.datagrams.isEmpty())
    }
}
