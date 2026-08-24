package com.example.interception.quic

/**
 * Walks the frame stream inside a decrypted QUIC packet payload (RFC 9000 §19) and:
 *
 * - **CID lifecycle (Gap 5):** applies [QuicCidTable.addCid] for every `NEW_CONNECTION_ID`
 *   (0x18) frame, honoring its **Retire Prior To** field (retiring every active CID whose
 *   sequence number is below the threshold), and applies [QuicCidTable.retireCid] for every
 *   `RETIRE_CONNECTION_ID` (0x19) frame — the latter keyed by sequence number, which the walker
 *   translates to the CID bytes it recorded when that sequence was issued. This is the call site
 *   that wires the previously-uncalled [QuicCidTable.retireCid].
 * - **Stream extraction:** surfaces every `STREAM` (0x08–0x0f) frame as [Frame.Stream] so the
 *   PacketPump 1-RTT path can feed reassembled bytes into [Http3StreamParser].
 * - **CRYPTO extraction:** surfaces every `CRYPTO` (0x06) frame as [Frame.Crypto] with its byte
 *   offset, so the caller can reassemble the encryption level's TLS handshake stream (a ServerHello
 *   or a server Certificate chain routinely spans several CRYPTO frames and several packets) and
 *   recover the negotiated cipher suite from the real ServerHello.
 * - **Close detection:** surfaces `CONNECTION_CLOSE` (0x1c / 0x1d) as [Frame.ConnectionClose] so
 *   the caller can drive the connection lifecycle ([com.example.interception.quic.QuicState]).
 *
 * **Frame delimiting:** QUIC frames are *not* uniformly length-prefixed. `PADDING` (0x00) and
 * `PING` (0x01) are single bytes with no length; `ACK`, `RESET_STREAM`, `NEW_CONNECTION_ID`,
 * `RETIRE_CONNECTION_ID`, `CONNECTION_CLOSE`, etc. are self-delimiting by their own field
 * structure; `CRYPTO`, `NEW_TOKEN`, length-tagged `STREAM`, and the reserved/grease frame types
 * (`0x1f*N+0x1e` / `0x1f*N+0x1f`, RFC 9000 §19.22) carry an explicit Length. The walker computes
 * each frame's end with per-type logic ([frameEnd]) so every standard 1-RTT frame is correctly
 * skipped or surfaced; a truncated final frame consumes the remainder and ends the walk. The
 * walker never throws on malformed input — an un-delimitable frame stops the walk cleanly.
 *
 * One instance is stateless apart from the per-connection sequence→CID map it maintains for
 * sequence-based retirement, so a single [QuicFrameWalker] is shared with the [QuicCidTable]
 * across a [com.example.interception.PacketPump].
 */
class QuicFrameWalker(private val cidTable: QuicCidTable) {

    /** A frame surfaced by [walk] for the caller (the PacketPump 1-RTT path). */
    sealed class Frame {
        /** A STREAM frame (RFC 9000 §19.8): bytes [data] at stream byte [offset], [fin] if the FIN bit was set. */
        data class Stream(val streamId: Long, val offset: Long, val fin: Boolean, val data: ByteArray) : Frame()
        /**
         * A CRYPTO frame (RFC 9000 §19.6): TLS handshake bytes [data] at crypto-stream byte [offset]
         * within this packet's encryption level. Each level (Initial/Handshake/1-RTT) has its own
         * independent crypto stream, so the caller must key its reassembly by level as well as
         * direction.
         */
        data class Crypto(val offset: Long, val data: ByteArray) : Frame()
        /** A NEW_CONNECTION_ID frame (RFC 9000 §19.15); already applied to [cidTable] when surfaced. */
        data class NewConnectionId(val sequence: Long, val retirePriorTo: Long, val cid: ByteArray, val statelessResetToken: ByteArray) : Frame()
        /** A RETIRE_CONNECTION_ID frame (RFC 9000 §19.16); already applied to [cidTable] when surfaced. */
        data class RetireConnectionId(val sequence: Long) : Frame()
        /** A CONNECTION_CLOSE frame (RFC 9000 §19.19); [frameType] is 0 for the 0x1d application variant. */
        data class ConnectionClose(val errorCode: Long, val frameType: Long, val reasonPhrase: String) : Frame()
    }

    /**
     * Per-connection sequence → CID bytes, recorded when a NEW_CONNECTION_ID is accepted so a
     * later RETIRE_CONNECTION_ID (keyed by sequence) and a Retire Prior To threshold can be
     * translated back to the CID bytes [cidTable] is keyed by.
     */
    private val seqToCid = mutableMapOf<String, MutableMap<Long, ByteArray>>()

