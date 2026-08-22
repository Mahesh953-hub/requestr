package com.example.interception.tun

/**
 * Bounded, TTL-pruned IP fragment reassembly for both IPv4 and IPv6.
 *
 * Neither existing parser handles fragmentation. `parseIpv4` only requires
 * `length >= ihl + 4` and reads ports straight from `ihl`/`ihl+2`, so a non-first IPv4 fragment
 * yields garbage ports. `parseIpv6` reads `nextHeader` at byte 6 and assumes the transport
 * header starts at offset 40, so any extension header (hop-by-hop 0, routing 43, fragment 44,
 * destination-options 60) breaks it, and a non-first IPv6 fragment carries no transport header
 * at all -- the 5-tuple is unknowable until reassembled.
 *
 * Dropping fragments outright would recreate the VPN_TUN blackhole in miniature, so incomplete
 * groups are buffered rather than discarded, bounded and TTL-pruned exactly like
 * [com.example.interception.PacketPump]'s `activeFlows` (`MAX_TRACKED_FLOWS = 512`,
 * `FLOW_TTL_MS = 30_000L`), so a fragment flood cannot exhaust memory. Groups that never
 * complete before the TTL are dropped and counted via [droppedIncompleteCount], never silently
 * forgotten.
 */
class IpFragmentReassembler {

    /** Number of fragment groups dropped incomplete after [flowTtlMs]. */
    var droppedIncompleteCount: Int = 0
        private set

    /** TTL for an incomplete fragment group, mirroring [com.example.interception.tcp.TcpReassembler.idleTimeoutMs]'s pattern of an overridable var for testability. */
    var flowTtlMs: Long = FLOW_TTL_MS

    private data class FragKey(val srcIp: String, val dstIp: String, val id: Long, val protocol: Int)

    private class FragmentGroup(val createdAt: Long) {
        // offset-in-bytes -> fragment payload bytes
        val pieces = sortedMapOf<Int, ByteArray>()
        var totalLen: Int? = null // known once the last fragment (MF=0) arrives
        var prefixBytes: ByteArray? = null // header(s) before the fragment payload, from the offset-0 fragment
        var protocol: Int = -1 // real transport protocol/next-header, learned from either header
    }

    private val groups = LinkedHashMap<FragKey, FragmentGroup>()

    /**
     * Feed one raw IP packet. Returns the packet unchanged (same reference — "identity-equal")
     * when it is not fragmented, buffers it and returns null while a fragment group is
     * incomplete, or returns the fully reassembled datagram once the group completes.
     */
    fun offer(packet: ByteArray, length: Int, version: Int): ByteArray? = when (version) {
        4 -> offerIpv4(packet, length)
        6 -> offerIpv6(packet, length)
        else -> packet
    }

    // ── IPv4 ─────────────────────────────────────────────────────────────

