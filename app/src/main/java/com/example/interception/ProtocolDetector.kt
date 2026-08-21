package com.example.interception

/**
 * Protocol fingerprinting engine.
 * Classifies observed traffic into protocol + confidence score.
 */
object ProtocolDetector {
    enum class Protocol(val label: String) {
        HTTP_1_1("HTTP/1.1"), HTTP_2("HTTP/2"), HTTP_3("HTTP/3"),
        WEBSOCKET("WebSocket"), SSE("SSE"), GRPC("gRPC"),
        QUIC("QUIC"), DNS("DNS"), DOH("DoH"), DOT("DoT"),
        TLS("TLS"), UNKNOWN("Unknown")
    }

    enum class Confidence { DETECTED, LIKELY, POSSIBLE, UNKNOWN }

    data class Detection(val protocol: Protocol, val confidence: Confidence, val reason: String)

    fun detect(port: Int, firstBytes: ByteArray, tlsMetadata: TlsMeta? = null, httpMetadata: HttpMeta? = null): Detection {
        val hex = firstBytes.take(10).joinToString(" ") { "%02X".format(it) }

        // DNS: UDP/53 with DNS header
        if (port == 53 && firstBytes.size > 12 && firstBytes[2].toInt() and 0x80 == 0) {
            return Detection(Protocol.DNS, Confidence.DETECTED, "UDP:53 + query header")
        }

        // TLS: first byte 0x16 with TLS record
        if (port == 443 || port == 853) {
            if (firstBytes.size > 3 && firstBytes[0] == 0x16.toByte() && firstBytes[1] == 0x03.toByte()) {
                val tlsVersion = firstBytes[2].toInt() and 0xFF
                val label = when {
                    port == 853 -> "Dot"
                    tlsVersion == 0x01 -> "TLS 1.0"
                    tlsVersion == 0x02 -> "TLS 1.1"
                    tlsVersion == 0x03 -> "TLS 1.2"
                    tlsVersion == 0x04 -> "TLS 1.3"
                    else -> "TLS"
                }
                return Detection(if (port == 853) Protocol.DOT else Protocol.TLS, Confidence.DETECTED, "$label ($hex)")
            }
        }

        // QUIC: UDP + first byte has QUIC long header marker
        if (firstBytes.size > 2 && (firstBytes[0].toInt() and 0x80) != 0 && firstBytes[0].toInt() and 0x40 == 0x40) {
            return Detection(Protocol.QUIC, Confidence.DETECTED, "QUIC long header ($hex)")
        }

        // HTTP: starts with GET/POST/PUT/DELETE/HEAD/OPTIONS/CONNECT
        val prefix = String(firstBytes, 0, minOf(20, firstBytes.size), Charsets.UTF_8).uppercase()
        if (prefix.startsWith("GET ") || prefix.startsWith("POST ") || prefix.startsWith("PUT ") ||
            prefix.startsWith("DELETE ") || prefix.startsWith("HEAD ") || prefix.startsWith("OPTIONS ") ||
            prefix.startsWith("PATCH ") || prefix.startsWith("CONNECT ")) {
            return Detection(Protocol.HTTP_1_1, Confidence.DETECTED, "$prefix...")
        }

        // HTTP/2: connection preface
        val h2preface = "PRI * HTTP/2.0".toByteArray()
        if (firstBytes.size >= h2preface.size && firstBytes.take(h2preface.size) == h2preface.toList()) {
            return Detection(Protocol.HTTP_2, Confidence.DETECTED, "H2 preface")
        }

        // HTTP/1.1 response
        if (prefix.startsWith("HTTP/1.")) {
            return Detection(Protocol.HTTP_1_1, Confidence.DETECTED, "$prefix...")
        }

        // gRPC: HTTP/2 + content-type: application/grpc
        if (httpMetadata != null && httpMetadata.contentType.contains("grpc")) {
            return Detection(Protocol.GRPC, Confidence.DETECTED, "HTTP/2 + grpc content-type")
        }

        // WebSocket upgrade
        if (httpMetadata != null && httpMetadata.headers["Upgrade"]?.contains("websocket", ignoreCase = true) == true) {
            return Detection(Protocol.WEBSOCKET, Confidence.DETECTED, "Upgrade: websocket")
        }

        // SSE
        if (httpMetadata != null && httpMetadata.contentType.contains("text/event-stream")) {
            return Detection(Protocol.SSE, Confidence.DETECTED, "text/event-stream")
        }

        // DoH: HTTPS POST with application/dns-message
        if (httpMetadata != null && httpMetadata.headers["content-type"]?.contains("dns-message") == true && port == 443) {
            return Detection(Protocol.DOH, Confidence.LIKELY, "HTTPS + dns-message content-type")
        }

        return Detection(Protocol.UNKNOWN, Confidence.UNKNOWN, hex)
    }
}

data class TlsMeta(val sni: String?, val version: String?, val alpn: String?)
data class HttpMeta(val contentType: String, val headers: Map<String, String>)