    /**
     * Walks [payload] starting at [offset], applying CID-lifecycle frames to [cidTable] for
     * [routingKey] and returning every surfaced frame in order ([Frame.Stream],
     * [Frame.NewConnectionId], [Frame.RetireConnectionId], [Frame.ConnectionClose]). All other
     * frame types are delimited by [frameEnd] and skipped. Never throws; a truncated or
     * un-delimitable frame ends the walk.
     */
    fun walk(routingKey: String, payload: ByteArray, offset: Int = 0): List<Frame> {
        val out = mutableListOf<Frame>()
        val seqs = seqToCid.getOrPut(routingKey) { mutableMapOf() }
        var pos = offset
        while (pos < payload.size) {
            val typeRead = readVarint(payload, pos) ?: break
            val (afterType, type) = typeRead
            val end = frameEnd(type, payload, afterType)
            if (end < afterType) break // un-delimitable: stop cleanly
            val body = payload.copyOfRange(afterType, end)
            when (type.toInt()) {
                in STREAM_TYPES -> parseStream(type.toInt(), body)?.let(out::add)
                CRYPTO -> parseCrypto(body)?.let(out::add)
                NEW_CONNECTION_ID -> parseNewConnectionId(body)?.let { f ->
                    applyNewConnectionId(routingKey, seqs, f)
                    out.add(f)
                }
                RETIRE_CONNECTION_ID -> {
                    val seqRead = readVarint(body, 0)
                    if (seqRead != null) {
                        val seq = seqRead.second
                        seqs.remove(seq)?.let { cidTable.retireCid(it) }
                        out.add(Frame.RetireConnectionId(seq))
                    }
                }
                CONNECTION_CLOSE, CONNECTION_CLOSE_APP -> parseConnectionClose(type.toInt(), body)?.let(out::add)
                else -> Unit // PADDING/PING/ACK/MAX_*/BLOCKED/DATAGRAM/grease: delimited & skipped
            }
            pos = end
        }
        return out
    }

    /** Applies a NEW_CONNECTION_ID: honors Retire Prior To, then registers the new (bounded) CID. */
    private fun applyNewConnectionId(routingKey: String, seqs: MutableMap<Long, ByteArray>, f: Frame.NewConnectionId) {
        if (f.retirePriorTo > 0) {
            for (s in seqs.keys.filter { it < f.retirePriorTo }) {
                seqs.remove(s)?.let { cidTable.retireCid(it) }
            }
        }
        // Record sequence→CID only if the bounded table accepted it, so a rejected (over-bound) CID
        // is never later retired by a sequence it never truly held.
        if (cidTable.addCid(routingKey, f.cid)) seqs[f.sequence] = f.cid
    }

    /** Computes the offset just past the whole frame whose type varint ended at [afterType]. */
    private fun frameEnd(type: Long, payload: ByteArray, afterType: Int): Int = when (type.toInt()) {
        PADDING, PING -> afterType // single-byte frames: no body
        ACK, ACK_ECN -> skipAck(type.toInt(), payload, afterType)
        RESET_STREAM -> skipVarints(payload, afterType, 3)
        STOP_SENDING -> skipVarints(payload, afterType, 2)
        CRYPTO -> skipLengthPrefixed(payload, afterType, leadingVarints = 1) // offset (i) + length (i) + data
        NEW_TOKEN -> skipLengthPrefixed(payload, afterType, leadingVarints = 0) // length (i) + token
        in STREAM_TYPES -> streamEnd(type.toInt(), payload, afterType)
        MAX_DATA, DATA_BLOCKED -> skipVarints(payload, afterType, 1)
        MAX_STREAM_DATA, STREAM_DATA_BLOCKED -> skipVarints(payload, afterType, 2)
        MAX_STREAMS_BIDI, MAX_STREAMS_UNI, STREAMS_BLOCKED_BIDI, STREAMS_BLOCKED_UNI -> skipVarints(payload, afterType, 1)
        NEW_CONNECTION_ID -> newConnectionIdEnd(payload, afterType)
        RETIRE_CONNECTION_ID -> skipVarints(payload, afterType, 1)
        PATH_CHALLENGE, PATH_RESPONSE -> (afterType + 8).coerceAtMost(payload.size) // 8-byte data
        CONNECTION_CLOSE, CONNECTION_CLOSE_APP -> connectionCloseEnd(type.toInt(), payload, afterType)
        DATAGRAM -> payload.size // no Length: data extends to end of packet
        DATAGRAM_WITH_LENGTH -> skipLengthPrefixed(payload, afterType, leadingVarints = 0)
        else -> {
            // Reserved/grease frame types (0x1f*N+0x1e / 0x1f*N+0x1f) carry a Length (i) field
            // (RFC 9000 §19.22); treat any other unknown type the same and skip by that length.
            skipLengthPrefixed(payload, afterType, leadingVarints = 0)
        }
    }

