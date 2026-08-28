package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.StringValue
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Robolectric-backed (same reason as [OkHttpInterceptorTest] -- [RequestrObserver.observeDetailed]
 * touches `android.util.Base64`/`android.content.Context`) test of [RequestrGrpcInterceptor]
 * against a hand-rolled fake [Channel]/[ClientCall] -- the same "real `io.grpc.*` API types, fake
 * transport, no native/network layer" technique [CronetInterceptorTest] uses for Cronet. This
 * drives the interceptor's [ClientCall] wrapper through a full lifecycle (headers -> sent message
 * -> response headers -> received message -> close) by hand, exactly as gRPC's real
 * `ClientCallImpl` would drive it, and inspects what ended up queued for [RequestrObserver.flush].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GrpcInterceptorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        RequestrObserver.init(context, pairingToken = "unused-in-this-test", apiBaseUrl = "http://127.0.0.1:1")
        RecordedGrpcEventCapture.clear()
    }

    @Test
    fun `unary call reports request+response messages, headers, and OK status from onClose`() {
        val method = stringMethodDescriptor("test.Service/Echo", MethodDescriptor.MethodType.UNARY)
        val fakeCall = FakeClientCall<String, String>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)

        val requestHeaders = Metadata()
        requestHeaders.put(Metadata.Key.of("x-request-header", Metadata.ASCII_STRING_MARSHALLER), "req-value")
        val listener = RecordingListener<String>()
        wrappedCall.start(listener, requestHeaders)
        wrappedCall.sendMessage("ping")

        // Drives the fake "server" side by hand -- exactly the calls gRPC's real transport would
        // make on the response listener it was handed at start().
        val responseHeaders = Metadata()
        responseHeaders.put(Metadata.Key.of("x-response-header", Metadata.ASCII_STRING_MARSHALLER), "resp-value")
        fakeCall.listener!!.onHeaders(responseHeaders)
        fakeCall.listener!!.onMessage("pong")
        val trailers = Metadata()
        trailers.put(Metadata.Key.of("x-trailer", Metadata.ASCII_STRING_MARSHALLER), "trailer-value")
        fakeCall.listener!!.onClose(Status.OK, trailers)

        assertEquals(listOf("ping"), fakeCall.sentMessages)

        val event = RecordedGrpcEventCapture.latestFor("test.Service/Echo")
        assertNotNull("expected a reported gRPC event", event)
        event!!
        assertEquals("grpc://test.Service/Echo", event.url)
        assertEquals("UNARY", event.method)
        assertEquals("gRPC", event.httpStack)
        assertEquals(Status.Code.OK.value(), event.statusCode)
        assertTrue(event.isDecrypted)
        assertEquals("req-value", event.headers["request:x-request-header"])
        assertEquals("resp-value", event.headers["response:x-response-header"])
        assertEquals("trailer-value", event.headers["trailer:x-trailer"])
        assertEquals("UNARY", event.headers["grpc-method-type"])
        assertEquals("OK", event.headers["grpc-status-code"])
        assertNull(event.headers["grpc-cancelled"])

        val requestBody = String(android.util.Base64.decode(event.requestBodyBase64, android.util.Base64.NO_WRAP))
        val responseBody = String(android.util.Base64.decode(event.responseBodyBase64, android.util.Base64.NO_WRAP))
        assertEquals("""["ping"]""", requestBody)
        assertEquals("""["pong"]""", responseBody)
    }

    @Test
    fun `cancel() does not report until the matching onClose arrives, then reports cancelled=true`() {
        val method = stringMethodDescriptor("test.Service/Slow", MethodDescriptor.MethodType.UNARY)
        val fakeCall = FakeClientCall<String, String>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)
        wrappedCall.start(RecordingListener(), Metadata())
        wrappedCall.cancel("client gave up", null)

        assertTrue(fakeCall.cancelled)
        // Cancelling the ClientCall must not, by itself, report anything -- gRPC still owes this
        // call exactly one onClose, and that's the only place RequestrGrpcInterceptor reports
        // from. No polling here (unlike latestFor below): cancel()/onClose() run synchronously on
        // this thread, so this is a deterministic check, not a race against a background flush.
        assertNull(
            "cancel() alone must not report before onClose",
            RecordedGrpcEventCapture.existsNowFor("test.Service/Slow"),
        )

        fakeCall.listener!!.onClose(Status.CANCELLED.withDescription("client gave up"), Metadata())

        val event = RecordedGrpcEventCapture.latestFor("test.Service/Slow")
        assertNotNull(event)
        assertEquals("true", event!!.headers["grpc-cancelled"])
        assertEquals("client gave up", event.headers["grpc-cancel-reason"])
        assertEquals(Status.Code.CANCELLED.value(), event.statusCode)
    }

    @Test
    fun `protobuf messages render as JSON via reflection instead of opaque toString`() {
        val method = MethodDescriptor.newBuilder<StringValue, StringValue>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Service/EchoProto")
            .setRequestMarshaller(protobufMarshaller { StringValue.parseFrom(it) })
            .setResponseMarshaller(protobufMarshaller { StringValue.parseFrom(it) })
            .build()
        val fakeCall = FakeClientCall<StringValue, StringValue>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)
        wrappedCall.start(RecordingListener(), Metadata())
        wrappedCall.sendMessage(StringValue.newBuilder().setValue("hello-request").build())
        fakeCall.listener!!.onMessage(StringValue.newBuilder().setValue("hello-response").build())
        fakeCall.listener!!.onClose(Status.OK, Metadata())

        val event = RecordedGrpcEventCapture.latestFor("test.Service/EchoProto")
        assertNotNull(event)
        val requestBody = String(android.util.Base64.decode(event!!.requestBodyBase64, android.util.Base64.NO_WRAP))
        val responseBody = String(android.util.Base64.decode(event.responseBodyBase64, android.util.Base64.NO_WRAP))
        // Raw protobuf Message#toString() would render as text-format (`value: "hello-request"\n`),
        // never bare quoted JSON -- seeing the value present without that text-format marker
        // confirms the reflective JsonFormat path actually ran, not the toString() fallback.
        assertTrue("expected JSON rendering, got: $requestBody", requestBody.contains("hello-request"))
        assertFalse("expected JSON rendering, not protobuf text-format, got: $requestBody", requestBody.contains("value:"))
        assertTrue("expected JSON rendering, got: $responseBody", responseBody.contains("hello-response"))
    }

    // -- fakes ----------------------------------------------------------------------------------

    private fun stringMethodDescriptor(fullName: String, type: MethodDescriptor.MethodType): MethodDescriptor<String, String> {
        val marshaller = object : MethodDescriptor.Marshaller<String> {
            override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
            override fun parse(stream: InputStream): String = stream.readBytes().toString(Charsets.UTF_8)
        }
        return MethodDescriptor.newBuilder<String, String>()
            .setType(type)
            .setFullMethodName(fullName)
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build()
    }

    private fun <T> protobufMarshaller(parse: (ByteArray) -> T): MethodDescriptor.Marshaller<T> where T : com.google.protobuf.MessageLite {
        return object : MethodDescriptor.Marshaller<T> {
            override fun stream(value: T): InputStream = ByteArrayInputStream(value.toByteArray())
            override fun parse(stream: InputStream): T = parse(stream.readBytes())
        }
    }

    private class FakeClientCall<ReqT, RespT> : ClientCall<ReqT, RespT>() {
        var listener: Listener<RespT>? = null
        val sentMessages = mutableListOf<ReqT>()
        var cancelled = false

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            listener = responseListener
        }

        override fun request(numMessages: Int) {}

        override fun cancel(message: String?, cause: Throwable?) {
            cancelled = true
        }

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

    private class RecordingListener<RespT> : ClientCall.Listener<RespT>() {
        override fun onClose(status: Status, trailers: Metadata) {}
    }
}

/**
 * Same rationale/technique as [OkHttpInterceptorTest]'s `RecordedEventCapture`: `pendingEvents`
 * has no public accessor, and [RequestrObserver.flush] re-queues on a background coroutine after
 * every failed POST (there's no real `LocalApiServer` listening on the bogus `apiBaseUrl` these
 * tests use), so this polls briefly rather than racing it.
 */
private object RecordedGrpcEventCapture {
    fun clear() {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(RequestrObserver) as MutableList<ObserverEvent>
        synchronized(list) { list.clear() }
    }

    /** Non-polling, immediate check -- see the "cancel() does not report" test's kdoc for why
     * this is safe to call without racing a background flush. */
    fun existsNowFor(fullMethodName: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        return synchronized(field.get(RequestrObserver) as Any) {
            @Suppress("UNCHECKED_CAST")
            (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.url.endsWith(fullMethodName) }
        }
    }

    fun latestFor(fullMethodName: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(field.get(RequestrObserver) as Any) {
                @Suppress("UNCHECKED_CAST")
                (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.url.endsWith(fullMethodName) }
            }
            if (found != null) return found
            Thread.sleep(20)
        }
        return null
    }
}
