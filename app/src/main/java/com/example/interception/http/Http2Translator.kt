package com.example.interception.http

import com.example.interception.http.Http2Parser.H2Headers
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * S3 task 57 (D7 / gate G27): message-level translation between HTTP/2 header lists and
 * HTTP/1.1 message heads, plus the RFC 9113 §8 validity rules a terminating endpoint enforces.
 *
 * Pure functions only -- no I/O except [Http1ResponseReader], which streams an HTTP/1.1
 * response off an [InputStream] so a long-lived h1 upstream response (SSE, large download) is
 * relayed to the h2 client as it arrives instead of being buffered.
 *
 * Direction coverage:
 * - h2 request -> HTTP/1.1 request head ([requestToHttp1]) and HTTP/1.1 response -> h2 header
 *   list ([responseToH2]): the wired path when the client negotiated `h2` and the upstream only
 *   speaks HTTP/1.1.
 * - HTTP/1.1 request -> h2 ([requestToH2]) and h2 response -> HTTP/1.1 head ([responseToHttp1]):
 *   the reverse mapping, complete and tested; not on the wire yet because the HTTP/1.x relay
 *   offers upstream `http/1.1` only (see `MitmProxyEngine`), so no h1 client ever meets an h2
 *   upstream today.
 */
internal object Http2Translator {

    /** RFC 9113 §8.2.2: connection-specific fields never cross into or out of HTTP/2. */
    private val CONNECTION_SPECIFIC = setOf("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade")

    private val REQUEST_PSEUDO = setOf(":method", ":scheme", ":authority", ":path", ":protocol")
    private val RESPONSE_PSEUDO = setOf(":status")

    // ── validation ────────────────────────────────────────────────────────────────────────

