package com.example.interception.http

import com.example.data.models.HttpMethod
import com.example.interception.http.Http2Parser.H2Headers

/**
 * S4 task 35: the HTTP/2 <-> [ParsedHttpMessage] boundary.
 *
 * Rules operate on the message model only (master plan task 35: "never on raw frames"), so a
 * terminated h2 stream is turned into a [ParsedHttpMessage] here ([request]/[response] -- the
 * same builders [Http2Session] uses for persistence, so the rules see exactly what the UI
 * shows), the staged pipeline mutates it, and [encodeRequest]/[encodeResponse] turn the result
 * back into one header list plus one body for a HEADERS + DATA(END_STREAM) pair.
 *
 * Re-encoding rules (the h2 counterpart of `MitmProxyEngine.serializeRequest/serializeResponse`):
 * - header lists are rebuilt from the wire-ordered `headerLines` reconciled against the
 *   (possibly rule-edited) `headers` map, so an untouched repeated field (`cookie`, `set-cookie`)
 *   keeps every entry, an edited one collapses to its new value, a removed one disappears and an
 *   added one is appended -- names lower-cased as RFC 9113 §8.2 requires;
 * - connection-specific fields and `transfer-encoding` never appear (§8.2.2);
 * - `content-length` is always recomputed from the bytes actually framed, never inherited;
 * - a `Host` header a rule set (mirror hostPolicy, redirect) becomes `:authority`; the request
 *   `:path` follows the message URL (redirect / query edits); `:method`/`:scheme`/`:protocol`
 *   fall back to the original pseudo-headers;
 * - body bytes follow the same `bodyReplaced` contract as the HTTP/1.x serializers
 *   ([bodyBytesFor]): a replaced body drops any inherited `content-encoding`, an untouched one
 *   forwards the captured wire bytes verbatim.
 */
internal object Http2Messages {

    /** One complete h2 message: header block (pseudo + regular, wire order), whole body, optional trailers. */
    class H2Message(
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val trailers: List<Pair<String, String>>? = null,
    ) {
        val hasTrailers: Boolean get() = !trailers.isNullOrEmpty()
        /** END_STREAM belongs on the HEADERS frame when there is nothing else to send. */
        val endsOnHeaders: Boolean get() = body.isEmpty() && !hasTrailers
    }

    private val CONNECTION_SPECIFIC = setOf("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade")
    private val EMPTY = ByteArray(0)

    // ── h2 -> message model ───────────────────────────────────────────────────────────────

    fun request(headers: H2Headers, bodyBytes: ByteArray, trailers: H2Headers? = null): ParsedHttpMessage {
        val all = headers.all
        val authority = all[":authority"] ?: ""
        val path = all[":path"] ?: "/"
        val scheme = all[":scheme"] ?: "https"
        val contentEncoding = headerValue(all, "content-encoding")
        val decoded = HttpBodyDecoder.decode(bodyBytes, contentEncoding)
        return ParsedHttpMessage(
            isRequest = true,
            method = parseMethod(all[":method"]),
            url = "$scheme://$authority$path",
            path = path,
            host = authority,
            version = "h2",
            headers = headers.regularHeaders,
            headerLines = headers.regularEntries,
            trailers = trailers?.regularEntries ?: emptyList(),
            body = decoded.text,
            isBodyBinary = decoded.isBinary,
            rawBodyBytes = bodyBytes,
            contentType = headerValue(all, "content-type") ?: "",
        )
    }

