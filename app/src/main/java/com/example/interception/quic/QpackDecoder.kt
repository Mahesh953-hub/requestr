package com.example.interception.quic

import com.example.interception.http.HpackHuffman

/**
 * QPACK decoder (RFC 9204) — header compression for HTTP/3.
 * Handles literal and indexed entries from the static table.
 *
 * v5.6 (P2): [decodeStr] now actually Huffman-decodes when the H bit is set, via
 * [HpackHuffman] — RFC 9204 §4.1.2 mandates reusing the RFC 7541 Appendix B table
 * unmodified, so QPACK shares the exact decode tree [com.example.interception.http.Http2Parser]
 * already built for HPACK rather than re-deriving it. Previously both the Huffman and
 * non-Huffman branches did a plain UTF-8 decode, silently corrupting any Huffman-coded
 * QPACK string (headers routinely are, since it is the default encoder mode).
 */
object QpackDecoder {

    // Static table entries (RFC 9204 §3.1)
    private val STATIC = listOf(
        ":authority" to "", ":path" to "/", "age" to "0",
        "content-disposition" to "", "content-length" to "0",
        "cookie" to "", "date" to "", "etag" to "",
        "if-modified-since" to "", "if-none-match" to "",
        "last-modified" to "", "link" to "",
        "location" to "", "referer" to "", "set-cookie" to "",
        ":method" to "CONNECT", ":method" to "DELETE", ":method" to "GET",
        ":method" to "HEAD", ":method" to "OPTIONS", ":method" to "POST",
        ":method" to "PUT", ":scheme" to "http", ":scheme" to "https",
        ":status" to "103", ":status" to "200", ":status" to "304",
        ":status" to "404", ":status" to "503", "accept" to "*/*",
        "accept" to "application/dns-message", "accept-encoding" to "gzip, deflate, br",
        "accept-ranges" to "bytes", "access-control-allow-headers" to "cache-control",
        "access-control-allow-headers" to "content-type",
        "access-control-allow-origin" to "*", "cache-control" to "max-age=0",
        "cache-control" to "max-age=2592000", "cache-control" to "max-age=604800",
        "cache-control" to "no-cache", "cache-control" to "no-store",
        "cache-control" to "public, max-age=31536000",
        "content-encoding" to "br", "content-encoding" to "gzip",
        "content-type" to "application/dns-message",
        "content-type" to "application/javascript",
        "content-type" to "application/json",
        "content-type" to "application/x-www-form-urlencoded",
        "content-type" to "image/gif", "content-type" to "image/jpeg",
        "content-type" to "image/png", "content-type" to "text/css",
        "content-type" to "text/html; charset=utf-8",
        "content-type" to "text/plain", "content-type" to "text/plain;charset=utf-8",
        "range" to "bytes=0-", "strict-transport-security" to "max-age=31536000",
        "strict-transport-security" to "max-age=31536000; includesubdomains",
        "strict-transport-security" to "max-age=31536000; includesubdomains; preload",
        "vary" to "accept-encoding", "vary" to "origin",
        "x-content-type-options" to "nosniff", "x-xss-protection" to "1; mode=block",
        ":status" to "100", ":status" to "204", ":status" to "206",
        ":status" to "302", ":status" to "400", ":status" to "403",
        ":status" to "421", ":status" to "425", ":status" to "500",
        "accept-language" to "", "access-control-allow-credentials" to "FALSE",
        "access-control-allow-credentials" to "TRUE",
        "access-control-allow-headers" to "*", "access-control-allow-methods" to "get",
        "access-control-allow-methods" to "get, post, options",
        "access-control-allow-methods" to "options",
        "access-control-expose-headers" to "content-length",
        "access-control-request-headers" to "content-type",
        "access-control-request-method" to "get", "access-control-request-method" to "post",
        "alt-svc" to "clear", "authorization" to "",
        "content-security-policy" to "script-src 'none';object-src 'none';base-uri 'none'",
        "early-data" to "1", "expect-ct" to "", "forwarded" to "",
        "if-range" to "", "origin" to "", "purpose" to "prefetch",
        "server" to "", "timing-allow-origin" to "*",
        "upgrade-insecure-requests" to "1", "user-agent" to "",
        "x-forwarded-for" to "", "x-frame-options" to "deny",
        "x-frame-options" to "sameorigin"
    )

