package com.example.interception.http

import android.util.Log
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.HttpMethod
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detects and parses complete HTTP/1.x messages from a reassembled TCP
 * byte-stream.
 *
 * Strategy: scan for the \r\n\r\n header terminator, then read Content-Length
 * (or rely on chunked transfer / connection close). When a complete message is
 * available, consume the bytes and emit a [ParsedHttpMessage].
 *
 * TLS streams are detected by the [TlsDecoder], not here.
 */
object HttpParser {

    private const val MAX_HEADERS_BYTES = 65536
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    // ── public API ──────────────────────────────────────────────────────

    /**
     * Attempt to parse an HTTP request from the client→server buffer.
     * Returns null if no complete request is available yet.
     *
     * On success the consumed bytes are drained from the buffer.
     */
    fun tryParseRequest(bytes: ByteArray, host: String, port: Int, isHttps: Boolean): ParsedHttpMessage? {
        val buf = bytes.copyOf(bytes.size.coerceAtMost(MAX_HEADERS_BYTES))
        val headerEnd = findHeaderEnd(buf) ?: return null
        val headerBlock = buf.copyOf(headerEnd)
        val headerStr = String(headerBlock, Charsets.ISO_8859_1)

        val lines = headerStr.split("\r\n")
        if (lines.isEmpty()) return null

        val requestLine = lines[0]
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 2) return null

        val method = parseHttpMethod(parts[0]) ?: return null
        val pathAndQuery = parts.getOrElse(1) { "/" }
        val version = parts.getOrElse(2) { "HTTP/1.1" }

