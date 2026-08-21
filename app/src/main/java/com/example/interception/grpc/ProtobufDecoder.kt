package com.example.interception.grpc

/**
 * Protobuf wire-format decoder (encoding described in
 * https://protobuf.dev/programming-guides/encoding/).
 *
 * gRPC DATA frames carry length-prefixed protobuf messages (see
 * [GrpcDetector]); this decoder turns one raw message payload into a
 * field tree without needing the .proto descriptors:
 *
 * - field tags (varint: field number << 3 | wire type)
 * - wire type 0: varints (int32/int64/uint32/uint64/sint/bool/enum)
 * - wire type 1: fixed64 (fixed64/double)
 * - wire type 2: length-delimited (string/bytes/embedded message/packed)
 * - wire type 5: fixed32 (fixed32/float)
 *
 * Wire types 3/4 (deprecated groups) are surfaced as [ProtoValue.Group]
 * for completeness but are not expected from gRPC (proto3 forbids them).
 *
 * Since descriptors are unavailable on the wire, field names are unknown;
 * output resembles `protoc --decode_raw`. A length-delimited field is
 * classified as text (valid UTF-8, printable), nested message (re-parses
 * cleanly), packed varints (parses as a run of varints), or raw bytes,
 * in that order — see [classifyLengthDelimited].
 */
object ProtobufDecoder {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_START_GROUP = 3
    const val WIRE_END_GROUP = 4
    const val WIRE_FIXED32 = 5

    /** Deepest nesting the nested-message heuristic will attempt. */
    private const val MAX_DEPTH = 8

    /** Cap on rendered hex so huge blobs don't flood the capture log. */
    private const val MAX_RENDER_BYTES = 4096

    sealed class ProtoValue {
        /** Wire type 0. Raw varint value (sign interpretation needs the schema). */
        data class Varint(val value: Long) : ProtoValue()
        /** Wire type 5. Unsigned 32-bit value held in a Long. */
        data class Fixed32(val value: Long) : ProtoValue()
        /** Wire type 1. Raw 64-bit value (double bits recoverable via [Double.fromBits]). */
        data class Fixed64(val value: Long) : ProtoValue()
        /** Wire type 2 classified as UTF-8 text. */
        data class Text(val value: String) : ProtoValue()
        /** Wire type 2 that re-parsed as an embedded message. */
        data class Nested(val fields: List<ProtoField>) : ProtoValue()
        /** Wire type 2 that looks like a packed repeated scalar field. */
        data class PackedVarints(val values: List<Long>) : ProtoValue()
        /** Wire type 2 with no better classification — raw bytes. */
        data class Bytes(val data: ByteArray) : ProtoValue() {
            override fun equals(other: Any?) = this === other || (other is Bytes && data.contentEquals(other.data))
            override fun hashCode() = data.contentHashCode()
        }
        /** Wire types 3/4 (deprecated groups) — kept opaque. */
        data class Group(val end: Boolean) : ProtoValue()
    }

    data class ProtoField(val number: Int, val wireType: Int, val value: ProtoValue)

