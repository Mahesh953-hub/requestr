package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Robolectric-backed (RequestrObserver.observeDetailed touches android.util.Base64 and schedules
 * a coroutine through android.content.Context, both unavailable on a plain JVM test) end-to-end
 * test of [addRequestrInterceptor] against a real local HTTP server: builds an OkHttpClient with
 * the interceptor installed, makes a real request, and inspects the base64-decoded bodies that
 * ended up queued for [RequestrObserver.flush] to confirm the interceptor actually captured
 * request/response bodies, headers, and status -- not just that it compiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class OkHttpInterceptorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // No pairing token needed for this test -- flush()'s outbound POST to a nonexistent
        // /api/observe server will fail (there's no LocalApiServer running here) and re-queue,
        // which is fine: the assertions below inspect pendingEvents captured via observe() calls
        // routed through the interceptor, not the flush outcome.
        RequestrObserver.init(context, pairingToken = "unused-in-this-test", apiBaseUrl = "http://127.0.0.1:1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `interceptor captures request body, response body, headers, and status`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"created":true}""")
        )

        client = OkHttpClient.Builder().addRequestrInterceptor(RequestrObserver).build()

        val request = Request.Builder()
            .url(server.url("/v1/things"))
            .post("""{"name":"widget"}""".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(201, response.code)
        }

        val event = RecordedEventCapture.latestFor("/v1/things")
        assertNotNull("expected an observed event for /v1/things", event)
        assertEquals(201, event!!.statusCode)
        assertEquals("POST", event.method)
        assertEquals("OkHttp", event.httpStack)
        assertNotNull(event.requestBodyBase64)
        assertEquals("""{"name":"widget"}""", String(Base64.getDecoder().decode(event.requestBodyBase64)))
        assertNotNull(event.responseBodyBase64)
        assertEquals("""{"created":true}""", String(Base64.getDecoder().decode(event.responseBodyBase64)))
    }

    @Test
    fun `interceptor still reports an event when the server errors`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))

        client = OkHttpClient.Builder().addRequestrInterceptor(RequestrObserver).build()

        val request = Request.Builder().url(server.url("/fail")).get().build()
        client.newCall(request).execute().use { response ->
            assertEquals(500, response.code)
        }

        val event = RecordedEventCapture.latestFor("/fail")
        assertNotNull(event)
        assertEquals(500, event!!.statusCode)
    }
}

/**
 * [RequestrObserver.observeDetailed] enqueues into a private `pendingEvents` list and immediately
 * calls [RequestrObserver.flush], which -- on failure to reach the (nonexistent, in this test)
 * API server -- re-queues the same events back into `pendingEvents`. There's no public accessor
 * for that list, so this helper polls the only public surface available
 * ([RequestrObserver.flush] being fire-and-forget) is insufficient; instead the test relies on
 * [RequestrObserver]'s events staying queued after a failed flush and inspects them via
 * reflection -- kept in one small helper so the test bodies above stay readable.
 */
private object RecordedEventCapture {
    fun latestFor(urlSuffix: String): ObserverEvent? {
        val field = RequestrObserver::class.java.getDeclaredField("pendingEvents")
        field.isAccessible = true
        // flush() re-queues on a background coroutine after a failed POST (there's no real
        // LocalApiServer listening on the bogus apiBaseUrl this test uses) -- poll briefly
        // rather than racing it.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(field.get(RequestrObserver) as Any) {
                @Suppress("UNCHECKED_CAST")
                (field.get(RequestrObserver) as List<ObserverEvent>).lastOrNull { it.url.endsWith(urlSuffix) }
            }
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }
}
