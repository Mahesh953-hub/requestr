package com.example.interception.tcp

import android.util.Log

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

    private fun feedIpv6(packet: ByteArray, length: Int): TcpStream? {
        if (length < 40 + 20) return null

        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != 6) return null

        val srcIp = packet.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }
        val dstIp = packet.copyOfRange(24, 40).joinToString("") { "%02x".format(it) }

        return feedTcp(packet, 40, length, srcIp, dstIp, 6)
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

        // Update handshake state
        if ((flags and FLAG_SYN) != 0) {
            if (!stream.handshakeComplete && (flags and FLAG_ACK) != 0) {
                // SYN-ACK observed → handshake in progress
            }
        }
        if ((flags and FLAG_ACK) != 0 && !stream.handshakeComplete && stream.clientToServer.size() > 0) {
            stream.handshakeComplete = true
        }
        if ((flags and (FLAG_FIN or FLAG_RST)) != 0) {
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
            stream.establishedAt = System.currentTimeMillis()
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
        streams.entries.removeAll { (_, s) -> now - s.establishedAt > idleTimeoutMs }
        // If still over limit, evict the oldest regardless
        while (streams.size >= maxStreams) {
            val oldest = streams.entries.minByOrNull { it.value.establishedAt }
            oldest?.let { streams.remove(it.key) }
        }
    }

    /** Prune idle streams. Call periodically (e.g. from the flush loop). */
    fun prune() {
        val cutoff = System.currentTimeMillis() - idleTimeoutMs
        streams.entries.removeAll { (_, s) -> s.closed && (System.currentTimeMillis() - s.establishedAt > idleTimeoutMs) }
        streams.entries.removeAll { (_, s) -> System.currentTimeMillis() - s.establishedAt > cutoff }
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