    /** Reads [count] consecutive varints and returns the offset after the last one. */
    private fun skipVarints(payload: ByteArray, start: Int, count: Int): Int {
        var p = start
        repeat(count) {
            val r = readVarint(payload, p) ?: return p
            p = r.first
        }
        return p
    }

    /** Reads [leadingVarints] varints, then a Length varint, then skips that many bytes. */
    private fun skipLengthPrefixed(payload: ByteArray, start: Int, leadingVarints: Int): Int {
        var p = skipVarints(payload, start, leadingVarints)
        val lenRead = readVarint(payload, p) ?: return payload.size
        return (lenRead.first + lenRead.second.toInt()).coerceAtMost(payload.size)
    }

    /** Delimits an ACK frame (RFC 9000 §19.3); ACK-ECN (0x03) carries three trailing ECN counts. */
    private fun skipAck(type: Int, payload: ByteArray, start: Int): Int {
        val largest = readVarint(payload, start) ?: return start
        val delay = readVarint(payload, largest.first) ?: return start
        val rangeCount = readVarint(payload, delay.first) ?: return start
        val firstRange = readVarint(payload, rangeCount.first) ?: return start
        var p = firstRange.first
        repeat(rangeCount.second.toInt()) {
            val gap = readVarint(payload, p) ?: return start
            val range = readVarint(payload, gap.first) ?: return start
            p = range.first
        }
        if (type == ACK_ECN) {
            val ect0 = readVarint(payload, p) ?: return start
            val ect1 = readVarint(payload, ect0.first) ?: return start
            val ce = readVarint(payload, ect1.first) ?: return start
            p = ce.first
        }
        return p
    }

    /** Delimits a STREAM frame (RFC 9000 §19.8): optional Offset, optional Length, then data. */
    private fun streamEnd(type: Int, payload: ByteArray, afterType: Int): Int {
        val hasLen = type and LEN_BIT != 0
        val hasOff = type and OFF_BIT != 0
        val idRead = readVarint(payload, afterType) ?: return afterType
        var p = idRead.first
        if (hasOff) {
            val offRead = readVarint(payload, p) ?: return afterType
            p = offRead.first
        }
        return if (hasLen) {
            val lenRead = readVarint(payload, p) ?: return afterType
            (lenRead.first + lenRead.second.toInt()).coerceAtMost(payload.size)
        } else {
            payload.size // no Length: data extends to end of packet
        }
    }

    /** Parses a STREAM frame body into a [Frame.Stream]; null on a truncated frame. */
    private fun parseStream(type: Int, body: ByteArray): Frame.Stream? {
        val fin = type and FIN_BIT != 0
        val hasLen = type and LEN_BIT != 0
        val hasOff = type and OFF_BIT != 0
        val idRead = readVarint(body, 0) ?: return null
        var p = idRead.first
        var offset = 0L
        if (hasOff) {
            val offRead = readVarint(body, p) ?: return null
            offset = offRead.second
            p = offRead.first
        }
        val data = if (hasLen) {
            val lenRead = readVarint(body, p) ?: return null
            if (lenRead.first + lenRead.second.toInt() > body.size) return null
            body.copyOfRange(lenRead.first, lenRead.first + lenRead.second.toInt())
        } else {
            body.copyOfRange(p, body.size)
        }
        return Frame.Stream(idRead.second, offset, fin, data)
    }

    /** Parses a CRYPTO frame body (offset varint + length varint + data) into a [Frame.Crypto]; null if truncated. */
    private fun parseCrypto(body: ByteArray): Frame.Crypto? {
        val offRead = readVarint(body, 0) ?: return null
        val lenRead = readVarint(body, offRead.first) ?: return null
        val dataLen = lenRead.second.toInt()
        if (dataLen < 0 || lenRead.first + dataLen > body.size) return null
        return Frame.Crypto(offRead.second, body.copyOfRange(lenRead.first, lenRead.first + dataLen))
    }

