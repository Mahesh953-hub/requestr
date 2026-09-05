package com.example.interception.http

import android.util.Log
import com.example.interception.grpc.GrpcDetector
import java.io.ByteArrayOutputStream

/**
 * Stateful per-connection HTTP/2 coordinator for the MITM proxy.
 *
 * [MitmProxyEngine]'s h2 relay loops (used once ALPN negotiates `h2` on the client-facing leg —
 * see its class doc) forward every byte, unmodified, the instant it is read; this class never
 * sees that responsibility. It is handed a *copy* of each direction's parsed [Http2Parser.H2Frame]s
 * purely to explain what they mean: HPACK-decompress HEADERS/CONTINUATION blocks, pair each
 * stream's request with its response, and emit exactly one [Http2Transaction] per non-gRPC
 * stream (when the response completes) or one [GrpcEvent] per gRPC message (matching
 * [com.example.interception.CaptureEngine]'s existing VPN-capture-path convention of emitting
 * gRPC per-message, not per-call).
 *
 * Two independent [Http2Parser.HpackDecoder] instances are kept — one per direction — because
 * RFC 7541 dynamic tables are per-direction: a client→server HEADERS block and a
 * server→client HEADERS block on the very same stream id are compressed against two
 * completely separate tables.
 *
 * Not thread-safe by itself; [onClientFrames]/[onServerFrames] serialize on [lock] since the
 * two relay directions run on independent threads but share this session and its dynamic
 * tables/stream map.
 */