    data class HeaderEntry(val name: String, val value: String)

    /**
     * Per-connection QPACK dynamic table (RFC 9204 §3.2): an insert-count-indexed table of
     * `[HeaderEntry]`s, bounded in *bytes* by [capacity] (each entry costs
     * `name.len + value.len + 32`, per RFC 9204 §3.2.1), with oldest entries evicted to stay within
     * capacity. Entries are stored oldest-first, so [entry] with absolute index 0 returns the
     * oldest still-present entry and absolute index `size-1` the newest — matching RFC 9204's
     * absolute-index convention (0 = first inserted, increasing toward the insertion point).
     *
     * [insertCount] is monotonic across evictions (RFC 9204 §3.2.1: the insert count never
     * decreases); [size] is the live entry count after eviction. [feedEncoderStream] is the
     * production mutator; [entry] is the lookup the field-section decoder uses to resolve
     * dynamic-indexed field lines.
     */
    class DynamicTable(initialCapacity: Int = 0) {
        private val entries = mutableListOf<HeaderEntry>()
        private var capacity: Int = initialCapacity.coerceAtLeast(0)
        var insertCount: Int = 0
            private set

        /** Number of live (non-evicted) entries. */
        fun size(): Int = entries.size

        /**
         * The entry at absolute insert index [absoluteIndex] (0 = first ever inserted, increasing
         * toward the insertion point), or null if that index was evicted or is out of range. RFC 9204
         * §3.2.1 addresses the table by absolute index; eviction removes the lowest absolute indices,
         * so the live entries occupy absolute indices `[insertCount - size, insertCount - 1]`. An
         * index below that range was evicted (referencing it is an encoder error — returned as null so
         * a field line silently resolves to nothing rather than crashing the capture).
         */
        fun entry(absoluteIndex: Int): HeaderEntry? {
            if (absoluteIndex < 0) return null
            val liveBase = insertCount - entries.size
            if (absoluteIndex < liveBase) return null // evicted
            val liveIdx = absoluteIndex - liveBase
            return entries.getOrNull(liveIdx)
        }

        /** Sets the dynamic table capacity in bytes, evicting oldest entries as needed. */
        fun setCapacity(cap: Int) {
            capacity = cap.coerceAtLeast(0)
            evict()
        }

        /** The current capacity in bytes. */
        fun capacity(): Int = capacity

        /** Appends [entry] at the insertion point (newest) and evicts if over capacity. */
        fun insert(entry: HeaderEntry) {
            entries.add(entry)
            insertCount++
            evict()
        }

        /** Re-inserts the entry at absolute index [absoluteIndex] (a Duplicate instruction); false if absent/evicted. */
        fun duplicate(absoluteIndex: Int): Boolean {
            val e = entry(absoluteIndex) ?: return false
            entries.add(e)
            insertCount++
            evict()
            return true
        }

        private fun entrySize(e: HeaderEntry): Int =
            e.name.toByteArray(Charsets.UTF_8).size + e.value.toByteArray(Charsets.UTF_8).size + 32

        private fun usedBytes(): Int = entries.sumOf { entrySize(it) }

        private fun evict() {
            while (entries.isNotEmpty() && usedBytes() > capacity) entries.removeAt(0)
        }
    }

    fun decode(payload: ByteArray, offset: Int = 0): Pair<Int, List<HeaderEntry>> {
        var pos = offset
        val entries = mutableListOf<HeaderEntry>()
        // Skip required insert count (2 bytes typically)
        if (pos + 2 <= payload.size) pos += 2
        while (pos < payload.size) {
            val firstByte = payload[pos].toInt() and 0xFF
            when {
                firstByte and 0x80 != 0 -> {
                    val (np, idx) = decodeInt(payload, pos, 7)
                    if (idx in STATIC.indices) entries.add(HeaderEntry(STATIC[idx].first, STATIC[idx].second))
                    pos = np
                }
                firstByte and 0x40 != 0 -> {
                    val (np, idx) = decodeInt(payload, pos, 6)
                    val name = if (idx in STATIC.indices) STATIC[idx].first else ""
                    pos = np
                    val (sp, value) = decodeStr(payload, pos)
                    entries.add(HeaderEntry(name, value))
                    pos = sp
                }
                else -> {
                    val (np, idx) = decodeInt(payload, pos, 4)
                    val name = if (idx in STATIC.indices) STATIC[idx].first else ""
                    pos = np
                    val (sp, value) = decodeStr(payload, pos)
                    entries.add(HeaderEntry(name, value))
                    pos = sp
                }
            }
        }
        return pos to entries
    }

