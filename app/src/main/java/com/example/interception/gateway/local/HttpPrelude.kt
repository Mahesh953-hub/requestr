package com.example.interception.gateway.local

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/**
 * Reads and parses an HTTP/1.x request head (request line + headers, up to the blank line) from a
 * blocking stream **without consuming anything past it**, keeping the raw bytes so plaintext
 * requests can be replayed verbatim to the forward-proxy handler.
 *
 * Only the pieces the gateway needs to route are interpreted: method, request-target form and the
 * `Host` header. Everything else stays opaque.
 */
object HttpPrelude {
    /** Hard cap on the size of a request head we are willing to buffer while classifying. */
    const val MAX_HEAD_BYTES = 64 * 1024

    data class Head(
        val raw: ByteArray,
        val method: String,
        val target: String,
        val version: String,
        val headers: Map<String, String>,
    ) {
        val host: String? get() = headers.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
    }

    sealed class Result {
        data class Parsed(val head: Head) : Result()
        data class Malformed(val detail: String) : Result()
        object Eof : Result()
    }

    /** Reads bytes until `\r\n\r\n` (or [MAX_HEAD_BYTES]) and parses them. */
    fun read(input: InputStream): Result {
        val buf = ByteArrayOutputStream()
        var state = 0 // count of consecutive CRLF chars matched: \r=1 \n=2 \r=3 \n=4
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) Result.Eof else Result.Malformed("EOF inside request head")
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
            if (buf.size() > MAX_HEAD_BYTES) return Result.Malformed("request head exceeds $MAX_HEAD_BYTES bytes")
        }
        return parse(buf.toByteArray())
    }

    fun parse(raw: ByteArray): Result {
        val text = String(raw, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return Result.Malformed("empty request line")
        val parts = requestLine.split(' ')
        if (parts.size != 3) return Result.Malformed("bad request line: $requestLine")
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) return Result.Malformed("unsupported version: $version")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) return Result.Malformed("bad header line: $line")
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return Result.Parsed(Head(raw, method, target, version, headers))
    }

    /** `CONNECT host:port` -> destination (default port 443). */
    fun connectDestination(head: Head): Destination? {
        val target = head.target.trim()
        if (target.isEmpty()) return null
        val (host, port) = splitHostPort(target, 443) ?: return null
        return Destination(host, port)
    }

    /** `GET http://host[:port]/path` -> destination (default 80/443 by scheme). */
    fun absoluteDestination(head: Head): Destination? {
        val uri = runCatching { URI(head.target) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return Destination(host, port)
    }

    /** Transparent origin-form request: destination from `Host` (default port 80). */
    fun hostHeaderDestination(head: Head, defaultPort: Int = 80): Destination? {
        val host = head.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val (h, p) = splitHostPort(host, defaultPort) ?: return null
        return Destination(h, p)
    }

    /** Builds the absolute-form URL a forward proxy expects from an origin-form request. */
    fun absoluteUrlFor(destination: Destination, originTarget: String, scheme: String): String {
        val defaultPort = if (scheme == "https") 443 else 80
        val authority = if (destination.port == defaultPort) destination.host else destination.toString()
        val path = if (originTarget.startsWith("/")) originTarget else "/$originTarget"
        return "$scheme://$authority$path"
    }

    /** `host:port`, `[v6]:port`, `host` (uses default) -> (host, port). */
    fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(':') == colon) {
            val port = authority.substring(colon + 1).toIntOrNull() ?: return null
            return authority.substring(0, colon) to port
        }
        return authority to defaultPort
    }
}