internal class Http2Session(
    private val onTransaction: (Http2Transaction) -> Unit,
    private val onGrpcMessage: (GrpcEvent) -> Unit,
    /**
     * S3 task 20: one gRPC call = one `captured_traffic` parent row. Fired once per stream when
     * the request HEADERS identify a gRPC call (before any message), and once when the stream
     * ends (trailers seen with END_STREAM, or RST_STREAM) with the status and frame timeline.
     */
    private val onGrpcCallStarted: (GrpcCallStart) -> Unit = {},
    private val onGrpcCallEnded: (GrpcCallEnd) -> Unit = {},
    /**
     * Bound on concurrently-tracked streams (P1 fix): the old `HashMap` grew without limit for
     * the lifetime of the connection, so a server/client opening many streams and never
     * completing them (deliberately or not) grew this map forever. Backed by a
     * `LinkedHashMap(accessOrder=false, insertion order)` capped at [maxStreams] -- once full,
     * the OLDEST still-open stream is evicted (its state discarded, so that stream's
     * transaction/gRPC messages are simply never emitted) rather than growing further. Mirrors
     * [com.example.interception.ExpiringLruCertCache]'s same "bounded eviction over unbounded
     * growth" trade for a background relay component.
     */
    private val maxStreams: Int = DEFAULT_MAX_STREAMS,
    /**
     * Bound on cumulative bytes buffered per direction per stream (P1 fix): [StreamState]'s
     * `requestBody`/`responseBody` used to grow with every DATA frame for the life of the
     * stream -- fine for ordinary bodies, a DoS vector for a server/client that streams
     * indefinitely (chunked bodies, long-lived gRPC streams) without ever setting END_STREAM.
     * Bytes beyond this cap are dropped (not buffered), so [maybeEmit]/[drainGrpc] still see a
     * (truncated) body instead of buffering forever.
     */
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    private val clientHpack = Http2Parser.HpackDecoder()
    private val serverHpack = Http2Parser.HpackDecoder()

    /** Header blocks that violated RFC 7541 on this connection (see [Http2Parser.H2Headers.error]). */
    @Volatile var hpackErrorCount: Int = 0
        private set
    private val streams = object : LinkedHashMap<Int, StreamState>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, StreamState>): Boolean =
            size > maxStreams
    }
    private val lock = Any()

    private class StreamState {
        var requestHeaders: Http2Parser.H2Headers? = null
        var responseHeaders: Http2Parser.H2Headers? = null
        val requestBody = ByteArrayOutputStream()
        val responseBody = ByteArrayOutputStream()
        var requestEnded = false
        var responseEnded = false
        val startedAt = System.currentTimeMillis()
        // S2 task 18: observed instants (first-set wins) for the measured phase split.
        var requestEndedAt: Long? = null
        var responseHeadersAt: Long? = null
        var responseEndedAt: Long? = null

        fun markRequestEnded() {
            requestEnded = true
            if (requestEndedAt == null) requestEndedAt = System.currentTimeMillis()
        }

        fun markResponseEnded() {
            responseEnded = true
            if (responseEndedAt == null) responseEndedAt = System.currentTimeMillis()
        }
        var grpcCall: GrpcDetector.GrpcCall? = null
        var grpcStatus: Int? = null
        var grpcMessage: String? = null
        var grpcMessageIndex = 0
        var grpcEnded = false
        // S3 task 20: trailers are kept, not discarded (RFC 9113 §8.1 trailer section).
        var requestTrailers: Http2Parser.H2Headers? = null
        var responseTrailers: Http2Parser.H2Headers? = null
        // S3 task 20: per-stream frame timeline for `http2_stream_events` (bounded).
        val frameEvents = ArrayList<H2FrameEvent>()
        // CONTINUATION coalescing (one in-flight header block per direction per stream --
        // RFC 7540 §6.10 forbids interleaving another frame on the SAME stream mid-block, but
        // request and response header blocks on the same stream id are still independent).
        var pendingClientHeaderFrame: Http2Parser.H2Frame? = null
        var pendingClientHeaderBlock: ByteArrayOutputStream? = null
        var pendingServerHeaderFrame: Http2Parser.H2Frame? = null
        var pendingServerHeaderBlock: ByteArrayOutputStream? = null
    }

    fun onClientFrames(frames: List<Http2Parser.H2Frame>) {
        synchronized(lock) { for (frame in frames) handleFrame(frame, fromClient = true) }
    }

    fun onServerFrames(frames: List<Http2Parser.H2Frame>) {
        synchronized(lock) { for (frame in frames) handleFrame(frame, fromClient = false) }
    }

    /**
     * P1 fix: connection-wide cleanup, callable any number of times from any thread. Wired to
     * two triggers -- a GOAWAY frame observed in [handleFrame] (RFC 7540 §6.8: the sender will
     * not initiate/accept any new streams; any stream already tracked here has no chance of
     * completing normally) and ordinary connection close (both of [MitmProxyEngine]'s h2 relay
     * loops call this from their `finally` block once their read loop ends, whichever direction
     * notices first). Idempotent: clearing an already-empty map is a no-op.
     */
    fun close() {
        synchronized(lock) { streams.clear() }
    }

    private fun handleFrame(frame: Http2Parser.H2Frame, fromClient: Boolean) {
        // Stream 0 carries connection-level frames (SETTINGS/PING/WINDOW_UPDATE -- never
        // per-stream header/body state -- and GOAWAY, which is the P1-fix trigger for [close]).
        if (frame.streamId == 0) {
            if (frame.type == Http2Parser.FRAME_GOAWAY) close()
            return
        }
        val state = streams.getOrPut(frame.streamId) { StreamState() }
        recordFrame(frame, fromClient, state)
        when (frame.type) {
            Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_PUSH_PROMISE ->
                onHeaderFrame(frame, fromClient, state)
            Http2Parser.FRAME_CONTINUATION ->
                onContinuationFrame(frame, fromClient, state)
            Http2Parser.FRAME_DATA ->
                onDataFrame(frame, fromClient, state)
            Http2Parser.FRAME_RST_STREAM -> {
                // A reset gRPC call still gets its parent row closed out (status unknown) so the
                // messages already written are not left dangling under an "in progress" call.
                if (state.grpcCall != null) endGrpc(frame.streamId, state)
                streams.remove(frame.streamId)
            }
            else -> { /* PRIORITY etc. carry nothing this session tracks */ }
        }
    }

    private fun recordFrame(frame: Http2Parser.H2Frame, fromClient: Boolean, state: StreamState) {
        if (state.frameEvents.size >= MAX_FRAME_EVENTS) return
        val note = when (frame.type) {
            Http2Parser.FRAME_RST_STREAM -> "RST_STREAM ${Http2Parser.errorCodeName(frame.payload)}"
            Http2Parser.FRAME_HEADERS, Http2Parser.FRAME_CONTINUATION, Http2Parser.FRAME_DATA -> buildList {
                if (frame.endStream && frame.type != Http2Parser.FRAME_CONTINUATION) add("END_STREAM")
                if (frame.endHeaders && frame.type != Http2Parser.FRAME_DATA) add("END_HEADERS")
            }.joinToString(" ").ifEmpty { null }
            else -> null
        }
        state.frameEvents += H2FrameEvent(
            streamId = frame.streamId,
            fromClient = fromClient,
            frameType = frame.type,
            flags = frame.flags,
            payloadLength = frame.payload.size,
            timestampMs = System.currentTimeMillis(),
            note = note,
        )
    }

    private fun onHeaderFrame(frame: Http2Parser.H2Frame, fromClient: Boolean, state: StreamState) {
        if (frame.endHeaders) {
            applyHeaders(frame, frame.payload, fromClient, state)
        } else {
            // Header block continues in one or more CONTINUATION frames — buffer until END_HEADERS.
            if (fromClient) {
                state.pendingClientHeaderFrame = frame
                state.pendingClientHeaderBlock = ByteArrayOutputStream().apply { write(frame.payload) }
            } else {
                state.pendingServerHeaderFrame = frame
                state.pendingServerHeaderBlock = ByteArrayOutputStream().apply { write(frame.payload) }
            }
        }
    }

    private fun onContinuationFrame(frame: Http2Parser.H2Frame, fromClient: Boolean, state: StreamState) {
        val pendingFrame = if (fromClient) state.pendingClientHeaderFrame else state.pendingServerHeaderFrame
        val pendingBlock = if (fromClient) state.pendingClientHeaderBlock else state.pendingServerHeaderBlock
        if (pendingFrame == null || pendingBlock == null) return // stray CONTINUATION, nothing to coalesce
        pendingBlock.write(frame.payload)
        if (!frame.endHeaders) return // still more CONTINUATION frames to come
        // The initiating HEADERS frame's own END_STREAM flag (set when there is no body at
        // all) must survive into the merged frame; END_HEADERS is now true since this
        // CONTINUATION closed the block.
        val merged = pendingFrame.copy(
            flags = pendingFrame.flags or Http2Parser.FLAG_END_HEADERS,
            payload = pendingBlock.toByteArray(),
        )
        if (fromClient) {
            state.pendingClientHeaderFrame = null
            state.pendingClientHeaderBlock = null
        } else {
            state.pendingServerHeaderFrame = null
            state.pendingServerHeaderBlock = null
        }
        applyHeaders(merged, merged.payload, fromClient, state)
    }

    private fun applyHeaders(frame: Http2Parser.H2Frame, payload: ByteArray, fromClient: Boolean, state: StreamState) {
        val decoder = if (fromClient) clientHpack else serverHpack
        val headers = runCatching { Http2Parser.decodeHeaders(frame, payload, decoder) }
            .onFailure { e -> Log.w(TAG, "HPACK decode failed on stream ${frame.streamId}: ${e.message}") }
            .getOrNull() ?: return
        if (headers.error != null) {
            // S3 task 23: observe-only today, so keep whatever decoded before the fault (the
            // stream is still worth a row) but count it -- a terminating endpoint (task 57)
            // answers this with GOAWAY(COMPRESSION_ERROR) instead.
            hpackErrorCount++
            Log.w(TAG, "HPACK violation on stream ${frame.streamId} (${if (fromClient) "client" else "server"}): ${headers.error}")
        }

        if (fromClient) {
            if (state.requestHeaders == null) {
                state.requestHeaders = headers
                val call = GrpcDetector.callFromHeaders(headers)
                state.grpcCall = call
                if (call != null) {
                    runCatching { onGrpcCallStarted(GrpcCallStart(call, headers, state.startedAt)) }
                        .onFailure { e -> Log.w(TAG, "gRPC call-start callback failed: ${e.message}") }
                }
            } else if (state.requestTrailers == null) {
                state.requestTrailers = headers
            }
            if (headers.endStream) state.markRequestEnded()
        } else {
            if (state.responseHeaders == null) {
                state.responseHeaders = headers
                state.responseHeadersAt = System.currentTimeMillis()
                // gRPC "trailers-only" response (gRPC-over-HTTP/2 spec): a single HEADERS block
                // with END_STREAM carrying :status AND grpc-status -- status lives in the headers.
                if (state.grpcCall != null && headers.endStream) readGrpcStatus(state, headers)
            } else {
                // A second HEADERS block on the response side is HTTP/2 trailers (RFC 7540
                // §8.1) -- gRPC's grpc-status/grpc-message live here.
                if (state.responseTrailers == null) state.responseTrailers = headers
                if (state.grpcCall != null) readGrpcStatus(state, headers)
            }
            if (headers.endStream) state.markResponseEnded()
        }
        maybeEmit(frame.streamId, state)
    }

    private fun onDataFrame(frame: Http2Parser.H2Frame, fromClient: Boolean, state: StreamState) {
        // P1 fix: strip RFC 7540 §6.1 padding before it ever reaches body/gRPC reassembly --
        // see Http2Parser.stripDataPadding's KDoc for why this was missing.
        val data = Http2Parser.stripDataPadding(frame)
        val grpcCall = state.grpcCall
        if (fromClient) {
            if (grpcCall != null) drainGrpc(grpcCall, state, isClient = true, data)
            else writeBounded(state.requestBody, data)
            if (frame.endStream) state.markRequestEnded()
        } else {
            if (grpcCall != null) drainGrpc(grpcCall, state, isClient = false, data)
            else writeBounded(state.responseBody, data)
            if (frame.endStream) state.markResponseEnded()
        }
        maybeEmit(frame.streamId, state)
    }

    /**
     * P1 fix: writes at most [maxBodyBytes] cumulative bytes into [buffer] across the whole
     * stream lifetime; anything beyond the cap is silently dropped rather than buffered, so a
     * pathologically large or never-ending body cannot grow this session's memory use without
     * bound. [maybeEmit]/[buildRequestMessage]/[buildResponseMessage] still run against
     * whatever did fit -- a truncated body is still useful signal, unlike refusing to emit at
     * all.
     */
    private fun writeBounded(buffer: ByteArrayOutputStream, bytes: ByteArray) {
        val remaining = maxBodyBytes - buffer.size()
        if (remaining <= 0) return
        val toWrite = if (bytes.size > remaining) remaining else bytes.size
        if (toWrite > 0) buffer.write(bytes, 0, toWrite)
    }

    private fun readGrpcStatus(state: StreamState, headers: Http2Parser.H2Headers) {
        GrpcDetector.grpcStatusFromTrailers(headers)?.let { status -> state.grpcStatus = status }
        headers.regularEntries.firstOrNull { it.first.equals("grpc-message", ignoreCase = true) }
            ?.let { state.grpcMessage = GrpcDetector.decodeGrpcMessage(it.second) }
    }

    private fun endGrpc(streamId: Int, state: StreamState) {
        val call = state.grpcCall ?: return
        if (state.grpcEnded) return
        state.grpcEnded = true
        val end = GrpcCallEnd(
            call = call,
            streamId = streamId,
            requestHeaders = state.requestHeaders,
            responseHeaders = state.responseHeaders,
            requestTrailers = state.requestTrailers,
            responseTrailers = state.responseTrailers,
            grpcStatus = state.grpcStatus,
            grpcMessage = state.grpcMessage,
            messageCount = state.grpcMessageIndex,
            frameEvents = state.frameEvents.toList(),
            startedAt = state.startedAt,
            requestEndedAt = state.requestEndedAt,
            responseHeadersAt = state.responseHeadersAt,
            responseEndedAt = state.responseEndedAt ?: System.currentTimeMillis(),
        )
        runCatching { onGrpcCallEnded(end) }
            .onFailure { e -> Log.w(TAG, "gRPC call-end callback failed: ${e.message}") }
    }

    private fun drainGrpc(call: GrpcDetector.GrpcCall, state: StreamState, isClient: Boolean, newBytes: ByteArray) {
        val buffer = if (isClient) state.requestBody else state.responseBody
        writeBounded(buffer, newBytes)
        val bytes = buffer.toByteArray()
        val (consumed, messages) = GrpcDetector.parseMessages(bytes)
        if (consumed > 0) {
            buffer.reset()
            buffer.write(bytes, consumed, bytes.size - consumed)
        }
        for (msg in messages) {
            val index = state.grpcMessageIndex++
            runCatching { onGrpcMessage(GrpcEvent(call, msg, isClient, state.grpcStatus, index)) }
                .onFailure { e -> Log.w(TAG, "gRPC message callback failed: ${e.message}") }
        }
    }

    private fun maybeEmit(streamId: Int, state: StreamState) {
        if (state.grpcCall != null) {
            // gRPC already emits per-message (drainGrpc above); when both directions are done
            // close the call out (status from trailers, frame timeline) and recycle the slot so
            // a very-long-lived connection does not leak state.
            if (state.requestEnded && state.responseEnded) {
                endGrpc(streamId, state)
                streams.remove(streamId)
            }
            return
        }
        val request = state.requestHeaders ?: return
        val response = state.responseHeaders ?: return
        if (!state.requestEnded || !state.responseEnded) return
        streams.remove(streamId)
        val latencyMs = System.currentTimeMillis() - state.startedAt
        val requestMsg = buildRequestMessage(request, state.requestBody.toByteArray(), state.requestTrailers)
        val responseMsg = buildResponseMessage(response, state.responseBody.toByteArray(), state.responseTrailers)
        val tx = Http2Transaction(
            requestMsg, responseMsg, latencyMs,
            startedAt = state.startedAt,
            requestEndedAt = state.requestEndedAt,
            responseHeadersAt = state.responseHeadersAt,
            responseEndedAt = state.responseEndedAt,
            requestHeaders = request,
            responseHeaders = response,
            requestTrailers = state.requestTrailers,
            responseTrailers = state.responseTrailers,
            frameEvents = state.frameEvents.toList(),
        )
        runCatching { onTransaction(tx) }
            .onFailure { e -> Log.w(TAG, "h2 transaction callback failed: ${e.message}") }
    }

    /** S4 task 35: the builders moved to [Http2Messages] so the rule path sees the same model. */
    private fun buildRequestMessage(headers: Http2Parser.H2Headers, bodyBytes: ByteArray, trailers: Http2Parser.H2Headers? = null): ParsedHttpMessage =
        Http2Messages.request(headers, bodyBytes, trailers)

    private fun buildResponseMessage(headers: Http2Parser.H2Headers, bodyBytes: ByteArray, trailers: Http2Parser.H2Headers? = null): ParsedHttpMessage =
        Http2Messages.response(headers, bodyBytes, trailers)

    companion object {
        private const val TAG = "Http2Session"

        /** P1 fix: cap on concurrently-tracked streams -- see the `maxStreams` constructor doc. */
        const val DEFAULT_MAX_STREAMS = 500

        /** P1 fix: cap on cumulative per-direction body bytes per stream -- see `maxBodyBytes`. */
        const val DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024

        /** S3 task 20: per-stream frame timeline cap; a long gRPC stream keeps its first N frames. */
        const val MAX_FRAME_EVENTS = 512
    }
}

