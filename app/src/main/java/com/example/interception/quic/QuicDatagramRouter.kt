package com.example.interception.quic

import com.example.correlation.ConnectionKey

/**
 * Routes a UDP datagram's QUIC payload into one [RoutedPacket] per coalesced QUIC packet,
 * dispatching each by its Destination Connection ID through a [QuicCidTable] (RFC 9000 §5.8),
 * with a 5-tuple fallback for zero-length-DCID connections.
 *
 * **Coalesced packets (RFC 9000 §12.2):** a single UDP datagram may carry several QUIC packets
 * back-to-back (a server commonly coalesces its Initial and Handshake responses into one
 * datagram). Long headers carry an explicit Length field, so the next packet's start is known; a
 * short header (form bit 0), a Retry packet (no Length field, never coalesced), and a version-0
 * long header (Version Negotiation, no Length field) each consume the remainder of the datagram.
 *
 * **CID routing:** each packet's DCID is looked up in [cidTable]. A known DCID resolves to the
 * owning connection's routing key, so a Handshake/1-RTT packet carrying a DCID first seen on an
 * Initial reaches the same connection — the Initial→Handshake DCID change is tolerated, and a
 * Retry re-key is tolerated because the Retry packet's SCID (the client's next DCID) is registered
 * for the connection. An unknown non-empty DCID starts a new connection keyed by the 5-tuple
 * ([ConnectionKey.forQuic]); a zero-length DCID routes by a direction-normalized 4-tuple
 * ([normalizedTuple]) so both directions of a zero-length-DCID connection resolve to one routing
 * key. The connection's SCID is registered too (the server's Initial SCID becomes the client's
 * Handshake DCID), which is what makes the Initial→Handshake correlation work before any
 * NEW_CONNECTION_ID frame is observed.
 *
 * **First-Initial identity:** for Initial packets, [QuicParser.parseInitial] is still called so the
 * real SNI/ALPN is recovered (the AEAD-decrypted ClientHello for v1/v2, the heuristic scan
 * fallback otherwise) — preserving the identity recovery [com.example.interception.PacketPump] used
 * to do inline. The result is attached to the [RoutedPacket]; the router itself never persists.
 *
 * This class is stateless apart from the [cidTable] it mutates, so a single instance per
 * [com.example.interception.PacketPump] is shared across the IPv4 and IPv6 ingestion paths.
 */
class QuicDatagramRouter(private val cidTable: QuicCidTable) {

    /** Direction of a routed packet relative to the observer's client/server model. */
    enum class Direction { OUTBOUND, INBOUND }

    /**
     * One QUIC packet split out of a coalesced datagram, with its resolved routing key
     * (== the [com.example.data.local.QuicConnectionEntity] connectionId), packet type, CIDs, and
     * — for Initial packets — the recovered SNI/ALPN.
     *
     * @param connectionId routing key resolved via [cidTable] (a [ConnectionKey.forQuic] 5-tuple
     *   key); the persist path uses this directly as the Room row's primary key.
     * @param packetType the long-header packet type, or null for a short header (1-RTT).
     * @param payloadBytes this single packet's bytes within the datagram
     *   ([offset] .. [offset] + [length]).
     * @param sniHostname recovered SNI (Initial packets only); null for non-Initial or when
     *   unrecoverable.
     * @param decrypted true when [sniHostname]/[alpn] came from a real AEAD-decrypted ClientHello.
     * @param clientRandom the ClientHello's 32-byte `random` (Initial packets whose ClientHello was
     *   decrypted only) — the identity a TLS key log is keyed by, so the 1-RTT decrypt path can look
     *   up this connection's traffic secrets in
     *   [com.example.interception.tls.TlsKeyLogRegistry]. Null for every other packet.
     * @param pnOffset offset of the (still-protected) Packet Number field **within
     *   [payloadBytes]**, for long-header packets that carry a Length field (Initial / Handshake /
     *   0-RTT); `-1` for Retry, Version Negotiation, unknown versions, and short headers (whose PN
     *   offset depends on connection state the router does not hold — see
     *   [QuicCrypto.unprotectShortHeaderPacket]).
     */
    data class RoutedPacket(
        val connectionId: String,
        val direction: Direction,
        val dcid: ByteArray,
        val scid: ByteArray,
        val packetType: QuicCrypto.LongHeaderPacketType?,
        val version: Int,
        val payloadBytes: ByteArray,
        val offset: Int,
        val length: Int,
        val sniHostname: String?,
        val alpn: String?,
        val decrypted: Boolean,
        val clientRandom: ByteArray? = null,
        val pnOffset: Int = -1,
    )