    /**
     * Returns a description of the first RFC 9113 §8 violation, or `null` when the block is a
     * well-formed request/response/trailer section. A non-null result is a stream error of type
     * PROTOCOL_ERROR for the endpoint.
     */
    fun validate(headers: H2Headers, isRequest: Boolean, trailers: Boolean, maxHeaderListSize: Int = Int.MAX_VALUE): String? {
        var listSize = 0L
        var seenRegular = false
        val pseudo = LinkedHashMap<String, String>()
        for ((name, value) in headers.entries) {
            listSize += name.length + value.length + 32
            if (name.isEmpty()) return "empty field name"
            if (name.any { it in 'A'..'Z' }) return "uppercase field name '$name'"
            if (name.any { it <= ' ' || it == ':' && !name.startsWith(":") || it.code == 0x7F }) return "invalid character in field name '$name'"
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) return "CR/LF/NUL in value of '$name'"
            if (name.startsWith(":")) {
                if (trailers) return "pseudo-header '$name' in trailers"
                if (seenRegular) return "pseudo-header '$name' after regular fields"
                if (pseudo.containsKey(name)) return "duplicate pseudo-header '$name'"
                if (isRequest && name !in REQUEST_PSEUDO || !isRequest && name !in RESPONSE_PSEUDO) return "unknown pseudo-header '$name'"
                pseudo[name] = value
            } else {
                seenRegular = true
                if (name in CONNECTION_SPECIFIC) return "connection-specific field '$name'"
                if (name == "te" && !value.equals("trailers", ignoreCase = true)) return "te must be 'trailers'"
                if (name == "content-length" && value.toLongOrNull()?.let { it < 0 } != false) return "invalid content-length '$value'"
            }
        }
        if (listSize > maxHeaderListSize) return "header list of $listSize octets exceeds $maxHeaderListSize"
        if (trailers) return null
        if (isRequest) {
            val method = pseudo[":method"] ?: return "missing :method"
            if (method.isEmpty()) return "empty :method"
            if (method == "CONNECT") {
                if (pseudo[":protocol"] == null && (pseudo.containsKey(":scheme") || pseudo.containsKey(":path"))) return "CONNECT with :scheme/:path"
                if (pseudo[":authority"].isNullOrEmpty()) return "CONNECT without :authority"
            } else {
                if (pseudo[":scheme"].isNullOrEmpty()) return "missing :scheme"
                if (pseudo[":path"].isNullOrEmpty()) return "missing :path"
            }
        } else {
            val status = pseudo[":status"] ?: return "missing :status"
            if (status.length != 3 || status.any { it !in '0'..'9' }) return "invalid :status '$status'"
        }
        return null
    }

    // ── h2 -> HTTP/1.1 (request) ──────────────────────────────────────────────────────────

    /** An HTTP/1.1 request head and how the body will be framed after it. */
    class Http1RequestHead(val bytes: ByteArray, val chunked: Boolean)

    /**
     * Build the HTTP/1.1 request head for an h2 request. [bodyLength] is the complete body size
     * (the request is buffered before it is sent, so it is always known); a request with
     * [trailers] is framed with `Transfer-Encoding: chunked` because that is the only HTTP/1.1
     * framing that can carry a trailer section, every other request gets `Content-Length`.
     *
     * Mapping (RFC 9113 §8.3 / §8.2.2 / §8.2.3): `:method :path HTTP/1.1`; `Host` from
     * `:authority` unless the request already carries `host`; connection-specific fields are
     * dropped (they cannot appear in a valid h2 message anyway); `te` is dropped unless we
     * chunk; `expect` is dropped because the body is sent immediately; multiple `cookie`
     * fields are folded back into one with `"; "`. Original casing is kept for regular fields.
     */
    fun requestToHttp1(headers: H2Headers, bodyLength: Long, trailers: List<Pair<String, String>>?): Http1RequestHead {
        val method = headers.pseudoHeaders[":method"] ?: "GET"
        val path = headers.pseudoHeaders[":path"]?.ifEmpty { "/" } ?: "/"
        val authority = headers.pseudoHeaders[":authority"]
        val chunked = !trailers.isNullOrEmpty()
        val sb = StringBuilder(256)
        sb.append(method).append(' ').append(if (method == "CONNECT") (authority ?: path) else path).append(" HTTP/1.1\r\n")
        val hasHost = headers.regularEntries.any { it.first.equals("host", ignoreCase = true) }
        if (!hasHost && authority != null) sb.append("Host: ").append(authority).append("\r\n")
        val cookies = ArrayList<String>()
        var cookieWritten = false
        for ((name, value) in headers.regularEntries) {
            val lower = name.lowercase()
            when {
                lower in CONNECTION_SPECIFIC || lower == "content-length" || lower == "expect" -> continue
                lower == "te" -> if (chunked) sb.append("TE: trailers\r\n")
                lower == "cookie" -> { cookies += value; if (!cookieWritten) { sb.append("Cookie: \u0000\r\n"); cookieWritten = true } }
                else -> sb.append(name).append(": ").append(value).append("\r\n")
            }
        }
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        else if (bodyLength > 0 || method in BODY_METHODS) sb.append("Content-Length: ").append(bodyLength).append("\r\n")
        sb.append("\r\n")
        val head = if (cookieWritten) sb.toString().replace("\u0000", cookies.joinToString("; ")) else sb.toString()
        return Http1RequestHead(head.toByteArray(Charsets.ISO_8859_1), chunked)
    }

    /** RFC 9112 §7.1: a single chunk followed by the trailer section and the terminating CRLF. */
    fun chunkedBody(body: ByteArray, trailers: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream(body.size + 64)
        if (body.isNotEmpty()) {
            out.write(Integer.toHexString(body.size).toByteArray(Charsets.ISO_8859_1))
            out.write(CRLF); out.write(body); out.write(CRLF)
        }
        out.write('0'.code); out.write(CRLF)
        for ((n, v) in trailers) out.write("$n: $v\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(CRLF)
        return out.toByteArray()
    }

    // ── HTTP/1.1 -> h2 (response) ─────────────────────────────────────────────────────────

    /**
     * Map an HTTP/1.1 status + header lines to an h2 response header list: `:status` first, then
     * every field lower-cased in wire order with duplicates kept, minus the connection-specific
     * fields and every field the `Connection` header nominated (RFC 9113 §8.2.2). `Content-Length`
     * is kept as-is; when the h1 body was chunked there is none, and the h2 stream is framed by
     * END_STREAM alone.
     */
    fun responseToH2(statusCode: Int, headerLines: List<Pair<String, String>>): List<Pair<String, String>> {
        val nominated = HashSet<String>()
        for ((n, v) in headerLines) if (n.equals("connection", ignoreCase = true)) v.split(',').forEach { nominated += it.trim().lowercase() }
        val out = ArrayList<Pair<String, String>>(headerLines.size + 1)
        out += ":status" to statusCode.toString()
        for ((n, v) in headerLines) {
            val lower = n.lowercase()
            if (lower in CONNECTION_SPECIFIC || lower in nominated) continue
            out += lower to v
        }
        return out
    }

    /** Trailer section of a chunked h1 response as an h2 trailer block (names lower-cased). */
    fun trailersToH2(trailers: List<Pair<String, String>>): List<Pair<String, String>> =
        trailers.filter { it.first.lowercase() !in CONNECTION_SPECIFIC && !it.first.startsWith(":") }
            .map { it.first.lowercase() to it.second }

    // ── reverse direction (HTTP/1.1 client, h2 upstream) ──────────────────────────────────

    /**
     * HTTP/1.1 request line + header lines to an h2 request header list (RFC 9113 §8.3.1):
     * `:method`, `:scheme`, `:authority` (from `Host`), `:path` (origin-form of the target),
     * then regular fields lower-cased with connection-specific ones and the nominated ones
     * removed. `Content-Length` is preserved; chunked framing is replaced by END_STREAM.
     */
    fun requestToH2(method: String, target: String, scheme: String, headerLines: List<Pair<String, String>>): List<Pair<String, String>> {
        val nominated = HashSet<String>()
        var host: String? = null
        for ((n, v) in headerLines) {
            if (n.equals("connection", ignoreCase = true)) v.split(',').forEach { nominated += it.trim().lowercase() }
            if (n.equals("host", ignoreCase = true) && host == null) host = v
        }
        var path = target
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            val afterScheme = path.indexOf("://") + 3
            val slash = path.indexOf('/', afterScheme)
            if (host == null) host = if (slash < 0) path.substring(afterScheme) else path.substring(afterScheme, slash)
            path = if (slash < 0) "/" else path.substring(slash)
        }
        val out = ArrayList<Pair<String, String>>(headerLines.size + 4)
        out += ":method" to method
        if (method != "CONNECT") {
            out += ":scheme" to scheme
            out += ":path" to path.ifEmpty { "/" }
        }
        if (host != null) out += ":authority" to host
        for ((n, v) in headerLines) {
            val lower = n.lowercase()
            if (lower == "host" || lower in CONNECTION_SPECIFIC || lower in nominated) continue
            if (lower == "te") { if (v.equals("trailers", ignoreCase = true)) out += lower to v; continue }
            out += lower to v
        }
        return out
    }

    /**
     * h2 response header list to an HTTP/1.1 head. [bodyLength] `null` means the length is not
     * known up front, in which case the response is framed with `Transfer-Encoding: chunked`
     * (unless the h2 headers already carry `content-length`, or the status has no body).
     */
    fun responseToHttp1(headers: H2Headers, bodyLength: Long?, keepAlive: Boolean = true): Http1RequestHead {
        val status = headers.pseudoHeaders[":status"]?.toIntOrNull() ?: 200
        val bodyless = status in 100..199 || status == 204 || status == 304
        val hasContentLength = headers.regularEntries.any { it.first.equals("content-length", ignoreCase = true) }
        val chunked = !bodyless && !hasContentLength && bodyLength == null
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        for ((name, value) in headers.regularEntries) {
            val lower = name.lowercase()
            if (lower in CONNECTION_SPECIFIC) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        else if (!bodyless && !hasContentLength && bodyLength != null) sb.append("Content-Length: ").append(bodyLength).append("\r\n")
        if (!keepAlive) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return Http1RequestHead(sb.toString().toByteArray(Charsets.ISO_8859_1), chunked)
    }

    fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 206 -> "Partial Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"; 304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        408 -> "Request Timeout"; 409 -> "Conflict"; 410 -> "Gone"; 412 -> "Precondition Failed"; 413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> ""
    }

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

    // ── incremental HTTP/1.1 response reader ─────────────────────────────────────────────

    /** Parsed status line + header lines of one HTTP/1.1 response. */
    class Http1Head(val statusCode: Int, val reason: String, val headerLines: List<Pair<String, String>>) {
        fun header(name: String): String? = headerLines.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val chunked: Boolean get() = header("transfer-encoding")?.contains("chunked", ignoreCase = true) == true
        val contentLength: Long? get() = header("content-length")?.trim()?.toLongOrNull()
        val connectionClose: Boolean get() = header("connection")?.split(',')?.any { it.trim().equals("close", ignoreCase = true) } == true
    }

    /**
     * Streams HTTP/1.1 responses off one upstream [input]. Not thread-safe; one per connection,
     * driven by the upstream relay leg. Head parsing is bounded by [MAX_HEAD_BYTES].
     */
    class Http1ResponseReader(private val input: InputStream, bufferSize: Int = 16 * 1024) {
        private val buffer = ByteArray(bufferSize)
        private var pos = 0
        private var limit = 0
        private val head = ByteArrayOutputStream(1024)

        /** True once the upstream closed its side (a body delimited by close has ended). */
        var eof = false
            private set

        private fun fill(): Boolean {
            if (pos < limit) return true
            pos = 0
            val n = input.read(buffer, 0, buffer.size)
            if (n < 0) { limit = 0; eof = true; return false }
            limit = n
            return true
        }

        private fun readByte(): Int {
            if (!fill()) throw EOFException("upstream closed mid-response")
            return buffer[pos++].toInt() and 0xFF
        }

        /** Read one status line + header lines up to the blank line. Throws [EOFException] on close. */
        fun readHead(): Http1Head {
            head.reset()
            var matched = 0
            while (true) {
                val b = readByte()
                head.write(b)
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
                if (matched == 4) break
                if (head.size() > MAX_HEAD_BYTES) throw IOException("HTTP/1.1 response head exceeds $MAX_HEAD_BYTES bytes")
            }
            val text = String(head.toByteArray(), Charsets.ISO_8859_1)
            val lines = text.split("\r\n").filter { it.isNotEmpty() }
            val statusLine = lines.firstOrNull() ?: throw IOException("empty response head")
            if (!statusLine.startsWith("HTTP/1.")) throw IOException("not an HTTP/1.x status line: '${statusLine.take(40)}'")
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line '${statusLine.take(40)}'")
            val headerLines = ArrayList<Pair<String, String>>(lines.size)
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon <= 0) continue
                headerLines += lines[i].substring(0, colon).trim() to lines[i].substring(colon + 1).trim()
            }
            return Http1Head(code, parts.getOrElse(2) { "" }, headerLines)
        }

        /** Copy exactly [length] body bytes to [sink] in chunks. */
        fun readFixed(length: Long, sink: (ByteArray, Int, Int) -> Unit) {
            var remaining = length
            while (remaining > 0) {
                if (!fill()) throw EOFException("upstream closed with $remaining body bytes outstanding")
                val n = minOf(remaining, (limit - pos).toLong()).toInt()
                sink(buffer, pos, n)
                pos += n
                remaining -= n
            }
        }

        /** Copy body bytes to [sink] until the upstream closes (RFC 9112 §6.3 item 8). */
        fun readUntilClose(sink: (ByteArray, Int, Int) -> Unit) {
            while (fill()) {
                sink(buffer, pos, limit - pos)
                pos = limit
            }
        }

        /**
         * Decode chunked framing (RFC 9112 §7.1), handing each chunk's data to [sink] as it is
         * read; returns the trailer section (original casing, wire order).
         */
        fun readChunked(sink: (ByteArray, Int, Int) -> Unit): List<Pair<String, String>> {
            while (true) {
                val sizeLine = readLine()
                val hex = sizeLine.substringBefore(';').trim()
                val size = hex.toLongOrNull(16) ?: throw IOException("bad chunk size '${hex.take(20)}'")
                if (size == 0L) break
                readFixed(size, sink)
                if (readLine().isNotEmpty()) throw IOException("missing CRLF after chunk data")
            }
            val trailers = ArrayList<Pair<String, String>>()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) trailers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            return trailers
        }

        private fun readLine(): String {
            val sb = StringBuilder(32)
            while (true) {
                val b = readByte()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > MAX_HEAD_BYTES) throw IOException("chunk line too long")
            }
            return sb.toString()
        }

        companion object { const val MAX_HEAD_BYTES = 64 * 1024 }
    }
}
