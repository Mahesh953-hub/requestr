package com.example.interception.tun

import org.junit.Assert.*
import org.junit.Test

class IpFragmentReassemblerTest {

    private fun ipv4Packet(
        id: Int,
        mf: Boolean,
        fragOffsetBytes: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val ihl = 20
        val totalLen = ihl + payload.size
        val buf = ByteArray(totalLen)
        buf[0] = ((4 shl 4) or 5).toByte()
        buf[2] = (totalLen ushr 8).toByte()
        buf[3] = totalLen.toByte()
        buf[4] = (id ushr 8).toByte()
        buf[5] = id.toByte()
        val flagOffsetField = (if (mf) 0x2000 else 0) or (fragOffsetBytes / 8)
        buf[6] = (flagOffsetField ushr 8).toByte()
        buf[7] = flagOffsetField.toByte()
        buf[8] = 64
        buf[9] = protocol.toByte()
        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(dstIp, 0, buf, 16, 4)
        System.arraycopy(payload, 0, buf, ihl, payload.size)
        return buf
    }

    @Test
    fun `unfragmented ipv4 packet passes through untouched and identity-equal`() {
        val r = IpFragmentReassembler()
        val packet = ipv4Packet(id = 1, mf = false, fragOffsetBytes = 0, protocol = 6, srcIp = byteArrayOf(10, 0, 0, 1), dstIp = byteArrayOf(10, 0, 0, 2), payload = ByteArray(10))
        val out = r.offer(packet, packet.size, 4)
        assertSame(packet, out)
    }

    @Test
    fun `split ipv4 datagram reassembles out of order to the original bytes`() {
        val src = byteArrayOf(10, 0, 0, 1)
        val dst = byteArrayOf(10, 0, 0, 2)
        val fullPayload = ByteArray(16) { it.toByte() }
        val firstHalf = fullPayload.copyOfRange(0, 8)
        val secondHalf = fullPayload.copyOfRange(8, 16)

        val frag0 = ipv4Packet(id = 42, mf = true, fragOffsetBytes = 0, protocol = 17, srcIp = src, dstIp = dst, payload = firstHalf)
        val frag1 = ipv4Packet(id = 42, mf = false, fragOffsetBytes = 8, protocol = 17, srcIp = src, dstIp = dst, payload = secondHalf)

        val r = IpFragmentReassembler()
        // offer out of order: second fragment first
        val partial = r.offer(frag1, frag1.size, 4)
        assertNull(partial)
        val complete = r.offer(frag0, frag0.size, 4)
        assertNotNull(complete)

        val ihl = 20
        val reassembledPayload = complete!!.copyOfRange(ihl, complete.size)
        assertArrayEquals(fullPayload, reassembledPayload)
        assertEquals(17, complete[9].toInt() and 0xFF)
    }

    @Test
    fun `incomplete ipv4 group is dropped after TTL and counted`() {
        val src = byteArrayOf(10, 0, 0, 1)
        val dst = byteArrayOf(10, 0, 0, 2)
        val frag0 = ipv4Packet(id = 7, mf = true, fragOffsetBytes = 0, protocol = 6, srcIp = src, dstIp = dst, payload = ByteArray(8))

        val r = IpFragmentReassembler()
        r.flowTtlMs = 10L
        r.offer(frag0, frag0.size, 4)
        assertEquals(0, r.droppedIncompleteCount)

        Thread.sleep(30)

        // Offering any other packet triggers pruneExpired(), which sweeps the now-stale group.
        val other = ipv4Packet(id = 8, mf = true, fragOffsetBytes = 0, protocol = 6, srcIp = src, dstIp = dst, payload = ByteArray(8))
        r.offer(other, other.size, 4)

        assertEquals(1, r.droppedIncompleteCount)
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────

    private fun ipv6WithFragmentHeader(
        id: Long,
        mf: Boolean,
        fragOffsetBytes: Int,
        realProtocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val fixed = ByteArray(40)
        fixed[0] = (6 shl 4).toByte()
        fixed[6] = 44 // next header = Fragment
        fixed[7] = 64
        System.arraycopy(srcIp, 0, fixed, 8, 16)
        System.arraycopy(dstIp, 0, fixed, 24, 16)

        val fragHeader = ByteArray(8)
        fragHeader[0] = realProtocol.toByte()
        fragHeader[1] = 0 // reserved
        val offsetFlags = (fragOffsetBytes and 0xFFF8) or (if (mf) 1 else 0)
        fragHeader[2] = (offsetFlags ushr 8).toByte()
        fragHeader[3] = offsetFlags.toByte()
        fragHeader[4] = (id ushr 24).toByte()
        fragHeader[5] = (id ushr 16).toByte()
        fragHeader[6] = (id ushr 8).toByte()
        fragHeader[7] = id.toByte()

        val total = fixed + fragHeader + payload
        val payloadLen = total.size - 40
        total[4] = (payloadLen ushr 8).toByte()
        total[5] = payloadLen.toByte()
        return total
    }

    @Test
    fun `ipv6 fragment header pair reassembles to the original bytes`() {
        val src = ByteArray(16) { 1 }
        val dst = ByteArray(16) { 2 }
        val fullPayload = ByteArray(16) { it.toByte() }
        val firstHalf = fullPayload.copyOfRange(0, 8)
        val secondHalf = fullPayload.copyOfRange(8, 16)

        val frag0 = ipv6WithFragmentHeader(id = 99L, mf = true, fragOffsetBytes = 0, realProtocol = 6, srcIp = src, dstIp = dst, payload = firstHalf)
        val frag1 = ipv6WithFragmentHeader(id = 99L, mf = false, fragOffsetBytes = 8, realProtocol = 6, srcIp = src, dstIp = dst, payload = secondHalf)

        val r = IpFragmentReassembler()
        assertNull(r.offer(frag0, frag0.size, 6))
        val complete = r.offer(frag1, frag1.size, 6)
        assertNotNull(complete)

        assertEquals(6, complete!![6].toInt() and 0xFF) // next header patched to real protocol (TCP)
        val reassembledPayload = complete.copyOfRange(40, complete.size)
        assertArrayEquals(fullPayload, reassembledPayload)
    }

    @Test
    fun `unfragmented ipv6 packet passes through untouched`() {
        val fixed = ByteArray(40)
        fixed[0] = (6 shl 4).toByte()
        fixed[6] = 6 // TCP directly, no fragment header
        val r = IpFragmentReassembler()
        val out = r.offer(fixed, fixed.size, 6)
        assertSame(fixed, out)
    }

    @Test
    fun `walkIpv6ExtensionHeaders finds transport offset past hop-by-hop and destination-options`() {
        val fixed = ByteArray(40)
        fixed[0] = (6 shl 4).toByte()
        fixed[6] = 0 // hop-by-hop next

        // hop-by-hop: next header = destination options (60), hdrExtLen=0 -> 8 bytes total
        val hopByHop = ByteArray(8)
        hopByHop[0] = 60
        hopByHop[1] = 0

        // destination options: next header = TCP (6), hdrExtLen=0 -> 8 bytes total
        val destOpts = ByteArray(8)
        destOpts[0] = 6
        destOpts[1] = 0

        val packet = fixed + hopByHop + destOpts + ByteArray(20) // fake TCP header space
        val r = IpFragmentReassembler()
        val result = r.walkIpv6ExtensionHeaders(packet, packet.size)
        assertNotNull(result)
        assertEquals(40 + 8 + 8, result!!.first)
        assertEquals(6, result.second)
    }
}
