package com.example.interception.tun

import com.example.interception.tcp.TcpReassembler
import com.example.interception.tcp.TcpStream
import org.junit.Assert.*
import org.junit.Test

class IpPacketBuilderTest {

    private val srcV4 = byteArrayOf(10, 0, 0, 5)
    private val dstV4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
    private val srcV6 = ByteArray(16) { (it + 1).toByte() }
    private val dstV6 = ByteArray(16) { (it + 100).toByte() }

    @Test
    fun `ipv4 tcp checksum against hand-computed header`() {
        val packet = IpPacketBuilder.buildIpv4Tcp(srcV4, 51000, dstV4, 443, seq = 1, ack = 0, flags = TcpReassembler.FLAG_SYN)
        // Recomputing the checksum over the finished 20-byte IP header (with checksum field
        // included) must yield 0 -- the RFC 1071 self-check.
        assertEquals(0, IpPacketBuilder.internetChecksum(packet, 0, 20))
    }

    @Test
    fun `ipv4 tcp round-trips through TcpReassembler recovering 5-tuple flags and payload`() {
        val payload = "hello".toByteArray()
        val packet = IpPacketBuilder.buildIpv4Tcp(
            srcV4, 51000, dstV4, 443,
            seq = 100, ack = 0, flags = TcpReassembler.FLAG_ACK or TcpReassembler.FLAG_PSH,
            payload = payload,
        )
        val r = TcpReassembler()
        val stream = r.feed(packet, packet.size, 4)
        assertNotNull(stream)
        assertEquals("10.0.0.5", stream!!.srcIp)
        assertEquals(51000, stream.srcPort)
        assertEquals("93.184.216.34", stream.dstIp)
        assertEquals(443, stream.dstPort)
        assertEquals(5, stream.clientToServer.size())
        assertArrayEquals(payload, stream.clientToServer.bytes())
    }

    @Test
    fun `ipv6 has no header checksum field but correct tcp pseudo-header checksum`() {
        val payload = "abc".toByteArray()
        val packet = IpPacketBuilder.buildIpv6Tcp(
            srcV6, 40000, dstV6, 443,
            seq = 1, ack = 0, flags = TcpReassembler.FLAG_ACK, payload = payload,
        )
        // IPv6 fixed header is exactly 40 bytes with no checksum field -- byte 6 is next-header,
        // there is no analogous "IP checksum" slot to verify (that's the point).
        assertEquals(6, packet[6].toInt() and 0xFF) // next header = TCP

        // Recompute the TCP checksum over pseudo-header + segment; must be 0.
        val tcpSegment = packet.copyOfRange(40, packet.size)
        val pseudo = ByteArray(40)
        System.arraycopy(srcV6, 0, pseudo, 0, 16)
        System.arraycopy(dstV6, 0, pseudo, 16, 16)
        pseudo[32] = 0; pseudo[33] = 0; pseudo[34] = 0; pseudo[35] = tcpSegment.size.toByte()
        pseudo[39] = 6
        val combined = pseudo + tcpSegment
        assertEquals(0, IpPacketBuilder.internetChecksum(combined, 0, combined.size))
    }

    @Test
    fun `ipv6 tcp round-trips through TcpReassembler`() {
        val payload = "xyz".toByteArray()
        val packet = IpPacketBuilder.buildIpv6Tcp(
            srcV6, 40000, dstV6, 443,
            seq = 1, ack = 0, flags = TcpReassembler.FLAG_ACK, payload = payload,
        )
        val r = TcpReassembler()
        val stream = r.feed(packet, packet.size, 6)
        assertNotNull(stream)
        assertEquals(IpAddressCodec.formatIpv6(srcV6, 0), stream!!.srcIp)
        assertEquals(40000, stream.srcPort)
        assertArrayEquals(payload, stream.clientToServer.bytes())
    }

    @Test
    fun `udp length fields are correct`() {
        val payload = ByteArray(20) { it.toByte() }
        val packet = IpPacketBuilder.buildIpv4Udp(srcV4, 5000, dstV4, 53, payload)
        val ihl = (packet[0].toInt() and 0xF) * 4
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        assertEquals(8 + payload.size, udpLen)
        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        assertEquals(ihl + 8 + payload.size, totalLen)
    }

    @Test
    fun `icmpv4 echo reply builds a valid checksum`() {
        val rest = ByteArray(4) // id+seq placeholder
        val packet = IpPacketBuilder.buildIpv4Icmp(srcV4, dstV4, type = 0, code = 0, restBytes = rest)
        val ihl = (packet[0].toInt() and 0xF) * 4
        val icmpLen = packet.size - ihl
        assertEquals(0, IpPacketBuilder.internetChecksum(packet, ihl, icmpLen))
    }

    @Test
    fun `oversize datagram throws rather than silently truncating`() {
        val bigPayload = ByteArray(IpPacketBuilder.MTU) // guarantees total > MTU once headers added
        try {
            IpPacketBuilder.buildIpv4Udp(srcV4, 1, dstV4, 2, bigPayload)
            fail("expected IllegalArgumentException for oversize datagram")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
