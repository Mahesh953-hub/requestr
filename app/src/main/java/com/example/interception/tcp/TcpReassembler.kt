package com.example.interception.tcp

import android.util.Log
import com.example.interception.tun.IpAddressCodec
import com.example.interception.tun.IpFragmentReassembler

/**
 * Parses TCP headers from raw IP packets and routes payload bytes into
 * per-flow [TcpStream] reassembly buffers.
 *
 * The tun fd delivers full IP datagrams.  We strip the IP header, parse the
 * TCP header, and append the payload to the matching direction's buffer.
 * Completed HTTP / TLS messages are detected by the higher-level decoders,
 * not here — this layer only manages the byte-stream.
 */
class TcpReassembler {

    /** Active TCP streams, keyed by 5-tuple. */
    val streams = LinkedHashMap<String, TcpStream>()

    /** Max concurrent streams before we evict the oldest idle. */
    var maxStreams: Int = 512

    /** Idle timeout in ms — streams untouched for this long are pruned. */
    var idleTimeoutMs: Long = 120_000L

    /** Task 9: [feedIpv6] needs [IpFragmentReassembler.walkIpv6ExtensionHeaders] to find the
     * real transport offset instead of assuming the fixed offset 40, exactly like
     * [com.example.interception.tun.TunRelay]'s own v6 header parsing (task 3) already does.
     * Pure function of the packet bytes -- reads no reassembly-group state -- so a dedicated
     * instance here, never fed through [IpFragmentReassembler.offer], is safe. */
    private val ipv6HeaderWalker = IpFragmentReassembler()

    // ── entry point ─────────────────────────────────────────────────────

    /**
     * Feed one raw IP packet through the reassembler.
     *
     * @param packet  the complete IP datagram (including IP header)
     * @param length  bytes actually read
     * @param version  4 or 6 (already determined by caller)
     * @return  the [TcpStream] that received data, or null if this packet
     *          was non-TCP, a pure control segment, or discarded.
     */
    fun feed(packet: ByteArray, length: Int, version: Int): TcpStream? {
        return when (version) {
            4 -> feedIpv4(packet, length)
            6 -> feedIpv6(packet, length)
            else -> null
        }
    }

    // ── IPv4 ────────────────────────────────────────────────────────────

    private fun feedIpv4(packet: ByteArray, length: Int): TcpStream? {
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (length < ihl + 20) return null  // need at least IP + minimal TCP header

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6) return null  // TCP only

        val srcIp = "${packet[12].u()}.${packet[13].u()}.${packet[14].u()}.${packet[15].u()}"
        val dstIp = "${packet[16].u()}.${packet[17].u()}.${packet[18].u()}.${packet[19].u()}"