        val headers = mutableMapOf<String, String>()
        // W4 (stack fingerprinting): header name order + original casing is a real
        // stack-discriminating signal (see StackFingerprinter), but `headers` above
        // lowercases every name and Room's Map<String,String> converter does not preserve
        // insertion order once written -- so the only place this survives is right here,
        // at parse time, in a field kept alongside (not instead of) the lowercased map.
        val rawHeaderNames = mutableListOf<String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val rawName = lines[i].substring(0, colon).trim()
                val name = rawName.lowercase()
                val value = lines[i].substring(colon + 1).trim()
                headers[name] = value
                rawHeaderNames.add(rawName)
            }
        }

        // MUST-FIX 2: Content-Length is attacker-controlled wire input. The old
        // `?.toIntOrNull() ?: 0` treated a negative value like "-1" as a valid length of -1,
        // producing `totalNeeded = headerEnd + 3` (one byte short of the real framing) and
        // desynchronizing every subsequent parse on the stream; a huge-but-syntactically-valid
        // value could also overflow the `headerEnd + 4 + contentLength` Int sum. Parse as Long,
        // reject negative/overflowing values, and bail out (as "not parseable yet") instead of
        // ever constructing a message from a bad offset.
        val totalNeeded = computeTotalNeeded(headerEnd, headers["content-length"]) ?: return null

        if (bytes.size < totalNeeded) return null // body not fully received

        // §1.4/§2.7: decompress per Content-Encoding and flag non-text bodies before ever
        // charset-decoding them. consumedBytes below is derived from totalNeeded (the raw,
        // still-encoded wire size), never from the decoded body, so framing is unaffected.
        val contentLength = totalNeeded - headerEnd - 4
        val rawBodyBytes = if (contentLength > 0) {
            bytes.copyOfRange(headerEnd + 4, totalNeeded)
        } else ByteArray(0)
        val decoded = HttpBodyDecoder.decode(rawBodyBytes, headers["content-encoding"])

        val scheme = if (isHttps) "https" else "http"
        val url = "$scheme://$host$pathAndQuery"

        return ParsedHttpMessage(
            isRequest = true,
            method = method,
            url = url,
            path = pathAndQuery,
            host = host,
            port = port,
            version = version,
            headers = headers,
            body = decoded.text,
            consumedBytes = totalNeeded,
            rawHeaderNames = rawHeaderNames,
            isBodyBinary = decoded.isBinary,
            bodyEncoding = normalizeContentEncoding(headers["content-encoding"]),
            bodyContentEncodingConsumed = decoded.encodingConsumed,
            rawBodyBytes = rawBodyBytes,
        )
    }

    /**
     * Attempt to parse an HTTP response from the server→client buffer.
     *
     * [sentRequestMethod] matters for body framing: a response to HEAD carries
     * no body even when a Content-Length header is present, and neither do
     * 1xx / 204 / 304 responses (RFC 7230 §3.3). Without this, callers that
     * stream bytes (the MITM relay) would stall waiting for body bytes that
     * never arrive.
     */
    fun tryParseResponse(
        bytes: ByteArray,
        sentRequestUrl: String? = null,
        sentRequestMethod: HttpMethod? = null,
    ): ParsedHttpMessage? {
        val buf = bytes.copyOf(bytes.size.coerceAtMost(MAX_HEADERS_BYTES))
        val headerEnd = findHeaderEnd(buf) ?: return null
        val headerBlock = buf.copyOf(headerEnd)
        val headerStr = String(headerBlock, Charsets.ISO_8859_1)

        val lines = headerStr.split("\r\n")
        if (lines.isEmpty()) return null

        val statusLine = lines[0]
        if (!statusLine.startsWith("HTTP/")) return null

        val statusParts = statusLine.split(" ", limit = 3)
        val version = statusParts.getOrElse(0) { "HTTP/1.1" }
        val statusCode = statusParts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
        val reasonPhrase = statusParts.getOrElse(2) { "" }

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val name = lines[i].substring(0, colon).trim().lowercase()
                val value = lines[i].substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val transferEncoding = headers["transfer-encoding"]
        val isChunked = transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)

        // RFC 7230 §3.3: these responses never carry a body, regardless of headers.
        val bodyless = sentRequestMethod == HttpMethod.HEAD ||
            statusCode in 100..199 || statusCode == 204 || statusCode == 304

        // §1.4/§2.7: work in raw bytes until decompression + binary detection has run, then
        // charset-decode. totalNeeded (returned as consumedBytes) is always derived from the
        // raw, still-encoded wire size -- decompression never changes how many bytes were
        // consumed from the buffer, only how the body is represented afterwards.
        val rawBodyBytes: ByteArray
        val totalNeeded: Int

        if (bodyless) {
            totalNeeded = headerEnd + 4
            rawBodyBytes = ByteArray(0)
        } else if (isChunked) {
            // MUST-FIX 3: decodeChunked's tri-state result distinguishes "not enough bytes
            // yet" from "this framing is corrupt and will never resolve" -- both must return
            // null here (never build a message), rather than the old behavior of silently
            // returning whatever partial bytes had accumulated so far, which let CaptureEngine
            // (which has no separate completeness re-check, unlike MitmProxyEngine's
            // chunkedBodyComplete) persist a truncated response as if it were complete.
            when (val chunkResult = decodeChunked(bytes, headerEnd + 4)) {
                is ChunkedDecodeResult.Complete -> {
                    rawBodyBytes = chunkResult.body
                    totalNeeded = chunkResult.consumedBytes
                }
                ChunkedDecodeResult.Incomplete, ChunkedDecodeResult.Malformed -> return null
            }
        } else {
            // MUST-FIX 2: same negative/overflow-safe Content-Length handling as the request
            // path -- see computeTotalNeeded's KDoc. When no Content-Length header is present
            // at all, computeTotalNeeded treats this as a headers-only response (matching the
            // old "no Content-Length, no chunking" fallback).
            val computed = computeTotalNeeded(headerEnd, headers["content-length"]) ?: return null
            if (bytes.size < computed) return null
            totalNeeded = computed
            val contentLength = totalNeeded - headerEnd - 4
            rawBodyBytes = if (contentLength > 0) bytes.copyOfRange(headerEnd + 4, totalNeeded) else ByteArray(0)
        }

        val decoded = HttpBodyDecoder.decode(rawBodyBytes, headers["content-encoding"])
        val contentType = headers["content-type"] ?: ""

        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            url = sentRequestUrl ?: "",
            statusCode = statusCode,
            statusMessage = reasonPhrase,
            version = version,
            headers = headers,
            body = decoded.text,
            contentType = contentType,
            consumedBytes = totalNeeded,
            isBodyBinary = decoded.isBinary,
            bodyEncoding = normalizeContentEncoding(headers["content-encoding"]),
            bodyContentEncodingConsumed = decoded.encodingConsumed,
            rawBodyBytes = rawBodyBytes,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Find the \r\n\r\n terminator. Returns the index of the first \r (so the
     *  body starts at return + 4). */
    private fun findHeaderEnd(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
    }

    /**
     * MUST-FIX 2: validates and computes `headerEnd + 4 + Content-Length` as a safe Int, or
     * returns null if the header is present but malformed (non-numeric, negative) or would
     * overflow what a ByteArray index can represent.
     *
     * Absent header ⇒ headers-only message (`headerEnd + 4`), matching the pre-fix fallback.
     * A malformed or overflowing Content-Length can never be satisfied by any amount of
     * buffered data, so returning null here is indistinguishable to callers from "incomplete,
     * wait for more bytes" -- both correctly mean "do not build a message from this buffer" --
     * but it never throws and never desyncs the next parse by rounding a bad value into a
     * plausible-looking-but-wrong offset (the old `?.toIntOrNull() ?: 0` treated "-1" as a
     * valid length of -1, silently consuming one byte too few on every such message).
     */
    private fun computeTotalNeeded(headerEnd: Int, contentLengthHeader: String?): Int? {
        if (contentLengthHeader == null) return headerEnd + 4
        val contentLength = contentLengthHeader.trim().toLongOrNull() ?: return null
        if (contentLength < 0) return null
        val total = headerEnd.toLong() + 4 + contentLength
        if (total > Int.MAX_VALUE) return null
        return total.toInt()
    }

    /**
     * §1.4: [ParsedHttpMessage.bodyEncoding] stores the *declared* Content-Encoding token
     * (lowercased, "identity" when absent) rather than [HttpBodyDecoder.Encoding]'s coarser
     * classification -- callers care whether a body was "br" vs "zstd" (both classify as
     * [HttpBodyDecoder.Encoding.UNDECODABLE]), and this is the one place that distinction is
     * still available.
     */
    private fun normalizeContentEncoding(header: String?): String =
        header?.trim()?.lowercase()?.ifEmpty { "identity" } ?: "identity"

    private fun parseHttpMethod(s: String): HttpMethod? {
        return when (s.uppercase()) {
            "GET" -> HttpMethod.GET
            "POST" -> HttpMethod.POST
            "PUT" -> HttpMethod.PUT
            "DELETE" -> HttpMethod.DELETE
            "PATCH" -> HttpMethod.PATCH
            "HEAD" -> HttpMethod.HEAD
            "OPTIONS" -> HttpMethod.OPTIONS
            "CONNECT" -> HttpMethod.WS // CONNECT is tunnelling, map to WS for now
            else -> null
        }
    }

    /**
     * MUST-FIX 3: result of attempting to decode a chunked-transfer-encoded body from a
     * (possibly still-arriving) byte buffer. The previous implementation returned a plain
     * `Pair<ByteArray, Int>` and silently handed back whatever partial bytes it had
     * accumulated when a chunk-size line, a chunk's data, or the trailer section was cut off
     * mid-stream -- indistinguishable from a genuinely complete message. CaptureEngine has no
     * separate completeness re-check (unlike MitmProxyEngine's own `chunkedBodyComplete`) and
     * would happily persist that truncated body as if it were the whole response. This
     * three-way result makes "not enough bytes yet" ([Incomplete]) and "these bytes are not
     * valid chunked framing and never will be" ([Malformed]) both impossible to mistake for
     * done ([Complete]) -- callers must only build a [ParsedHttpMessage] from [Complete].
     */
    private sealed class ChunkedDecodeResult {
        data class Complete(val body: ByteArray, val consumedBytes: Int) : ChunkedDecodeResult()
        object Incomplete : ChunkedDecodeResult()
        object Malformed : ChunkedDecodeResult()
    }

    /**
     * De-chunks the transfer-encoding, returning the reassembled raw body bytes (not yet
     * charset-decoded). §1.4: this must hand back raw bytes rather than a decoded String --
     * a chunked, gzip-encoded body needs every chunk's bytes concatenated before gunzip can
     * even run, and decoding each chunk as UTF-8 independently (the old behavior) would also
     * mis-decode any multi-byte character that happens to straddle a chunk boundary.
     *
     * MUST-FIX 3: only returns [ChunkedDecodeResult.Complete] once the terminal zero-size
     * chunk AND its full trailer section (through the final blank line, RFC 7230 §4.1.2) have
     * arrived; validates every chunk-size with checked [Long] arithmetic so an attacker-
     * controlled hex value cannot overflow the `Int` offsets used to index [bytes]; and
     * verifies the CRLF that must follow every chunk's data instead of assuming it is there.
     */
    private fun decodeChunked(bytes: ByteArray, startOffset: Int): ChunkedDecodeResult {
        val out = java.io.ByteArrayOutputStream()
        var pos = startOffset
        while (true) {
            val crlf = findCrlf(bytes, pos) ?: return ChunkedDecodeResult.Incomplete
            val sizeToken = String(bytes, pos, crlf - pos, Charsets.ISO_8859_1).trim().split(";")[0].trim()
            val chunkSize = parseChunkSizeHex(sizeToken) ?: return ChunkedDecodeResult.Malformed

            val chunkDataStart = crlf + 2
            if (chunkSize == 0L) {
                // Last-chunk: consume zero-or-more trailer field lines through the final
                // blank line. The old code only consumed the FIRST trailer line (or the
                // terminator itself, when there were none), leaving any additional trailer
                // lines in the buffer to be misparsed as the start of the next message.
                val trailerEnd = findTrailerEnd(bytes, chunkDataStart) ?: return ChunkedDecodeResult.Incomplete
                return ChunkedDecodeResult.Complete(out.toByteArray(), trailerEnd)
            }

            // Checked arithmetic: chunkSize is attacker-controlled hex from the wire. A value
            // large enough to overflow Int index arithmetic must be rejected outright -- it
            // can never be satisfied by any buffer size we would ever actually hold -- not
            // treated as "just needs more bytes".
            val chunkDataEndLong = chunkDataStart.toLong() + chunkSize
            if (chunkDataEndLong + 2 > Int.MAX_VALUE) return ChunkedDecodeResult.Malformed
            val chunkDataEnd = chunkDataEndLong.toInt()
            val afterCrlf = chunkDataEnd + 2
            if (afterCrlf > bytes.size) return ChunkedDecodeResult.Incomplete // need more bytes

            // RFC 7230 §4.1: every chunk's data must be immediately followed by CRLF. Verify
            // it rather than assuming, so a corrupted stream is caught here instead of
            // silently eating/dropping bytes and desyncing everything after it.
            if (bytes[chunkDataEnd] != '\r'.code.toByte() || bytes[chunkDataEnd + 1] != '\n'.code.toByte()) {
                return ChunkedDecodeResult.Malformed
            }

            out.write(bytes, chunkDataStart, chunkSize.toInt())
            pos = afterCrlf
        }
    }

    /**
     * A chunk-size token (chunk-ext already stripped by the caller) must be pure hex digits.
     * Rejecting anything else outright matters because `String.toLongOrNull(16)` alone would
     * happily parse a token like "-5" as a negative number, which would then need its own
     * separate negative-size check -- simpler and safer to refuse non-hex-digit input up front.
     */
    private fun parseChunkSizeHex(token: String): Long? {
        if (token.isEmpty() || token.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return token.toLongOrNull(16)
    }

    /**
     * Scans trailer field lines starting at [start] until the final blank-line terminator (an
     * empty line immediately at a line boundary) and returns the index just past it, or null
     * if the buffer runs out before that terminator arrives.
     */
    private fun findTrailerEnd(bytes: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            val crlf = findCrlf(bytes, pos) ?: return null
            if (crlf == pos) return crlf + 2 // blank line: trailer section (maybe empty) is done
            pos = crlf + 2
        }
    }

    private fun findCrlf(bytes: ByteArray, start: Int): Int? {
        for (i in start until bytes.size - 1) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i
        }
        return null
    }

    // ── conversion ──────────────────────────────────────────────────────

    fun toCapturedTrafficItem(
        request: ParsedHttpMessage,
        response: ParsedHttpMessage?,
        sniHost: String?,
        timestamp: Long = System.currentTimeMillis(),
    ): CapturedTrafficItem {
        val responseHeaders = response?.headers ?: emptyMap()
        // Merge the content-type into response headers if present
        val mergedHeaders = if (response != null && response.contentType.isNotEmpty()) {
            responseHeaders.toMutableMap().apply {
                putIfAbsent("content-type", response.contentType)
            }
        } else responseHeaders

        return CapturedTrafficItem(
            id = "tcp-${timestamp}-${(Math.random() * 100000).toInt()}",
            timestamp = timestamp,
            method = request.method ?: HttpMethod.GET,
            url = request.url,
            host = sniHost ?: request.host,
            path = request.path,
            protocol = request.version,
            statusCode = response?.statusCode ?: 0,
            statusMessage = response?.statusMessage ?: "",
            latencyMs = 0L,
            requestHeaders = request.headers,
            requestBody = request.body,
            requestSize = request.body.toByteArray(Charsets.UTF_8).size.toLong(),
            responseHeaders = mergedHeaders,
            responseBody = response?.body ?: "",
            responseSize = (response?.body ?: "").toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }
}

