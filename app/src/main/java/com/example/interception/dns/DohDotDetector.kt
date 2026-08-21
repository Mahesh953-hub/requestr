package com.example.interception.dns

/**
 * DNS-over-HTTPS and DNS-over-TLS detection.
 * Identifies encrypted DNS transports that bypass the UDP/53 pipeline.
 */
object DohDotDetector {

    fun isDoH(port: Int, host: String?, path: String?, contentType: String?, method: String?): Boolean {
        if (port != 443) return false
        if (method == "POST" && path == "/dns-query" && contentType?.contains("application/dns-message") == true) return true
        if (method == "GET" && path?.startsWith("/dns-query?dns=") == true) return true
        if (host != null && host.contains("dns") && contentType?.contains("application/dns-message") == true) return true
        return false
    }

    fun isDoT(port: Int, firstBytes: ByteArray): Boolean {
        // DoT: TLS on TCP port 853. First bytes: TLS record with ALPN "dot"
        if (port != 853) return false
        if (firstBytes.size < 5) return false
        if (firstBytes[0] == 0x16.toByte() && firstBytes[1] == 0x03.toByte()) return true
        return false
    }

    fun classify(port: Int, host: String? = null, path: String? = null, contentType: String? = null, method: String? = null): String {
        return when {
            isDoH(port, host, path, contentType, method) -> "DoH (encrypted / browser-observed)"
            isDoT(port, byteArrayOf()) -> "DoT (encrypted)"
            port == 53 -> "UDP/53: decoded"
            else -> "unknown"
        }
    }
}
