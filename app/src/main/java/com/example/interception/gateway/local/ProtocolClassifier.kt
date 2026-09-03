package com.example.interception.gateway.local

/**
 * Classifies the first bytes of an accepted connection into the protocol the gateway must speak
 * to it. Pure function over bytes -- no I/O -- so every branch is unit-testable.
 *
 * Classification only needs a short prefix (see [MIN_BYTES_FOR_HTTP]); the protocol handlers
 * then read the rest through the session's replaying input stream.
 */
object ProtocolClassifier {

    sealed class Classification {
        object Socks5 : Classification()
        object Socks4 : Classification()
        object HttpConnect : Classification()
        /** HTTP method + absolute-form URI (`GET http://host/...`). */
        object HttpAbsolute : Classification()
        /** HTTP method + origin-form URI (`GET /path`), i.e. transparent plaintext. */
        object HttpOrigin : Classification()
        object Tls : Classification()
        /** Not enough bytes yet to decide -- the caller should read more (bounded). */
        object NeedMore : Classification()
        object Unknown : Classification()
    }

    /** Longest HTTP method token we recognise, plus a space. */
    private const val MIN_BYTES_FOR_HTTP = 8

    /** Every method token the HTTP branch accepts (RFC 9110 + WebDAV common set). */
    val HTTP_METHODS: Set<String> = setOf(
        "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE",
        "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "REPORT",
    )

    fun classify(bytes: ByteArray, length: Int = bytes.size): Classification {
        if (length <= 0) return Classification.NeedMore
        val b0 = bytes[0].toInt() and 0xFF
        when (b0) {
            0x05 -> return Classification.Socks5
            0x04 -> return Classification.Socks4
            0x16 -> {
                // TLS record: content type 0x16 (handshake), version major 0x03.
                if (length < 2) return Classification.NeedMore
                return if ((bytes[1].toInt() and 0xFF) == 0x03) Classification.Tls else Classification.Unknown
            }
        }
        // Anything else must be printable ASCII to be HTTP.
        if (!isPrintableAscii(b0)) return Classification.Unknown

        // Find the first space (end of the method token).
        var space = -1
        val scanEnd = minOf(length, MIN_BYTES_FOR_HTTP + 2)
        for (i in 0 until scanEnd) {
            val c = bytes[i].toInt() and 0xFF
            if (c == ' '.code) { space = i; break }
            if (!isTokenChar(c)) return Classification.Unknown
        }
        if (space < 0) {
            return if (length < MIN_BYTES_FOR_HTTP + 2) Classification.NeedMore else Classification.Unknown
        }
        val method = String(bytes, 0, space, Charsets.US_ASCII)
        if (method == "CONNECT") return Classification.HttpConnect
        if (method !in HTTP_METHODS) return Classification.Unknown

        // Need at least one byte of the request target to tell absolute-form from origin-form.
        val targetStart = space + 1
        if (targetStart >= length) return Classification.NeedMore
        val t0 = bytes[targetStart].toInt() and 0xFF
        if (t0 == '/'.code) return Classification.HttpOrigin
        if (t0 == '*'.code) return Classification.HttpOrigin // OPTIONS *
        // Absolute-form starts with a scheme: `http://` or `https://` (case-insensitive).
        val remaining = length - targetStart
        val probe = String(bytes, targetStart, minOf(remaining, 8), Charsets.US_ASCII).lowercase()
        if ("http://".startsWith(probe.take(7)) && probe.length < 7) return Classification.NeedMore
        if (probe.startsWith("http://") || probe.startsWith("https://")) return Classification.HttpAbsolute
        if (probe.length < 8 && "https://".startsWith(probe)) return Classification.NeedMore
        return Classification.Unknown
    }

    private fun isPrintableAscii(c: Int) = c in 0x21..0x7E

    /** RFC 9110 token characters (method names are tokens). */
    private fun isTokenChar(c: Int): Boolean =
        c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '!'.code || c == '#'.code || c == '$'.code ||
            c == '%'.code || c == '&'.code || c == '\''.code || c == '*'.code || c == '+'.code ||
            c == '.'.code || c == '^'.code || c == '`'.code || c == '|'.code || c == '~'.code
}