    /**
     * Splits [datagramPayload] into its coalesced QUIC packets, routes each by DCID, classifies
     * each, and (for Initial packets) recovers SNI/ALPN. [src]/[sPort]/[dst]/[dPort] are the UDP
     * 5-tuple; [isIpv6] is metadata only — address family does not change DCID-based routing, so
     * an IPv6 datagram routes identically to an IPv4 one. Returns one [RoutedPacket] per parsed
     * packet, in datagram order; an unparseable remainder yields no further packets (never throws).
     */
    fun ingest(
        datagramPayload: ByteArray,
        src: String,
        sPort: Int,
        dst: String,
        dPort: Int,
        @Suppress("UNUSED_PARAMETER") isIpv6: Boolean = false,
    ): List<RoutedPacket> {
        if (datagramPayload.isEmpty()) return emptyList()
        val direction = directionFor(sPort, dPort)
        val tupleKey = ConnectionKey.forQuic(src, sPort, dst, dPort)
        val normTuple = normalizedTuple(src, sPort, dst, dPort)
        val out = mutableListOf<RoutedPacket>()
        var offset = 0
        while (offset < datagramPayload.size) {
            val firstByte = datagramPayload[offset].toInt() and 0xFF
            if ((firstByte and 0x80) == 0) {
                // Short header (1-RTT): no Length field — consumes the remainder of the datagram.
                val remaining = datagramPayload.size - offset
                routeShortHeader(datagramPayload, offset, remaining, direction, tupleKey, normTuple)
                    ?.let(out::add)
                break
            }
            val parsed = parseLongHeader(datagramPayload, offset, firstByte, direction, tupleKey, normTuple)
                ?: break
            out.add(parsed.routed)
            if (parsed.consumeRemainder) break
            offset = parsed.nextOffset
        }
        return out
    }

    /** OUTBOUND when the destination is 443 (client→server), INBOUND when the source is 443. */
    private fun directionFor(sPort: Int, dPort: Int): Direction =
        if (dPort == QUIC_PORT) Direction.OUTBOUND else if (sPort == QUIC_PORT) Direction.INBOUND else Direction.OUTBOUND

