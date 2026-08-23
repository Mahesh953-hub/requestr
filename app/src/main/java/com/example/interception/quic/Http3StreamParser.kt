package com.example.interception.quic

/**
 * HTTP/3 stream parser (RFC 9114) — demultiplexes QUIC streams into HTTP/3 frames and decodes
 * HEADERS field sections through a production [QpackDecoder] dynamic table.
 *
 * **Unidirectional streams (RFC 9114 §6.2):** a stream-type varint prefix selects the role —
 * Control (`0x00`), Push (`0x01`), QPACK encoder (`0x02` → [QpackDecoder.feedEncoderStream]), or
 * QPACK decoder (`0x03` → [QpackDecoder.consumeDecoderStream]). The control stream carries
 * SETTINGS (which sets the QPACK dynamic-table capacity via [QpackDecoder.DynamicTable.setCapacity]),
 * GOAWAY, and MAX_PUSH_ID.
 *
 * **Request streams (bidirectional, RFC 9114 §4.1):** carry DATA (`0x00`) and HEADERS (`0x01`)
 * frames. The direction the bytes arrived on tags HEADERS as a request (client→server,
 * `:method`/`:path`/`:authority`/`:scheme`) or a response (server→client, `:status`); DATA appends
 * to the matching buffer. Grease/reserved frames are skipped by their length field.
 *
 * **Incremental & blocked (RFC 9204 §2.2.1):** bytes may arrive across several STREAM deliveries,
 * so each stream buffers its un-consumed tail and parses only complete frames. A HEADERS field
 * section whose Required Insert Count exceeds the table's current insert count is **blocked**: its
 * bytes are retained and retried automatically once the QPACK encoder stream delivers the missing
 * inserts, at which point the decoded headers surface as a [Event.RequestHeaders] /
 * [Event.ResponseHeaders].
 *
 * One instance owns one connection's [QpackDecoder.DynamicTable] and per-stream state, so a single
 * [Http3StreamParser] is created per [com.example.interception.quic.QuicConnection].
 */
class Http3StreamParser {

    /** The per-connection QPACK dynamic table this parser grows via the encoder stream. */
    val table: QpackDecoder.DynamicTable = QpackDecoder.DynamicTable()

    /** Parsed HTTP/3 events surfaced to the caller (the PacketPump persist path). */
    sealed class Event {
        /** SETTINGS frame on a control stream; carries the negotiated QPACK capacity/blocked-streams. */
        data class Settings(val maxTableCapacity: Int?, val blockedStreams: Int?) : Event()
        /** Request HEADERS (client→server) decoded into pseudo + regular headers. */
        data class RequestHeaders(val streamId: Long, val headers: Map<String, String>) : Event()
        /** Response HEADERS (server→client); [status] is the `:status` pseudo-header value. */
        data class ResponseHeaders(val streamId: Long, val status: String?, val headers: Map<String, String>) : Event()
        /** Request DATA (client→server) body bytes. */
        data class RequestData(val streamId: Long, val bytes: ByteArray) : Event()
        /** Response DATA (server→client) body bytes. */
        data class ResponseData(val streamId: Long, val bytes: ByteArray) : Event()
        /** GOAWAY frame id (a stream/request id, RFC 9114 §7.2.6). */
        data class Goaway(val id: Long) : Event()
    }

    // Per-stream un-consumed byte tail (for incremental frame parsing across STREAM deliveries).
    private val buffers = mutableMapOf<Long, ByteArray>()

    // Unidirectional stream type once its prefix varint has been read; absent = bidirectional request stream.
    private val uniType = mutableMapOf<Long, Int>()

    // HEADERS field sections blocked on encoder-stream inserts, retained for retry with the direction
    // they arrived on, so an unblocked retry emits the correct request-vs-response event type.
    private val pendingHeaders = mutableMapOf<Long, PendingSection>()

    /** A HEADERS field section held back because its Required Insert Count is not yet satisfied. */
    private data class PendingSection(val section: ByteArray, val isOutbound: Boolean)

