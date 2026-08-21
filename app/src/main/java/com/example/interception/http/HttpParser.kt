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
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val name = lines[i].substring(0, colon).trim().lowercase()
                val value = lines[i].substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val totalNeeded = headerEnd + 4 + contentLength // +4 for \r\n\r\n

        if (bytes.size < totalNeeded) return null // body not fully received

        val body = if (contentLength > 0) {
            String(bytes, headerEnd + 4, contentLength.coerceAtMost(bytes.size - headerEnd - 4), Charsets.UTF_8)
        } else ""

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
            body = body,
            consumedBytes = totalNeeded,
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

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val transferEncoding = headers["transfer-encoding"]
        val isChunked = transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)

        // RFC 7230 §3.3: these responses never carry a body, regardless of headers.
        val bodyless = sentRequestMethod == HttpMethod.HEAD ||
            statusCode in 100..199 || statusCode == 204 || statusCode == 304

        val body: String
        val totalNeeded: Int

        if (bodyless) {
            totalNeeded = headerEnd + 4
            body = ""
        } else if (isChunked) {
            val (chunkedBody, consumed) = decodeChunked(bytes, headerEnd + 4)
            body = chunkedBody
            totalNeeded = consumed
        } else if (contentLength > 0) {
            totalNeeded = headerEnd + 4 + contentLength
            if (bytes.size < totalNeeded) return null
            body = String(bytes, headerEnd + 4, contentLength.coerceAtMost(bytes.size - headerEnd - 4), Charsets.UTF_8)
        } else {
            // No Content-Length, no chunking — treat headers-only response
            totalNeeded = headerEnd + 4
            body = ""
        }

        val contentType = headers["content-type"] ?: ""

        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            url = sentRequestUrl ?: "",
            statusCode = statusCode,
            statusMessage = reasonPhrase,
            version = version,
            headers = headers,
            body = body,
            contentType = contentType,
            consumedBytes = totalNeeded,
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

    private fun decodeChunked(bytes: ByteArray, startOffset: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = startOffset
        while (pos < bytes.size) {
            // Find end of chunk-size line
            val crlf = findCrlf(bytes, pos) ?: break
            val sizeHex = String(bytes, pos, crlf - pos, Charsets.ISO_8859_1).trim()
            val chunkSize = sizeHex.split(";")[0].toIntOrNull(16) ?: break

            if (chunkSize == 0) {
                // Final chunk — consume trailing CRLF
                val trailerEnd = findCrlf(bytes, crlf + 2)
                pos = trailerEnd?.plus(2) ?: (crlf + 2)
                break
            }

            pos = crlf + 2
            val chunkEnd = (pos + chunkSize).coerceAtMost(bytes.size)
            sb.append(String(bytes, pos, chunkEnd - pos, Charsets.UTF_8))
            pos = chunkEnd + 2 // skip trailing CRLF
        }
        return sb.toString() to pos
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
)
