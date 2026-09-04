package com.example.interception.http

import com.example.interception.http.Http2Endpoint.ErrorCode
import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.relay.BufferPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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
 * S3 task 57 (gate G27): end-to-end h2 client -> [Http2ProxyConnection] -> fake upstream over
 * pipes, for both upstream flavours the proxy translates to (HTTP/1.1 by ALPN `http/1.1`, h2 by
 * ALPN `h2`), with the [Http2Session] observer attached exactly as [com.example.interception.MitmProxyEngine]
 * attaches it -- so the rows the UI gets from a terminated connection are asserted here too.
 */
class Http2ProxyConnectionTest {

    // ── h2 test client ──────────────────────────────────────────────────────────────────

    private class ClientRecorder : Http2Endpoint.Listener {
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

        fun body(streamId: Int): String = String(data[streamId]?.toByteArray() ?: ByteArray(0), Charsets.ISO_8859_1)
        fun status(streamId: Int): String? = headers[streamId]?.firstOrNull()?.pseudoHeaders?.get(":status")
    }

    // ── fake HTTP/1.1 origin ─────────────────────────────────────────────────────────────

    private class H1Request(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray, val trailers: List<Pair<String, String>>) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private class FakeH1Server(private val respond: (H1Request) -> ByteArray) {
        val toServer = PipedOutputStream()
        private val serverIn = PipedInputStream(toServer, 1 shl 20)
        val fromServer = PipedInputStream(1 shl 20)
        private val serverOut = PipedOutputStream(fromServer)
        val requests = CopyOnWriteArrayList<H1Request>()
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

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun readRequest(input: InputStream): H1Request? {
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
            return H1Request(parts[0], parts[1], headers, body.toByteArray(), trailers)
        }
    }

    private fun h1Response(status: String, headers: String, body: String) =
        "HTTP/1.1 $status\r\n$headers${if (body.isNotEmpty()) "Content-Length: ${body.length}\r\n" else ""}\r\n$body".toByteArray(Charsets.ISO_8859_1)

    // ── harness ─────────────────────────────────────────────────────────────────────────

    private class Harness(
        alpn: String?,
        upstreamIn: InputStream,
        upstreamOut: OutputStream,
        connectFails: Boolean = false,
    ) {
        val transactions = CopyOnWriteArrayList<Http2Transaction>()
        val grpcEvents = CopyOnWriteArrayList<GrpcEvent>()
        val grpcEnds = CopyOnWriteArrayList<GrpcCallEnd>()
        val session = Http2Session(
            onTransaction = { transactions += it },
            onGrpcMessage = { grpcEvents += it },
            onGrpcCallEnded = { grpcEnds += it },
        )
        val fromClient = AtomicLong()
        val fromUpstream = AtomicLong()
        private val clientToProxy = PipedOutputStream()
        private val proxyToClient = PipedOutputStream()
        val proxy = Http2ProxyConnection(
            clientInput = PipedInputStream(clientToProxy, 1 shl 20),
            clientOutput = proxyToClient,
            connectUpstream = {
                if (connectFails) throw IOException("connection refused")
                Http2ProxyConnection.UpstreamLeg(upstreamIn, upstreamOut, alpn)
            },
            observer = session,
            bufferPool = BufferPool(),
            bytesFromClient = fromClient,
            bytesFromUpstream = fromUpstream,
            name = "test",
        )
        val listener = ClientRecorder()
        val client = Http2Endpoint(PipedInputStream(proxyToClient, 1 shl 20), clientToProxy, Http2Endpoint.Role.CLIENT, listener, "app")
        private val done = CountDownLatch(3)
        val errors = CopyOnWriteArrayList<Throwable>()
        private fun leg(block: () -> Unit) = Thread { try { block() } catch (t: Throwable) { errors += t } finally { done.countDown() } }.also { it.isDaemon = true; it.start() }

        init {
            leg { proxy.runClientLeg() }
            leg { proxy.runUpstreamLeg() }
            client.start() // preface + SETTINGS before any test-thread frame can interleave
            leg { client.run() }
        }

        fun finish() {
            client.close()
            proxy.client.close()
            assertTrue("legs did not end: $errors", done.await(5, TimeUnit.SECONDS))
        }

        fun request(streamId: Int, path: String, method: String = "GET", extra: List<Pair<String, String>> = emptyList(), endStream: Boolean = true) {
            assertTrue(client.writeHeaders(streamId, listOf(":method" to method, ":scheme" to "https", ":authority" to "origin.test", ":path" to path) + extra, endStream))
        }
    }

    private var harness: Harness? = null
    private var h1: FakeH1Server? = null

    private var h2: FakeH2Server? = null