    /** Delimits a NEW_CONNECTION_ID frame: sequence (i) + retire-prior-to (i) + CID-len (8) + CID + 16-byte token. */
    private fun newConnectionIdEnd(payload: ByteArray, start: Int): Int {
        val seq = readVarint(payload, start) ?: return start
        val rpt = readVarint(payload, seq.first) ?: return start
        if (rpt.first >= payload.size) return start
        val cidLen = payload[rpt.first].toInt() and 0xFF
        val end = rpt.first + 1 + cidLen + STATELESS_RESET_TOKEN_LEN
        return if (end > payload.size) payload.size else end
    }

    /** Parses a NEW_CONNECTION_ID frame body; null if truncated. */
    private fun parseNewConnectionId(body: ByteArray): Frame.NewConnectionId? {
        val seq = readVarint(body, 0) ?: return null
        val rpt = readVarint(body, seq.first) ?: return null
        if (rpt.first >= body.size) return null
        val cidLen = body[rpt.first].toInt() and 0xFF
        val cidStart = rpt.first + 1
        if (cidStart + cidLen + STATELESS_RESET_TOKEN_LEN > body.size) return null
        val cid = body.copyOfRange(cidStart, cidStart + cidLen)
        val token = body.copyOfRange(cidStart + cidLen, cidStart + cidLen + STATELESS_RESET_TOKEN_LEN)
        return Frame.NewConnectionId(seq.second, rpt.second, cid, token)
    }

    /** Delimits a CONNECTION_CLOSE frame (0x1c carries a Frame Type field; 0x1d does not). */
    private fun connectionCloseEnd(type: Int, payload: ByteArray, start: Int): Int {
        val ec = readVarint(payload, start) ?: return start
        var p = ec.first
        if (type == CONNECTION_CLOSE) {
            val ft = readVarint(payload, p) ?: return start
            p = ft.first
        }
        val reasonLen = readVarint(payload, p) ?: return start
        return (reasonLen.first + reasonLen.second.toInt()).coerceAtMost(payload.size)
    }

    /** Parses a CONNECTION_CLOSE frame body; null if truncated. */
    private fun parseConnectionClose(type: Int, body: ByteArray): Frame.ConnectionClose? {
        val ec = readVarint(body, 0) ?: return null
        var p = ec.first
        var frameType = 0L
        if (type == CONNECTION_CLOSE) {
            val ft = readVarint(body, p) ?: return null
            frameType = ft.second
            p = ft.first
        }
        val reasonLen = readVarint(body, p) ?: return null
        if (reasonLen.first + reasonLen.second.toInt() > body.size) return null
        val reason = String(body, reasonLen.first, reasonLen.second.toInt(), Charsets.UTF_8)
        return Frame.ConnectionClose(ec.second, frameType, reason)
    }

    /** QUIC variable-length integer (RFC 9000 §16): (offsetAfterValue, value) or null if out of bounds. */
    private fun readVarint(data: ByteArray, offset: Int): Pair<Int, Long>? {
        if (offset < 0 || offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        val len = when (first shr 6) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; else -> return null
        }
        if (offset + len > data.size) return null
        var value = (first and 0x3f).toLong()
        for (i in 1 until len) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return (offset + len) to value
    }

    private companion object {
        // QUIC frame types (RFC 9000 §19).
        const val PADDING = 0x00
        const val PING = 0x01
        const val ACK = 0x02
        const val ACK_ECN = 0x03
        const val RESET_STREAM = 0x04
        const val STOP_SENDING = 0x05
        const val CRYPTO = 0x06
        const val NEW_TOKEN = 0x07
        const val MAX_DATA = 0x10
        const val MAX_STREAM_DATA = 0x11
        const val MAX_STREAMS_BIDI = 0x12
        const val MAX_STREAMS_UNI = 0x13
        const val DATA_BLOCKED = 0x14
        const val STREAM_DATA_BLOCKED = 0x15
        const val STREAMS_BLOCKED_BIDI = 0x16
        const val STREAMS_BLOCKED_UNI = 0x17
        const val NEW_CONNECTION_ID = 0x18
        const val RETIRE_CONNECTION_ID = 0x19
        const val PATH_CHALLENGE = 0x1a
        const val PATH_RESPONSE = 0x1b
        const val CONNECTION_CLOSE = 0x1c
        const val CONNECTION_CLOSE_APP = 0x1d
        const val DATAGRAM = 0x30
        const val DATAGRAM_WITH_LENGTH = 0x31

        // STREAM frame (0x08..0x0f) low-bit flags (RFC 9000 §19.8).
        const val FIN_BIT = 0x01
        const val LEN_BIT = 0x02
        const val OFF_BIT = 0x04
        val STREAM_TYPES = 0x08..0x0f

        const val STATELESS_RESET_TOKEN_LEN = 16
    }
}
