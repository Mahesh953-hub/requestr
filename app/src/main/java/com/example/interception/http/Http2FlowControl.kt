package com.example.interception.http

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * S3 task 57 (gate G27): RFC 9113 §5.2 / §6.9 flow control for one HTTP/2 connection.
 *
 * **Send side** ([reserve] / [onWindowUpdate] / [onInitialWindowSizeChanged]): the peer's
 * connection window and per-stream windows, decremented as DATA is written and replenished by
 * the peer's WINDOW_UPDATE frames. [reserve] blocks the writing relay leg while both windows are
 * empty -- that blocked read loop is the backpressure ("relay pool drives backpressure rather
 * than unbounded buffering"): a slow peer stops us reading from the other leg instead of making
 * us buffer its bytes. The wait is bounded by [waitTimeoutMs]; a peer that never opens its window
 * is treated as gone.
 *
 * **Receive side** ([onDataReceived] / [consumed]): our own windows towards the peer. A frame
 * that overflows either window is a FLOW_CONTROL_ERROR ([onDataReceived] returns `false`);
 * [consumed] reports how many bytes were handed on and returns the WINDOW_UPDATE increments
 * (connection, stream) to send once at least half a window has been released -- the endpoint
 * writes them, so this class never touches the wire.
 */
internal class Http2FlowControl(
    /** Our `SETTINGS_INITIAL_WINDOW_SIZE`: the receive window each new stream starts with. */
    val localInitialWindowSize: Int = DEFAULT_WINDOW_SIZE,
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    private val lock = ReentrantLock()
    private val windowOpened = lock.newCondition()

    // ── send side (peer's windows) ────────────────────────────────────────────────────────
    private var peerInitialWindowSize = DEFAULT_WINDOW_SIZE
    private var sendConnectionWindow = DEFAULT_WINDOW_SIZE.toLong()
    private val sendStreamWindows = HashMap<Int, Long>()

    // ── receive side (our windows) ────────────────────────────────────────────────────────
    private var recvConnectionWindow = DEFAULT_WINDOW_SIZE.toLong()
    private var recvConnectionPending = 0L
    private val recvStreamWindows = HashMap<Int, Long>()
    private val recvStreamPending = HashMap<Int, Long>()

    @Volatile private var closed = false

    /** Current peer connection window (diagnostics/tests). */
    val sendConnectionWindowSize: Long get() = lock.withLock { sendConnectionWindow }

    /** Current peer window for [streamId], or the initial size when the stream is unknown. */
    fun sendStreamWindowSize(streamId: Int): Long = lock.withLock { sendStreamWindows[streamId] ?: peerInitialWindowSize.toLong() }

    /** A stream came into existence on this connection (either side opened it). */
    fun openStream(streamId: Int) = lock.withLock {
        sendStreamWindows.putIfAbsent(streamId, peerInitialWindowSize.toLong())
        recvStreamWindows.putIfAbsent(streamId, localInitialWindowSize.toLong())
        recvStreamPending.putIfAbsent(streamId, 0L)
    }

    /** The stream is closed in both directions; drop its accounting and release any waiter. */
    fun closeStream(streamId: Int) = lock.withLock {
        sendStreamWindows.remove(streamId)
        recvStreamWindows.remove(streamId)
        recvStreamPending.remove(streamId)
        windowOpened.signalAll()
    }

    /** Wakes every blocked [reserve]; they then throw. Idempotent. */
    fun close() = lock.withLock {
        closed = true
        windowOpened.signalAll()
    }

    /**
     * Reserve up to [wanted] send bytes for [streamId], blocking while both windows are empty.
     * Returns the number of bytes granted (`> 0`, `<= wanted`), or throws when the connection
     * closed, the stream vanished, or the peer kept its window shut for [waitTimeoutMs].
     * `wanted == 0` (an empty END_STREAM DATA frame) never blocks and returns 0.
     */
    fun reserve(streamId: Int, wanted: Int): Int {
        if (wanted <= 0) return 0
        lock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(waitTimeoutMs)
            while (true) {
                if (closed) throw FlowControlException("connection closed while waiting for flow-control window")
                val streamWindow = sendStreamWindows[streamId]
                    ?: throw FlowControlException("stream $streamId closed while waiting for flow-control window")
                val grant = minOf(wanted.toLong(), sendConnectionWindow, streamWindow)
                if (grant > 0) {
                    sendConnectionWindow -= grant
                    sendStreamWindows[streamId] = streamWindow - grant
                    return grant.toInt()
                }
                if (remainingNs <= 0) {
                    throw FlowControlException("peer kept stream $streamId flow-control window closed for ${waitTimeoutMs} ms")
                }
                remainingNs = windowOpened.awaitNanos(remainingNs)
            }
        }
    }

    /**
     * Peer WINDOW_UPDATE. Returns `false` when the increment would push the window past
     * 2^31-1 (RFC 9113 §6.9.1: FLOW_CONTROL_ERROR -- connection error for stream 0, stream error
     * otherwise). An increment of 0 is a PROTOCOL_ERROR and also returns `false`.
     */
    fun onWindowUpdate(streamId: Int, increment: Int): Boolean = lock.withLock {
        if (increment <= 0) return false
        if (streamId == 0) {
            val next = sendConnectionWindow + increment
            if (next > MAX_WINDOW) return false
            sendConnectionWindow = next
        } else {
            val current = sendStreamWindows[streamId] ?: return true // closed stream: ignore
            val next = current + increment
            if (next > MAX_WINDOW) return false
            sendStreamWindows[streamId] = next
        }
        windowOpened.signalAll()
        true
    }

    /**
     * Peer changed `SETTINGS_INITIAL_WINDOW_SIZE` (RFC 9113 §6.9.2): every open stream's send
     * window moves by the delta, possibly negative. Returns `false` when a window would exceed
     * 2^31-1 (connection FLOW_CONTROL_ERROR).
     */
    fun onInitialWindowSizeChanged(newSize: Int): Boolean = lock.withLock {
        if (newSize < 0) return false
        val delta = newSize.toLong() - peerInitialWindowSize
        peerInitialWindowSize = newSize
        for ((id, w) in sendStreamWindows) {
            val next = w + delta
            if (next > MAX_WINDOW) return false
            sendStreamWindows[id] = next
        }
        if (delta > 0) windowOpened.signalAll()
        true
    }

    /**
     * Peer sent [length] DATA bytes (padding included) on [streamId]. Returns `false` when that
     * overflows our connection or stream window -- the caller answers with FLOW_CONTROL_ERROR.
     */
    fun onDataReceived(streamId: Int, length: Int): Boolean = lock.withLock {
        if (length < 0) return false
        if (recvConnectionWindow - length < 0) return false
        val streamWindow = recvStreamWindows[streamId]
        if (streamWindow != null && streamWindow - length < 0) return false
        recvConnectionWindow -= length
        if (streamWindow != null) recvStreamWindows[streamId] = streamWindow - length
        true
    }

    /**
     * [length] received bytes were handed downstream and may be re-credited. Returns the
     * `(connectionIncrement, streamIncrement)` the endpoint must announce -- each is 0 when the
     * released amount has not yet reached half of the respective window (RFC 9113 §6.9 leaves
     * the strategy to the receiver; half-window batching keeps WINDOW_UPDATE traffic low).
     */
    fun consumed(streamId: Int, length: Int): Pair<Int, Int> = lock.withLock {
        if (length <= 0) return 0 to 0
        recvConnectionPending += length
        var connectionIncrement = 0
        if (recvConnectionPending >= DEFAULT_WINDOW_SIZE / 2) {
            connectionIncrement = recvConnectionPending.toInt()
            recvConnectionWindow += connectionIncrement
            recvConnectionPending = 0
        }
        var streamIncrement = 0
        val pending = recvStreamPending[streamId]
        if (pending != null && recvStreamWindows.containsKey(streamId)) {
            val next = pending + length
            if (next >= localInitialWindowSize / 2) {
                streamIncrement = next.toInt()
                recvStreamWindows[streamId] = (recvStreamWindows[streamId] ?: 0L) + streamIncrement
                recvStreamPending[streamId] = 0
            } else {
                recvStreamPending[streamId] = next
            }
        }
        connectionIncrement to streamIncrement
    }

    class FlowControlException(message: String) : java.io.IOException(message)

    companion object {
        const val DEFAULT_WINDOW_SIZE = 65_535
        const val MAX_WINDOW = Int.MAX_VALUE.toLong()
        const val DEFAULT_WAIT_TIMEOUT_MS = 30_000L
    }
}
