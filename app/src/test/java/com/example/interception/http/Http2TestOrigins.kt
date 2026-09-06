package com.example.interception.http

import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.relay.BufferPool
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * S4 task 35: shared h2 test plumbing -- an h2 client recorder, fake HTTP/1.1 and h2 origins
 * over pipes, and a harness wiring them through [Http2ProxyConnection] with an optional
 * [Http2StreamInterceptor]. Mirrors the private fixtures of `Http2ProxyConnectionTest` (task 57)
 * so the rule-engine tests can drive the exact same translation path.
 */
internal class H2ClientRecorder : Http2Endpoint.Listener {
    private val events = LinkedBlockingQueue<String>()
    private val seen = ArrayList<String>()
    val headers = ConcurrentHashMap<Int, MutableList<H2Headers>>()
    val data = ConcurrentHashMap<Int, ByteArrayOutputStream>()

    override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
        this.headers.getOrPut(streamId) { CopyOnWriteArrayList() } += headers
        events.put("HEADERS $streamId end=$endStream trailers=$trailers")
    }
    override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
        this.data.getOrPut(streamId) { ByteArrayOutputStream() }.write(data)
        if (endStream) events.put("DATA_END $streamId")
    }
    override fun onRstStream(streamId: Int, errorCode: Int) { events.put("RST $streamId $errorCode") }
    override fun onGoAway(lastStreamId: Int, errorCode: Int) { events.put("GOAWAY $lastStreamId $errorCode") }
    override fun onStreamClosed(streamId: Int) { events.put("CLOSED $streamId") }

    fun expect(prefix: String, timeoutSec: Long = 5): String {
        synchronized(seen) { seen.firstOrNull { it.startsWith(prefix) }?.let { seen.remove(it); return it } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("timed out waiting for '$prefix'; seen=$seen")
            val e = events.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (e.startsWith(prefix)) return e
            synchronized(seen) { seen += e }
        }
    }

    /** Waits for the stream to finish either on a HEADERS frame with END_STREAM or on DATA END_STREAM. */
    fun expectEnd(streamId: Int, timeoutSec: Long = 5) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (true) {
            synchronized(seen) {
                seen.firstOrNull { it == "DATA_END $streamId" || (it.startsWith("HEADERS $streamId end=true")) }?.let { seen.remove(it); return }
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("timed out waiting for stream $streamId to end; seen=$seen")
            val e = events.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (e == "DATA_END $streamId" || e.startsWith("HEADERS $streamId end=true")) return
            synchronized(seen) { seen += e }
        }
    }

    fun body(streamId: Int): String = String(data[streamId]?.toByteArray() ?: ByteArray(0), Charsets.ISO_8859_1)
    fun status(streamId: Int): String? = headers[streamId]?.firstOrNull()?.pseudoHeaders?.get(":status")
    fun header(streamId: Int, name: String): String? = headers[streamId]?.firstOrNull()?.entries?.firstOrNull { it.first == name }?.second
}

internal class H1OriginRequest(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray, val trailers: List<Pair<String, String>>) {
    fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    val bodyText: String get() = String(body, Charsets.ISO_8859_1)
}

internal class FakeH1Origin(private val respond: (H1OriginRequest) -> ByteArray) {
    val toServer = PipedOutputStream()
    private val serverIn = PipedInputStream(toServer, 1 shl 20)
    val fromServer = PipedInputStream(1 shl 20)
    private val serverOut = PipedOutputStream(fromServer)
    val requests = CopyOnWriteArrayList<H1OriginRequest>()
    val thread = Thread {
        try {
            while (true) {
                val req = readRequest(serverIn) ?: break
                requests += req
                serverOut.write(respond(req))
                serverOut.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { serverOut.close() }
        }
    }.also { it.isDaemon = true; it.start() }

    fun close() {
        runCatching { toServer.close() }
        thread.join(2_000)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readRequest(input: InputStream): H1OriginRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
        }
        val body = ByteArrayOutputStream()
        val trailers = ArrayList<Pair<String, String>>()
        val te = headers.firstOrNull { it.first.equals("transfer-encoding", ignoreCase = true) }?.second
        val cl = headers.firstOrNull { it.first.equals("content-length", ignoreCase = true) }?.second?.toIntOrNull()
        if (te?.contains("chunked") == true) {
            while (true) {
                val size = readLine(input)!!.substringBefore(';').trim().toInt(16)
                if (size == 0) break
                repeat(size) { body.write(input.read()) }
                readLine(input)
            }
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                trailers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
            }
        } else if (cl != null) {
            repeat(cl) { body.write(input.read()) }
        }
        return H1OriginRequest(parts[0], parts[1], headers, body.toByteArray(), trailers)
    }

    companion object {
        fun response(status: String, headers: String, body: String) =
            "HTTP/1.1 $status\r\n$headers${if (body.isNotEmpty()) "Content-Length: ${body.length}\r\n" else ""}\r\n$body".toByteArray(Charsets.ISO_8859_1)
    }
}

