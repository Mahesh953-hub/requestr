package com.example.interception.http

import android.util.Log
import com.example.interception.http.Http2Endpoint.ErrorCode
import com.example.interception.http.Http2Parser.H2Frame
import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.relay.BufferPool
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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

    // ── client -> upstream ────────────────────────────────────────────────────────────────

    private inner class ClientListener : Http2Endpoint.Listener {
        override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
            if (!ensureUpstream()) { answer502(streamId); return }
            val h2 = h2Upstream
            if (h2 != null) {
                if (!trailers && h2.openStreamCount >= h2.peerSettings.maxConcurrentStreams) {
                    // RFC 9113 §8.7: REFUSED_STREAM tells the client it may safely retry.
                    client.writeRstStream(streamId, ErrorCode.REFUSED_STREAM)
                    return
                }
                val written = h2.writeHeaders(streamId, headers.entries, endStream)
                if (!written) client.writeRstStream(streamId, ErrorCode.REFUSED_STREAM)
                return
            }
            h1Upstream?.onHeaders(streamId, headers, endStream, trailers)
        }

        override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
            val h2 = h2Upstream
            if (h2 != null) {
                if (!h2.writeData(streamId, data, 0, data.size, endStream)) client.writeRstStream(streamId, ErrorCode.STREAM_CLOSED)
                return
            }
            h1Upstream?.onData(streamId, data, endStream)
        }

        override fun onRstStream(streamId: Int, errorCode: Int) {
            h2Upstream?.writeRstStream(streamId, errorCode)
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
            if (!client.writeHeaders(streamId, headers.entries, endStream)) h2Upstream?.writeRstStream(streamId, ErrorCode.CANCEL)
        }

        override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
            if (!client.writeData(streamId, data, 0, data.size, endStream)) h2Upstream?.writeRstStream(streamId, ErrorCode.CANCEL)
        }

        override fun onRstStream(streamId: Int, errorCode: Int) {
            client.writeRstStream(streamId, errorCode)
        }

        override fun onGoAway(lastStreamId: Int, errorCode: Int) {
            // Same ids on both legs, so the upstream's last-stream-id is meaningful to the client.
            runCatching { client.writeGoAway(errorCode, "upstream GOAWAY") }
        }
    }

    companion object { private const val TAG = "Http2ProxyConnection" }
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
        var deliver = !req.cancelled
        if (deliver && !client.writeHeaders(req.streamId, entries, endStream = bodyless)) deliver = false

        val sink: (ByteArray, Int, Int) -> Unit = { buf, off, len ->
            bytesFromUpstream?.addAndGet(len.toLong())
            if (deliver && !client.writeData(req.streamId, buf, off, len, endStream = false)) deliver = false
        }
        var trailers: List<Pair<String, String>> = emptyList()
        var reusable = !h1.connectionClose
        when {
            bodyless -> {}
            h1.chunked -> trailers = reader.readChunked(sink)
            h1.contentLength != null -> reader.readFixed(h1.contentLength!!, sink)
            else -> { reader.readUntilClose(sink); reusable = false }
        }
        if (deliver && !bodyless) {
            val h2Trailers = Http2Translator.trailersToH2(trailers)
            if (h2Trailers.isNotEmpty()) client.writeHeaders(req.streamId, h2Trailers, endStream = true)
            else client.writeData(req.streamId, ByteArray(0), 0, 0, endStream = true)
        }
        if (!reusable) Log.d(TAG, "[$name] upstream connection not reusable after stream ${req.streamId}")
        return reusable
    }

    companion object { private const val TAG = "Http1UpstreamBridge" }
}