    private fun decodeInt(bytes: ByteArray, offset: Int, prefixBits: Int): Pair<Int, Int> {
        val mask = (1 shl prefixBits) - 1
        var result = bytes[offset].toInt() and mask
        if (result < mask) return offset + 1 to result
        var m = 0
        var pos = offset + 1
        while (true) {
            if (pos >= bytes.size) return pos to result
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result += (b and 127) shl m
            if (b and 128 == 0) break
            m += 7
        }
        return pos to result
    }

    private fun decodeStr(bytes: ByteArray, offset: Int): Pair<Int, String> =
        decodeStrN(bytes, offset, 7)

    /**
     * Decodes a QPACK string literal (RFC 9204 §4.1.3): the H (Huffman) bit sits immediately above
     * the [prefixBits]-bit length prefix, followed by the length-prefixed bytes (Huffman-decoded via
     * [HpackHuffman] when H is set, raw UTF-8 otherwise). The 7-bit prefix is the common case (every
     * value string and the field-line literal name); encoder-stream Insert With Literal Name uses a
     * 5-bit prefix, and the field-line Literal With Literal Name uses a 3-bit prefix. Returns the new
     * offset and the decoded string; on truncation returns the buffer end and an empty string so a
     * malformed instruction degrades to an empty field rather than throwing.
     */
    private fun decodeStrN(bytes: ByteArray, offset: Int, prefixBits: Int): Pair<Int, String> {
        val firstByte = bytes[offset].toInt() and 0xFF
        val huffman = (firstByte shr prefixBits) and 1 != 0
        val (pos, len) = decodeInt(bytes, offset, prefixBits)
        if (len < 0 || pos + len > bytes.size) return bytes.size to ""
        val value = if (huffman) HpackHuffman.decode(bytes, pos, len) else String(bytes, pos, len, Charsets.UTF_8)
        return (pos + len) to value
    }

    // ── Production QPACK: encoder/decoder streams + field-section decode (RFC 9204) ──────
    //
    // The legacy [decode] above is a static-only prefix decoder kept for backward compatibility
    // (it skips RIC/Base with a hardcoded `pos += 2` and only resolves static entries). The methods
    // below are the production path: [feedEncoderStream] builds a real dynamic table, [decodeFieldSection]
    // reconstructs Required Insert Count / Base and resolves static, dynamic and post-base addresses,
    // and [consumeDecoderStream] parses the decoder-stream instructions the encoder expects back.