    // Last negotiated SETTINGS, surfaced once and cached for inspection.
    var maxTableCapacity: Int? = null
        private set
    var blockedStreams: Int? = null
        private set

    /**
     * Feeds [data] bytes reassembled for [streamId], arriving on a packet whose direction is
     * [isOutbound] (true = client→server). Returns the HTTP/3 events decoded from this delivery
     * (and any headers newly unblocked by an encoder-stream insert carried in [data]). A partial
     * frame at the tail is buffered for the next delivery; a blocked HEADERS is retained and retried
     * on a later encoder-stream feed. Never throws on malformed input — a bad frame stops parsing
     * that stream's buffer at the failure point.
     */
    fun feed(streamId: Long, data: ByteArray, isOutbound: Boolean): List<Event> {
        val events = mutableListOf<Event>()
        val isUni = (streamId and 0x02L) != 0L
        var buf = (buffers.remove(streamId) ?: ByteArray(0)) + data
        var pos = 0

        // Read the unidirectional stream-type prefix once (RFC 9114 §6.2).
        if (isUni && streamId !in uniType) {
            val (np, type) = readVarint(buf, 0) ?: run { buffers[streamId] = buf; return events }
            uniType[streamId] = type.toInt()
            pos = np
        }
        val type = uniType[streamId]

        when (type) {
            QPACK_ENCODER -> {
                // The QPACK encoder stream is a raw byte stream of encoder instructions. Feed the
                // (possibly already-buffered) bytes and keep the un-consumed tail — a truncated
                // final instruction — so the next delivery resumes mid-instruction-free.
                val (applied, consumed) = QpackDecoder.feedEncoderStreamWithOffset(table, buf, pos)
                if (applied > 0) {
                    // An encoder insert may unblock previously-blocked HEADERS on request streams.
                    events += retryBlockedHeaders()
                }
                if (consumed < buf.size) buffers[streamId] = buf.copyOfRange(consumed, buf.size)
            }
            QPACK_DECODER -> QpackDecoder.consumeDecoderStream(buf, pos) // passive observer: count/validate only
            CONTROL, null -> {
                // Control (uni) and request (bi, type==null) streams both carry HTTP/3 frames; the
                // direction tags request-stream frames as request vs response. Control-stream frames
                // are direction-agnostic (SETTINGS/GOAWAY/MAX_PUSH_ID).
                val (newPos, frameEvents) = parseH3Frames(streamId, buf, pos, isRequestStream = type == null, isOutbound = isOutbound)
                events += frameEvents
                pos = newPos
                if (pos < buf.size) buffers[streamId] = buf.copyOfRange(pos, buf.size)
            }
            else -> Unit // Push streams and reserved stream types: not decoded (passive observer).
        }
        return events
    }

    /** Retries every blocked HEADERS section against the now-larger table; returns any that unblock. */
    private fun retryBlockedHeaders(): List<Event> {
        val out = mutableListOf<Event>()
        val stillBlocked = mutableMapOf<Long, PendingSection>()
        for ((streamId, pending) in pendingHeaders) {
            val decoded = QpackDecoder.decodeFieldSection(table, pending.section)
            if (decoded == null) {
                // Still blocked: keep it for a later encoder-stream feed.
                stillBlocked[streamId] = pending
            } else {
                out += headersEvent(streamId, decoded, pending.isOutbound)
            }
        }
        // Replace the retained set: resolved sections are dropped, still-blocked ones stay.
        pendingHeaders.clear()
        pendingHeaders.putAll(stillBlocked)
        return out
    }