internal class FakeH2Origin(private val onRequest: (FakeH2Origin, Int, H2Headers, Boolean) -> Unit) : Http2Endpoint.Listener {
    val toServer = PipedOutputStream()
    val fromServer = PipedInputStream(1 shl 20)
    val endpoint = Http2Endpoint(PipedInputStream(toServer, 1 shl 20), PipedOutputStream(fromServer), Http2Endpoint.Role.SERVER, this, "origin-h2")
    val data = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    val events = CopyOnWriteArrayList<String>()
    val headers = ConcurrentHashMap<Int, MutableList<H2Headers>>()
    @Volatile var error: Throwable? = null
    val thread = Thread { try { endpoint.start(); endpoint.run() } catch (t: Throwable) { error = t } }.also { it.isDaemon = true; it.start() }

    override fun onHeaders(streamId: Int, headers: H2Headers, endStream: Boolean, trailers: Boolean) {
        this.headers.getOrPut(streamId) { CopyOnWriteArrayList() } += headers
        events += "HEADERS $streamId end=$endStream trailers=$trailers"
        onRequest(this, streamId, headers, endStream)
    }
    override fun onData(streamId: Int, data: ByteArray, endStream: Boolean) {
        this.data.getOrPut(streamId) { ByteArrayOutputStream() }.write(data)
        if (endStream) { events += "DATA_END $streamId"; onRequest(this, streamId, headers[streamId]!!.first(), true) }
    }
    override fun onRstStream(streamId: Int, errorCode: Int) { events += "RST $streamId $errorCode" }
    override fun onGoAway(lastStreamId: Int, errorCode: Int) { events += "GOAWAY $lastStreamId $errorCode" }

    fun body(streamId: Int): String = String(data[streamId]?.toByteArray() ?: ByteArray(0), Charsets.ISO_8859_1)

    fun close() {
        endpoint.close()
        runCatching { toServer.close() }
        thread.join(2_000)
    }
}

internal class H2ProxyHarness(
    alpn: String?,
    upstreamIn: InputStream,
    upstreamOut: OutputStream,
    session: Http2Session? = null,
    interceptor: Http2StreamInterceptor? = null,
    connectFails: Boolean = false,
    private val authority: String = "origin.test",
) {
    val transactions = CopyOnWriteArrayList<Http2Transaction>()
    val session: Http2Session = session ?: Http2Session(onTransaction = { transactions += it }, onGrpcMessage = {})
    val connects = AtomicLong()
    private val clientToProxy = PipedOutputStream()
    private val proxyToClient = PipedOutputStream()
    val proxy = Http2ProxyConnection(
        clientInput = PipedInputStream(clientToProxy, 1 shl 20),
        clientOutput = proxyToClient,
        connectUpstream = {
            connects.incrementAndGet()
            if (connectFails) throw IOException("connection refused")
            Http2ProxyConnection.UpstreamLeg(upstreamIn, upstreamOut, alpn)
        },
        observer = this.session,
        bufferPool = BufferPool(),
        bytesFromClient = AtomicLong(),
        bytesFromUpstream = AtomicLong(),
        name = "test",
        interceptor = interceptor,
    )
    val listener = H2ClientRecorder()
    val client = Http2Endpoint(PipedInputStream(proxyToClient, 1 shl 20), clientToProxy, Http2Endpoint.Role.CLIENT, listener, "app")
    private val done = CountDownLatch(3)
    val errors = CopyOnWriteArrayList<Throwable>()
    private fun leg(block: () -> Unit) = Thread { try { block() } catch (t: Throwable) { errors += t } finally { done.countDown() } }.also { it.isDaemon = true; it.start() }

    init {
        leg { proxy.runClientLeg() }
        leg { proxy.runUpstreamLeg() }
        client.start()
        leg { client.run() }
    }

    fun finish() {
        client.close()
        proxy.client.close()
        assertTrue("legs did not end: $errors", done.await(5, TimeUnit.SECONDS))
    }

    fun request(streamId: Int, path: String, method: String = "GET", extra: List<Pair<String, String>> = emptyList(), endStream: Boolean = true) {
        assertTrue(client.writeHeaders(streamId, listOf(":method" to method, ":scheme" to "https", ":authority" to authority, ":path" to path) + extra, endStream))
    }

    fun requestWithBody(streamId: Int, path: String, body: String, method: String = "POST", extra: List<Pair<String, String>> = emptyList()) {
        request(streamId, path, method, extra + ("content-length" to body.length.toString()), endStream = false)
        assertTrue(client.writeData(streamId, body.toByteArray(Charsets.ISO_8859_1), endStream = true))
    }

    companion object {
        fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition()) {
                if (System.currentTimeMillis() > deadline) fail("condition not met within ${timeoutMs}ms")
                Thread.sleep(10)
            }
        }
    }
}