/** S3 task 20: one HTTP/2 frame on a stream, as observed (maps to `http2_stream_events`). */
internal data class H2FrameEvent(
    val streamId: Int,
    val fromClient: Boolean,
    val frameType: Int,
    val flags: Int,
    val payloadLength: Int,
    val timestampMs: Long,
    val note: String? = null,
)

/** One completed non-gRPC HTTP/2 request/response pair, ready for [MitmProxyEngine.emitTransaction]. */
internal data class Http2Transaction(
    val request: ParsedHttpMessage,
    val response: ParsedHttpMessage,
    val latencyMs: Long,
    /** S2 task 18: epoch-millis instants the session observed; null when a frame was never seen. */
    val startedAt: Long? = null,
    val requestEndedAt: Long? = null,
    val responseHeadersAt: Long? = null,
    val responseEndedAt: Long? = null,
    /** S3 task 20: the wire-ordered header blocks (pseudo-headers included) and frame timeline. */
    val requestHeaders: Http2Parser.H2Headers? = null,
    val responseHeaders: Http2Parser.H2Headers? = null,
    val requestTrailers: Http2Parser.H2Headers? = null,
    val responseTrailers: Http2Parser.H2Headers? = null,
    val frameEvents: List<H2FrameEvent> = emptyList(),
)

