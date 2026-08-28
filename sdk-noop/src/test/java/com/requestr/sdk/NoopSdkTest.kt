package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Confirms this module's whole reason for existing: every capture entry point is callable
 * (source-compatible with `:sdk`'s equivalents) but genuinely does nothing -- no batching, no
 * network calls, no wrapping. See `sdk-noop/README.md` for the full contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class NoopSdkTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `RequestrObserver observe and observeDetailed never contact the local API`() {
        server.enqueue(MockResponse().setResponseCode(200))
        RequestrObserver.init(context, pairingToken = "token", apiBaseUrl = server.url("/").toString().trimEnd('/'))

        RequestrObserver.observe(url = "https://example.com/a")
        RequestrObserver.observeDetailed(url = "https://example.com/b", requestBody = "x".toByteArray())
        RequestrObserver.flush()

        // Give any (hypothetical) background work a moment to run, then assert the request
        // never actually happened -- MockWebServer.requestCount stays exactly 0.
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
        assertTrue(RequestrObserver.lastFlushStatus.value is FlushStatus.Idle)
        assertFalse(RequestrObserver.isApiReachable())
    }

    @Test
    fun `RequestrSdk okHttpBuilder returns the exact same builder instance unmodified`() {
        RequestrSdk.init(context, pairingToken = "token")
        val builder = OkHttpClient.Builder()

        val result = RequestrSdk.okHttpBuilder(builder)

        assertSame(builder, result)
        assertTrue(RequestrSdk.isInitialized())
    }

    @Test
    fun `addRequestrInterceptor-wired OkHttpClient makes an unobserved but otherwise normal request`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val client = OkHttpClient.Builder().addRequestrInterceptor(RequestrObserver).build()

        val request = okhttp3.Request.Builder().url(server.url("/ping")).build()
        client.newCall(request).execute().use { response ->
            assertEquals(204, response.code)
        }
        // The request itself still went out over the wire (this decorator never breaks the
        // host app's own networking) -- it's only requestr's own observation that's absent.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `HttpUrlConnectionWrapper install never installs a URLStreamHandlerFactory`() {
        val installed = HttpUrlConnectionWrapper.install(RequestrObserver)
        assertFalse(installed)
    }
}
