package com.example.interception.http

import android.util.Log
import com.example.interception.http.Http2Parser.H2Frame
import com.example.interception.http.Http2Parser.H2Headers
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * S3 task 57 (D7 / gate G27): a real HTTP/2 peer over one blocking byte stream pair.
 *
 * In [Role.SERVER] this is what the intercepted app talks to (requestr terminates its h2
 * connection); in [Role.CLIENT] it is the connection requestr opens to the upstream server when
 * that server negotiated `h2`. The endpoint owns everything RFC 9113 makes a *connection*
 * concern -- preface, SETTINGS exchange and acknowledgement, PING, PRIORITY (accepted, ignored),
 * WINDOW_UPDATE/flow control ([Http2FlowControl]), GOAWAY and RST_STREAM in both directions,
 * HEADERS + CONTINUATION reassembly, HPACK state per direction, and the stream lifecycle
 * (idle -> open -> half-closed -> closed). Messages are handed to a [Listener] as decoded
 * ordered header lists ([H2Headers]) and DATA chunks; the listener never sees frames.
 *
 * Threading: [run] is the single reader (one relay leg). Writes are frame-atomic under one
 * lock, so frames from different streams written by different threads never interleave inside
 * a frame or a header block. [writeData] blocks the calling thread on the peer's flow-control
 * window -- deliberate backpressure, see [Http2FlowControl].
 *
 * Every frame parsed or written is also reported through [onFrameRead] / [onFrameWritten] so
 * the existing observer ([Http2Session]) keeps producing rows and gRPC events for the
 * client-facing connection without knowing whether the bytes were forwarded or terminated.
 */
