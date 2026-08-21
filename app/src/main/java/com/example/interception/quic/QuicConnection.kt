package com.example.interception.quic

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP/3 QUIC connection model.
 * QUIC streams are independent — each maps to a logical HTTP request/response.
 */
data class QuicConnection(
    val connectionId: String,
    val sessionId: Long,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val sniHost: String?,
    val alpn: String?,
    val quicVersion: Int?,
    val dstConnectionId: String,
    val establishedAt: Long = System.currentTimeMillis()
) {
    val streams = ConcurrentHashMap<Int, QuicStream>()
    var state: QuicState = QuicState.INITIALIZING
    var bytesSent: Long = 0
    var bytesReceived: Long = 0
}

/** QUIC connection state machine */
enum class QuicState { INITIALIZING, HANDSHAKE_IN_PROGRESS, HANDSHAKE_COMPLETE, DRAINING, CLOSED }

/**
 * A single QUIC stream — maps to one HTTP request/response.
 */
data class QuicStream(
    val streamId: Int,
    val connectionId: String,
    var isUnidirectional: Boolean = false,
    var state: StreamState = StreamState.OPEN
) {
    val requestBuffer = StringBuilder()
    val responseBuffer = StringBuilder()
    var headersEndOffset: Int = -1
    var decodedHeaders: Map<String, String>? = null
}

enum class StreamState { OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED }

/**
 * QUIC packet-level observation.
 */
data class QuicPacket(
    val packetNumber: Long,
    val packetType: Int,  // Initial, 0-RTT, Handshake, Retry, Short Header
    val connectionId: String,
    val streamId: Int? = null,
    val offset: Long,
    val length: Int,
    val fin: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
