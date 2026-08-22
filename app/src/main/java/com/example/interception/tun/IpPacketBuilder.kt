package com.example.interception.tun

/**
 * Synthesizes complete IPv4/IPv6 + TCP/UDP/ICMP datagrams to write back into the tun fd.
 *
 * Nothing in the repo could do this before: a grep for `checksum`/`buildIpv4`/`ByteBuffer.allocate`
 * across `app/src/main/java` returns only unrelated hits (see plan-transport-detailed.md task 2).
 * [PacketCaptureEngine] writes PCAP record headers, which is a different thing entirely.
 *
 * IPv4 emits the classic 20-byte header with a real header checksum. IPv6 emits the 40-byte
 * fixed header with **no header checksum by design** (a genuine RFC 8200 format difference, not
 * an omission — IPv6 relies on the link layer + upper-layer checksums instead). TCP/UDP checksums
 * use the respective pseudo-headers, which differ between v4 (12 bytes) and v6 (40 bytes).
 */
object IpPacketBuilder {

    /** Matches [com.example.interception.RequestrVpnService.MTU]. Callers must segment above this. */
    const val MTU = 1500

    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val PROTO_ICMPV4 = 1
    private const val PROTO_ICMPV6 = 58

    // ── checksum (RFC 1071) ────────────────────────────────────────────

