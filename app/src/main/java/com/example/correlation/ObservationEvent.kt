package com.example.correlation

/**
 * Unified event model for all observation planes.
 * Every capture subsystem emits these — the CorrelationEngine merges them.
 */
sealed class ObservationEvent {
    abstract val eventId: String
    abstract val timestamp: Long
    abstract val source: ObserverSource
    abstract val connectionId: String?

    /** Which observation plane produced this event */
    enum class ObserverSource { VPN, MITM, RUNTIME, BROWSER, PACKET, DNS, QUIC, PROXY }
}

/** Raw network event: TCP connection, packet-level observation */
data class NetworkEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val protocol: String,       // TCP, UDP, ICMP
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val byteCount: Long,
) : ObservationEvent()

/** TLS handshake observation: cipher, SNI, certificate metadata */
data class TlsEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val sniHost: String?,
    val tlsVersion: String,
    val cipherSuite: String,
    val alpn: String?,
    val certSubject: String?,
    val wasDecrypted: Boolean = false,
) : ObservationEvent()

/** HTTP request/response observation */
data class HttpEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val method: String,
    val url: String,
    val host: String,
    val statusCode: Int?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val requestSize: Long,
    val responseSize: Long,
    val wasDecrypted: Boolean,
    val protocol: String = "HTTP/1.1",
    val requestId: String?,     // CDP request ID for browser events
    /**
     * W7: human-readable initiator description (e.g. a URL/script location), first-class
     * instead of being smuggled in as a synthetic `X-Requestr-Initiator` request header (see
     * [com.example.browser.CdpNetworkAdapter], which used to do exactly that).
     */
    val initiator: String? = null,
    /** W7: structured call-stack backing [initiator], when the source can resolve one. */
    val initiatorStackFrames: List<StackFrame> = emptyList(),
    val requestBody: String? = null,
    val responseBody: String? = null,
) : ObservationEvent()

/** Runtime instrumentation event (Frida, Xposed, SDK) */
data class RuntimeEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val processName: String?,
    val pid: Int?,
    val uid: Int?,
    val eventType: String,      // request_started, headers_ready, body_ready, response_ready, exception
    val method: String?,
    val url: String?,
    val stackFrames: List<StackFrame>,
    val adapterType: String?,
) : ObservationEvent()

/** Browser/CDP observation */
data class BrowserEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val targetId: String?,
    val frameId: String?,
    val requestId: String?,
    val initiatorType: String?,
    val initiatorUrl: String?,
    val scriptUrl: String?,
    val functionName: String?,
    val lineNumber: Int?,
    val serviceWorkerSource: String?,
) : ObservationEvent()

/** DNS query event */
data class DnsEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val hostname: String,
    val recordType: String,
    val resolvedIps: List<String>,
    val resolver: String,       // UDP:53, DoT, DoH
) : ObservationEvent()

/** QUIC/HTTP3 connection event */
data class QuicEvent(
    override val eventId: String,
    override val timestamp: Long,
    override val source: ObserverSource,
    override val connectionId: String?,
    val sniHost: String?,
    val alpn: String?,
    val version: Int?,
    val dstConnectionId: String,
) : ObservationEvent()

/** A single stack frame from runtime instrumentation */
data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int?,
)