    /**
     * Parses one long-header packet starting at [offset]. Resolves its span from the Long Packet
     * Type: Initial/Handshake/0-RTT carry a Length field (Initial also a Token field) so the next
     * coalesced packet's start is known; Retry and version-0/unknown versions have no Length field
     * and consume the remainder. Routes by DCID, classifies via
     * [QuicCrypto.classifyLongHeaderPacketType] (version-aware v1/v2), and recovers SNI/ALPN for
     * Initial packets. Returns null on a truncated/unparseable header (the caller stops walking).
     */
    private fun parseLongHeader(
        data: ByteArray,
        offset: Int,
        firstByte: Int,
        direction: Direction,
        tupleKey: String,
        normTuple: String,
    ): LongHeaderParse? {
        // first byte(1) + version(4) + DCID-len(1) is the minimum to begin.
        if (data.size - offset < 6) return null
        val version = readVersion(data, offset + 1)

        var pos = offset + 5
        if (pos >= data.size) return null
        val dcidLen = data[pos].toInt() and 0xFF
        pos += 1
        if (pos + dcidLen > data.size) return null
        val dcid = data.copyOfRange(pos, pos + dcidLen)
        pos += dcidLen

        if (pos >= data.size) return null
        val scidLen = data[pos].toInt() and 0xFF
        pos += 1
        if (pos + scidLen > data.size) return null
        val scid = data.copyOfRange(pos, pos + scidLen)
        pos += scidLen

        // Version 0 = Version Negotiation; its type bits are not a real QUIC packet type, so map to
        // null (and it has no Length field). Unknown versions (gQUIC) also classify to null.
        val packetType = if (version == 0) null else QuicCrypto.classifyLongHeaderPacketType(version, firstByte)

        var packetEnd: Int
        var consumeRemainder = false
        // Offset of the Packet Number field within the datagram, for the levels that carry a Length
        // field; stays -1 for Retry / Version Negotiation / unknown versions (no Length field).
        var pnOffsetAbs = -1
        when (packetType) {
            null, QuicCrypto.LongHeaderPacketType.RETRY -> {
                // No Length field: Version Negotiation, Retry, or an unrecognized version — consume
                // the remainder of the datagram (Retry is never coalesced; the others can't be
                // walked reliably without a Length field).
                packetEnd = data.size
                consumeRemainder = true
            }
            QuicCrypto.LongHeaderPacketType.INITIAL -> {
                // Initial: Token Length varint + token, then the Length varint spanning PN + payload.
                val tokenVar = readVarint(data, pos) ?: return null
                val (tokenLen, tokenSize) = tokenVar
                val afterToken = pos + tokenSize + tokenLen.toInt()
                if (afterToken < 0 || afterToken > data.size) return null
                val lenVar = readVarint(data, afterToken) ?: return null
                val (lenVal, lenSize) = lenVar
                val pnOffset = afterToken + lenSize
                pnOffsetAbs = pnOffset
                val end = pnOffset + lenVal.toInt()
                if (end < pnOffset || end > data.size) {
                    // Truncated payload / coalesced boundary mismatch — consume the remainder.
                    packetEnd = data.size
                    consumeRemainder = true
                } else {
                    packetEnd = end
                }
            }
            else -> {
                // Handshake / 0-RTT: Length varint directly (no token).
                val lenVar = readVarint(data, pos) ?: return null
                val (lenVal, lenSize) = lenVar
                val pnOffset = pos + lenSize
                pnOffsetAbs = pnOffset
                val end = pnOffset + lenVal.toInt()
                if (end < pnOffset || end > data.size) {
                    packetEnd = data.size
                    consumeRemainder = true
                } else {
                    packetEnd = end
                }
            }
        }

        val length = packetEnd - offset
        val payloadBytes = data.copyOfRange(offset, packetEnd)

        var sni: String? = null
        var alpn: String? = null
        var decrypted = false
        var clientRandom: ByteArray? = null
        if (packetType == QuicCrypto.LongHeaderPacketType.INITIAL) {
            val initial = QuicParser.parseInitial(payloadBytes)
            if (initial != null) {
                sni = initial.sniHostname
                alpn = initial.alpn
                decrypted = initial.decrypted
                clientRandom = initial.clientRandom
            }
        }

        val connectionId = route(dcid, scid, tupleKey, normTuple, trustDcid = packetType != null)

        val routed = RoutedPacket(
            connectionId = connectionId,
            direction = direction,
            dcid = dcid,
            scid = scid,
            packetType = packetType,
            version = version,
            payloadBytes = payloadBytes,
            offset = offset,
            length = length,
            sniHostname = sni,
            alpn = alpn,
            decrypted = decrypted,
            clientRandom = clientRandom,
            // Re-based to the packet's own start so the decrypt path can address [payloadBytes]
            // directly, regardless of where in a coalesced datagram this packet began.
            pnOffset = if (pnOffsetAbs >= 0) pnOffsetAbs - offset else -1,
        )
        return LongHeaderParse(routed, packetEnd, consumeRemainder)
    }