    fun response(headers: H2Headers, bodyBytes: ByteArray, trailers: H2Headers? = null): ParsedHttpMessage {
        val all = headers.all
        val status = all[":status"]?.toIntOrNull() ?: 0
        val contentEncoding = headerValue(all, "content-encoding")
        val decoded = HttpBodyDecoder.decode(bodyBytes, contentEncoding)
        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = status,
            version = "h2",
            headers = headers.regularHeaders,
            headerLines = headers.regularEntries,
            trailers = trailers?.regularEntries ?: emptyList(),
            body = decoded.text,
            isBodyBinary = decoded.isBinary,
            rawBodyBytes = bodyBytes,
            contentType = headerValue(all, "content-type") ?: "",
        )
    }

    fun parseMethod(method: String?): HttpMethod? = when (method?.uppercase()) {
        "GET" -> HttpMethod.GET
        "POST" -> HttpMethod.POST
        "PUT" -> HttpMethod.PUT
        "DELETE" -> HttpMethod.DELETE
        "PATCH" -> HttpMethod.PATCH
        "HEAD" -> HttpMethod.HEAD
        "OPTIONS" -> HttpMethod.OPTIONS
        else -> null
    }

    private fun headerValue(all: Map<String, String>, name: String): String? =
        all.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    // ── message model -> h2 ───────────────────────────────────────────────────────────────

    /**
     * Re-encode a rule-mutated request. [original] is the header block the client sent; its
     * pseudo-headers are the fallback for anything the rules did not change.
     */
    fun encodeRequest(message: ParsedHttpMessage, original: H2Headers, trailers: List<Pair<String, String>>? = null): H2Message {
        val regular = reconcile(message.headerLines, message.headers)
        val body = bodyBytesFor(message, regular)
        val pseudo = original.pseudoHeaders
        val method = message.method?.name ?: pseudo[":method"] ?: "GET"
        val (urlScheme, urlAuthority, urlPath) = splitUrl(message.url)
        val hostHeader = regular.firstOrNull { it.first == "host" }?.second
        // Legacy REDIRECT parity: the request line follows the new URL, the authority follows the
        // Host header when a rule set one (preserveHost keeps the original authority even though
        // the URL now names another host -- exactly what the HTTP/1.x relay does).
        val authority = hostHeader ?: pseudo[":authority"] ?: urlAuthority
        val out = ArrayList<Pair<String, String>>(regular.size + 5)
        out += ":method" to method
        if (method != "CONNECT") {
            out += ":scheme" to (urlScheme ?: pseudo[":scheme"] ?: "https")
            out += ":path" to (urlPath ?: pseudo[":path"] ?: "/")
        }
        if (authority != null) out += ":authority" to authority
        pseudo[":protocol"]?.let { out += ":protocol" to it }
        val hadContentLength = appendRegular(out, regular, isRequest = true)
        if (body.isNotEmpty() || hadContentLength) out += "content-length" to body.size.toString()
        return H2Message(out, body, trailers?.takeIf { it.isNotEmpty() })
    }

    /**
     * Re-encode a (possibly synthesized) response. [bodyless] is true for a HEAD response and the
     * 204/304 statuses, whose `content-length` describes a body that is deliberately not sent.
     */
    fun encodeResponse(message: ParsedHttpMessage, bodyless: Boolean = false, trailers: List<Pair<String, String>>? = null): H2Message {
        val regular = reconcile(message.headerLines, message.headers)
        val status = message.statusCode.takeIf { it in 100..999 } ?: 200
        val noBody = bodyless || status == 204 || status == 304 || status in 100..199
        val body = if (noBody) EMPTY else bodyBytesFor(message, regular)
        val out = ArrayList<Pair<String, String>>(regular.size + 2)
        out += ":status" to status.toString()
        if (noBody) {
            // Keep the declared length (a HEAD answer's real size) but never frame a body.
            for ((n, v) in regular) if (n !in CONNECTION_SPECIFIC) out += n to v
        } else {
            appendRegular(out, regular, isRequest = false)
            out += "content-length" to body.size.toString()
        }
        return H2Message(out, body, trailers?.takeIf { it.isNotEmpty() && !noBody })
    }

    /**
     * Same contract as `MitmProxyEngine.bodyBytesFor`: a rule's explicit body replacement wins
     * (byte-exact replacement first, else the UTF-8 text) and drops `content-encoding`; every
     * other message forwards its captured wire bytes verbatim.
     */
    fun bodyBytesFor(message: ParsedHttpMessage, regular: MutableList<Pair<String, String>>): ByteArray =
        if (message.bodyReplaced) {
            regular.removeAll { it.first == "content-encoding" }
            message.replacedBodyBytes ?: message.body.toByteArray(Charsets.UTF_8)
        } else {
            message.rawBodyBytes ?: message.body.toByteArray(Charsets.UTF_8)
        }

    /** Appends [regular] minus host/pseudo/connection-specific/length fields; returns whether a length field was present. */
    private fun appendRegular(out: MutableList<Pair<String, String>>, regular: List<Pair<String, String>>, isRequest: Boolean): Boolean {
        var hadContentLength = false
        for ((n, v) in regular) {
            when {
                n.startsWith(":") -> continue
                n == "content-length" -> { hadContentLength = true; continue }
                n in CONNECTION_SPECIFIC -> continue
                isRequest && n == "host" -> continue
                n == "te" -> { if (v.equals("trailers", ignoreCase = true)) out += n to v; continue }
                else -> out += n to v
            }
        }
        return hadContentLength
    }

    /**
     * Wire-ordered [original] lines reconciled against the rule-edited [current] map (names
     * compared case-insensitively, output names lower-cased): untouched names keep every wire
     * entry, an edited name collapses to its new value at its first position, a removed name
     * disappears, new names are appended in map order.
     */
    fun reconcile(original: List<Pair<String, String>>, current: Map<String, String>): MutableList<Pair<String, String>> {
        val originalLast = LinkedHashMap<String, String>()
        for ((n, v) in original) originalLast[n.lowercase()] = v
        val currentLower = LinkedHashMap<String, String>()
        for ((n, v) in current) currentLower[n.lowercase()] = v
        val out = ArrayList<Pair<String, String>>(original.size + 4)
        val collapsed = HashSet<String>()
        for ((n, v) in original) {
            val lower = n.lowercase()
            val now = currentLower[lower] ?: continue
            if (now == originalLast[lower]) out += lower to v
            else if (collapsed.add(lower)) out += lower to now
        }
        for ((lower, v) in currentLower) if (lower !in originalLast) out += lower to v
        return out
    }

    /** `scheme`, `authority`, `path?query` of an absolute URL; nulls for whatever is missing. */
    private fun splitUrl(url: String): Triple<String?, String?, String?> {
        val i = url.indexOf("://")
        if (i <= 0) return Triple(null, null, url.takeIf { it.startsWith("/") })
        val rest = url.substring(i + 3)
        val slash = rest.indexOf('/')
        val authority = (if (slash < 0) rest else rest.substring(0, slash)).substringBefore('#').ifEmpty { null }
        val path = if (slash < 0) "/" else rest.substring(slash).substringBefore('#').ifEmpty { "/" }
        return Triple(url.substring(0, i), authority, path)
    }
}