    private fun offerIpv4(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return packet
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (length < ihl) return packet

        val flagsFrag = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        val mf = (flagsFrag ushr 13) and 0x1 != 0
        val fragOffsetBytes = (flagsFrag and 0x1FFF) * 8

        if (!mf && fragOffsetBytes == 0) return packet // unfragmented — pass through untouched

        val id = (((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)).toLong()
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = IpAddressCodec.formatIpv4(packet, 12)
        val dstIp = IpAddressCodec.formatIpv4(packet, 16)
        val key = FragKey(srcIp, dstIp, id, protocol)

        val now = System.currentTimeMillis()
        pruneExpired(now)
        val group = groups.getOrPut(key) {
            if (groups.size >= MAX_TRACKED_FLOWS) evictOldest()
            FragmentGroup(now)
        }
        group.protocol = protocol
        if (fragOffsetBytes == 0) {
            group.prefixBytes = packet.copyOfRange(0, ihl)
        }
        val data = packet.copyOfRange(ihl, length)
        group.pieces[fragOffsetBytes] = data
        if (!mf) group.totalLen = fragOffsetBytes + data.size

        val reassembledPayload = tryComplete(group) ?: return null
        groups.remove(key)
        return buildIpv4Datagram(group.prefixBytes ?: return null, reassembledPayload)
    }

    private fun buildIpv4Datagram(originalHeader: ByteArray, payload: ByteArray): ByteArray {
        val header = originalHeader.copyOf()
        val totalLen = header.size + payload.size
        header[2] = (totalLen ushr 8).toByte()
        header[3] = totalLen.toByte()
        header[6] = 0 // clear flags/fragOffset high byte — MF=0, offset=0
        header[7] = 0
        header[10] = 0 // checksum placeholder
        header[11] = 0
        val checksum = IpPacketBuilder.internetChecksum(header, 0, header.size)
        header[10] = (checksum ushr 8).toByte()
        header[11] = checksum.toByte()
        return header + payload
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────

    private fun offerIpv6(packet: ByteArray, length: Int): ByteArray? {
        if (length < 40) return packet
        val walk = walkExtensionHeadersInternal(packet, length) ?: return packet
        val (fragHeaderOffset, finalNextHeader, nextHeaderFieldPos) = walk
        if (finalNextHeader != 44) return packet // no fragment header in the chain — unfragmented

        if (length < fragHeaderOffset + 8) return packet // malformed; nothing sane to buffer
        val realProtocol = packet[fragHeaderOffset].toInt() and 0xFF
        val fragOffsetAndFlags = ((packet[fragHeaderOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[fragHeaderOffset + 3].toInt() and 0xFF)
        val mf = (fragOffsetAndFlags and 0x1) != 0
        val fragOffsetBytes = (fragOffsetAndFlags and 0xFFF8)
        val id = readU32(packet, fragHeaderOffset + 4)

        val srcIp = IpAddressCodec.formatIpv6(packet, 8)
        val dstIp = IpAddressCodec.formatIpv6(packet, 24)
        val key = FragKey(srcIp, dstIp, id, realProtocol)

        val now = System.currentTimeMillis()
        pruneExpired(now)
        val group = groups.getOrPut(key) {
            if (groups.size >= MAX_TRACKED_FLOWS) evictOldest()
            FragmentGroup(now)
        }
        group.protocol = realProtocol
        if (fragOffsetBytes == 0) {
            // Prefix = everything before the fragment header, with its "next header" field
            // patched to point straight at the real transport protocol (the fragment header
            // itself is removed once reassembly completes).
            val prefix = packet.copyOfRange(0, fragHeaderOffset)
            prefix[nextHeaderFieldPos] = realProtocol.toByte()
            group.prefixBytes = prefix
        }
        val data = packet.copyOfRange(fragHeaderOffset + 8, length)
        group.pieces[fragOffsetBytes] = data
        if (!mf) group.totalLen = fragOffsetBytes + data.size

        val reassembledPayload = tryComplete(group) ?: return null
        groups.remove(key)
        return buildIpv6Datagram(group.prefixBytes ?: return null, reassembledPayload)
    }

    private fun buildIpv6Datagram(prefix: ByteArray, payload: ByteArray): ByteArray {
        val out = prefix.copyOf() + payload
        val payloadLen = out.size - 40
        out[4] = (payloadLen ushr 8).toByte()
        out[5] = payloadLen.toByte()
        return out
    }

    /**
     * Walks hop-by-hop (0) / routing (43) / destination-options (60) extension headers starting
     * right after the fixed 40-byte IPv6 header, stopping safely on the first header type it
     * does not know how to walk through (any transport protocol, the fragment header, or a
     * genuinely unknown extension type). Public per the plan's spec: returns
     * (transport offset, final next-header) or null if the packet is too short to walk safely.
     */
    fun walkIpv6ExtensionHeaders(packet: ByteArray, length: Int): Pair<Int, Int>? {
        val result = walkExtensionHeadersInternal(packet, length) ?: return null
        return Pair(result.first, result.second)
    }

    /** Same as [walkIpv6ExtensionHeaders] but also returns the offset of the "next header"
     * field that currently declares [Triple.second] — needed to patch it during reassembly. */
    private fun walkExtensionHeadersInternal(packet: ByteArray, length: Int): Triple<Int, Int, Int>? {
        var offset = 40
        var nextHeaderFieldPos = 6
        var nextHeader = packet[6].toInt() and 0xFF
        while (true) {
            when (nextHeader) {
                0, 43, 60 -> {
                    if (offset + 2 > length) return null
                    val hdrExtLen = packet[offset + 1].toInt() and 0xFF
                    val headerLen = (hdrExtLen + 1) * 8
                    if (offset + headerLen > length) return null
                    nextHeaderFieldPos = offset
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += headerLen
                }
                else -> return Triple(offset, nextHeader, nextHeaderFieldPos)
            }
        }
    }

    // ── shared completion / bookkeeping ────────────────────────────────

    private fun tryComplete(group: FragmentGroup): ByteArray? {
        val totalLen = group.totalLen ?: return null
        var expected = 0
        val out = ByteArray(totalLen)
        for ((offset, data) in group.pieces) {
            if (offset != expected) return null // gap — still incomplete
            if (offset + data.size > totalLen) return null // malformed overrun
            System.arraycopy(data, 0, out, offset, data.size)
            expected += data.size
        }
        return if (expected == totalLen) out else null
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - flowTtlMs
        val stale = groups.entries.filter { it.value.createdAt < cutoff }
        for (entry in stale) {
            groups.remove(entry.key)
            droppedIncompleteCount++
        }
    }

    private fun evictOldest() {
        val oldest = groups.entries.minByOrNull { it.value.createdAt }
        oldest?.let {
            groups.remove(it.key)
            droppedIncompleteCount++
        }
    }

    private fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    companion object {
        const val MAX_TRACKED_FLOWS = 512
        const val FLOW_TTL_MS = 30_000L
    }
}