    /**
     * Routes a short-header (1-RTT) packet that consumes the datagram remainder. The DCID length is
     * connection-state-dependent and not carried in a short header, so without per-connection DCID
     * length tracking the DCID cannot be extracted here; routing falls back to the normalized
     * 4-tuple (registered for the connection when its Initial was seen). [packetType] is null
     * (short header); 1-RTT decryption is a separate, key-log-sourced path.
     */
    private fun routeShortHeader(
        data: ByteArray,
        offset: Int,
        remaining: Int,
        direction: Direction,
        tupleKey: String,
        normTuple: String,
    ): RoutedPacket {
        val payloadBytes = data.copyOfRange(offset, offset + remaining)
        val connectionId = route(ByteArray(0), ByteArray(0), tupleKey, normTuple, trustDcid = false)
        return RoutedPacket(
            connectionId = connectionId,
            direction = direction,
            dcid = ByteArray(0),
            scid = ByteArray(0),
            packetType = null,
            version = 0,
            payloadBytes = payloadBytes,
            offset = offset,
            length = remaining,
            sniHostname = null,
            alpn = null,
            decrypted = false,
        )
    }

    /**
     * Resolves the routing key for a packet carrying [dcid]/[scid]. When [trustDcid] is true and
     * [dcid] is non-empty, an existing CID resolves to its connection; an unknown DCID starts a new
     * connection keyed by the 5-tuple [tupleKey]. Otherwise (zero-length DCID, or an untrusted
     * DCID from an unrecognized version / short header) routing falls back to the normalized
     * 4-tuple [normTuple]. The DCID and SCID are registered for the resolved connection so future
     * packets in either direction route here (the SCID registration is what tolerates the
     * Initial→Handshake DCID change and the Retry re-key); the normalized tuple is always
     * registered so a zero-length-DCID packet from the opposite direction reaches the connection.
     */
    private fun route(dcid: ByteArray, scid: ByteArray, tupleKey: String, normTuple: String, trustDcid: Boolean): String {
        val connectionId = if (trustDcid && dcid.isNotEmpty()) {
            cidTable.lookup(dcid) ?: tupleKey
        } else {
            cidTable.lookupByTuple(normTuple) ?: tupleKey
        }
        if (trustDcid) {
            if (dcid.isNotEmpty()) cidTable.addCid(connectionId, dcid)
            if (scid.isNotEmpty()) cidTable.addCid(connectionId, scid)
        }
        cidTable.registerZeroLengthConnection(normTuple, connectionId)
        return connectionId
    }

    private fun readVersion(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    /** QUIC variable-length integer (RFC 9000 §16): (value, bytesConsumed) or null if out of bounds. */
    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset < 0 || offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        val len = when (first shr 6) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; else -> return null
        }
        if (offset + len > data.size) return null
        var value = (first and 0x3f).toLong()
        for (i in 1 until len) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value to len
    }

    /**
     * Direction-normalized 4-tuple key: the two `ip:port` endpoints sorted lexicographically, so a
     * packet and its reply (reversed 5-tuple) produce the same key. Used as the zero-length-DCID
     * fallback so both directions of one connection resolve to one routing key.
     */
    private fun normalizedTuple(src: String, sPort: Int, dst: String, dPort: Int): String {
        val a = "$src:$sPort"
        val b = "$dst:$dPort"
        return if (a <= b) "$a|$b" else "$b|$a"
    }

    /** Result of [parseLongHeader]: the routed packet, the next coalesced offset, and whether the remainder was consumed. */
    private data class LongHeaderParse(val routed: RoutedPacket, val nextOffset: Int, val consumeRemainder: Boolean)

    private companion object {
        const val QUIC_PORT = 443
    }
}
