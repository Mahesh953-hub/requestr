package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric-backed (same reason as [OkHttpInterceptorTest]) end-to-end test of
 * [newRequestrWebSocket] against a real [MockWebServer] WebSocket upgrade: opens a real socket,
 * sends a text message, receives the server's echo, then closes -- and inspects what ended up
 * queued for [RequestrObserver.flush] to confirm [RequestrWebSocketListener]/[RequestrWebSocket]
 * actually captured open/sent/received/closing/closed, not just that they compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class WebSocketInterceptorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // No pairing token needed -- same rationale as OkHttpInterceptorTest: the outbound flush
        // POST fails against a nonexistent /api/observe server and re-queues, which is fine since
        // the assertions inspect pendingEvents, not the flush outcome.
        RequestrObserver.init(context, pairingToken = "unused-in-this-test", apiBaseUrl = "http://127.0.0.1:1")
        RecordedWsEventCapture.clear()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `open, sent text, received text, closing, and closed are all captured`() {
        val serverReceivedLatch = CountDownLatch(1)
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("echo:$text")
                serverReceivedLatch.countDown()
            }

            // OkHttp's WebSocket close handshake requires the peer that receives a close frame
            // to answer with its own close() call -- without this, the client never sees its
            // own onClosing/onClosed (both fire only once the *other* side acknowledges), and
            // the handshake just hangs until MockWebServer's own shutdown timeout.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val clientReceivedLatch = CountDownLatch(1)
        val clientListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                clientReceivedLatch.countDown()
            }
        }

        val request = Request.Builder().url(server.url("/ws")).build()
        val socket = client.newRequestrWebSocket(request, clientListener, RequestrObserver)

        assertNotNull("expected an 'open' event", RecordedWsEventCapture.await("open"))

        socket.send("ping")
        assertNotNull("expected a 'message-sent-text' event", RecordedWsEventCapture.await("message-sent-text"))

        assertTrue(serverReceivedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(clientReceivedLatch.await(5, TimeUnit.SECONDS))

        val received = RecordedWsEventCapture.await("message-received-text")
        assertNotNull(received)
        val body = String(android.util.Base64.decode(received!!.responseBodyBase64, android.util.Base64.NO_WRAP))
        assertEquals("echo:ping", body)

        socket.close(1000, "done")
        assertNotNull("expected a 'close-requested' event", RecordedWsEventCapture.await("close-requested"))
        assertNotNull("expected a 'closing' event", RecordedWsEventCapture.await("closing"))
        assertNotNull("expected a 'closed' event", RecordedWsEventCapture.await("closed"))

    }

    @Test
    fun `binary messages are captured as message-sent-binary and message-received-binary`() {
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(bytes)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val clientListener = object : WebSocketListener() {}
        val request = Request.Builder().url(server.url("/ws-bin")).build()
        val socket = client.newRequestrWebSocket(request, clientListener, RequestrObserver)
        assertNotNull(RecordedWsEventCapture.await("open"))

        socket.send(ByteString.of(1, 2, 3, 4))
        val sent = RecordedWsEventCapture.await("message-sent-binary")
        assertNotNull(sent)
        assertEquals(4L, sent!!.bodySize)

        val received = RecordedWsEventCapture.await("message-received-binary")
        assertNotNull(received)

    }

    @Test
    fun `a failure to upgrade is captured as a failure event, not silently dropped`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val failureLatch = CountDownLatch(1)
        val clientListener = object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failureLatch.countDown()
            }
        }
        val request = Request.Builder().url(server.url("/ws-fail")).build()
        client.newRequestrWebSocket(request, clientListener, RequestrObserver)

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS))
        val event = RecordedWsEventCapture.await("failure")
        assertNotNull(event)
        assertEquals(404, event!!.statusCode)
        assertNull(event.responseBodyBase64)

    }
}

/**
 * Same rationale/technique as [OkHttpInterceptorTest]'s `RecordedEventCapture`: `pendingEvents`
 * has no public accessor, and [RequestrObserver.flush] re-queues on a background coroutine after
 * every failed POST, so this polls rather than racing it. Looks up events by the synthetic
 * `ws-event` header [RequestrWebSocketListener]/[RequestrWebSocket] tag every reported event
 * with, since (unlike every other interceptor's tests) all of a WebSocket's events share one URL.
 */
private object RecordedWsEventCapture {
    fun clear() {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(RequestrObserver) as MutableList<ObserverEvent>
        synchronized(list) { list.clear() }
    }

    fun await(wsEvent: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(field.get(RequestrObserver) as Any) {
                @Suppress("UNCHECKED_CAST")
                (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.headers["ws-event"] == wsEvent }
            }
            if (found != null) return found
            Thread.sleep(20)
        }
        return null
    }
}