internal class Http2Endpoint(
    private val input: InputStream,
    private val output: OutputStream,
    val role: Role,
    private val listener: Listener,
    private val name: String = role.name.lowercase(),
    private val onFrameRead: (H2Frame) -> Unit = {},
    private val onFrameWritten: (H2Frame) -> Unit = {},
    private val bytesRead: AtomicLong? = null,
    private val bytesWritten: AtomicLong? = null,
    val flowControl: Http2FlowControl = Http2FlowControl(),
    /** Our `SETTINGS_MAX_CONCURRENT_STREAMS` (server role) -- extra streams are REFUSED. */
    private val maxConcurrentStreams: Int = DEFAULT_MAX_CONCURRENT_STREAMS,
    /** Our `SETTINGS_MAX_HEADER_LIST_SIZE`; a bigger decoded list is a malformed message. */
    private val maxHeaderListSize: Int = DEFAULT_MAX_HEADER_LIST_SIZE,
) {
    enum class Role { SERVER, CLIENT }

    interface Listener {
        /**
         * A complete header block. [trailers] is `true` for the second block on a stream (HTTP
         * trailers). [endStream] mirrors END_STREAM on the block's initiating frame.
         */
        fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean)

        /** DATA payload with padding stripped. Called on the reader thread; blocking here is backpressure. */
        fun onData(streamId: Int, data: ByteArray, endStream: Boolean)

        /** Peer reset the stream (or we did, in response to a peer fault on that stream). */
        fun onRstStream(streamId: Int, errorCode: Int)

        /** Peer GOAWAY: streams above [lastStreamId] were never processed. */
        fun onGoAway(lastStreamId: Int, errorCode: Int)

        /** Both directions of [streamId] completed normally. */
        fun onStreamClosed(streamId: Int) {}

        /** Peer SETTINGS applied (after our ACK was queued). */
        fun onPeerSettings(settings: PeerSettings) {}
    }

    /** The peer's current SETTINGS (RFC 9113 §6.5.2 defaults until told otherwise). */
    class PeerSettings {
        @Volatile var headerTableSize: Int = 4096
        @Volatile var enablePush: Boolean = true
        @Volatile var maxConcurrentStreams: Int = Int.MAX_VALUE
        @Volatile var initialWindowSize: Int = Http2FlowControl.DEFAULT_WINDOW_SIZE
        @Volatile var maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE
        @Volatile var maxHeaderListSize: Int = Int.MAX_VALUE
    }

    /** RFC 9113 §7 error codes. */
    object ErrorCode {
        const val NO_ERROR = 0x0
        const val PROTOCOL_ERROR = 0x1
        const val INTERNAL_ERROR = 0x2
        const val FLOW_CONTROL_ERROR = 0x3
        const val SETTINGS_TIMEOUT = 0x4
        const val STREAM_CLOSED = 0x5
        const val FRAME_SIZE_ERROR = 0x6
        const val REFUSED_STREAM = 0x7
        const val CANCEL = 0x8
        const val COMPRESSION_ERROR = 0x9
        const val CONNECT_ERROR = 0xa
        const val ENHANCE_YOUR_CALM = 0xb
        const val INADEQUATE_SECURITY = 0xc
        const val HTTP_1_1_REQUIRED = 0xd
    }

    /** Connection-level fault: GOAWAY has been (best-effort) written; the connection is over. */
    class Http2ConnectionException(val errorCode: Int, message: String) : IOException(message)

    private class StreamState(val id: Int) {
        var localClosed = false
        var remoteClosed = false
        var headerBlocksReceived = 0
        var pendingBlock: ByteArrayOutputStream? = null
        var pendingEndStream = false
    }

    val peerSettings = PeerSettings()
    val hpackDecoder = Http2Parser.HpackDecoder()
    private val hpackEncoder = HpackEncoder()

    private val stateLock = Any()
    private val streams = HashMap<Int, StreamState>()
    private val recentlyReset = ArrayDeque<Int>()
    private var lastPeerStreamId = 0
    private var lastLocalStreamId = 0
    @Volatile private var goAwaySent = false
    @Volatile var goAwayReceived = false
        private set
    @Volatile private var closed = false
    private var peerSettingsSeen = false
    private var continuationStream = 0

    private val writeLock = Any()
    private val frameHeader = ByteArray(FRAME_HEADER_LENGTH)

    /** Highest peer-initiated stream id seen so far (for GOAWAY's last-stream-id). */
    val lastPeerStreamIdSeen: Int get() = synchronized(stateLock) { lastPeerStreamId }

    /** Number of streams currently tracked (not yet closed in both directions). */
    val openStreamCount: Int get() = synchronized(stateLock) { streams.size }

    fun isStreamOpen(streamId: Int): Boolean = synchronized(stateLock) { streams.containsKey(streamId) }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────

    /**
     * Send our connection preface: the 24-octet magic in client role, then SETTINGS. Must be
     * called once before [run]; in server role the client's preface is consumed by [run].
     */
    fun start() {
        if (role == Role.CLIENT) {
            synchronized(writeLock) {
                output.write(PREFACE)
                bytesWritten?.addAndGet(PREFACE.size.toLong())
            }
        }
        writeSettings()
    }

    /**
     * Read loop. Returns normally when the peer closes the connection (EOF) or after a GOAWAY
     * from either side has drained; throws [Http2ConnectionException] on a connection error
     * (GOAWAY already sent) or [IOException] on transport failure. Streams still open when the
     * loop ends are reported to the listener as [ErrorCode.CANCEL] resets.
     */
    fun run() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var filled = 0
        try {
            if (role == Role.SERVER) {
                while (filled < PREFACE.size) {
                    val n = input.read(buffer, filled, PREFACE.size - filled)
                    if (n < 0) throw EOFException("connection closed before the HTTP/2 preface")
                    filled += n
                    bytesRead?.addAndGet(n.toLong())
                }
                if (!Http2Parser.isPreface(buffer)) {
                    throw connectionError(ErrorCode.PROTOCOL_ERROR, "invalid client connection preface")
                }
                filled = 0
            }
            while (!closed) {
                // Frame header first: an oversized declared length is a FRAME_SIZE_ERROR straight
                // away instead of a 16 MiB buffering attempt.
                if (filled >= FRAME_HEADER_LENGTH) {
                    val length = ((buffer[0].toInt() and 0xFF) shl 16) or ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                    if (length > DEFAULT_MAX_FRAME_SIZE) {
                        throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "frame length $length exceeds SETTINGS_MAX_FRAME_SIZE $DEFAULT_MAX_FRAME_SIZE")
                    }
                    if (filled >= FRAME_HEADER_LENGTH + length) {
                        val frame = Http2Parser.parseFrames(buffer, 0, FRAME_HEADER_LENGTH + length).firstOrNull()
                            ?: throw connectionError(ErrorCode.PROTOCOL_ERROR, "unparseable frame header")
                        val consumed = FRAME_HEADER_LENGTH + length
                        System.arraycopy(buffer, consumed, buffer, 0, filled - consumed)
                        filled -= consumed
                        runCatching { onFrameRead(frame) }
                        handleFrame(frame)
                        continue
                    }
                }
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
                bytesRead?.addAndGet(n.toLong())
            }
        } finally {
            closed = true
            flowControl.close()
            val orphaned = synchronized(stateLock) { streams.keys.toList().also { streams.clear() } }
            for (id in orphaned) {
                flowControl.closeStream(id)
                runCatching { listener.onRstStream(id, ErrorCode.CANCEL) }
            }
        }
    }

    /**
     * Stop reading/writing; unblocks any writer parked on flow control and closes the
     * underlying streams so a [run] blocked in `read` on another thread returns. Idempotent.
     */
    fun close() {
        closed = true
        flowControl.close()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    // ── frame dispatch ────────────────────────────────────────────────────────────────────

    private fun handleFrame(frame: H2Frame) {
        if (continuationStream != 0 && (frame.type != Http2Parser.FRAME_CONTINUATION || frame.streamId != continuationStream)) {
            throw connectionError(ErrorCode.PROTOCOL_ERROR, "expected CONTINUATION for stream $continuationStream, got ${frame.typeName} on ${frame.streamId}")
        }
        if (!peerSettingsSeen) {
            if (frame.type != Http2Parser.FRAME_SETTINGS || (frame.flags and Http2Parser.FLAG_ACK) != 0) {
                throw connectionError(ErrorCode.PROTOCOL_ERROR, "first frame from peer must be SETTINGS, got ${frame.typeName}")
            }
            peerSettingsSeen = true
        }
        when (frame.type) {
            Http2Parser.FRAME_SETTINGS -> onSettings(frame)
            Http2Parser.FRAME_PING -> onPing(frame)
            Http2Parser.FRAME_WINDOW_UPDATE -> onWindowUpdate(frame)
            Http2Parser.FRAME_GOAWAY -> onGoAway(frame)
            Http2Parser.FRAME_PRIORITY -> onPriority(frame)
            Http2Parser.FRAME_RST_STREAM -> onRstStream(frame)
            Http2Parser.FRAME_HEADERS -> onHeaders(frame)
            Http2Parser.FRAME_CONTINUATION -> onContinuation(frame)
            Http2Parser.FRAME_DATA -> onData(frame)
            Http2Parser.FRAME_PUSH_PROMISE ->
                throw connectionError(ErrorCode.PROTOCOL_ERROR, "PUSH_PROMISE received (push is disabled)")
            else -> { /* RFC 9113 §4.1: unknown frame types are ignored */ }
        }
    }

    private fun onSettings(frame: H2Frame) {
        if (frame.streamId != 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "SETTINGS on stream ${frame.streamId}")
        if ((frame.flags and Http2Parser.FLAG_ACK) != 0) {
            if (frame.length != 0) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "SETTINGS ACK with payload")
            return
        }
        if (frame.length % 6 != 0) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "SETTINGS length ${frame.length} not a multiple of 6")
        for ((id, valueRaw) in Http2Parser.decodeSettings(frame)) {
            val value = valueRaw.toLong() and 0xFFFFFFFFL
            when (id) {
                Http2Parser.SETTINGS_HEADER_TABLE_SIZE -> {
                    val v = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    peerSettings.headerTableSize = v
                    hpackEncoder.setMaxHeaderTableSize(v)
                }
                Http2Parser.SETTINGS_ENABLE_PUSH -> {
                    if (value > 1) throw connectionError(ErrorCode.PROTOCOL_ERROR, "SETTINGS_ENABLE_PUSH=$value")
                    if (role == Role.CLIENT && value == 1L) throw connectionError(ErrorCode.PROTOCOL_ERROR, "server sent SETTINGS_ENABLE_PUSH=1")
                    peerSettings.enablePush = value == 1L
                }
                Http2Parser.SETTINGS_MAX_CONCURRENT_STREAMS -> peerSettings.maxConcurrentStreams = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE -> {
                    if (value > Http2FlowControl.MAX_WINDOW) throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "SETTINGS_INITIAL_WINDOW_SIZE=$value")
                    peerSettings.initialWindowSize = value.toInt()
                    if (!flowControl.onInitialWindowSizeChanged(value.toInt())) {
                        throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "SETTINGS_INITIAL_WINDOW_SIZE overflowed a stream window")
                    }
                }
                Http2Parser.SETTINGS_MAX_FRAME_SIZE -> {
                    if (value < DEFAULT_MAX_FRAME_SIZE || value > MAX_ALLOWED_FRAME_SIZE) throw connectionError(ErrorCode.PROTOCOL_ERROR, "SETTINGS_MAX_FRAME_SIZE=$value")
                    peerSettings.maxFrameSize = value.toInt()
                }
                Http2Parser.SETTINGS_MAX_HEADER_LIST_SIZE -> peerSettings.maxHeaderListSize = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> { /* unknown settings are ignored (RFC 9113 §6.5.2) */ }
            }
        }
        writeFrame(Http2Parser.FRAME_SETTINGS, Http2Parser.FLAG_ACK, 0, EMPTY)
        runCatching { listener.onPeerSettings(peerSettings) }
    }

    private fun onPing(frame: H2Frame) {
        if (frame.streamId != 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "PING on stream ${frame.streamId}")
        if (frame.length != 8) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "PING length ${frame.length}")
        if ((frame.flags and Http2Parser.FLAG_ACK) == 0) writeFrame(Http2Parser.FRAME_PING, Http2Parser.FLAG_ACK, 0, frame.payload)
    }

    private fun onWindowUpdate(frame: H2Frame) {
        if (frame.length != 4) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "WINDOW_UPDATE length ${frame.length}")
        val increment = readInt(frame.payload, 0) and 0x7FFFFFFF
        if (increment == 0) {
            if (frame.streamId == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE increment 0 on connection")
            resetStream(frame.streamId, ErrorCode.PROTOCOL_ERROR, notifyListener = true)
            return
        }
        if (!flowControl.onWindowUpdate(frame.streamId, increment)) {
            if (frame.streamId == 0) throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "connection window overflow")
            resetStream(frame.streamId, ErrorCode.FLOW_CONTROL_ERROR, notifyListener = true)
        }
    }

    private fun onGoAway(frame: H2Frame) {
        if (frame.streamId != 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "GOAWAY on stream ${frame.streamId}")
        if (frame.length < 8) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "GOAWAY length ${frame.length}")
        val lastStreamId = readInt(frame.payload, 0) and 0x7FFFFFFF
        val code = readInt(frame.payload, 4)
        goAwayReceived = true
        runCatching { listener.onGoAway(lastStreamId, code) }
        // Streams the peer never processed will not complete -- report them as refused.
        val refused = synchronized(stateLock) {
            streams.keys.filter { id -> isLocalStream(id) && id > lastStreamId }.also { ids -> ids.forEach { streams.remove(it) } }
        }
        for (id in refused) {
            flowControl.closeStream(id)
            runCatching { listener.onRstStream(id, ErrorCode.REFUSED_STREAM) }
        }
    }

    private fun onPriority(frame: H2Frame) {
        if (frame.streamId == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "PRIORITY on stream 0")
        if (frame.length != 5) resetStream(frame.streamId, ErrorCode.FRAME_SIZE_ERROR, notifyListener = false)
        // Otherwise accepted and ignored: this proxy does not reprioritise.
    }

    private fun onRstStream(frame: H2Frame) {
        if (frame.streamId == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "RST_STREAM on stream 0")
        if (frame.length != 4) throw connectionError(ErrorCode.FRAME_SIZE_ERROR, "RST_STREAM length ${frame.length}")
        val code = readInt(frame.payload, 0)
        val known = synchronized(stateLock) {
            if (isIdle(frame.streamId)) throw connectionError(ErrorCode.PROTOCOL_ERROR, "RST_STREAM on idle stream ${frame.streamId}")
            if (continuationStream == frame.streamId) continuationStream = 0
            streams.remove(frame.streamId) != null
        }
        flowControl.closeStream(frame.streamId)
        if (known) runCatching { listener.onRstStream(frame.streamId, code) }
    }

    private fun onHeaders(frame: H2Frame) {
        val streamId = frame.streamId
        if (streamId == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "HEADERS on stream 0")
        val payload = try {
            stripHeadersPaddingAndPriority(frame)
        } catch (e: Http2ConnectionException) {
            throw connectionError(e.errorCode, e.message ?: "malformed HEADERS")
        }
        var refuse = false
        var streamClosed = false
        val state: StreamState? = synchronized(stateLock) {
            if (isRecentlyReset(streamId)) return
            val existing = streams[streamId]
            if (existing == null) {
                if (role == Role.SERVER) {
                    if (streamId % 2 == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "client opened even stream $streamId")
                    if (streamId <= lastPeerStreamId) throw connectionError(ErrorCode.PROTOCOL_ERROR, "stream $streamId reused (last was $lastPeerStreamId)")
                    lastPeerStreamId = streamId
                    if (goAwaySent) { rememberReset(streamId); return }
                    val active = streams.keys.count { !isLocalStream(it) }
                    if (active >= maxConcurrentStreams) {
                        rememberReset(streamId)
                        refuse = true
                        null
                    } else {
                        StreamState(streamId).also { streams[streamId] = it; flowControl.openStream(streamId) }
                    }
                } else {
                    // Client role: the server can only send on streams we opened (push is off).
                    throw connectionError(ErrorCode.PROTOCOL_ERROR, "server sent HEADERS on unknown stream $streamId")
                }
            } else if (existing.remoteClosed) {
                streamClosed = true
                null
            } else {
                existing
            }
        }
        if (refuse) { writeRstStream(streamId, ErrorCode.REFUSED_STREAM); return }
        if (streamClosed) { resetStream(streamId, ErrorCode.STREAM_CLOSED, notifyListener = true); return }
        if (state == null) return
        state.pendingEndStream = frame.endStream
        if (frame.endHeaders) {
            deliverHeaderBlock(state, payload)
        } else {
            state.pendingBlock = ByteArrayOutputStream(payload.size * 2).also { it.write(payload) }
            continuationStream = streamId
        }
    }

    private fun onContinuation(frame: H2Frame) {
        if (frame.streamId == 0 || frame.streamId != continuationStream) {
            throw connectionError(ErrorCode.PROTOCOL_ERROR, "unexpected CONTINUATION on stream ${frame.streamId}")
        }
        val state = synchronized(stateLock) { streams[frame.streamId] } ?: run { continuationStream = 0; return }
        val block = state.pendingBlock ?: throw connectionError(ErrorCode.PROTOCOL_ERROR, "CONTINUATION without HEADERS")
        if (block.size() + frame.length > MAX_HEADER_BLOCK_BYTES) {
            throw connectionError(ErrorCode.ENHANCE_YOUR_CALM, "header block exceeds $MAX_HEADER_BLOCK_BYTES bytes")
        }
        block.write(frame.payload)
        if (!frame.endHeaders) return
        continuationStream = 0
        state.pendingBlock = null
        deliverHeaderBlock(state, block.toByteArray())
    }

    private fun deliverHeaderBlock(state: StreamState, block: ByteArray) {
        val synthetic = H2Frame(block.size, Http2Parser.FRAME_HEADERS, Http2Parser.FLAG_END_HEADERS or (if (state.pendingEndStream) Http2Parser.FLAG_END_STREAM else 0), state.id, block)
        val headers = Http2Parser.decodeHeaders(synthetic, block, hpackDecoder)
        if (headers.error != null) {
            throw connectionError(ErrorCode.COMPRESSION_ERROR, "HPACK: ${headers.error}")
        }
        val trailers = state.headerBlocksReceived > 0
        state.headerBlocksReceived++
        val violation = Http2Translator.validate(headers, isRequest = role == Role.SERVER, trailers = trailers, maxHeaderListSize = maxHeaderListSize)
        if (violation != null) {
            Log.w(TAG, "[$name] malformed ${if (trailers) "trailers" else "headers"} on stream ${state.id}: $violation")
            resetStream(state.id, ErrorCode.PROTOCOL_ERROR, notifyListener = true)
            return
        }
        if (trailers && !headers.endStream) {
            resetStream(state.id, ErrorCode.PROTOCOL_ERROR, notifyListener = true) // trailers must end the stream
            return
        }
        val endStream = headers.endStream
        if (endStream) state.remoteClosed = true
        listener.onHeaders(state.id, headers, endStream, trailers)
        if (endStream) maybeCloseStream(state)
    }

    private fun onData(frame: H2Frame) {
        val streamId = frame.streamId
        if (streamId == 0) throw connectionError(ErrorCode.PROTOCOL_ERROR, "DATA on stream 0")
        var reset = false
        val state = synchronized(stateLock) {
            if (isRecentlyReset(streamId)) reset = true
            else if (isIdle(streamId)) throw connectionError(ErrorCode.PROTOCOL_ERROR, "DATA on idle stream $streamId")
            streams[streamId]
        }
        if (reset) {
            // A stream we already reset: its bytes still count against the connection window
            // (RFC 9113 §6.9.1); re-credit immediately since nobody will consume them.
            if (!flowControl.onDataReceived(0, frame.length)) throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "connection window exceeded")
            val (conn, _) = flowControl.consumed(0, frame.length)
            if (conn > 0) writeWindowUpdate(0, conn)
            return
        }
        if (state == null || state.remoteClosed) {
            if (!flowControl.onDataReceived(0, frame.length)) throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "connection window exceeded")
            val (conn, _) = flowControl.consumed(0, frame.length)
            if (conn > 0) writeWindowUpdate(0, conn)
            resetStream(streamId, ErrorCode.STREAM_CLOSED, notifyListener = true)
            return
        }
        if (state.headerBlocksReceived == 0) {
            resetStream(streamId, ErrorCode.PROTOCOL_ERROR, notifyListener = true) // DATA before HEADERS
            return
        }
        if ((frame.flags and Http2Parser.FLAG_PADDED) != 0 && (frame.payload.isEmpty() || (frame.payload[0].toInt() and 0xFF) >= frame.payload.size)) {
            throw connectionError(ErrorCode.PROTOCOL_ERROR, "DATA padding exceeds payload")
        }
        if (!flowControl.onDataReceived(streamId, frame.length)) {
            throw connectionError(ErrorCode.FLOW_CONTROL_ERROR, "stream $streamId exceeded its receive window")
        }
        val data = Http2Parser.stripDataPadding(frame)
        if (frame.endStream) state.remoteClosed = true
        listener.onData(streamId, data, frame.endStream)
        val (conn, stream) = flowControl.consumed(streamId, frame.length)
        if (conn > 0) writeWindowUpdate(0, conn)
        if (stream > 0 && !state.remoteClosed) writeWindowUpdate(streamId, stream)
        if (frame.endStream) maybeCloseStream(state)
    }

    // ── stream bookkeeping ────────────────────────────────────────────────────────────────

    private fun isLocalStream(id: Int) = if (role == Role.CLIENT) id % 2 == 1 else id % 2 == 0

    /** Must hold [stateLock]. */
    private fun isIdle(id: Int): Boolean {
        if (streams.containsKey(id) || isRecentlyReset(id)) return false
        return if (isLocalStream(id)) id > lastLocalStreamId else id > lastPeerStreamId
    }

    /** Must hold [stateLock]. */
    private fun isRecentlyReset(id: Int) = recentlyReset.contains(id)

    /** Must hold [stateLock]. */
    private fun rememberReset(id: Int) {
        recentlyReset.addLast(id)
        while (recentlyReset.size > RECENT_RESET_MEMORY) recentlyReset.removeFirst()
    }

    private fun maybeCloseStream(state: StreamState) {
        val closedNow = synchronized(stateLock) {
            if (state.localClosed && state.remoteClosed && streams.remove(state.id) != null) true else false
        }
        if (closedNow) {
            flowControl.closeStream(state.id)
            runCatching { listener.onStreamClosed(state.id) }
        }
    }

    /** Stream error: RST_STREAM out, forget the stream, optionally tell the listener. */
    private fun resetStream(streamId: Int, errorCode: Int, notifyListener: Boolean) {
        val known = synchronized(stateLock) {
            if (continuationStream == streamId) continuationStream = 0
            rememberReset(streamId)
            streams.remove(streamId) != null
        }
        flowControl.closeStream(streamId)
        writeRstStream(streamId, errorCode)
        if (known && notifyListener) runCatching { listener.onRstStream(streamId, errorCode) }
    }

    private fun connectionError(code: Int, message: String): Http2ConnectionException {
        Log.w(TAG, "[$name] connection error ${Http2Parser.errorCodeName(intBytes(code))}: $message")
        runCatching { writeGoAway(code, message) }
        close()
        return Http2ConnectionException(code, message)
    }

    // ── writer API ────────────────────────────────────────────────────────────────────────

    /**
     * Send a header block on [streamId] (HEADERS plus CONTINUATION frames as needed so no frame
     * exceeds the peer's `SETTINGS_MAX_FRAME_SIZE`). In client role a new odd [streamId] opens
     * the stream; it must be greater than every stream opened before. Returns `false` when the
     * stream is not writable (already closed/reset) -- nothing is written then.
     */
    fun writeHeaders(streamId: Int, entries: List<Pair<String, String>>, endStream: Boolean): Boolean {
        require(streamId > 0) { "stream id must be positive" }
        val state = synchronized(stateLock) {
            val existing = streams[streamId]
            when {
                existing != null -> existing
                role == Role.CLIENT && isLocalStream(streamId) -> {
                    require(streamId > lastLocalStreamId) { "stream $streamId is not above the last opened stream $lastLocalStreamId" }
                    if (goAwayReceived || closed) return false
                    lastLocalStreamId = streamId
                    StreamState(streamId).also { streams[streamId] = it; flowControl.openStream(streamId) }
                }
                else -> return false
            }
        }
        if (state.localClosed) return false
        synchronized(writeLock) {
            val block = hpackEncoder.encode(entries)
            val max = peerSettings.maxFrameSize
            var offset = 0
            var first = true
            while (first || offset < block.size) {
                val n = minOf(max, block.size - offset)
                val last = offset + n >= block.size
                val type = if (first) Http2Parser.FRAME_HEADERS else Http2Parser.FRAME_CONTINUATION
                var flags = 0
                if (last) flags = flags or Http2Parser.FLAG_END_HEADERS
                if (first && endStream) flags = flags or Http2Parser.FLAG_END_STREAM
                writeFrameLocked(type, flags, streamId, block, offset, n)
                offset += n
                first = false
            }
        }
        if (endStream) {
            state.localClosed = true
            maybeCloseStream(state)
        }
        return true
    }

    /**
     * Send [len] body bytes on [streamId], honouring the peer's flow-control windows and
     * `SETTINGS_MAX_FRAME_SIZE`. Blocks while the window is closed (see [Http2FlowControl]).
     * Returns `false` when the stream is not writable.
     */
    fun writeData(streamId: Int, data: ByteArray, offset: Int = 0, len: Int = data.size - offset, endStream: Boolean): Boolean {
        val state = synchronized(stateLock) { streams[streamId] } ?: return false
        if (state.localClosed) return false
        var pos = offset
        var remaining = len
        if (remaining == 0) {
            if (endStream) writeFrame(Http2Parser.FRAME_DATA, Http2Parser.FLAG_END_STREAM, streamId, EMPTY)
        }
        while (remaining > 0) {
            val granted = try {
                flowControl.reserve(streamId, minOf(remaining, peerSettings.maxFrameSize))
            } catch (e: Http2FlowControl.FlowControlException) {
                if (synchronized(stateLock) { streams.containsKey(streamId) }) {
                    resetStream(streamId, ErrorCode.FLOW_CONTROL_ERROR, notifyListener = false)
                }
                throw e
            }
            val last = granted == remaining
            writeFrame(Http2Parser.FRAME_DATA, if (last && endStream) Http2Parser.FLAG_END_STREAM else 0, streamId, data, pos, granted)
            pos += granted
            remaining -= granted
        }
        if (endStream) {
            state.localClosed = true
            maybeCloseStream(state)
        }
        return true
    }

    /** RST_STREAM out; the stream is forgotten locally. Safe on unknown streams (frame still sent). */
    fun writeRstStream(streamId: Int, errorCode: Int) {
        synchronized(stateLock) {
            if (continuationStream == streamId) continuationStream = 0
            if (streams.remove(streamId) != null) rememberReset(streamId)
        }
        flowControl.closeStream(streamId)
        if (!closed) runCatching { writeFrame(Http2Parser.FRAME_RST_STREAM, 0, streamId, intBytes(errorCode)) }
    }

    /** GOAWAY with our last processed peer stream id. Only the first call writes a frame. */
    fun writeGoAway(errorCode: Int, debug: String = "") {
        if (goAwaySent) return
        goAwaySent = true
        val debugBytes = debug.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(8 + debugBytes.size)
        writeInt(payload, 0, lastPeerStreamIdSeen)
        writeInt(payload, 4, errorCode)
        System.arraycopy(debugBytes, 0, payload, 8, debugBytes.size)
        writeFrame(Http2Parser.FRAME_GOAWAY, 0, 0, payload)
    }

    fun writePing(payload: ByteArray = ByteArray(8), ack: Boolean = false) {
        require(payload.size == 8)
        writeFrame(Http2Parser.FRAME_PING, if (ack) Http2Parser.FLAG_ACK else 0, 0, payload)
    }

    fun writeWindowUpdate(streamId: Int, increment: Int) {
        writeFrame(Http2Parser.FRAME_WINDOW_UPDATE, 0, streamId, intBytes(increment))
    }

    private fun writeSettings() {
        val out = ByteArrayOutputStream(36)
        fun setting(id: Int, value: Int) {
            out.write(id ushr 8); out.write(id)
            out.write(value ushr 24); out.write(value ushr 16); out.write(value ushr 8); out.write(value)
        }
        setting(Http2Parser.SETTINGS_HEADER_TABLE_SIZE, Http2Parser.HpackDecoder.DEFAULT_TABLE_SIZE)
        setting(Http2Parser.SETTINGS_ENABLE_PUSH, 0)
        setting(Http2Parser.SETTINGS_MAX_CONCURRENT_STREAMS, maxConcurrentStreams)
        setting(Http2Parser.SETTINGS_INITIAL_WINDOW_SIZE, flowControl.localInitialWindowSize)
        setting(Http2Parser.SETTINGS_MAX_FRAME_SIZE, DEFAULT_MAX_FRAME_SIZE)
        setting(Http2Parser.SETTINGS_MAX_HEADER_LIST_SIZE, maxHeaderListSize)
        writeFrame(Http2Parser.FRAME_SETTINGS, 0, 0, out.toByteArray())
    }

    private fun writeFrame(type: Int, flags: Int, streamId: Int, payload: ByteArray, offset: Int = 0, len: Int = payload.size - offset) {
        synchronized(writeLock) { writeFrameLocked(type, flags, streamId, payload, offset, len) }
    }

    /** Must hold [writeLock]. */
    private fun writeFrameLocked(type: Int, flags: Int, streamId: Int, payload: ByteArray, offset: Int, len: Int) {
        frameHeader[0] = (len ushr 16).toByte(); frameHeader[1] = (len ushr 8).toByte(); frameHeader[2] = len.toByte()
        frameHeader[3] = type.toByte(); frameHeader[4] = flags.toByte()
        writeInt(frameHeader, 5, streamId and 0x7FFFFFFF)
        output.write(frameHeader)
        if (len > 0) output.write(payload, offset, len)
        output.flush()
        bytesWritten?.addAndGet((FRAME_HEADER_LENGTH + len).toLong())
        runCatching { onFrameWritten(H2Frame(len, type, flags, streamId, payload.copyOfRange(offset, offset + len))) }
    }

    companion object {
        private const val TAG = "Http2Endpoint"
        const val FRAME_HEADER_LENGTH = 9
        const val DEFAULT_MAX_FRAME_SIZE = 16_384
        const val MAX_ALLOWED_FRAME_SIZE = 16_777_215
        const val DEFAULT_MAX_CONCURRENT_STREAMS = 128
        const val DEFAULT_MAX_HEADER_LIST_SIZE = 256 * 1024
        /** Compressed header block cap across HEADERS + CONTINUATION (ENHANCE_YOUR_CALM above). */
        const val MAX_HEADER_BLOCK_BYTES = 512 * 1024
        private const val READ_BUFFER_SIZE = 2 * (FRAME_HEADER_LENGTH + DEFAULT_MAX_FRAME_SIZE)
        private const val RECENT_RESET_MEMORY = 64
        private val EMPTY = ByteArray(0)
        val PREFACE: ByteArray = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        internal fun stripHeadersPaddingAndPriority(frame: H2Frame): ByteArray {
            var start = 0
            var end = frame.payload.size
            if ((frame.flags and Http2Parser.FLAG_PADDED) != 0) {
                if (end < 1) throw Http2ConnectionException(ErrorCode.PROTOCOL_ERROR, "PADDED HEADERS with empty payload")
                val pad = frame.payload[0].toInt() and 0xFF
                start = 1
                end -= pad
                if (end < start) throw Http2ConnectionException(ErrorCode.PROTOCOL_ERROR, "HEADERS padding exceeds payload")
            }
            if ((frame.flags and Http2Parser.FLAG_PRIORITY) != 0) {
                start += 5
                if (end < start) throw Http2ConnectionException(ErrorCode.PROTOCOL_ERROR, "HEADERS priority fields truncated")
            }
            return frame.payload.copyOfRange(start, end)
        }

        internal fun readInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

        internal fun writeInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
        }

        internal fun intBytes(v: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, v) }
    }
}