    @After fun tearDown() {
        // Fake origins first: a PipedInputStream reader only wakes when its writer closes.
        h2?.let { it.endpoint.close(); runCatching { it.toServer.close() } }
        h1?.let { runCatching { it.toServer.close() } }
        harness?.finish()
        h1?.thread?.join(2_000)
        h2?.thread?.join(2_000)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within $timeoutMs ms")
            Thread.sleep(10)
        }
    }

    // ── h2 client, HTTP/1.1 upstream ────────────────────────────────────────────────────

    @Test
    fun `GET over h2 is translated to HTTP 1_1 and the response back to h2 with one observed transaction`() {
        val server = FakeH1Server { req ->
            h1Response("200 OK", "Content-Type: text/plain\r\nConnection: keep-alive, X-Hop\r\nX-Hop: 1\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n", "hello from ${req.target}")
        }.also { h1 = it }
        val h = Harness("http/1.1", server.fromServer, server.toServer).also { harness = it }

        h.request(1, "/hello?x=1", extra = listOf("accept" to "*/*", "cookie" to "s=1", "cookie" to "t=2"))
        h.listener.expect("DATA_END 1")
        assertEquals("200", h.listener.status(1))
        val response = h.listener.headers[1]!!.first().entries
        assertEquals(listOf(":status" to "200", "content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2", "content-length" to "21"), response)
        assertEquals("hello from /hello?x=1", h.listener.body(1))
        h.listener.expect("CLOSED 1")

        val seen = server.requests.single()
        assertEquals("GET", seen.method)
        assertEquals("/hello?x=1", seen.target)
        assertEquals("origin.test", seen.header("Host"))
        assertEquals("s=1; t=2", seen.header("Cookie"))
        assertNull(seen.header("Content-Length"))
        assertEquals("http/1.1", h.proxy.upstreamMode)

        waitUntil { h.transactions.size == 1 }
        val tx = h.transactions.single()
        assertEquals("/hello?x=1", tx.request.path)
        assertEquals(200, tx.response.statusCode)
        assertEquals("hello from /hello?x=1", tx.response.body)
        assertEquals(listOf(":status" to "200", "content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2", "content-length" to "21"), tx.responseHeaders!!.entries)
        assertTrue(h.fromClient.get() > 0)
        assertTrue(h.fromUpstream.get() > 0)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `sequential streams reuse one HTTP 1_1 upstream connection and each gets its own response`() {
        val server = FakeH1Server { req -> h1Response("200 OK", "", "r:${req.target}") }.also { h1 = it }
        val h = Harness("http/1.1", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/a")
        h.request(3, "/b")
        h.request(5, "/c")
        h.listener.expect("DATA_END 1"); h.listener.expect("DATA_END 3"); h.listener.expect("DATA_END 5")
        assertEquals("r:/a", h.listener.body(1))
        assertEquals("r:/b", h.listener.body(3))
        assertEquals("r:/c", h.listener.body(5))
        assertEquals(3, server.requests.size)
        waitUntil { h.transactions.size == 3 }
        assertEquals(listOf("/a", "/b", "/c"), h.transactions.map { it.request.path })
    }

    @Test
    fun `POST body and trailers are framed chunked upstream and a chunked response with trailers comes back as h2 trailers`() {
        val server = FakeH1Server { req ->
            val body = "echo:" + String(req.body, Charsets.ISO_8859_1)
            ("HTTP/1.1 200 OK\r\nContent-Type: application/grpc\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "${body.length.toString(16)}\r\n$body\r\n0\r\nGrpc-Status: 0\r\nGrpc-Message: fine\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        }.also { h1 = it }
        val h = Harness("http/1.1", server.fromServer, server.toServer).also { harness = it }

        h.request(1, "/pkg.Svc/Call", method = "POST", extra = listOf("content-type" to "application/grpc", "te" to "trailers"), endStream = false)
        assertTrue(h.client.writeData(1, "payload".toByteArray(), endStream = false))
        assertTrue(h.client.writeHeaders(1, listOf("x-client-trailer" to "1"), endStream = true))

        assertEquals("HEADERS 1 end=false trailers=false", h.listener.expect("HEADERS 1 end=false"))
        assertEquals("HEADERS 1 end=true trailers=true", h.listener.expect("HEADERS 1 end=true"))
        assertEquals(listOf("grpc-status" to "0", "grpc-message" to "fine"), h.listener.headers[1]!![1].entries)
        assertEquals("echo:payload", h.listener.body(1))

        val seen = server.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("chunked", seen.header("Transfer-Encoding"))
        assertEquals("trailers", seen.header("TE"))
        assertEquals("payload", String(seen.body))
        assertEquals(listOf("x-client-trailer" to "1"), seen.trailers)

        // application/grpc: the observer reports the call end (with trailers) instead of a
        // plain Http2Transaction row.
        waitUntil { h.grpcEnds.size == 1 }
        assertEquals(listOf("grpc-status" to "0", "grpc-message" to "fine"), h.grpcEnds.single().responseTrailers!!.entries)
        assertEquals(0, h.grpcEnds.single().grpcStatus)
        assertTrue(h.transactions.isEmpty())
    }

    @Test
    fun `bodyless responses end the stream on HEADERS and a POST without body carries Content-Length 0`() {
        val server = FakeH1Server { req ->
            when (req.method) {
                "HEAD" -> "HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n".toByteArray()
                else -> "HTTP/1.1 204 No Content\r\n\r\n".toByteArray()
            }
        }.also { h1 = it }
        val h = Harness("http/1.1", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/h", method = "HEAD")
        assertEquals("HEADERS 1 end=true trailers=false", h.listener.expect("HEADERS 1"))
        assertEquals(listOf(":status" to "200", "content-length" to "1234"), h.listener.headers[1]!!.single().entries)
        h.request(3, "/p", method = "POST")
        assertEquals("HEADERS 3 end=true trailers=false", h.listener.expect("HEADERS 3"))
        assertEquals("204", h.listener.status(3))
        assertEquals("0", server.requests[1].header("Content-Length"))
        assertTrue(h.listener.data.isEmpty())
    }

    @Test
    fun `an upstream that cannot be reached answers every stream with 502`() {
        val h = Harness("http/1.1", PipedInputStream(), PipedOutputStream(), connectFails = true).also { harness = it }
        h.request(1, "/x")
        assertEquals("HEADERS 1 end=true trailers=false", h.listener.expect("HEADERS 1"))
        assertEquals("502", h.listener.status(1))
        h.request(3, "/y")
        h.listener.expect("HEADERS 3")
        assertEquals("502", h.listener.status(3))
        assertNull(h.proxy.upstreamMode)
        waitUntil { h.transactions.size == 2 }
        assertEquals(listOf(502, 502), h.transactions.map { it.response.statusCode })
    }

    @Test
    fun `a client reset while the HTTP 1_1 request is queued is not sent upstream`() {
        val gate = CountDownLatch(1)
        val server = FakeH1Server { req ->
            if (req.target == "/slow") gate.await(5, TimeUnit.SECONDS)
            h1Response("200 OK", "", "r:${req.target}")
        }.also { h1 = it }
        val h = Harness("http/1.1", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/slow")
        h.request(3, "/cancelled")
        waitUntil { server.requests.size == 1 }
        h.client.writeRstStream(3, ErrorCode.CANCEL)
        Thread.sleep(100)
        gate.countDown()
        h.listener.expect("DATA_END 1")
        h.request(5, "/after")
        h.listener.expect("DATA_END 5")
        assertEquals(listOf("/slow", "/after"), server.requests.map { it.target })
    }

    // ── h2 client, h2 upstream ──────────────────────────────────────────────────────────

    private class FakeH2Server(private val onRequest: (FakeH2Server, Int, H2Headers, Boolean) -> Unit) : Http2Endpoint.Listener {
        val toServer = PipedOutputStream()
        val fromServer = PipedInputStream(1 shl 20)
        val endpoint = Http2Endpoint(PipedInputStream(toServer, 1 shl 20), PipedOutputStream(fromServer), Http2Endpoint.Role.SERVER, this, "origin-h2")
        val data = ConcurrentHashMap<Int, ByteArrayOutputStream>()
        val events = CopyOnWriteArrayList<String>()
        val headers = ConcurrentHashMap<Int, MutableList<H2Headers>>()
        var error: Throwable? = null
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
    }

    @Test
    fun `h2 upstream by ALPN gets the request re-encoded on a new connection and the response is relayed stream by stream`() {
        val server = FakeH2Server { srv, id, req, end ->
            if (end) {
                srv.endpoint.writeHeaders(id, listOf(":status" to "201", "x-origin" to "h2"), endStream = false)
                srv.endpoint.writeData(id, "origin says ${req.pseudoHeaders[":path"]}".toByteArray(), endStream = true)
            }
        }.also { h2 = it }
        val h = Harness("h2", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/one", extra = listOf("x-req" to "a"))
        h.request(3, "/two")
        h.listener.expect("DATA_END 1"); h.listener.expect("DATA_END 3")
        assertEquals("h2", h.proxy.upstreamMode)
        assertEquals(listOf(":status" to "201", "x-origin" to "h2"), h.listener.headers[1]!!.first().entries)
        assertEquals("origin says /one", h.listener.body(1))
        assertEquals("origin says /two", h.listener.body(3))
        assertEquals(listOf(":method" to "GET", ":scheme" to "https", ":authority" to "origin.test", ":path" to "/one", "x-req" to "a"), server.headers[1]!!.first().entries)
        waitUntil { h.transactions.size == 2 }
        assertEquals(setOf(201), h.transactions.map { it.response.statusCode }.toSet())
        assertNull(server.error)
        // The upstream endpoint has its own dynamic tables: headers were re-encoded, not copied.
        assertTrue(h.proxy.client.hpackDecoder.dynamicCount > 0)
    }

    @Test
    fun `gRPC call over h2 upstream keeps trailers and the observer extracts the messages`() {
        val grpcMessage = byteArrayOf(0, 0, 0, 0, 3, 0x0a, 0x01, 0x41) // uncompressed, len 3, field 1 = "A"
        val server = FakeH2Server { srv, id, _, end ->
            if (end) {
                srv.endpoint.writeHeaders(id, listOf(":status" to "200", "content-type" to "application/grpc"), endStream = false)
                srv.endpoint.writeData(id, grpcMessage, endStream = false)
                srv.endpoint.writeHeaders(id, listOf("grpc-status" to "0", "grpc-message" to ""), endStream = true)
            }
        }.also { h2 = it }
        val h = Harness("h2", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/pkg.Svc/Method", method = "POST", extra = listOf("content-type" to "application/grpc", "te" to "trailers"), endStream = false)
        assertTrue(h.client.writeData(1, grpcMessage, endStream = true))
        assertEquals("HEADERS 1 end=true trailers=true", h.listener.expect("HEADERS 1 end=true"))
        assertEquals(listOf("grpc-status" to "0", "grpc-message" to ""), h.listener.headers[1]!![1].entries)
        assertEquals(grpcMessage.toList(), h.listener.data[1]!!.toByteArray().toList())
        assertEquals(grpcMessage.toList(), server.data[1]!!.toByteArray().toList())
        waitUntil { h.grpcEnds.size == 1 }
        assertEquals(2, h.grpcEvents.size)
        assertEquals(1, h.grpcEvents.count { it.isClient })
        assertEquals(0, h.grpcEnds.single().grpcStatus)
    }

    @Test
    fun `RST_STREAM and GOAWAY cross the proxy in both directions with their error codes`() {
        val server = FakeH2Server { srv, id, req, end ->
            if (end && req.pseudoHeaders[":path"] == "/reset-me") srv.endpoint.writeRstStream(id, ErrorCode.INTERNAL_ERROR)
        }.also { h2 = it }
        val h = Harness("h2", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/reset-me")
        assertEquals("RST 1 ${ErrorCode.INTERNAL_ERROR}", h.listener.expect("RST 1"))

        h.request(3, "/hang", endStream = false)
        waitUntil { server.events.contains("HEADERS 3 end=false trailers=false") }
        h.client.writeRstStream(3, ErrorCode.CANCEL)
        waitUntil { server.events.contains("RST 3 ${ErrorCode.CANCEL}") }

        server.endpoint.writeGoAway(ErrorCode.ENHANCE_YOUR_CALM, "slow down")
        assertEquals("GOAWAY 3 ${ErrorCode.ENHANCE_YOUR_CALM}", h.listener.expect("GOAWAY"))
        assertFalse(h.client.writeHeaders(5, listOf(":method" to "GET", ":scheme" to "https", ":path" to "/"), endStream = true))
    }

    @Test
    fun `a large response body is streamed through both flow-control windows intact`() {
        val payload = ByteArray(400_000) { (it * 7).toByte() }
        val server = FakeH2Server { srv, id, _, end ->
            if (end) Thread {
                srv.endpoint.writeHeaders(id, listOf(":status" to "200"), endStream = false)
                srv.endpoint.writeData(id, payload, endStream = true)
            }.start()
        }.also { h2 = it }
        val h = Harness("h2", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/big")
        h.listener.expect("DATA_END 1", timeoutSec = 15)
        assertEquals(payload.size, h.listener.data[1]!!.size())
        assertEquals(payload.toList(), h.listener.data[1]!!.toByteArray().toList())
        assertTrue(h.fromUpstream.get() >= payload.size)
    }

    @Test
    fun `the client leg closing sends GOAWAY upstream and ends the upstream leg`() {
        val server = FakeH2Server { _, _, _, _ -> }.also { h2 = it }
        val h = Harness("h2", server.fromServer, server.toServer).also { harness = it }
        h.request(1, "/open", endStream = false)
        waitUntil { server.events.contains("HEADERS 1 end=false trailers=false") }
        h.client.close()
        waitUntil { server.events.any { it.startsWith("GOAWAY") } }
        assertNotNull(server.events.firstOrNull { it.startsWith("GOAWAY") })
        server.thread.join(5_000)
        assertFalse(server.thread.isAlive)
    }
}