    /**
     * Feeds QPACK encoder-stream bytes (RFC 9204 §4.3) into [table], applying each encoder
     * instruction in order: Set Dynamic Table Capacity (`001`, §4.3.1), Insert With Name Reference
     * (`1T`, §4.3.2), Insert With Literal Name (`01`, §4.3.3), and Duplicate (`000`, §4.3.4). A
     * dynamic name reference resolves to absolute index `insertCount - 1 - relativeIndex` (relative
     * to the latest insert); a Set Capacity below the current used bytes evicts oldest entries.
     * Returns the number of instructions applied; a truncated instruction stops the walk (the bytes
     * so far are committed) so a partial encoder-stream delivery across STREAM frames is tolerated —
     * a later [feedEncoderStream] with the remaining bytes resumes from the next instruction.
     */
    fun feedEncoderStream(table: DynamicTable, bytes: ByteArray, offset: Int = 0): Int {
        var pos = offset
        var count = 0
        while (pos < bytes.size) {
            val firstByte = bytes[pos].toInt() and 0xFF
            when {
                firstByte and 0x80 != 0 -> {
                    // Insert With Name Reference: T(bit 6) static/dynamic, 6-bit prefix name index.
                    val isStatic = firstByte and 0x40 != 0
                    val (np, nameIdx) = decodeInt(bytes, pos, 6)
                    if (np > bytes.size) return count
                    val name = if (isStatic) {
                        STATIC.getOrNull(nameIdx)?.first
                    } else {
                        val abs = table.insertCount - 1 - nameIdx
                        table.entry(abs)?.name
                    }
                    val (sp, value) = decodeStrN(bytes, np, 7)
                    if (sp > bytes.size) return count
                    if (name != null) table.insert(HeaderEntry(name, value))
                    pos = sp
                }
                firstByte and 0x40 != 0 -> {
                    // Insert With Literal Name: H(bit 5) Huffman, 5-bit prefix name length, then value(7-bit).
                    val (np, name) = decodeStrN(bytes, pos, 5)
                    if (np > bytes.size) return count
                    val (sp, value) = decodeStrN(bytes, np, 7)
                    if (sp > bytes.size) return count
                    table.insert(HeaderEntry(name, value))
                    pos = sp
                }
                firstByte and 0x20 != 0 -> {
                    // Set Dynamic Table Capacity: 5-bit prefix capacity.
                    val (np, cap) = decodeInt(bytes, pos, 5)
                    if (np > bytes.size) return count
                    table.setCapacity(cap)
                    pos = np
                }
                else -> {
                    // Duplicate: 5-bit prefix relative index (relative to the latest insert).
                    val (np, relIdx) = decodeInt(bytes, pos, 5)
                    if (np > bytes.size) return count
                    table.duplicate(table.insertCount - 1 - relIdx)
                    pos = np
                }
            }
            count++
        }
        return count
    }

    /**
     * Parses QPACK decoder-stream bytes (RFC 9204 §4.4) — Section Acknowledgment (`1`, §4.4.1),
     * Stream Cancellation (`01`, §4.4.2), and Insert Count Increment (`00`, §4.4.3) — and returns the
     * number of instructions consumed. The decoder stream is the feedback channel from decoder to
     * encoder; requestr is a passive observer that never sends it, so this only counts/validates the
     * bytes a real encoder would receive (a malformed instruction stops the walk, never throws).
     */
    fun consumeDecoderStream(bytes: ByteArray, offset: Int = 0): Int {
        var pos = offset
        var count = 0
        while (pos < bytes.size) {
            val firstByte = bytes[pos].toInt() and 0xFF
            val np: Int = when {
                firstByte and 0x80 != 0 -> decodeInt(bytes, pos, 7).first // Section Acknowledgment (7-bit stream id)
                firstByte and 0x40 != 0 -> decodeInt(bytes, pos, 6).first // Stream Cancellation (6-bit stream id)
                else -> decodeInt(bytes, pos, 6).first                    // Insert Count Increment (6-bit increment)
            }
            if (np > bytes.size) return count
            pos = np
            count++
        }
        return count
    }