    /**
     * Parses as many complete HTTP/3 frames as [buf] holds starting at [pos], for a control stream
     * ([isRequestStream] false) or a request stream (true, tagged by [isOutbound]). Returns the
     * stopping offset and the decoded events; an incomplete final frame leaves the tail for buffering.
     */
    private fun parseH3Frames(
        streamId: Long,
        buf: ByteArray,
        pos: Int,
        isRequestStream: Boolean,
        isOutbound: Boolean,
    ): Pair<Int, List<Event>> {
        val events = mutableListOf<Event>()
        var p = pos
        while (p < buf.size) {
            val (afterType, frameType) = readVarint(buf, p) ?: return p to events
            val (afterLen, length) = readVarint(buf, afterType) ?: return p to events
            val payloadStart = afterLen
            val payloadEnd = afterLen + length.toInt()
            if (length > Int.MAX_VALUE.toLong() || payloadEnd > buf.size) return p to events // incomplete frame
            val payload = buf.copyOfRange(payloadStart, payloadEnd)
            when (frameType.toInt()) {
                FRAME_DATA -> {
                    if (isRequestStream) {
                        events += if (isOutbound) Event.RequestData(streamId, payload) else Event.ResponseData(streamId, payload)
                    }
                }
                FRAME_HEADERS -> {
                    val decoded = QpackDecoder.decodeFieldSection(table, payload)
                    if (decoded == null) {
                        // Blocked on a not-yet-received encoder insert: retain (with its direction)
                        // and retry on the next encoder-stream feed (RFC 9204 §2.2.1).
                        pendingHeaders[streamId] = PendingSection(payload, isOutbound)
                    } else {
                        events += headersEvent(streamId, decoded, isOutbound)
                    }
                }
                FRAME_SETTINGS -> {
                    val (cap, blocked) = parseSettings(payload)
                    if (cap != null) { maxTableCapacity = cap; table.setCapacity(cap) }
                    if (blocked != null) blockedStreams = blocked
                    events += Event.Settings(maxTableCapacity, blockedStreams)
                }
                FRAME_GOAWAY -> {
                    readVarint(payload, 0)?.let { events += Event.Goaway(it.second) }
                }
                else -> Unit // CANCEL_PUSH / PUSH_PROMISE / MAX_PUSH_ID / grease: skipped by length.
            }
            p = payloadEnd
        }
        return p to events
    }

    /** Builds a request or response HEADERS event from decoded [entries], partitioning pseudo-headers. */
    private fun headersEvent(streamId: Long, entries: List<QpackDecoder.HeaderEntry>, isOutbound: Boolean): Event {
        val map = linkedMapOf<String, String>()
        var status: String? = null
        for (e in entries) {
            if (e.name == ":status") status = e.value else map[e.name] = e.value
        }
        return if (isOutbound) {
            Event.RequestHeaders(streamId, map)
        } else {
            Event.ResponseHeaders(streamId, status, map)
        }
    }

    /** Parses a SETTINGS payload (pairs of varint id + varint value); returns (maxTableCapacity, blockedStreams). */
    private fun parseSettings(payload: ByteArray): Pair<Int?, Int?> {
        var cap: Int? = null
        var blocked: Int? = null
        var p = 0
        while (p < payload.size) {
            val (afterId, id) = readVarint(payload, p) ?: break
            val (afterVal, value) = readVarint(payload, afterId) ?: break
            when (id.toInt()) {
                SETTING_QPACK_MAX_TABLE_CAPACITY -> cap = value.toInt()
                SETTING_QPACK_BLOCKED_STREAMS -> blocked = value.toInt()
                else -> Unit // other settings ignored (grease / max_field_section_size / unknown)
            }
            p = afterVal
        }
        return cap to blocked
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
        // Unidirectional stream types (RFC 9114 §6.2).
        const val CONTROL = 0x00
        const val QPACK_ENCODER = 0x02
        const val QPACK_DECODER = 0x03

        // HTTP/3 frame types (RFC 9114 §7.2).
        const val FRAME_DATA = 0x00
        const val FRAME_HEADERS = 0x01
        const val FRAME_SETTINGS = 0x04
        const val FRAME_GOAWAY = 0x07

        // SETTINGS identifiers (RFC 9114 §7.2.4.1 / RFC 9204).
        const val SETTING_QPACK_MAX_TABLE_CAPACITY = 0x01
        const val SETTING_QPACK_BLOCKED_STREAMS = 0x07
    }
}