    /**
     * One's-complement checksum over `buf[offset, offset+length)`. Returns the 16-bit value to
     * place into the checksum field; recomputing over the finished datagram (checksum field
     * included) yields 0.
     */
    fun internetChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.toInt().inv()) and 0xFFFF
    }

    private fun checksumOverConcat(a: ByteArray, b: ByteArray): Int {
        val combined = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, combined, 0, a.size)
        System.arraycopy(b, 0, combined, a.size, b.size)
        return internetChecksum(combined, 0, combined.size)
    }

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun checkMtu(size: Int) {
        require(size <= MTU) { "Datagram of $size bytes exceeds MTU=$MTU; caller must segment" }
    }

    // ── IPv4 ─────────────────────────────────────────────────────────────

    private fun wrapIpv4(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4)
        val totalLen = 20 + payload.size
        checkMtu(totalLen)
        val out = ByteArray(totalLen)
        out[0] = ((4 shl 4) or 5).toByte() // version 4, IHL 5 (no options)
        out[1] = 0 // TOS
        writeU16(out, 2, totalLen)
        writeU16(out, 4, 0) // identification
        writeU16(out, 6, 0) // flags + fragment offset
        out[8] = 64 // TTL
        out[9] = protocol.toByte()
        writeU16(out, 10, 0) // checksum placeholder
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        val cksum = internetChecksum(out, 0, 20)
        writeU16(out, 10, cksum)
        System.arraycopy(payload, 0, out, 20, payload.size)
        return out
    }

    fun buildIpv4Tcp(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val tcp = buildTcpSegment(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, window, payload, pseudoHeaderV4(srcIp, dstIp, PROTO_TCP, 20 + payload.size))
        return wrapIpv4(srcIp, dstIp, PROTO_TCP, tcp)
    }

    fun buildIpv4Udp(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val udp = buildUdpSegment(srcPort, dstPort, payload, pseudoHeaderV4(srcIp, dstIp, PROTO_UDP, 8 + payload.size))
        return wrapIpv4(srcIp, dstIp, PROTO_UDP, udp)
    }

    fun buildIpv4Icmp(srcIp: ByteArray, dstIp: ByteArray, type: Int, code: Int, restBytes: ByteArray): ByteArray {
        val icmp = buildIcmpv4Message(type, code, restBytes)
        return wrapIpv4(srcIp, dstIp, PROTO_ICMPV4, icmp)
    }

    private fun pseudoHeaderV4(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, upperLayerLen: Int): ByteArray {
        val pseudo = ByteArray(12)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = protocol.toByte()
        writeU16(pseudo, 10, upperLayerLen)
        return pseudo
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────

    private fun wrapIpv6(srcIp: ByteArray, dstIp: ByteArray, nextHeader: Int, payload: ByteArray): ByteArray {
        require(srcIp.size == 16 && dstIp.size == 16)
        val totalLen = 40 + payload.size
        checkMtu(totalLen)
        val out = ByteArray(totalLen)
        out[0] = (6 shl 4).toByte() // version 6; traffic class + flow label left at 0
        out[1] = 0
        out[2] = 0
        out[3] = 0
        writeU16(out, 4, payload.size) // payload length (excludes the 40-byte fixed header)
        out[6] = nextHeader.toByte()
        out[7] = 64 // hop limit
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)
        System.arraycopy(payload, 0, out, 40, payload.size)
        // Deliberately no header checksum field — RFC 8200 removed it; IPv6 relies on the
        // upper-layer (TCP/UDP/ICMPv6) checksum instead. This is a format difference, not a gap.
        return out
    }

    fun buildIpv6Tcp(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val tcp = buildTcpSegment(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, window, payload, pseudoHeaderV6(srcIp, dstIp, PROTO_TCP, 20 + payload.size))
        return wrapIpv6(srcIp, dstIp, PROTO_TCP, tcp)
    }

    fun buildIpv6Udp(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val udp = buildUdpSegment(srcPort, dstPort, payload, pseudoHeaderV6(srcIp, dstIp, PROTO_UDP, 8 + payload.size))
        return wrapIpv6(srcIp, dstIp, PROTO_UDP, udp)
    }

    fun buildIpv6Icmp(srcIp: ByteArray, dstIp: ByteArray, type: Int, code: Int, restBytes: ByteArray): ByteArray {
        val icmp = buildIcmpv6Message(srcIp, dstIp, type, code, restBytes)
        return wrapIpv6(srcIp, dstIp, PROTO_ICMPV6, icmp)
    }

    private fun pseudoHeaderV6(srcIp: ByteArray, dstIp: ByteArray, nextHeader: Int, upperLayerLen: Int): ByteArray {
        val pseudo = ByteArray(40)
        System.arraycopy(srcIp, 0, pseudo, 0, 16)
        System.arraycopy(dstIp, 0, pseudo, 16, 16)
        writeU32(pseudo, 32, upperLayerLen.toLong())
        pseudo[36] = 0
        pseudo[37] = 0
        pseudo[38] = 0
        pseudo[39] = nextHeader.toByte()
        return pseudo
    }

    // ── shared TCP/UDP/ICMP segment builders ──────────────────────────

    private fun buildTcpSegment(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        pseudoHeader: ByteArray,
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val tcp = ByteArray(tcpLen)
        writeU16(tcp, 0, srcPort)
        writeU16(tcp, 2, dstPort)
        writeU32(tcp, 4, seq)
        writeU32(tcp, 8, ack)
        tcp[12] = (5 shl 4).toByte() // data offset = 5 (20 bytes), no options
        tcp[13] = flags.toByte()
        writeU16(tcp, 14, window)
        writeU16(tcp, 16, 0) // checksum placeholder
        writeU16(tcp, 18, 0) // urgent pointer
        System.arraycopy(payload, 0, tcp, 20, payload.size)
        val cksum = checksumOverConcat(pseudoHeader, tcp)
        writeU16(tcp, 16, cksum)
        return tcp
    }

    private fun buildUdpSegment(srcPort: Int, dstPort: Int, payload: ByteArray, pseudoHeader: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val udp = ByteArray(udpLen)
        writeU16(udp, 0, srcPort)
        writeU16(udp, 2, dstPort)
        writeU16(udp, 4, udpLen)
        writeU16(udp, 6, 0) // checksum placeholder
        System.arraycopy(payload, 0, udp, 8, payload.size)
        var cksum = checksumOverConcat(pseudoHeader, udp)
        // RFC 768: an all-zero computed checksum is transmitted as all-ones (0 means "no checksum
        // computed", which UDP/IPv4 permits but UDP/IPv6 (RFC 8200 §8.1) forbids outright — always
        // emitting the all-ones form is correct and unambiguous for both families).
        if (cksum == 0) cksum = 0xFFFF
        writeU16(udp, 6, cksum)
        return udp
    }

    private fun buildIcmpv4Message(type: Int, code: Int, restBytes: ByteArray): ByteArray {
        val icmp = ByteArray(4 + restBytes.size)
        icmp[0] = type.toByte()
        icmp[1] = code.toByte()
        writeU16(icmp, 2, 0) // checksum placeholder
        System.arraycopy(restBytes, 0, icmp, 4, restBytes.size)
        val cksum = internetChecksum(icmp, 0, icmp.size)
        writeU16(icmp, 2, cksum)
        return icmp
    }

    /** ICMPv6 (RFC 4443 §2.3) checksum covers a pseudo-header, unlike ICMPv4. */
    private fun buildIcmpv6Message(srcIp: ByteArray, dstIp: ByteArray, type: Int, code: Int, restBytes: ByteArray): ByteArray {
        val icmp = ByteArray(4 + restBytes.size)
        icmp[0] = type.toByte()
        icmp[1] = code.toByte()
        writeU16(icmp, 2, 0) // checksum placeholder
        System.arraycopy(restBytes, 0, icmp, 4, restBytes.size)
        val pseudo = pseudoHeaderV6(srcIp, dstIp, PROTO_ICMPV6, icmp.size)
        val cksum = checksumOverConcat(pseudo, icmp)
        writeU16(icmp, 2, cksum)
        return icmp
    }
}
