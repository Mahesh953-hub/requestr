package com.example.interception.http

import java.io.ByteArrayOutputStream

/**
 * S3 task 57 (D7 / gate G27): RFC 7541 HPACK encoder for the two legs the terminating endpoint
 * writes -- the response side of the client-facing connection and the request side of the
 * upstream connection. One instance per connection direction, mirroring
 * [Http2Parser.HpackDecoder].
 *
 * Strategy (close to nghttp2's defaults, which is what the decoder side of gate G11 was proven
 * against):
 * - exact `name: value` match in the static or dynamic table -> indexed representation;
 * - otherwise literal **with incremental indexing**, referencing the name by index when it is
 *   known, so repeated fields (`user-agent`, `cookie`, custom headers) shrink on later blocks;
 * - `authorization`, `proxy-authorization` and short `cookie` values are sent **never indexed**
 *   (RFC 7541 §7.1.3 -- they must not end up in an intermediary's dynamic table);
 * - a field larger than the whole dynamic table is sent **without indexing** (it could never be
 *   stored anyway);
 * - strings use Huffman coding whenever that is shorter than the raw octets.
 *
 * Dynamic table size: the encoder starts at the RFC default (4096) and follows
 * `SETTINGS_HEADER_TABLE_SIZE` from the peer via [setMaxHeaderTableSize]. Per RFC 7541 §4.2 a
 * change is signalled with a dynamic table size update at the very start of the next block (a
 * shrink below the current size evicts immediately so the two tables never disagree).
 */
internal class HpackEncoder(initialMaxTableSize: Int = Http2Parser.HpackDecoder.DEFAULT_TABLE_SIZE) {

    /** Most recent entry first; HPACK index 62 == element 0. */
    private val dynamic = ArrayDeque<Pair<String, String>>()
    private var dynamicSize = 0
    private var maxDynamicSize = initialMaxTableSize

    /** Pending size update to emit at the start of the next block (RFC 7541 §4.2); -1 = none. */
    private var pendingSizeUpdate = -1

    /** Current dynamic table size in RFC 7541 §4.1 octets. */
    val size: Int get() = dynamicSize

    /** Number of dynamic entries currently held. */
    val dynamicCount: Int get() = dynamic.size

    /** Peer's acknowledged `SETTINGS_HEADER_TABLE_SIZE`; a change is signalled in the next block. */
    fun setMaxHeaderTableSize(settingsValue: Int) {
        val target = settingsValue.coerceAtLeast(0)
        if (target == maxDynamicSize && pendingSizeUpdate < 0) return
        pendingSizeUpdate = target
        if (target < maxDynamicSize) {
            maxDynamicSize = target
            evict()
        } else {
            maxDynamicSize = target
        }
    }

    /**
     * Encode one complete header block. Field names are lower-cased on the way out (RFC 9113
     * §8.2: field names MUST be lower case) -- callers keep their original casing for the
     * persisted rows; the wire form is always lower case.
     */
    fun encode(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream(64 + entries.size * 16)
        if (pendingSizeUpdate >= 0) {
            encodeInteger(out, pendingSizeUpdate, 5, 0x20)
            pendingSizeUpdate = -1
        }
        for ((rawName, value) in entries) {
            val name = rawName.lowercase()
            val exact = indexOfExact(name, value)
            if (exact > 0) {
                encodeInteger(out, exact, 7, 0x80)
                continue
            }
            val nameIndex = indexOfName(name)
            val entrySize = name.length + value.length + 32
            when {
                neverIndex(name, value) -> {
                    // Literal never indexed (0001xxxx).
                    encodeInteger(out, nameIndex.coerceAtLeast(0), 4, 0x10)
                    if (nameIndex <= 0) encodeString(out, name)
                    encodeString(out, value)
                }
                entrySize > maxDynamicSize -> {
                    // Literal without indexing (0000xxxx) -- would never fit the table.
                    encodeInteger(out, nameIndex.coerceAtLeast(0), 4, 0x00)
                    if (nameIndex <= 0) encodeString(out, name)
                    encodeString(out, value)
                }
                else -> {
                    // Literal with incremental indexing (01xxxxxx).
                    encodeInteger(out, nameIndex.coerceAtLeast(0), 6, 0x40)
                    if (nameIndex <= 0) encodeString(out, name)
                    encodeString(out, value)
                    insert(name, value)
                }
            }
        }
        return out.toByteArray()
    }

    private fun neverIndex(name: String, value: String): Boolean = when (name) {
        "authorization", "proxy-authorization" -> true
        "cookie" -> value.length < 20
        else -> false
    }

    private fun indexOfExact(name: String, value: String): Int {
        val static = Http2Parser.STATIC_TABLE
        for (i in static.indices) if (static[i].first == name && static[i].second == value) return i + 1
        var i = 0
        for ((n, v) in dynamic) {
            if (n == name && v == value) return Http2Parser.STATIC_TABLE_SIZE + 1 + i
            i++
        }
        return -1
    }

    private fun indexOfName(name: String): Int {
        val static = Http2Parser.STATIC_TABLE
        for (i in static.indices) if (static[i].first == name) return i + 1
        var i = 0
        for ((n, _) in dynamic) {
            if (n == name) return Http2Parser.STATIC_TABLE_SIZE + 1 + i
            i++
        }
        return -1
    }

    private fun insert(name: String, value: String) {
        val entrySize = name.length + value.length + 32
        if (entrySize > maxDynamicSize) {
            // RFC 7541 §4.4: an entry larger than the table empties it.
            dynamic.clear()
            dynamicSize = 0
            return
        }
        dynamic.addFirst(name to value)
        dynamicSize += entrySize
        evict()
    }

    private fun evict() {
        while (dynamicSize > maxDynamicSize && dynamic.isNotEmpty()) {
            val (n, v) = dynamic.removeLast()
            dynamicSize -= n.length + v.length + 32
        }
    }

    private fun encodeString(out: ByteArrayOutputStream, s: String) {
        val raw = s.toByteArray(Charsets.UTF_8)
        val huffmanLength = HpackHuffman.encodedLength(raw)
        if (huffmanLength < raw.size) {
            encodeInteger(out, huffmanLength, 7, 0x80)
            out.write(HpackHuffman.encode(raw))
        } else {
            encodeInteger(out, raw.size, 7, 0x00)
            out.write(raw)
        }
    }

    companion object {
        /** RFC 7541 §5.1 integer representation with an N-bit prefix under [prefixMask]'s pattern bits. */
        fun encodeInteger(out: ByteArrayOutputStream, value: Int, prefixBits: Int, prefixMask: Int) {
            val max = (1 shl prefixBits) - 1
            if (value < max) {
                out.write(prefixMask or value)
                return
            }
            out.write(prefixMask or max)
            var rest = value - max
            while (rest >= 128) {
                out.write((rest and 0x7F) or 0x80)
                rest = rest ushr 7
            }
            out.write(rest)
        }
    }
}
