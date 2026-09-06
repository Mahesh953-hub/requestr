package com.example.interception.http

import android.util.Log
import com.example.interception.http.Http2Endpoint.ErrorCode
import com.example.interception.http.Http2Messages.H2Message
import com.example.interception.http.Http2Parser.H2Frame
import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.http.Http2StreamInterceptor.RequestVerdict
import com.example.interception.relay.BufferPool
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * S3 task 57 (D7 / gate G27): one intercepted HTTP/2 connection, terminated and translated.
 *
 * The client-facing leg is an [Http2Endpoint] in server role. The upstream leg is dialled on
 * the first request (never for a connection that only exchanges SETTINGS) and its ALPN result
 * decides the translation:
 * - `h2`: a second [Http2Endpoint] in client role; streams map 1:1 with the same ids (the
 *   client's ids are odd and increasing, which is exactly what a client-role endpoint needs),
 *   header blocks are decoded on one leg and re-encoded on the other (separate HPACK contexts),
 *   DATA is forwarded as it arrives under both legs' flow control, RST_STREAM/GOAWAY cross over.
 * - anything else (HTTP/1.1, no ALPN): [Http1UpstreamBridge] -- each complete h2 request is
 *   buffered (per-stream [BufferPool.FlowBuffer], spilling past the task 7 cap), written as one
 *   HTTP/1.1 request, and the response is streamed back as HEADERS/DATA/trailers. Requests are
 *   issued strictly one at a time (no pipelining); the client's multiplexing is preserved
 *   because streams stay independent on the h2 side.
 *
 * Two threads drive everything: [runClientLeg] (reads the client) and [runUpstreamLeg] (reads
 * the upstream), the same two relay legs `StructuredRelay` already runs for every connection.
 * Both endpoints' writers are frame-atomic; blocking on a closed flow-control window is the
 * backpressure between the legs.
 *
 * Observation: every frame read from or written to the *client* connection is replayed into
 * [observer] (an [Http2Session]), so persistence, gRPC extraction and timings work exactly as
 * they did when the bytes were forwarded verbatim -- with the difference that the frames the
 * client sees are now ours.
 *
 * Rules (S4 task 35): an optional [interceptor] is asked once per request stream, at HEADERS.
 * Streams it declines are relayed as described above. Streams it claims are buffered whole
 * (request on the way in, response on the way back when the plan wants it) and the messages the
 * interceptor returns are what gets framed -- `content-length` and DATA/END_STREAM are recomputed
 * from those messages, never inherited. Request-side work runs on a per-connection rule thread so
 * a paused breakpoint or a large synthetic body never stalls the client reader (which must keep
 * processing WINDOW_UPDATE/SETTINGS for the other streams). Because a claimed stream reaches the
 * h2 upstream later than the streams the client opened after it, upstream stream ids are
 * allocated here in open order and mapped back to the client's ids ([upstreamIds]/[clientIds]);
 * they still coincide whenever nothing is intercepted.
 */
internal class Http2ProxyConnection(
    clientInput: InputStream,
    clientOutput: OutputStream,
    private val connectUpstream: () -> UpstreamLeg,
    private val observer: Http2Session?,
    private val bufferPool: BufferPool,
    bytesFromClient: AtomicLong? = null,
    private val bytesFromUpstream: AtomicLong? = null,
    private val name: String = "h2",
    private val interceptor: Http2StreamInterceptor? = null,
) {
    /** A connected upstream: streams plus the ALPN protocol it negotiated (`null` = none). */
    class UpstreamLeg(val input: InputStream, val output: OutputStream, val alpn: String?)

    val client: Http2Endpoint = Http2Endpoint(
        clientInput, clientOutput, Http2Endpoint.Role.SERVER, ClientListener(), name = "$name/client",
        onFrameRead = { f -> observer?.onClientFrames(listOf(f)) },
        onFrameWritten = { f -> observer?.onServerFrames(listOf(f)) },
        bytesRead = bytesFromClient,
    )

    private val upstreamReady = CountDownLatch(1)
    private val clientDone = CountDownLatch(1)
    @Volatile private var upstreamLeg: UpstreamLeg? = null
    @Volatile private var h2Upstream: Http2Endpoint? = null
    @Volatile private var h1Upstream: Http1UpstreamBridge? = null
    @Volatile private var upstreamFailed: String? = null
    private val connectLock = Any()

    /** Client stream id -> upstream (h2) stream id and back; identical unless a stream was held back. */
    private val upstreamIds = ConcurrentHashMap<Int, Int>()
    private val clientIds = ConcurrentHashMap<Int, Int>()
    private var nextUpstreamId = 1
    private val openLock = Any()

    /** S4 task 35: streams the [interceptor] claimed, by client stream id, until their exchange ends. */
    private val intercepted = ConcurrentHashMap<Int, InterceptedStream>()
    @Volatile private var ruleThread: ExecutorService? = null
    private val ruleThreadLock = Any()

    private class InterceptedStream(val streamId: Int, val plan: Http2StreamInterceptor.Plan, val headers: H2Headers) {
        var body: BufferPool.FlowBuffer? = null
        var trailers: List<Pair<String, String>>? = null
        var responseHeaders: H2Headers? = null
        var responseBody: BufferPool.FlowBuffer? = null
        var responseTrailers: List<Pair<String, String>>? = null
        @Volatile var cancelled = false
        @Volatile var forwarded = false

        fun release() {
            body?.close(); body = null
            responseBody?.close(); responseBody = null
        }
    }

    /** Number of streams currently held for the interceptor (diagnostics/tests). */
    val interceptedStreamCount: Int get() = intercepted.size

    /** `"h2"`, `"http/1.1"` or `null` until the upstream is dialled (diagnostics/tests). */
    val upstreamMode: String? get() = when {
        h2Upstream != null -> "h2"
        h1Upstream != null -> "http/1.1"
        else -> null
    }

    // ── relay legs ────────────────────────────────────────────────────────────────────────

    /** Client leg: terminate the app's h2 connection. Returns when the client is gone. */
    fun runClientLeg() {
        try {
            client.start()
            client.run()
        } catch (e: Http2Endpoint.Http2ConnectionException) {
            Log.w(TAG, "[$name] client connection error: ${e.message}")
        } catch (_: IOException) {
            // client closed
        } finally {
            upstreamReady.countDown()
            clientDone.countDown()
            client.close()
            h2Upstream?.let { up -> runCatching { up.writeGoAway(ErrorCode.NO_ERROR, "client closed") }; up.close() }
            h1Upstream?.shutdown()
            ruleThread?.shutdownNow()
            for (s in intercepted.values) { s.cancelled = true; s.release(); runCatching { s.plan.onAbandoned("client closed") } }
            intercepted.clear()
        }
    }

    /** Upstream leg: waits for the first request to dial, then reads the upstream until it ends. */
    fun runUpstreamLeg() {
        upstreamReady.await()
        if (h2Upstream == null && h1Upstream == null) {
            // The dial failed (every stream is being answered 502 by the client leg) or the
            // client left before its first request: nothing to read, keep the client alive
            // until its own leg ends.
            clientDone.await()
            return
        }
        try {
            h2Upstream?.run()
            h1Upstream?.run()
        } catch (e: Http2Endpoint.Http2ConnectionException) {
            Log.w(TAG, "[$name] upstream connection error: ${e.message}")
        } catch (_: IOException) {
            // upstream closed
        } finally {
            h2Upstream?.close()
            // Whatever ended the upstream, the client must learn that no further streams will
            // be served on this connection; streams still open are reset by run()'s cleanup.
            runCatching { client.writeGoAway(ErrorCode.NO_ERROR, "upstream closed") }
            client.close()
        }
    }

    /**
     * Dial the upstream once (on the client thread, at the first HEADERS). Returns `false` when
     * the upstream could not be reached; the caller then answers the stream with 502.
     */
    private fun ensureUpstream(): Boolean {
        if (upstreamLeg != null) return true
        if (upstreamFailed != null) return false
        synchronized(connectLock) {
            if (upstreamLeg != null) return true
            if (upstreamFailed != null) return false
            val leg = try {
                connectUpstream()
            } catch (e: Exception) {
                upstreamFailed = e.message ?: e::class.java.simpleName
                Log.w(TAG, "[$name] upstream connect failed: ${e.message}")
                upstreamReady.countDown()
                return false
            }
            if (leg.alpn == "h2") {
                val endpoint = Http2Endpoint(
                    leg.input, leg.output, Http2Endpoint.Role.CLIENT, UpstreamListener(), name = "$name/upstream",
                    bytesRead = bytesFromUpstream,
                )
                endpoint.start()
                h2Upstream = endpoint
            } else {
                h1Upstream = Http1UpstreamBridge(leg.input, leg.output, client, bufferPool, bytesFromUpstream, "$name/upstream-h1")
            }
            upstreamLeg = leg
            upstreamReady.countDown()
            return true
        }
    }

    private fun answer502(streamId: Int) {
        runCatching {
            client.writeHeaders(streamId, listOf(":status" to "502", "content-length" to "0"), endStream = true)
        }
    }

    // ── upstream stream ids ───────────────────────────────────────────────────────────────

    /**
     * Opens [clientStreamId]'s request on the h2 upstream under the next free upstream id. The
     * id is allocated and the HEADERS written under one lock so upstream ids open strictly in
     * increasing order (RFC 9113 §5.1.1) no matter which thread forwards. `false` = refused
     * (concurrency limit, GOAWAY received, or the endpoint is closed); nothing was written.
     */
    private fun openUpstream(h2: Http2Endpoint, clientStreamId: Int, entries: List<Pair<String, String>>, endStream: Boolean): Boolean {
        synchronized(openLock) {
            if (h2.openStreamCount >= h2.peerSettings.maxConcurrentStreams) return false
            val id = nextUpstreamId
            nextUpstreamId += 2
            upstreamIds[clientStreamId] = id
            clientIds[id] = clientStreamId
            val written = runCatching { h2.writeHeaders(id, entries, endStream) }.getOrDefault(false)
            if (!written) { upstreamIds.remove(clientStreamId); clientIds.remove(id) }
            return written
        }
    }

    private fun forgetUpstream(upstreamStreamId: Int): Int? =
        clientIds.remove(upstreamStreamId)?.also { upstreamIds.remove(it) }

    // ── rule thread ───────────────────────────────────────────────────────────────────────

    private fun ruleThread(): ExecutorService {
        ruleThread?.let { return it }
        synchronized(ruleThreadLock) {
            ruleThread?.let { return it }
            return Executors.newSingleThreadExecutor { r -> Thread(r, "$name/rules").also { it.isDaemon = true } }.also { ruleThread = it }
        }
    }

    /** The whole request is in: hand it to the interceptor off the client reader thread. */
    private fun completeRequest(s: InterceptedStream) {
        try {
            ruleThread().execute { processRequest(s) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            s.release()
        }
    }

    private fun processRequest(s: InterceptedStream) {
        if (s.cancelled) { s.release(); return }
        val body = s.body?.let { b -> b.toByteArray().also { b.close(); s.body = null } } ?: EMPTY
        val verdict = try {
            s.plan.onRequest(H2Message(s.headers.entries, body, s.trailers))
        } catch (t: Throwable) {
            Log.w(TAG, "[$name] rule stage failed on stream ${s.streamId}: ${t.message}")
            intercepted.remove(s.streamId)
            runCatching { client.writeRstStream(s.streamId, ErrorCode.INTERNAL_ERROR) }
            return
        }
        if (s.cancelled) { intercepted.remove(s.streamId); return }
        when (verdict) {
            is RequestVerdict.Respond -> {
                intercepted.remove(s.streamId)
                writeToClient(s.streamId, verdict.response)
            }
            is RequestVerdict.Forward -> forwardRequest(s, verdict.request)
        }
    }

    private fun forwardRequest(s: InterceptedStream, req: H2Message) {
        if (!ensureUpstream()) { intercepted.remove(s.streamId); answer502(s.streamId); return }
        if (!s.plan.interceptsResponse) intercepted.remove(s.streamId) // the response streams through
        s.forwarded = true
        val h2 = h2Upstream
        if (h2 != null) {
            if (!openUpstream(h2, s.streamId, req.headers, req.endsOnHeaders)) {
                intercepted.remove(s.streamId)
                client.writeRstStream(s.streamId, ErrorCode.REFUSED_STREAM)
                return
            }
            val up = upstreamIds[s.streamId] ?: return
            if (!req.endsOnHeaders) {
                var ok = true
                if (req.body.isNotEmpty()) ok = h2.writeData(up, req.body, 0, req.body.size, endStream = !req.hasTrailers)
                if (ok && req.hasTrailers) ok = h2.writeHeaders(up, req.trailers!!, endStream = true)
                if (!ok) { intercepted.remove(s.streamId); client.writeRstStream(s.streamId, ErrorCode.STREAM_CLOSED) }
            }
            return
        }
        val bridge = h1Upstream ?: return
        bridge.submit(s.streamId, req, responseHook = if (s.plan.interceptsResponse) { msg -> interceptResponse(s, msg) } else null)
    }

    /** Runs the plan's response step; a failing stage forwards the origin's message untouched. */
    private fun interceptResponse(s: InterceptedStream, response: H2Message): H2Message {
        intercepted.remove(s.streamId)
        return try {
            s.plan.onResponse(response)
        } catch (t: Throwable) {
            Log.w(TAG, "[$name] response rule stage failed on stream ${s.streamId}: ${t.message}")
            response
        }
    }

    /** Frames one complete message to the client: HEADERS, DATA, trailers -- END_STREAM on the last. */
    private fun writeToClient(streamId: Int, msg: H2Message): Boolean {
        if (!client.writeHeaders(streamId, msg.headers, endStream = msg.endsOnHeaders)) return false
        if (msg.endsOnHeaders) return true
        if (msg.body.isNotEmpty() && !client.writeData(streamId, msg.body, 0, msg.body.size, endStream = !msg.hasTrailers)) return false
        if (msg.hasTrailers) return client.writeHeaders(streamId, msg.trailers!!, endStream = true)
        return true
    }

    /** Appends to a held stream's buffer; `false` when the task 7 cap is hit (stream already reset). */
    private fun buffer(s: InterceptedStream, data: ByteArray, response: Boolean): Boolean {
        if (data.isEmpty()) return true
        val existing = if (response) s.responseBody else s.body
        val buf = existing ?: bufferPool.newFlowBuffer().also { if (response) s.responseBody = it else s.body = it }
        if (buf.append(data, 0, data.size)) return true
        intercepted.remove(s.streamId)
        s.cancelled = true
        s.release()
        runCatching { s.plan.onAbandoned("body exceeds the buffer cap") }
        client.writeRstStream(s.streamId, ErrorCode.ENHANCE_YOUR_CALM)
        if (response) upstreamIds[s.streamId]?.let { h2Upstream?.writeRstStream(it, ErrorCode.CANCEL) }
        return false
    }

    /** The whole response is in (h2 upstream): run the plan on this (upstream reader) thread and frame the result. */
    private fun completeResponse(s: InterceptedStream) {
        val headers = s.responseHeaders ?: run { intercepted.remove(s.streamId); return }
        val body = s.responseBody?.let { b -> b.toByteArray().also { b.close(); s.responseBody = null } } ?: EMPTY
        val out = interceptResponse(s, H2Message(headers.entries, body, s.responseTrailers))
        if (s.cancelled) return
        if (!writeToClient(s.streamId, out)) upstreamIds[s.streamId]?.let { h2Upstream?.writeRstStream(it, ErrorCode.CANCEL) }
    }

    // ── client -> upstream ────────────────────────────────────────────────────────────────

    private inner class ClientListener : Http2Endpoint.Listener {
        override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
            if (trailers) {
                intercepted[streamId]?.takeIf { !it.forwarded }?.let { s ->
                    s.trailers = headers.entries
                    if (endStream) completeRequest(s)
                    return
                }
            } else {
                val plan = interceptor?.let { runCatching { it.plan(streamId, headers) }.onFailure { e -> Log.w(TAG, "[$name] rule plan failed: ${e.message}") }.getOrNull() }
                if (plan != null) {
                    val s = InterceptedStream(streamId, plan, headers)
                    intercepted[streamId] = s
                    if (endStream) completeRequest(s)
                    return
                }
            }
            if (!ensureUpstream()) { answer502(streamId); return }
            val h2 = h2Upstream
            if (h2 != null) {
                if (trailers) {
                    val up = upstreamIds[streamId] ?: return
                    if (!h2.writeHeaders(up, headers.entries, endStream)) client.writeRstStream(streamId, ErrorCode.STREAM_CLOSED)
                    return
                }
                // RFC 9113 §8.7: REFUSED_STREAM tells the client it may safely retry.
                if (!openUpstream(h2, streamId, headers.entries, endStream)) client.writeRstStream(streamId, ErrorCode.REFUSED_STREAM)
                return
            }
            h1Upstream?.onHeaders(streamId, headers, endStream, trailers)
        }

        override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
            intercepted[streamId]?.takeIf { !it.forwarded }?.let { s ->
                if (!buffer(s, data, response = false)) return
                if (endStream) completeRequest(s)
                return
            }
            val h2 = h2Upstream
            if (h2 != null) {
                val up = upstreamIds[streamId] ?: return
                if (!h2.writeData(up, data, 0, data.size, endStream)) client.writeRstStream(streamId, ErrorCode.STREAM_CLOSED)
                return
            }
            h1Upstream?.onData(streamId, data, endStream)
        }

        override fun onRstStream(streamId: Int, errorCode: Int) {
            intercepted.remove(streamId)?.let { s -> s.cancelled = true; s.release(); runCatching { s.plan.onAbandoned("client reset") } }
            upstreamIds.remove(streamId)?.let { up -> clientIds.remove(up); h2Upstream?.writeRstStream(up, errorCode) }
            h1Upstream?.cancel(streamId)
        }

        override fun onGoAway(lastStreamId: Int, errorCode: Int) {
            // The client will open no more streams; in-flight ones finish normally. The upstream
            // hears about it when the client leg actually ends.
        }
    }

    // ── upstream (h2) -> client ───────────────────────────────────────────────────────────

    private inner class UpstreamListener : Http2Endpoint.Listener {
        override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
            val cid = clientIds[streamId] ?: return
            intercepted[cid]?.takeIf { !it.cancelled }?.let { s ->
                if (trailers) {
                    s.responseTrailers = headers.entries
                    if (endStream) completeResponse(s)
                    return
                }
                val status = headers.pseudoHeaders[":status"]?.toIntOrNull() ?: 0
                if (status in 100..199) {
                    // Interim responses are not the message the rules see; pass them straight on.
                    if (!client.writeHeaders(cid, headers.entries, endStream)) h2Upstream?.writeRstStream(streamId, ErrorCode.CANCEL)
                    return
                }
                s.responseHeaders = headers
                if (endStream) completeResponse(s)
                return
            }
            if (!client.writeHeaders(cid, headers.entries, endStream)) h2Upstream?.writeRstStream(streamId, ErrorCode.CANCEL)
        }

        override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
            val cid = clientIds[streamId] ?: return
            intercepted[cid]?.takeIf { !it.cancelled }?.let { s ->
                if (!buffer(s, data, response = true)) return
                if (endStream) completeResponse(s)
                return
            }
            if (!client.writeData(cid, data, 0, data.size, endStream)) h2Upstream?.writeRstStream(streamId, ErrorCode.CANCEL)
        }

        override fun onRstStream(streamId: Int, errorCode: Int) {
            val cid = forgetUpstream(streamId) ?: return
            intercepted.remove(cid)?.let { s -> s.cancelled = true; s.release(); runCatching { s.plan.onAbandoned("upstream reset") } }
            client.writeRstStream(cid, errorCode)
        }

        override fun onStreamClosed(streamId: Int) {
            forgetUpstream(streamId)
        }

        override fun onGoAway(lastStreamId: Int, errorCode: Int) {
            // The client endpoint reports its own last processed stream id; the upstream's is
            // meaningless to the client once ids are mapped.
            runCatching { client.writeGoAway(errorCode, "upstream GOAWAY") }
        }
    }

    companion object {
        private const val TAG = "Http2ProxyConnection"
        private val EMPTY = ByteArray(0)
    }
}

