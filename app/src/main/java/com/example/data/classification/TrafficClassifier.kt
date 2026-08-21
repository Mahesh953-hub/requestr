package com.example.data.classification

import com.example.data.models.CapturedTrafficItem
import com.example.data.models.ProtocolHint
import com.example.data.models.TrafficSource

object TrafficClassifier {

    fun classify(item: CapturedTrafficItem): CapturedTrafficItem {
        return item.copy(
            trafficSource = detectSource(item),
            protocolHint = detectProtocol(item),
        )
    }

    private fun detectSource(item: CapturedTrafficItem): TrafficSource {
        val pkg = item.appPackage.lowercase()
        val ua = (item.requestHeaders["User-Agent"] ?: "").lowercase()
        return when {
            pkg.contains("chrom") || pkg.contains("webview") || pkg.contains("browser")
                || ua.contains("chrome") || ua.contains("webkit") -> TrafficSource.BROWSER
            pkg.contains("android.webkit") || ua.contains("wv") -> TrafficSource.WEBVIEW
            ua.contains("frida") || item.appName.contains("Frida", ignoreCase = true) -> TrafficSource.FRIDA
            pkg.isNotBlank() && pkg != "unknown" -> TrafficSource.NATIVE
            else -> TrafficSource.UNKNOWN
        }
    }

    private fun detectProtocol(item: CapturedTrafficItem): ProtocolHint {
        val proto = item.protocol.lowercase().trim()
        val alpn = item.alpnProtocol.lowercase().trim()
        return when {
            proto.contains("h3") || proto.contains("http/3") || alpn.contains("h3") -> ProtocolHint.HTTP3
            proto.contains("h2") || proto.contains("http/2") -> ProtocolHint.HTTP2
            proto == "websocket" || item.statusCode == 101 -> ProtocolHint.WEBSOCKET
            item.responseHeaders["content-type"]?.contains("text/event-stream") == true -> ProtocolHint.SSE
            proto.contains("quic") || alpn.contains("quic") -> ProtocolHint.QUIC
            proto.contains("http/1.1") || proto == "http/1.1" -> ProtocolHint.HTTP11
            proto.isNotBlank() -> ProtocolHint.UNKNOWN
            else -> ProtocolHint.UNKNOWN
        }
    }
}
