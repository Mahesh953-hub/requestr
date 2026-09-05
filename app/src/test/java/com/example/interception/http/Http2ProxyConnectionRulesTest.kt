package com.example.interception.http

import com.example.interception.http.Http2Endpoint.ErrorCode
import com.example.interception.http.Http2Messages.H2Message
import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.http.Http2StreamInterceptor.RequestVerdict
import com.example.interception.http.H2ProxyHarness.Companion.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S4 task 35: the [Http2StreamInterceptor] seam inside [Http2ProxyConnection] -- streams a plan
 * claims are held as whole messages, mutated, and re-framed with fresh `content-length`/`DATA`
 * framing toward either upstream flavour; streams without a plan keep the frame-by-frame
 * passthrough of task 57. Rule semantics themselves are covered by the engine-level tests.
 */
class Http2ProxyConnectionRulesTest {

    /** A scripted interceptor: [planFor] decides per request path; verdicts are recorded. */
    private class FakeInterceptor(private val planFor: (String, H2Headers) -> Http2StreamInterceptor.Plan?) : Http2StreamInterceptor {
        val planned = CopyOnWriteArrayList<Int>()
        val abandoned = CopyOnWriteArrayList<String>()
        override fun plan(streamId: Int, headers: H2Headers): Http2StreamInterceptor.Plan? {
            val plan = planFor(headers.pseudoHeaders[":path"] ?: "", headers) ?: return null
            planned += streamId
            return object : Http2StreamInterceptor.Plan by plan {
                override fun onAbandoned(reason: String) { abandoned += "$streamId:$reason"; plan.onAbandoned(reason) }
            }
        }
    }

    private class RequestPlan(
        override val interceptsResponse: Boolean = false,
        private val onReq: (H2Message) -> RequestVerdict,
        private val onResp: (H2Message) -> H2Message = { it },
    ) : Http2StreamInterceptor.Plan {
        val requests = CopyOnWriteArrayList<H2Message>()
        val responses = CopyOnWriteArrayList<H2Message>()
        override fun onRequest(request: H2Message): RequestVerdict { requests += request; return onReq(request) }
        override fun onResponse(response: H2Message): H2Message { responses += response; return onResp(response) }
    }

    private var harness: H2ProxyHarness? = null
    private var h1: FakeH1Origin? = null
    private var h2: FakeH2Origin? = null

    @After fun tearDown() {
        h2?.close()
        h1?.let { runCatching { it.toServer.close() } }
        harness?.finish()
        h1?.thread?.join(2_000)
    }

    private fun h1Origin(body: (H1OriginRequest) -> String = { "origin saw ${it.target}" }) = FakeH1Origin { req ->
        FakeH1Origin.response("200 OK", "X-Origin: h1\r\n", body(req))
    }.also { h1 = it }