/**
 * HTTP/1.1 upstream side of [Http2ProxyConnection]: buffers each h2 request stream until
 * END_STREAM, then (on the upstream relay leg, strictly one at a time) writes it as an HTTP/1.1
 * request and streams the response back to the client endpoint as h2 frames.
 */
internal class Http1UpstreamBridge(
    private val input: InputStream,
    private val output: OutputStream,
    private val client: Http2Endpoint,
    private val bufferPool: BufferPool,
    private val bytesFromUpstream: AtomicLong?,
    private val name: String = "upstream-h1",
) {
    private class PendingRequest(val streamId: Int, val headers: H2Headers) {
        var body: BufferPool.FlowBuffer? = null
        var bodyLength = 0L
        var trailers: List<Pair<String, String>>? = null
        @Volatile var cancelled = false
        /** S4 task 35: when set, the response is buffered whole and passed through this before it is framed. */
        var responseHook: ((H2Message) -> H2Message)? = null
    }

    private val building = ConcurrentHashMap<Int, PendingRequest>()
    private val queue = LinkedBlockingQueue<PendingRequest>()
    private val poison = PendingRequest(0, H2Headers(0, emptyList(), true))
    @Volatile private var stopped = false

    /** Requests waiting for the upstream (diagnostics/tests). */
    val queuedCount: Int get() = queue.size

    fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
        if (trailers) {
            val req = building[streamId] ?: return
            req.trailers = headers.entries
            if (endStream) complete(req)
            return
        }
        val req = PendingRequest(streamId, headers)
        building[streamId] = req
        if (endStream) complete(req)
    }

    fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
        val req = building[streamId] ?: return
        if (data.isNotEmpty()) {
            val buffer = req.body ?: bufferPool.newFlowBuffer().also { req.body = it }
            if (!buffer.append(data, 0, data.size)) {
                // Task 7 cap reached with no spill directory: refuse rather than grow the heap.
                building.remove(streamId)
                client.writeRstStream(streamId, ErrorCode.ENHANCE_YOUR_CALM)
                return
            }
            req.bodyLength += data.size
        }
        if (endStream) complete(req)
    }

    fun cancel(streamId: Int) {
        building.remove(streamId)?.cancelled = true
        queue.forEach { if (it.streamId == streamId) it.cancelled = true }
    }

    /**
     * S4 task 35: queue a request the rule engine already saw whole. [responseHook] (optional)
     * receives the complete response before anything is written to the client.
     */
    fun submit(streamId: Int, request: H2Message, responseHook: ((H2Message) -> H2Message)?) {
        val req = PendingRequest(streamId, H2Headers(streamId, request.headers, request.endsOnHeaders))
        if (request.body.isNotEmpty()) {
            val buffer = bufferPool.newFlowBuffer()
            if (!buffer.append(request.body, 0, request.body.size)) {
                buffer.close()
                client.writeRstStream(streamId, ErrorCode.ENHANCE_YOUR_CALM)
                return
            }
            req.body = buffer
            req.bodyLength = request.body.size.toLong()
        }
        req.trailers = request.trailers
        req.responseHook = responseHook
        complete(req)
    }

    private fun complete(req: PendingRequest) {
        building.remove(req.streamId)
        if (stopped) { client.writeRstStream(req.streamId, ErrorCode.REFUSED_STREAM); return }
        queue.put(req)
    }

    fun shutdown() {
        stopped = true
        queue.offer(poison)
    }

    /** Upstream relay leg body: serve queued requests until the upstream or the client is gone. */
    fun run() {
        val reader = Http2Translator.Http1ResponseReader(input, bufferPool.bufferSize)
        try {
            while (!stopped) {
                val req = queue.take()
                if (req === poison) break
                if (req.cancelled) { req.body?.close(); continue }
                val keepAlive = serve(req, reader)
                if (!keepAlive) break
            }
        } finally {
            stopped = true
            // Anything still queued can be retried by the client on a fresh connection.
            val leftovers = ArrayList<PendingRequest>()
            queue.drainTo(leftovers)
            for (req in leftovers) if (req !== poison && !req.cancelled) runCatching { client.writeRstStream(req.streamId, ErrorCode.REFUSED_STREAM) }
            for (req in building.values) runCatching { client.writeRstStream(req.streamId, ErrorCode.REFUSED_STREAM) }
            building.clear()
        }
    }

    /** Writes one request, streams its response. Returns `false` when the upstream connection cannot be reused. */
    private fun serve(req: PendingRequest, reader: Http2Translator.Http1ResponseReader): Boolean {
        val body = req.body?.let { b -> b.toByteArray().also { b.close() } } ?: ByteArray(0)
        val head = Http2Translator.requestToHttp1(req.headers, body.size.toLong(), req.trailers)
        output.write(head.bytes)
        if (head.chunked) output.write(Http2Translator.chunkedBody(body, req.trailers.orEmpty()))
        else if (body.isNotEmpty()) output.write(body)
        output.flush()

        val method = req.headers.pseudoHeaders[":method"]
        var h1 = reader.readHead()
        // 1xx interim responses are forwarded as informational HEADERS (RFC 9113 §8.1) and the
        // reader keeps going until the final status.
        while (h1.statusCode in 100..199) {
            if (h1.statusCode != 100 && !req.cancelled) client.writeHeaders(req.streamId, Http2Translator.responseToH2(h1.statusCode, h1.headerLines), endStream = false)
            h1 = reader.readHead()
        }
        val bodyless = method == "HEAD" || h1.statusCode == 204 || h1.statusCode == 304
        val entries = Http2Translator.responseToH2(h1.statusCode, h1.headerLines)
        val hook = req.responseHook
        var deliver = !req.cancelled
        // Held response (S4 task 35): everything is collected first, the rules see it whole, and
        // what they return is framed. Otherwise the response streams to the client as it arrives.
        var held: BufferPool.FlowBuffer? = if (hook != null && !bodyless) bufferPool.newFlowBuffer() else null
        if (hook == null && deliver && !client.writeHeaders(req.streamId, entries, endStream = bodyless)) deliver = false

        val sink: (ByteArray, Int, Int) -> Unit = { buf, off, len ->
            bytesFromUpstream?.addAndGet(len.toLong())
            val h = held
            if (h != null) {
                if (!h.append(buf, off, len)) {
                    // Cap reached: the exchange cannot be offered to the rules; drop it honestly.
                    h.close(); held = null
                    if (deliver) client.writeRstStream(req.streamId, ErrorCode.ENHANCE_YOUR_CALM)
                    deliver = false
                }
            } else if (hook == null && deliver && !client.writeData(req.streamId, buf, off, len, endStream = false)) deliver = false
        }
        var trailers: List<Pair<String, String>> = emptyList()
        var reusable = !h1.connectionClose
        when {
            bodyless -> {}
            h1.chunked -> trailers = reader.readChunked(sink)
            h1.contentLength != null -> reader.readFixed(h1.contentLength!!, sink)
            else -> { reader.readUntilClose(sink); reusable = false }
        }
        val h2Trailers = Http2Translator.trailersToH2(trailers)
        if (hook != null) {
            val body = held?.let { b -> b.toByteArray().also { b.close() } } ?: ByteArray(0)
            if (deliver) {
                val out = hook(H2Message(entries, body, h2Trailers.takeIf { it.isNotEmpty() }))
                if (client.writeHeaders(req.streamId, out.headers, endStream = out.endsOnHeaders) && !out.endsOnHeaders) {
                    if (out.body.isEmpty() || client.writeData(req.streamId, out.body, 0, out.body.size, endStream = !out.hasTrailers)) {
                        if (out.hasTrailers) client.writeHeaders(req.streamId, out.trailers!!, endStream = true)
                    }
                }
            }
        } else if (deliver && !bodyless) {
            if (h2Trailers.isNotEmpty()) client.writeHeaders(req.streamId, h2Trailers, endStream = true)
            else client.writeData(req.streamId, ByteArray(0), 0, 0, endStream = true)
        }
        if (!reusable) Log.d(TAG, "[$name] upstream connection not reusable after stream ${req.streamId}")
        return reusable
    }

    companion object { private const val TAG = "Http1UpstreamBridge" }
}
