package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
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
 * Task 11: [RequestrSdk] is the one-line facade over [RequestrObserver], [HttpUrlConnectionWrapper],
 * and [KeyLogProducer]. These tests exercise the facade's own decision logic (init-guarding,
 * per-flag builder pass-through) without re-testing what [OkHttpInterceptorTest] /
 * [HttpUrlConnectionWrapperTest] / [KeyLogProducerTest] already cover for the underlying pieces.
 *
 * `captureHttpUrlConnection = false` in every [RequestrSdk.Options] below is deliberate: this
 * class shares a test JVM with [HttpUrlConnectionWrapperTest] (both in the `:sdk` module's
 * `debugUnitTest` source set), and [java.net.URL.setURLStreamHandlerFactory] can only succeed
 * once per JVM (see that test's kdoc) -- exercising it again here would be redundant with that
 * class's coverage and order-dependent across test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RequestrSdkTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetSdkState() {
        RequestrSdk.resetForTests()
    }

    @After
    fun tearDown() {
        RequestrSdk.resetForTests()
    }

    @Test
    fun `not initialized by default`() {
        assertFalse(RequestrSdk.isInitialized())
    }

    @Test
    fun `init marks the SDK initialized`() {
        RequestrSdk.init(
            context,
            pairingToken = "test-token",
            options = RequestrSdk.Options(
                captureOkHttp = true,
                captureHttpUrlConnection = false,
                captureCronet = false,
                captureKtor = false,
                enableKeyLog = false,
            ),
        )
        assertTrue(RequestrSdk.isInitialized())
    }

    @Test
    fun `second init call is ignored, first options win`() {
        RequestrSdk.init(
            context,
            pairingToken = "first-token",
            options = RequestrSdk.Options(captureOkHttp = true, captureHttpUrlConnection = false, enableKeyLog = false),
        )
        RequestrSdk.init(
            context,
            pairingToken = "second-token",
            options = RequestrSdk.Options(captureOkHttp = false, captureHttpUrlConnection = false, enableKeyLog = false),
        )

        // captureOkHttp stayed true (the first call's options) -- okHttpBuilder still instruments.
        val client = RequestrSdk.okHttpBuilder(OkHttpClient.Builder()).build()
        assertEquals(
            "expected okHttpBuilder to still add the interceptor from the FIRST init() call's options",
            1,
            client.interceptors.size,
        )
    }

    @Test
    fun `okHttpBuilder returns the same builder unmodified before init`() {
        val builder = OkHttpClient.Builder()
        val result = RequestrSdk.okHttpBuilder(builder)
        assertSame(builder, result)
    }

    @Test
    fun `okHttpBuilder adds interceptor and event listener when captureOkHttp is true`() {
        RequestrSdk.init(
            context,
            pairingToken = "test-token",
            options = RequestrSdk.Options(captureOkHttp = true, captureHttpUrlConnection = false, enableKeyLog = false),
        )
        val client = RequestrSdk.okHttpBuilder(OkHttpClient.Builder()).build()
        assertEquals(1, client.interceptors.size)
        assertTrue(client.interceptors.first() is RequestrOkHttpInterceptor)
    }

    @Test
    fun `okHttpBuilder returns builder unmodified when captureOkHttp is false`() {
        RequestrSdk.init(
            context,
            pairingToken = "test-token",
            options = RequestrSdk.Options(captureOkHttp = false, captureHttpUrlConnection = false, enableKeyLog = false),
        )
        val builder = OkHttpClient.Builder()
        val result = RequestrSdk.okHttpBuilder(builder)
        assertSame(builder, result)
    }
}