/** One gRPC message (request or response direction), with the call it belongs to. */
internal data class GrpcEvent(
    val call: GrpcDetector.GrpcCall,
    val message: GrpcDetector.GrpcMessage,
    val isClient: Boolean,
    val grpcStatus: Int?,
    /** S3 task 20: 0-based position within the call, both directions interleaved. */
    val messageIndex: Int = 0,
)

/** S3 task 20: a gRPC call was identified from its request HEADERS (before any message). */
internal data class GrpcCallStart(
    val call: GrpcDetector.GrpcCall,
    val requestHeaders: Http2Parser.H2Headers,
    val startedAt: Long,
)

/** S3 task 20: a gRPC call's stream ended (both directions END_STREAM, or RST_STREAM). */
internal data class GrpcCallEnd(
    val call: GrpcDetector.GrpcCall,
    val streamId: Int,
    val requestHeaders: Http2Parser.H2Headers?,
    val responseHeaders: Http2Parser.H2Headers?,
    val requestTrailers: Http2Parser.H2Headers?,
    val responseTrailers: Http2Parser.H2Headers?,
    /** Null when the stream was reset or the trailers never carried `grpc-status`. */
    val grpcStatus: Int?,
    val grpcMessage: String?,
    val messageCount: Int,
    val frameEvents: List<H2FrameEvent>,
    val startedAt: Long,
    val requestEndedAt: Long?,
    val responseHeadersAt: Long?,
    val responseEndedAt: Long,
)
