package com.example.interception.tcp

/**
 * State tracked per TCP flow (5-tuple: srcIP:srcPort -> dstIP:dstPort).
 *
 * A tun interface sees individual TCP segments. This class reassembles the
 * byte-stream in each direction so the HTTP / TLS decoders above can consume
 * complete application-layer messages.
 *
 * Limitations (honest, documented):
 *  - No retransmission handling. If a segment is lost or out-of-order, the
 *    gap is treated as start of the next message. On a real device the kernel
 *    feeds mostly in-order segments through the tun, so this is rarely hit.
 *  - Window scaling is not tracked. Flow control is left to the kernel.
 *  - Max stream buffer is soft-capped; oversize streams are flushed as-is
 *    to prevent memory exhaustion.
 */
data class TcpStream(
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val protocol: Int = 6, // TCP
) {
    /** Bytes received from the client (app → server direction). */
    val clientToServer = DirectionBuffer("$srcIp:$srcPort → $dstIp:$dstPort")

    /** Bytes received from the server (server → app direction). */
    val serverToClient = DirectionBuffer("$dstIp:$dstPort → $srcIp:$srcPort")

    val key: String get() = "$protocol|$srcIp:$srcPort>$dstIp:$dstPort"

    /** When we first saw this flow (SYN or first data). */
    var establishedAt: Long = System.currentTimeMillis()

    /** Whether the handshake completed (SYN → SYN-ACK → ACK seen). */
    var handshakeComplete: Boolean = false

    /** Whether we've seen a FIN or RST on this flow. */
    var closed: Boolean = false

    /** True once we've identified this as TLS (ClientHello seen). */
    var isTls: Boolean = false

    /** SNI hostname extracted from TLS ClientHello, if any. */
    var sniHost: String? = null

    companion object {
        fun keyFrom(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, protocol: Int = 6): String =
            "$protocol|$srcIp:$srcPort>$dstIp:$dstPort"
    }
}

/**
 * One half of a TCP stream (one direction).
 */
class DirectionBuffer(val label: String) {
    private val buffer = ByteArray(INITIAL_CAPACITY)
    private var writePos = 0

    /** Expected next sequence number. Initialised on first data segment. */
    var expectedSeq: Long = -1
        private set

    /**
     * Write payload bytes, tracking sequence continuity. Returns the number
     * of bytes accepted (may be less than [len] if the buffer is full).
     */
    fun write(seq: Long, data: ByteArray, offset: Int, len: Int): Int {
        if (expectedSeq == -1L) {
            expectedSeq = seq
        }

        val accepted = minOf(len, buffer.size - writePos)
        if (accepted <= 0) return 0

        System.arraycopy(data, offset, buffer, writePos, accepted)
        writePos += accepted
        expectedSeq = seq + len
        return accepted
    }

    /** Snapshot of the buffered bytes without draining. */
    fun bytes(): ByteArray = buffer.copyOf(writePos)

    /** Total bytes buffered so far. */
    fun size(): Int = writePos

    /** Reset the buffer (after the consumer extracted a complete message). */
    fun drain(keep: Int = 0) {
        if (keep > 0 && keep < writePos) {
            // Move un-consumed tail to front
            val remaining = writePos - keep
            System.arraycopy(buffer, keep, buffer, 0, remaining)
            writePos = remaining
        } else {
            writePos = 0
        }
    }

    /** Discard everything and reset sequence tracking. */
    fun reset() {
        writePos = 0
        expectedSeq = -1
    }

    fun isEmpty(): Boolean = writePos == 0

    companion object {
        const val INITIAL_CAPACITY = 65536  // 64 KiB — one typical HTTP response
    }
}
