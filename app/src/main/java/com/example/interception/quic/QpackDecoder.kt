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

    private fun decodeStr(bytes: ByteArray, offset: Int): Pair<Int, String> {
        val firstByte = bytes[offset].toInt() and 0xFF
        val huffman = firstByte and 0x80 != 0
        val (pos, len) = decodeInt(bytes, offset, 7)
        if (len < 0 || pos + len > bytes.size) return bytes.size to ""
        val value = if (huffman) HpackHuffman.decode(bytes, pos, len) else String(bytes, pos, len, Charsets.UTF_8)
        return (pos + len) to value
    }
}