    /**
     * Decodes an HTTP/3 HEADERS field section (RFC 9204 §4.5) against [table], reconstructing the
     * Required Insert Count (§4.5.1) and Base (§4.5.2) from the section prefix, then decoding each
     * field line: Indexed (`1T`, §4.5.3), Literal With Name Reference (`01NT`, §4.5.5), Literal With
     * Literal Name (`001N`, §4.5.6), and Post-Base Indexed (`0001`, §4.5.4). A static index resolves
     * directly; a dynamic index resolves to absolute index `Base - relative - 1`; a post-base index
     * resolves to `ReqInsertCount + postBaseIndex`.
     *
     * Returns `null` when the section is **blocked** — its Required Insert Count exceeds the entries
     * [table] currently holds ([DynamicTable.insertCount]) — so the caller can retry once more
     * encoder-stream bytes have been fed. Returns the decoded [HeaderEntry] list (possibly empty)
     * once unblocked; a structurally-invalid prefix (RIC that cannot be unwrapped, or a truncated
     * line) also yields `null` rather than throwing. Pseudo-headers (`:method`, `:path`,
     * `:authority`, `:status`, `:scheme`) appear as ordinary entries the caller partitions out.
     */
    fun decodeFieldSection(table: DynamicTable, payload: ByteArray, offset: Int = 0): List<HeaderEntry>? {
        var pos = offset
        // Required Insert Count: 8-bit prefix (RFC 9204 §4.5.1).
        val (afterRic, encInsertCount) = decodeInt(payload, pos, 8)
        if (afterRic > payload.size) return null
        pos = afterRic
        val reqInsertCount = if (encInsertCount == 0) {
            0
        } else {
            val maxEntries = table.capacity() / 32
            reconstructRic(encInsertCount, maxEntries, table.insertCount)
        }
        if (reqInsertCount < 0) return null // un-reconstructable prefix
        // Blocked: the section depends on inserts the encoder stream has not delivered yet.
        if (reqInsertCount > table.insertCount) return null

        // Base: 7-bit prefix Delta Base with a sign bit (RFC 9204 §4.5.2).
        if (pos >= payload.size) return null
        val baseFirstByte = payload[pos].toInt() and 0xFF
        val negative = baseFirstByte and 0x80 != 0
        val (afterBase, deltaBase) = decodeInt(payload, pos, 7)
        if (afterBase > payload.size) return null
        pos = afterBase
        val base = if (negative) reqInsertCount - deltaBase - 1 else reqInsertCount + deltaBase

        val out = mutableListOf<HeaderEntry>()
        while (pos < payload.size) {
            val b = payload[pos].toInt() and 0xFF
            when {
                b and 0x80 != 0 -> {
                    // Indexed Field Line: T(bit 6) static/dynamic, 6-bit prefix.
                    val isStatic = b and 0x40 != 0
                    val (np, idx) = decodeInt(payload, pos, 6)
                    if (np > payload.size) return null
                    if (isStatic) {
                        STATIC.getOrNull(idx)?.let { out.add(HeaderEntry(it.first, it.second)) }
                    } else {
                        table.entry(base - idx - 1)?.let { out.add(it) }
                    }
                    pos = np
                }
                b and 0x40 != 0 -> {
                    // Literal With Name Reference: N(bit 5) never-indexed, T(bit 4) static/dynamic, 4-bit prefix.
                    val isStatic = b and 0x10 != 0
                    val (np, nameIdx) = decodeInt(payload, pos, 4)
                    if (np > payload.size) return null
                    val name = if (isStatic) {
                        STATIC.getOrNull(nameIdx)?.first
                    } else {
                        table.entry(base - nameIdx - 1)?.name
                    }
                    val (sp, value) = decodeStrN(payload, np, 7)
                    if (sp > payload.size) return null
                    if (name != null) out.add(HeaderEntry(name, value))
                    pos = sp
                }
                b and 0x20 != 0 -> {
                    // Literal With Literal Name: N(bit 4), H(bit 3), 3-bit prefix name length, value(7-bit).
                    val (np, name) = decodeStrN(payload, pos, 3)
                    if (np > payload.size) return null
                    val (sp, value) = decodeStrN(payload, np, 7)
                    if (sp > payload.size) return null
                    out.add(HeaderEntry(name, value))
                    pos = sp
                }
                else -> {
                    // Post-Base Indexed: 4-bit prefix; absolute index = ReqInsertCount + postBaseIndex.
                    val (np, postBaseIdx) = decodeInt(payload, pos, 4)
                    if (np > payload.size) return null
                    table.entry(reqInsertCount + postBaseIdx)?.let { out.add(it) }
                    pos = np
                }
            }
        }
        return out
    }

    /**
     * Reconstructs the Required Insert Count from its encoded wire value (RFC 9204 §4.5.1):
     * [maxEntries] is `capacity / 32`, [totalInserts] is the decoder's current insert count, and the
     * encoded value is the required count wrapped into `1..2*maxEntries`. Returns the unwrapped
     * Required Insert Count, or `-1` on a structurally-invalid encoding (a value larger than the full
     * range, or an unwrap that resolves to 0) — the caller treats that as an un-decodable section.
     */
    private fun reconstructRic(encInsertCount: Int, maxEntries: Int, totalInserts: Int): Int {
        if (maxEntries <= 0) return -1 // a non-zero RIC requires a non-zero-capacity table
        val fullRange = 2 * maxEntries
        if (encInsertCount > fullRange) return -1
        val maxValue = totalInserts + maxEntries
        val maxWrapped = (maxValue / fullRange) * fullRange
        var ric = maxWrapped + encInsertCount - 1
        if (ric > maxValue) {
            if (ric <= fullRange) return -1
            ric -= fullRange
        }
        if (ric == 0) return -1
        return ric
    }
}
