package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.junit.Assert.assertEquals
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
 * Stress/bounded-retention coverage for [GrpcCallCapture]/[BoundedMessageBuffer] -- a long-lived
 * gRPC bidi/streaming call can send or receive an effectively unbounded number of messages before
 * it ever closes, and [RequestrGrpcInterceptor] only reports once, from `onClose`. Without an
 * explicit retention bound, that single call's memory footprint (every rendered message, held
 * until close) would grow without limit for the lifetime of the stream.
 *
 * These tests exercise [BoundedMessageBuffer] both directly (fast, deterministic, no fake
 * [ClientCall] plumbing needed to assert on internal accounting -- see [GrpcCallCapture]'s kdoc
 * for why it's `internal`) and through the full interceptor (to prove the bounded buffers are
 * wired correctly end to end and that the reported event still carries the truncation/drop
 * metadata a consumer needs to know the capture isn't complete).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GrpcInterceptorStressTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        RequestrObserver.init(context, pairingToken = "unused-in-this-test", apiBaseUrl = "http://127.0.0.1:1")
        RecordedGrpcStressEventCapture.clear()
    }

    @Test
    fun `BoundedMessageBuffer retains only up to the message-count budget and tracks the rest as dropped`() {
        val buffer = BoundedMessageBuffer(maxMessages = 50, maxTotalBytes = 10 * 1024 * 1024)

        repeat(5000) { i -> buffer.add("msg-$i") }

        assertEquals(5000, buffer.totalCount)
        assertEquals(50, buffer.messages.size)
        assertEquals(4950, buffer.droppedCount)
        assertTrue("expected some dropped bytes to be tallied", buffer.droppedBytes > 0)
        assertTrue(buffer.isTruncated)
        // Arrival order is preserved for what *is* retained -- the first 50 messages sent, not an
        // arbitrary/most-recent 50.
        assertEquals("msg-0", buffer.messages.first())
        assertEquals("msg-49", buffer.messages.last())
    }

    @Test
    fun `BoundedMessageBuffer retains only up to the byte budget even under the message-count limit`() {
        // Budget for exactly 10 messages of 100 bytes each -- count budget (50) is intentionally
        // far above what actually fits, so this isolates the byte-budget code path.
        val buffer = BoundedMessageBuffer(maxMessages = 50, maxTotalBytes = 1000)
        val bigMessage = "x".repeat(100)

        repeat(30) { buffer.add(bigMessage) }

        assertEquals(30, buffer.totalCount)
        assertEquals(10, buffer.messages.size)
        assertEquals(20, buffer.droppedCount)
        assertEquals(20L * 100, buffer.droppedBytes)
        assertTrue(buffer.isTruncated)
        assertTrue(buffer.retainedBytes <= 1000)
    }

    @Test
    fun `BoundedMessageBuffer under budget reports no truncation and no drops`() {
        val buffer = BoundedMessageBuffer(maxMessages = 50, maxTotalBytes = 10 * 1024 * 1024)

        repeat(10) { i -> buffer.add("msg-$i") }

        assertEquals(10, buffer.totalCount)
        assertEquals(10, buffer.messages.size)
        assertEquals(0, buffer.droppedCount)
        assertEquals(0L, buffer.droppedBytes)
        assertTrue(!buffer.isTruncated)
    }

    @Test
    fun `clear releases retained messages and resets retainedBytes but keeps totals and drop accounting`() {
        val buffer = BoundedMessageBuffer(maxMessages = 5, maxTotalBytes = 10 * 1024 * 1024)
        repeat(20) { i -> buffer.add("msg-$i") }
        assertEquals(5, buffer.messages.size)
        assertTrue(buffer.retainedBytes > 0)

        buffer.clear()

        assertTrue("clear() must release retained message strings", buffer.messages.isEmpty())
        assertEquals(0, buffer.retainedBytes)
        // Historical accounting (what actually happened over the call) is not part of what
        // clear() is for -- only the retained payload itself is released.
        assertEquals(20, buffer.totalCount)
        assertEquals(15, buffer.droppedCount)
    }

    @Test
    fun `GrpcCallCapture report() clears both buffers exactly once as terminal cleanup`() {
        val capture = GrpcCallCapture("test.Service/Stream", MethodDescriptor.MethodType.BIDI_STREAMING.name)
        repeat(200) { i ->
            capture.recordSent("sent-$i")
            capture.recordReceived("received-$i")
        }
        capture.status = Status.OK

        assertTrue(capture.sentBuffer.messages.isNotEmpty())
        assertTrue(capture.receivedBuffer.messages.isNotEmpty())

        capture.report(RequestrObserver)

        assertTrue("terminal cleanup must clear the sent buffer after reporting", capture.sentBuffer.messages.isEmpty())
        assertTrue("terminal cleanup must clear the received buffer after reporting", capture.receivedBuffer.messages.isEmpty())
        assertEquals(0, capture.sentBuffer.retainedBytes)
        assertEquals(0, capture.receivedBuffer.retainedBytes)

        // Calling report() again (defensive guard only -- onClose's own contract means this
        // should never happen in practice) must not throw or double-report.
        val before = RecordedGrpcStressEventCapture.countFor("test.Service/Stream")
        capture.report(RequestrObserver)
        assertEquals(before, RecordedGrpcStressEventCapture.countFor("test.Service/Stream"))
    }

    @Test
    fun `a long-lived bidi stream reports bounded retention and explicit drop metadata through the full interceptor`() {
        val method = stringMethodDescriptor("test.Service/BidiStream", MethodDescriptor.MethodType.BIDI_STREAMING)
        val fakeCall = FakeClientCall<String, String>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)
        wrappedCall.start(RecordingListener(), Metadata())

        // Simulates a stream that sends/receives thousands of small messages before closing --
        // the exact scenario an unbounded capture would blow memory on.
        repeat(3000) { i ->
            wrappedCall.sendMessage("req-$i")
            fakeCall.listener!!.onMessage("resp-$i")
        }
        fakeCall.listener!!.onClose(Status.OK, Metadata())

        val event = RecordedGrpcStressEventCapture.latestFor("test.Service/BidiStream")
        assertNotNull(event)
        event!!

        assertEquals("3000", event.headers["grpc-sent-message-count"])
        assertEquals("3000", event.headers["grpc-received-message-count"])
        assertEquals("true", event.headers["grpc-sent-truncated"])
        assertEquals("true", event.headers["grpc-received-truncated"])
        val sentRetained = event.headers["grpc-sent-messages-retained"]!!.toInt()
        val receivedRetained = event.headers["grpc-received-messages-retained"]!!.toInt()
        assertTrue("retained count must be bounded, got $sentRetained", sentRetained < 3000)
        assertTrue("retained count must be bounded, got $receivedRetained", receivedRetained < 3000)
        assertTrue(event.headers["grpc-sent-messages-dropped"]!!.toInt() > 0)
        assertTrue(event.headers["grpc-received-messages-dropped"]!!.toInt() > 0)
        assertTrue(event.headers["grpc-sent-bytes-dropped"]!!.toLong() > 0)
        assertTrue(event.headers["grpc-received-bytes-dropped"]!!.toLong() > 0)

        // The reported body itself must actually be bounded too -- not just the metadata about
        // it -- otherwise the drop accounting would be a lie.
        val requestBody = String(android.util.Base64.decode(event.requestBodyBase64, android.util.Base64.NO_WRAP))
        val responseBody = String(android.util.Base64.decode(event.responseBodyBase64, android.util.Base64.NO_WRAP))
        assertTrue(requestBody.length < 100_000)
        assertTrue(responseBody.length < 100_000)
    }

    @Test
    fun `cancel() after many messages on a still-bounded capture still reports exactly once with drop metadata`() {
        val method = stringMethodDescriptor("test.Service/CancelledStream", MethodDescriptor.MethodType.CLIENT_STREAMING)
        val fakeCall = FakeClientCall<String, String>()
        val channel = FakeChannel(fakeCall)
        val interceptor = RequestrGrpcInterceptor(RequestrObserver)

        val wrappedCall = interceptor.interceptCall(method, CallOptions.DEFAULT, channel)
        wrappedCall.start(RecordingListener(), Metadata())
        repeat(500) { i -> wrappedCall.sendMessage("chunk-$i") }
        wrappedCall.cancel("client gave up mid-stream", null)

        assertNull(
            "cancel() alone must not report before onClose",
            RecordedGrpcStressEventCapture.existsNowFor("test.Service/CancelledStream"),
        )

        fakeCall.listener!!.onClose(Status.CANCELLED.withDescription("client gave up mid-stream"), Metadata())

        val event = RecordedGrpcStressEventCapture.latestFor("test.Service/CancelledStream")
        assertNotNull(event)
        event!!
        assertEquals("true", event.headers["grpc-cancelled"])
        assertEquals("500", event.headers["grpc-sent-message-count"])
        assertEquals("true", event.headers["grpc-sent-truncated"])
        assertTrue(event.headers["grpc-sent-messages-dropped"]!!.toInt() > 0)
    }

    // -- fakes (mirrors GrpcInterceptorTest's) ---------------------------------------------------

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
 * Same reflection-into-`pendingEvents` technique as [GrpcInterceptorTest]'s
 * `RecordedGrpcEventCapture` -- kept as its own private object per this codebase's per-file
 * test-helper-duplication convention (see [OkHttpInterceptorTest]'s `RecordedEventCapture`).
 */
private object RecordedGrpcStressEventCapture {
    fun clear() {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(RequestrObserver) as MutableList<ObserverEvent>
        synchronized(list) { list.clear() }
    }

    fun existsNowFor(fullMethodName: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        return synchronized(field.get(RequestrObserver) as Any) {
            @Suppress("UNCHECKED_CAST")
            (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.url.endsWith(fullMethodName) }
        }
    }

    fun countFor(fullMethodName: String): Int {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        return synchronized(field.get(RequestrObserver) as Any) {
            @Suppress("UNCHECKED_CAST")
            (field.get(RequestrObserver) as List<ObserverEvent>).count { it.url.endsWith(fullMethodName) }
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