        return feedTcp(packet, ihl, length, srcIp, dstIp, 4)
    }

    // ── IPv6 ────────────────────────────────────────────────────────────

    /**
     * Task 9: uses [IpFragmentReassembler.walkIpv6ExtensionHeaders] to find the true transport
     * offset and protocol instead of assuming `nextHeader` (byte 6) is always TCP and the
     * transport header always starts at offset 40 -- that assumption broke on any hop-by-hop
     * (0), routing (43), fragment (44), or destination-options (60) extension header.
     */
    private fun feedIpv6(packet: ByteArray, length: Int): TcpStream? {
        if (length < 40) return null
        val walk = ipv6HeaderWalker.walkIpv6ExtensionHeaders(packet, length) ?: return null
        val (transportOffset, protocol) = walk
        if (protocol != 6) return null  // TCP only
        if (length < transportOffset + 20) return null  // need at least the minimal TCP header

        val srcIp = IpAddressCodec.formatIpv6(packet, 8)
        val dstIp = IpAddressCodec.formatIpv6(packet, 24)

        return feedTcp(packet, transportOffset, length, srcIp, dstIp, 6)
    }

    // ── TCP header ──────────────────────────────────────────────────────

    private fun feedTcp(
        packet: ByteArray,
        ipHeaderLen: Int,
        length: Int,
        srcIp: String,
        dstIp: String,
        ipVersion: Int,
    ): TcpStream? {
        val off = ipHeaderLen

        val srcPort = ((packet[off].u() shl 8) or packet[off + 1].u())
        val dstPort = ((packet[off + 2].u() shl 8) or packet[off + 3].u())
        val seqNum = readU32(packet, off + 4)
        // ackNum = readU32(packet, off + 8)
        val dataOffset = ((packet[off + 12].toInt() and 0xF0) shr 4) * 4
        val flags = packet[off + 13].toInt() and 0xFF

        val payloadStart = ipHeaderLen + dataOffset
        val payloadLen = (length - payloadStart).coerceAtLeast(0)

        val stream = getOrCreate(srcIp, srcPort, dstIp, dstPort)
        val now = System.currentTimeMillis()
        stream.lastActivityAt = now

        // Real three-way-handshake tracking, driven by the actual flags seen on the wire —
        // not inferred from data arriving.
        val isSyn = (flags and FLAG_SYN) != 0
        val isAck = (flags and FLAG_ACK) != 0
        val isFin = (flags and FLAG_FIN) != 0
        val isRst = (flags and FLAG_RST) != 0

        when {
            isRst -> {
                stream.handshakePhase = TcpHandshakePhase.RESET
                stream.resetByPeer = true
                stream.closed = true
            }
            isSyn && !isAck -> {
                if (stream.handshakePhase == TcpHandshakePhase.NONE) {
                    stream.handshakePhase = TcpHandshakePhase.SYN_SENT
                    stream.synSeenAt = now
                }
            }
            isSyn && isAck -> {
                if (stream.handshakePhase == TcpHandshakePhase.SYN_SENT ||
                    stream.handshakePhase == TcpHandshakePhase.NONE
                ) {
                    stream.handshakePhase = TcpHandshakePhase.SYN_ACK_SEEN
                }
            }
            isFin -> {
                if (stream.handshakePhase != TcpHandshakePhase.CLOSED) {
                    stream.handshakePhase = TcpHandshakePhase.CLOSING
                }
            }
            isAck && stream.handshakePhase == TcpHandshakePhase.SYN_ACK_SEEN -> {
                stream.handshakePhase = TcpHandshakePhase.ESTABLISHED
                stream.establishedAtHandshake = now
            }
        }

        if ((isFin || isRst) && stream.handshakePhase != TcpHandshakePhase.RESET) {
            stream.handshakePhase = TcpHandshakePhase.CLOSED
        }
        if (isFin || isRst) {
            stream.closed = true
        }

        if (payloadLen <= 0) return stream

        // Decide direction: client→server vs server→client
        // In a typical capture, the first packet we see is from the client.
        // Heuristic: if srcIp matches the stream's srcIp, it's client→server.
        val isClientToServer = srcIp == stream.srcIp
        val dir = if (isClientToServer) stream.clientToServer else stream.serverToClient
        val accepted = dir.write(seqNum, packet, payloadStart, payloadLen)
        if (accepted == -1) {
            stream.retransmissions++
        } else if (accepted > 0) {
            stream.lastActivityAt = now
            if (isClientToServer) stream.totalBytesSent += accepted else stream.totalBytesReceived += accepted
        }
        return stream
    }

    // ── stream management ───────────────────────────────────────────────

    private fun getOrCreate(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): TcpStream {
        val key = TcpStream.keyFrom(srcIp, srcPort, dstIp, dstPort)
        return streams.getOrPut(key) {
            if (streams.size >= maxStreams) evictOldest()
            TcpStream(srcIp = srcIp, srcPort = srcPort, dstIp = dstIp, dstPort = dstPort)
        }
    }

    private fun evictOldest() {
        val now = System.currentTimeMillis()
        streams.entries.removeAll { (_, s) -> now - s.lastActivityAt > idleTimeoutMs }
        // If still over limit, evict the oldest regardless
        while (streams.size >= maxStreams) {
            val oldest = streams.entries.minByOrNull { it.value.lastActivityAt }
            oldest?.let { streams.remove(it.key) }
        }
    }

    /**
     * Prune idle streams. Call periodically (e.g. from the flush loop).
     *
     * Bug fix: the previous second statement compared a *duration*
     * (`now - establishedAt`, thousands of ms) against an *absolute epoch timestamp*
     * (`cutoff = now - idleTimeoutMs`, ~1.7e12), so it was never true and never pruned
     * anything. Both statements below now correctly compare `lastActivityAt` (bumped on every
     * accepted packet, not just once at flow creation) against the absolute cutoff.
     */
    fun prune() {
        val cutoff = System.currentTimeMillis() - idleTimeoutMs
        streams.entries.removeAll { (_, s) -> s.closed && s.lastActivityAt < cutoff }
        streams.entries.removeAll { (_, s) -> s.lastActivityAt < cutoff }
    }

    fun streamCount(): Int = streams.size

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
        ((buf[off + 1].toLong() and 0xFF) shl 16) or
        ((buf[off + 2].toLong() and 0xFF) shl 8) or
        (buf[off + 3].toLong() and 0xFF)

    private fun Byte.u(): Int = this.toInt() and 0xFF

    companion object {
        private const val TAG = "TcpReassembler"
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
    }
}