/**
 * A fully parsed HTTP message (request or response).
 */
data class ParsedHttpMessage(
    val isRequest: Boolean,
    val method: HttpMethod?,
    var url: String = "",
    val path: String = "",
    var host: String = "",
    val port: Int = 80,
    val version: String = "HTTP/1.1",
    var statusCode: Int = 0,
    val statusMessage: String = "",
    var headers: Map<String, String> = emptyMap(),
    var body: String = "",
    val contentType: String = "",
    /** Total bytes consumed from the buffer (headers + body). */
    val consumedBytes: Int = 0,
    /**
     * Header names in original wire order and casing, request path only (W4). [headers]
     * above is lowercased and unordered for lookup convenience; this field exists solely so
     * [com.example.runtime.http.StackFingerprinter] can sample the real order/casing before
     * it is lost -- never populated for responses, and empty when the request had no headers.
     */
    val rawHeaderNames: List<String> = emptyList(),
    /**
     * §2.7: true when [HttpBodyDecoder.isProbablyUtf8] flagged the (post-decompression) body
     * bytes as binary. When true, [body] is [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER], which
     * today is literally the empty string -- NOT a sentinel that marks "this was binary" while
     * still carrying the real bytes somehow. The original bytes are gone from [body]; they
     * are only recoverable from [rawBodyBytes]. (The same empty-placeholder fallback also
     * fires when UTF-8 decoding of a non-binary-flagged body throws, or when decompression
     * fails outright -- see [HttpBodyDecoder.decode].) Do not "fix" [HttpBodyDecoder]'s
     * placeholder into some human-readable string without also checking every place that
     * writes [body] back onto the wire: MitmProxyEngine's serializeRequest/serializeResponse
     * key off [bodyReplaced] (not [isBodyBinary]) specifically so a non-replaced binary body is
     * re-serialized from [rawBodyBytes] verbatim, never from this lossy text rendering -- if
     * that ever became a non-empty string, it would start appearing as literal wire content in
     * place of every image/protobuf/undecodable response.
     */
    var isBodyBinary: Boolean = false,
    /**
     * §1.4: the Content-Encoding this message declared, lowercase (see
     * [HttpBodyDecoder.Encoding]) -- "identity" when absent. Independent of whether decoding
     * actually happened; see [bodyContentEncodingConsumed] for that.
     */
    var bodyEncoding: String = "identity",
    /**
     * True iff [body] was produced by successfully decompressing a declared Content-Encoding
     * (gzip/deflate), meaning it no longer matches that header. This field is no longer
     * consulted by MitmProxyEngine's serializeRequest/serializeResponse (see [bodyReplaced]);
     * it remains for callers (e.g. UI/export) that only care about *why* [body] looks the way
     * it does, not for wire re-serialization.
     */
    var bodyContentEncodingConsumed: Boolean = false,
    /**
     * MUST-FIX 1: the exact post-de-chunking, pre-decode wire bytes this message's body was
     * built from -- i.e. what actually arrived on the wire, before any Content-Encoding
     * decompression or [HttpBodyDecoder.isProbablyUtf8] sniffing touched it. Null only for
     * messages that were never parsed off a real wire (e.g. RuleEnforcer.buildMockResponse's
     * synthesized MOCK_RESPONSE, or hand-built test fixtures) -- both [HttpParser] entry points
     * always populate it, including with an empty array for a bodyless message.
     *
     * This exists because [body] is a *rendering* of the bytes (UTF-8 text, or
     * [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] for anything [isBodyBinary] flagged, or for a
     * failed decode) -- lossy or empty in exactly the cases where byte-for-byte fidelity
     * matters most. Serializers must fall back to re-encoding [body] only when [rawBodyBytes]
     * is null (the synthesized-message case above, where [body] *is* the real content).
     *
     * EQUALITY NOTE: [ParsedHttpMessage] is a Kotlin `data class`, so this `ByteArray`
     * property silently downgrades the generated `equals`/`hashCode` to array *identity*
     * comparison (two messages with byte-for-byte-identical but distinct raw body arrays
     * compare unequal), and `copy()` shares the same array instance between the original and
     * the copy rather than cloning it. As of this writing nothing compares whole
     * [ParsedHttpMessage] instances or mutates a [rawBodyBytes] array after construction --
     * it is set once at parse time and only ever read (at serialization time) -- so this
     * aliasing is safe today. If that ever changes (e.g. a future test asserts
     * `parsedA == parsedB`, or something mutates a shared array in place), this field needs to
     * move out of the generated equality contract deliberately (wrapper class, or explicit
     * `equals`/`hashCode` overrides) rather than being trusted to just work.
     */
    val rawBodyBytes: ByteArray? = null,
    /**
     * MUST-FIX 1: true only when a rule actually overwrote [body] via the `bodyReplace` config
     * key -- set at the exact two sites in RuleEnforcer.kt (`applyRequestModification` /
     * `applyResponseModification`) that perform that assignment, nowhere else. Re-serialization
     * (MitmProxyEngine's serializeRequest/serializeResponse) uses this single bit to decide
     * what bytes to write for the body:
     *  - `true`: [body] (re-encoded as UTF-8) is the real, intended content -- a rule
     *    explicitly replaced it with plaintext -- and any inherited Content-Encoding header
     *    must be dropped (the replacement is not actually gzip/br/whatever the header claims).
     *  - `false` (default): no rule touched the body (no rule matched, or it only edited
     *    headers/status) -- write [rawBodyBytes] back out verbatim and leave Content-Encoding
     *    alone, so a header-only edit stays byte-transparent for the body no matter whether
     *    HttpBodyDecoder could decompress/decode it as text.
     */
    var bodyReplaced: Boolean = false,
)
