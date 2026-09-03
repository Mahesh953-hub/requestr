package com.example.interception.relay

import com.example.data.local.TransactionTimingEntity
import com.example.data.persistence.PersistenceHealth
import com.example.interception.http.ParsedHttpMessage

/**
 * S2 task 18 -- measured connection-setup phases of one upstream leg, in milliseconds.
 *
 * Every value is what the relay actually observed around the corresponding blocking call; null
 * means the phase did not happen (plaintext upstream has no TLS) or could not be observed. Nothing
 * here is derived from a total.
 */
data class UpstreamSetupTimings(
    /** Name resolution of the upstream host; ~0 for an IP literal, which is the truth. */
    val dnsMs: Long?,
    /** TCP connect (SYN to established). */
    val connectMs: Long?,
    /** Upstream TLS handshake, `startHandshake()` to return. */
    val tlsMs: Long?,
)

/**
 * S2 task 18 -- per-relayed-connection collector for the transaction phases the proxy can
 * honestly observe on the HTTP/1.x inspecting relay:
 *
 * - `sendMs`: from the moment the parsed request was handed to the upstream write until the
 *   write+flush returned.
 * - `waitMs` (TTFB): request fully sent -> first byte of the matching response read from upstream.
 * - `receiveMs`: first response byte -> the response parsed complete.
 * - `dnsMs`/`connectMs`/`tlsMs`: the upstream leg's setup, attached to the FIRST transaction on
 *   the connection only -- later transactions reuse the connection and get NULL, the same
 *   convention HAR uses for a reused connection.
 *
 * Any phase that comes out negative (clock skew between threads, a response that raced its
 * request record) is stored as NULL and counted on [PersistenceHealth.recordTimingUnderflow];
 * the failure table in the plan forbids clamping it to zero and pretending it was measured.
 *
 * The request side ([onRequestSent]) and the response side ([markFirstResponseByte],
 * [completeResponse]) run on different relay legs; the small critical sections are guarded by
 * [lock]. Records are paired FIFO with the engine's own `pending` queue (HTTP/1.1 responses
 * arrive in request order). If the two ever disagree -- the response's request is not the head
 * record -- the stale records are dropped and the transaction gets no phase timings rather than
 * someone else's.
 */
class ConnectionTimingTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val health: PersistenceHealth = PersistenceHealth,
) {
    private class SendRecord(val request: ParsedHttpMessage, val startedAt: Long, val sentAt: Long)

    private val lock = Any()
    private val sends = ArrayDeque<SendRecord>()
    private var setup: UpstreamSetupTimings? = null
    private var setupConsumed = false
    private var firstResponseByteAt: Long? = null

    /** Upstream setup phases become available once the lazy upstream leg connects. */
    fun attachSetup(timings: UpstreamSetupTimings) {
        synchronized(lock) { if (setup == null) setup = timings }
    }

    /** Called by the client->upstream leg right after the request bytes were written and flushed. */
    fun onRequestSent(request: ParsedHttpMessage, startedAt: Long, sentAt: Long = clock()) {
        synchronized(lock) { sends.addLast(SendRecord(request, startedAt, sentAt)) }
    }

    /**
     * Called by the upstream->client leg when bytes arrive for a response whose parse buffer was
     * empty -- i.e. the first byte of a new response. Idempotent until [completeResponse].
     */
    fun markFirstResponseByte(now: Long = clock()) {
        synchronized(lock) { if (firstResponseByteAt == null) firstResponseByteAt = now }
    }

    /**
     * Called once the response for [request] parsed completely. Returns the measured row (with
     * `trafficId` unset; the persistence queue fills it in) or null when this request was never
     * recorded through [onRequestSent].
     */
    fun completeResponse(request: ParsedHttpMessage, lastByteAt: Long = clock()): TransactionTimingEntity? {
        val record: SendRecord?
        val firstByteAt: Long?
        val setupForThis: UpstreamSetupTimings?
        synchronized(lock) {
            firstByteAt = firstResponseByteAt
            firstResponseByteAt = null
            val head = sends.firstOrNull()
            record = if (head != null && head.request === request) {
                sends.removeFirst()
            } else {
                // Pairing drifted from the engine's queue: never attribute someone else's phases.
                sends.clear()
                null
            }
            setupForThis = if (record != null && !setupConsumed) {
                setupConsumed = true
                setup
            } else null
        }
        if (record == null) return null
        return TransactionTimingEntity(
            trafficId = 0L,
            dnsMs = setupForThis?.dnsMs,
            connectMs = setupForThis?.connectMs,
            tlsMs = setupForThis?.tlsMs,
            sendMs = phase(record.startedAt, record.sentAt),
            waitMs = firstByteAt?.let { phase(record.sentAt, it) },
            receiveMs = firstByteAt?.let { phase(it, lastByteAt) },
            totalMs = phase(record.startedAt, lastByteAt),
            queuedAt = record.startedAt,
            firstByteAt = firstByteAt,
            lastByteAt = lastByteAt,
        )
    }

    /** Measured setup for a connection whose first transaction did not go through [completeResponse]. */
    fun takeSetup(): UpstreamSetupTimings? = synchronized(lock) {
        if (setupConsumed) return null
        setupConsumed = true
        setup
    }

    /** Number of requests sent and still awaiting their response. */
    val inFlight: Int get() = synchronized(lock) { sends.size }

    private fun phase(from: Long, to: Long): Long? = phaseOrNull(from, to, health)

    companion object {
        /**
         * `to - from`, or NULL (and one [PersistenceHealth.recordTimingUnderflow]) when the
         * difference is negative. Shared by the HTTP/1.x tracker and the HTTP/2 stream timings.
         */
        fun phaseOrNull(from: Long?, to: Long?, health: PersistenceHealth = PersistenceHealth): Long? {
            if (from == null || to == null) return null
            val d = to - from
            if (d < 0) {
                health.recordTimingUnderflow()
                return null
            }
            return d
        }
    }
}
