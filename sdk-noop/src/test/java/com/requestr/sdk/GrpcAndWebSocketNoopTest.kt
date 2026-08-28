package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 6 no-op coverage: [RequestrGrpcInterceptor]/`addRequestrInterceptor` (gRPC) and
 * [RequestrWebSocketListener]/[RequestrWebSocket]/`newRequestrWebSocket` (WebSocket) must be
 * fully pass-through here -- calls/messages/lifecycle events still happen exactly as they would
 * uninstrumented, nothing is captured or reported anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GrpcAndWebSocketNoopTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RequestrObserver.init(context, pairingToken = "unused", apiBaseUrl = "http://127.0.0.1:1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `RequestrGrpcInterceptor passes the call straight through to the real channel`() {
        val method = MethodDescriptor.newBuilder<String, String>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Service/Echo")
            .setRequestMarshaller(stringMarshaller())
            .setResponseMarshaller(stringMarshaller())
            .build()
        val fakeCall = FakeClientCall<String, String>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)
        assertSame("no-op interceptor must return the delegate's own ClientCall unwrapped", fakeCall, wrappedCall)

        wrappedCall.sendMessage("ping")
        assertEquals(listOf("ping"), fakeCall.sentMessages)
    }

    @Test
    fun `WebSocket send-receive-close round trip still works end-to-end, just unobserved`() {
        val serverReceivedLatch = CountDownLatch(1)
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("echo:$text")
                serverReceivedLatch.countDown()
            }

            // MockWebServer close-handshake gotcha (also hit in WebSocketInterceptorTest): the
            // peer that RECEIVES a close frame must answer with its own close() call, or the
            // client's onClosing/onClosed never fire and MockWebServer.shutdown() hangs until it
            // throws "Gave up waiting for queue to shut down".
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val clientReceivedLatch = CountDownLatch(1)
        val clientClosedLatch = CountDownLatch(1)
        var receivedText: String? = null
        val clientListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedText = text
                clientReceivedLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clientClosedLatch.countDown()
            }
        }

        val client = OkHttpClient()
        val request = Request.Builder().url(server.url("/ws")).build()
        val socket = client.newRequestrWebSocket(request, clientListener, RequestrObserver)

        socket.send("ping")
        assertTrue(serverReceivedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(clientReceivedLatch.await(5, TimeUnit.SECONDS))
        assertEquals("echo:ping", receivedText)

        socket.close(1000, "done")
        assertTrue(clientClosedLatch.await(5, TimeUnit.SECONDS))
    }

    private fun stringMarshaller(): MethodDescriptor.Marshaller<String> = object : MethodDescriptor.Marshaller<String> {
        override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
        override fun parse(stream: InputStream): String = stream.readBytes().toString(Charsets.UTF_8)
    }

    private class FakeClientCall<ReqT, RespT> : ClientCall<ReqT, RespT>() {
        val sentMessages = mutableListOf<ReqT>()
        override fun start(responseListener: Listener<RespT>, headers: Metadata) {}
        override fun request(numMessages: Int) {}
        override fun cancel(message: String?, cause: Throwable?) {}
        override fun halfClose() {}
        override fun sendMessage(message: ReqT) {
            sentMessages.add(message)
        }
    }

    private class FakeChannel<ReqT, RespT>(private val call: FakeClientCall<ReqT, RespT>) : Channel() {
        override fun <RequestT, ResponseT> newCall(
            methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
            callOptions: CallOptions,
        ): ClientCall<RequestT, ResponseT> {
            @Suppress("UNCHECKED_CAST")
            return call as ClientCall<RequestT, ResponseT>
        }

        override fun authority(): String = "fake-authority"
    }
}