    @Test
    fun `a Respond verdict answers the client without ever touching the upstream`() {
        val origin = h1Origin()
        val plan = RequestPlan(onReq = {
            RequestVerdict.Respond(H2Message(listOf(":status" to "403", "x-requestr-rule" to "block", "content-length" to "0"), ByteArray(0)))
        })
        val interceptor = FakeInterceptor { path, _ -> if (path == "/blocked") plan else null }
        val h = H2ProxyHarness("http/1.1", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.request(1, "/blocked")
        h.listener.expect("HEADERS 1 end=true")
        assertEquals("403", h.listener.status(1))
        assertEquals("block", h.listener.header(1, "x-requestr-rule"))
        assertEquals(0L, h.connects.get())
        assertTrue(origin.requests.isEmpty())

        // The connection stays healthy: a later passthrough stream still reaches the origin.
        h.request(3, "/open")
        h.listener.expect("DATA_END 3")
        assertEquals("origin saw /open", h.listener.body(3))
        assertEquals(listOf(1), interceptor.planned)
        waitUntil { h.proxy.interceptedStreamCount == 0 }
        waitUntil { h.transactions.size == 2 }
        assertEquals(setOf(403, 200), h.transactions.map { it.response.statusCode }.toSet())
    }

    @Test
    fun `a Forward verdict with a rewritten body reaches the h1 origin with a recomputed Content-Length`() {
        val origin = h1Origin { "echo:${it.bodyText}" }
        val plan = RequestPlan(onReq = { req ->
            val body = "rewritten payload".toByteArray()
            RequestVerdict.Forward(H2Message(req.headers.filter { it.first != "content-length" } + ("content-length" to body.size.toString()) + ("x-rule" to "yes"), body))
        })
        val interceptor = FakeInterceptor { path, _ -> if (path == "/upload") plan else null }
        val h = H2ProxyHarness("http/1.1", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.requestWithBody(1, "/upload", "original payload!!")
        h.listener.expect("DATA_END 1")
        assertEquals("200", h.listener.status(1))
        assertEquals("echo:rewritten payload", h.listener.body(1))
        val seen = origin.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("rewritten payload", seen.bodyText)
        assertEquals("17", seen.header("Content-Length"))
        assertEquals("yes", seen.header("x-rule"))
        assertEquals("original payload!!", String(plan.requests.single().body))
        waitUntil { h.proxy.interceptedStreamCount == 0 }
    }

    @Test
    fun `a response hook over an h1 origin sees the whole message and its output is re-framed`() {
        val origin = FakeH1Origin { req ->
            // Chunked origin response: the proxy must collect it before the hook runs.
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray()
        }.also { h1 = it }
        val plan = RequestPlan(
            interceptsResponse = true,
            onReq = { RequestVerdict.Forward(it) },
            onResp = { resp ->
                assertEquals("hello world", String(resp.body))
                val body = "HELLO WORLD, REWRITTEN".toByteArray()
                H2Message(resp.headers.filter { it.first != "content-length" } + ("content-length" to body.size.toString()), body)
            },
        )
        val interceptor = FakeInterceptor { path, _ -> if (path == "/page") plan else null }
        val h = H2ProxyHarness("http/1.1", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.request(1, "/page")
        h.listener.expect("DATA_END 1")
        assertEquals("200", h.listener.status(1))
        assertEquals("HELLO WORLD, REWRITTEN", h.listener.body(1))
        assertEquals("22", h.listener.header(1, "content-length"))
        assertEquals(1, plan.responses.size)
        waitUntil { h.proxy.interceptedStreamCount == 0 }
        waitUntil { h.transactions.size == 1 }
        // The observer replays the frames written to the client: the persisted response is the post-rule one.
        assertEquals("HELLO WORLD, REWRITTEN", h.transactions.single().response.body)
    }

    @Test
    fun `a held stream released after a later passthrough stream is remapped onto a fresh upstream id`() {
        val release = CountDownLatch(1)
        val origin = FakeH2Origin { srv, id, req, end ->
            if (end) {
                srv.endpoint.writeHeaders(id, listOf(":status" to "200", "x-upstream-id" to id.toString()), endStream = false)
                srv.endpoint.writeData(id, "origin says ${req.pseudoHeaders[":path"]} body=${srv.body(id)}".toByteArray(), endStream = true)
            }
        }.also { h2 = it }
        val plan = RequestPlan(onReq = { req ->
            release.await(5, TimeUnit.SECONDS)
            val body = "held-then-forwarded".toByteArray()
            RequestVerdict.Forward(H2Message(req.headers.filter { it.first != "content-length" } + ("content-length" to body.size.toString()), body))
        })
        val interceptor = FakeInterceptor { path, _ -> if (path == "/held") plan else null }
        val h = H2ProxyHarness("h2", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.requestWithBody(1, "/held", "client body")
        h.request(3, "/direct")
        h.listener.expect("DATA_END 3")
        assertEquals("origin says /direct body=", h.listener.body(3))
        assertEquals("1", h.listener.header(3, "x-upstream-id")) // the passthrough stream took upstream id 1
        assertEquals(1, h.proxy.interceptedStreamCount)

        release.countDown()
        h.listener.expect("DATA_END 1")
        assertEquals("origin says /held body=held-then-forwarded", h.listener.body(1))
        assertEquals("3", h.listener.header(1, "x-upstream-id")) // the held stream was remapped to id 3
        val forwarded = origin.headers[3]!!.first()
        assertEquals("/held", forwarded.pseudoHeaders[":path"])
        assertEquals("19", forwarded.entries.first { it.first == "content-length" }.second)
        assertNull(origin.error)
        waitUntil { h.proxy.interceptedStreamCount == 0 }
        waitUntil { h.transactions.size == 2 }
        assertEquals(setOf("/held", "/direct"), h.transactions.map { it.request.path }.toSet())
    }

    @Test
    fun `a response hook over an h2 origin collects DATA and trailers before re-framing`() {
        val origin = FakeH2Origin { srv, id, _, end ->
            if (end) {
                srv.endpoint.writeHeaders(id, listOf(":status" to "200", "content-type" to "application/grpc"), endStream = false)
                srv.endpoint.writeData(id, "part-one ".toByteArray(), endStream = false)
                srv.endpoint.writeData(id, "part-two".toByteArray(), endStream = false)
                srv.endpoint.writeHeaders(id, listOf("grpc-status" to "0"), endStream = true)
            }
        }.also { h2 = it }
        val plan = RequestPlan(
            interceptsResponse = true,
            onReq = { RequestVerdict.Forward(it) },
            onResp = { resp ->
                assertEquals("part-one part-two", String(resp.body))
                assertEquals(listOf("grpc-status" to "0"), resp.trailers)
                H2Message(resp.headers + ("x-hook" to "1"), resp.body.reversedArray(), resp.trailers)
            },
        )
        val interceptor = FakeInterceptor { path, _ -> if (path == "/svc/Call") plan else null }
        val h = H2ProxyHarness("h2", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.request(1, "/svc/Call", method = "POST")
        h.listener.expect("HEADERS 1 end=true trailers=true")
        assertEquals("1", h.listener.header(1, "x-hook"))
        assertEquals("owt-trap eno-trap", h.listener.body(1))
        assertEquals(listOf("grpc-status" to "0"), h.listener.headers[1]!!.last().entries)
        waitUntil { h.proxy.interceptedStreamCount == 0 }
    }

    @Test
    fun `a client reset while a stream is held abandons the plan and frees the record`() {
        val origin = h1Origin()
        val release = CountDownLatch(1)
        val plan = RequestPlan(onReq = { release.await(5, TimeUnit.SECONDS); RequestVerdict.Forward(it) })
        val interceptor = FakeInterceptor { _, _ -> plan }
        val h = H2ProxyHarness("http/1.1", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.request(1, "/held", endStream = false) // body never arrives: the record waits in `intercepted`
        waitUntil { h.proxy.interceptedStreamCount == 1 }
        h.client.writeRstStream(1, ErrorCode.CANCEL)
        waitUntil { interceptor.abandoned.isNotEmpty() }
        assertEquals(listOf("1:client reset"), interceptor.abandoned)
        assertEquals(0, h.proxy.interceptedStreamCount)
        release.countDown()
        assertTrue(origin.requests.isEmpty())
    }

    @Test
    fun `a plan that throws resets the stream instead of killing the connection`() {
        val origin = h1Origin()
        val plan = RequestPlan(onReq = { throw IllegalStateException("boom") })
        val interceptor = FakeInterceptor { path, _ -> if (path == "/bad") plan else null }
        val h = H2ProxyHarness("http/1.1", origin.fromServer, origin.toServer, interceptor = interceptor).also { harness = it }

        h.request(1, "/bad")
        assertEquals("RST 1 ${ErrorCode.INTERNAL_ERROR}", h.listener.expect("RST 1"))
        h.request(3, "/fine")
        h.listener.expect("DATA_END 3")
        assertEquals("origin saw /fine", h.listener.body(3))
        waitUntil { h.proxy.interceptedStreamCount == 0 }
    }
}