    /**
     * Read a base-128 varint starting at [offset].
     * @return value to next offset, or null when the buffer ends
     *         mid-varint or the varint exceeds 10 bytes (invalid).
     */
    fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < bytes.size && shift < 64) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null // truncated or >10 bytes — not a valid varint here
    }

    /**
     * Decode [bytes] as one protobuf message.
     * @return the field list, or null when [bytes] does not parse cleanly
     *         to the last byte (used by the nested-message heuristic).
     */
    fun decode(bytes: ByteArray, depth: Int = 0): List<ProtoField>? {
        if (bytes.isEmpty()) return emptyList()
        val fields = mutableListOf<ProtoField>()
        var pos = 0
        while (pos < bytes.size) {
            val tag = readVarint(bytes, pos) ?: return null
            pos = tag.second
            val number = (tag.first ushr 3).toInt()
            val wireType = (tag.first and 0x7).toInt()
            // field number 0 is illegal; absurdly large numbers indicate misparsing
            if (number <= 0 || number > 0x1FFFFFFF) return null

            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(bytes, pos) ?: return null
                    pos = v.second
                    fields += ProtoField(number, wireType, ProtoValue.Varint(v.first))
                }
                WIRE_FIXED64 -> {
                    if (pos + 8 > bytes.size) return null
                    var v = 0L
                    for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    fields += ProtoField(number, wireType, ProtoValue.Fixed64(v))
                }
                WIRE_LENGTH_DELIMITED -> {
                    val len = readVarint(bytes, pos) ?: return null
                    pos = len.second
                    if (len.first < 0 || len.first > Int.MAX_VALUE || pos + len.first.toInt() > bytes.size) return null
                    val payload = bytes.copyOfRange(pos, pos + len.first.toInt())
                    pos += len.first.toInt()
                    fields += ProtoField(number, wireType, classifyLengthDelimited(payload, depth))
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > bytes.size) return null
                    var v = 0L
                    for (i in 0 until 4) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 4
                    fields += ProtoField(number, wireType, ProtoValue.Fixed32(v))
                }
                WIRE_START_GROUP, WIRE_END_GROUP -> {
                    // Groups are deprecated and illegal in proto3; record and stop
                    // trying to be clever — callers mainly need the fields above.
                    fields += ProtoField(number, wireType, ProtoValue.Group(end = wireType == WIRE_END_GROUP))
                    if (wireType == WIRE_END_GROUP) return fields
                }
                else -> return null // 6/7 are invalid wire types
            }
        }
        return fields
    }

    /** True when [bytes] parses completely as a protobuf message. */
    fun looksLikeMessage(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && (decode(bytes, depth = MAX_DEPTH)?.isNotEmpty() == true)

    /**
     * Classify a length-delimited payload: printable UTF-8 text, embedded
     * message, packed varint run, or opaque bytes — in that order.
     */
    private fun classifyLengthDelimited(payload: ByteArray, depth: Int): ProtoValue {
        if (payload.isEmpty()) return ProtoValue.Text("")
        if (isPrintableUtf8(payload)) return ProtoValue.Text(String(payload, Charsets.UTF_8))
        if (depth < MAX_DEPTH) {
            decode(payload, depth + 1)?.let { nested ->
                if (nested.isNotEmpty()) return ProtoValue.Nested(nested)
            }
            decodePackedVarints(payload)?.let { return ProtoValue.PackedVarints(it) }
        }
        return ProtoValue.Bytes(payload)
    }

    /** Packed repeated varint fields: whole payload is a run of valid varints (>= 2 values). */
    private fun decodePackedVarints(payload: ByteArray): List<Long>? {
        val values = mutableListOf<Long>()
        var pos = 0
        while (pos < payload.size) {
            val v = readVarint(payload, pos) ?: return null
            pos = v.second
            values += v.first
        }
        return if (values.size >= 2) values else null
    }

    /** Strict UTF-8 + printable check (allows \t \r \n; rejects other control bytes). */
    private fun isPrintableUtf8(bytes: ByteArray): Boolean {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        // String() with UTF_8 replaces malformed input with U+FFFD instead of throwing
        if (text.contains('\uFFFD')) return false
        return text.all { it == '\t' || it == '\n' || it == '\r' || (it >= ' ' && it != '\u007F') }
    }

    /** Render a decoded field tree in `protoc --decode_raw` style. */
    fun toDebugString(fields: List<ProtoField>, indent: String = ""): String {
        val sb = StringBuilder()
        for (f in fields) {
            when (val v = f.value) {
                is ProtoValue.Varint -> sb.append(indent).append(f.number).append(": ").append(v.value).append('\n')
                is ProtoValue.Fixed32 -> sb.append(indent).append(f.number).append(": 0x").append("%08x".format(v.value)).append('\n')
                is ProtoValue.Fixed64 -> sb.append(indent).append(f.number).append(": 0x").append("%016x".format(v.value)).append('\n')
                is ProtoValue.Text -> sb.append(indent).append(f.number).append(": \"").append(escape(v.value)).append("\"\n")
                is ProtoValue.Nested -> {
                    sb.append(indent).append(f.number).append(" {\n")
                    sb.append(toDebugString(v.fields, "$indent  "))
                    sb.append(indent).append("}\n")
                }
                is ProtoValue.PackedVarints -> sb.append(indent).append(f.number).append(": [")
                    .append(v.values.joinToString(", ")).append("]\n")
                is ProtoValue.Bytes -> sb.append(indent).append(f.number).append(": ")
                    .append(toHex(v.data, MAX_RENDER_BYTES)).append('\n')
                is ProtoValue.Group -> sb.append(indent).append(f.number)
                    .append(if (v.end) " } // group end\n" else " { // group start\n")
            }
        }
        return sb.toString()
    }

    /** Decode a message and render it, or null when [bytes] is not one message. */
    fun decodeToString(bytes: ByteArray): String? =
        decode(bytes)?.let { toDebugString(it).trimEnd() }

    /** Compact hex string ("0aff12..."), capped at [maxBytes] with an ellipsis marker. */
    fun toHex(bytes: ByteArray, maxBytes: Int = MAX_RENDER_BYTES): String {
        val shown = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(shown * 2 + 16)
        for (i in 0 until shown) sb.append("%02x".format(bytes[i]))
        if (bytes.size > shown) sb.append("…(+").append(bytes.size - shown).append("B)")
        return sb.toString()
    }

    /** Spaced hex dump ("0a ff 12 ...") for persisted request/response bodies. */
    fun hexDump(bytes: ByteArray, maxBytes: Int = MAX_RENDER_BYTES): String {
        val shown = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(shown * 3 + 16)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            sb.append("%02x".format(bytes[i]))
        }
        if (bytes.size > shown) sb.append(" …(+").append(bytes.size - shown).append("B)")
        return sb.toString()
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
