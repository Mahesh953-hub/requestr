package com.requestr.sdk

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.ICronetEngineBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

/**
 * Verifies [addRequestrInterceptor]'s `ssl_key_log_file` experimental-option wiring without
 * needing a real Cronet native engine (which isn't available in a JVM unit test -- Cronet ships
 * a native `.so` that only loads on-device/emulator). Cronet's own builder API is deliberately
 * structured for this: [ExperimentalCronetEngine.Builder] is a thin wrapper that forwards every
 * call straight to an injectable [ICronetEngineBuilder] delegate (confirmed by decompiling the
 * real `cronet-api` AAR classes: `setExperimentalOptions` on the `Builder` does nothing but
 * `mBuilderDelegate.setExperimentalOptions(...)`). Constructing `ExperimentalCronetEngine.Builder`
 * with a hand-rolled [ICronetEngineBuilder] fake lets this test capture exactly what JSON options
 * string Requestr would have handed to the real native engine, with no native code involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CronetInterceptorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class RecordingCronetEngineBuilderDelegate : ICronetEngineBuilder() {
        var lastExperimentalOptions: String? = null

        override fun setExperimentalOptions(options: String?): ICronetEngineBuilder {
            lastExperimentalOptions = options
            return this
        }

        override fun addPublicKeyPins(
            hostName: String?,
            pinsSha256: MutableSet<ByteArray>?,
            includeSubdomains: Boolean,
            expirationDate: Date?,
        ): ICronetEngineBuilder = this

        override fun addQuicHint(host: String?, port: Int, alternatePort: Int): ICronetEngineBuilder = this
        override fun enableHttp2(value: Boolean): ICronetEngineBuilder = this
        override fun enableHttpCache(cacheMode: Int, maxSize: Long): ICronetEngineBuilder = this
        override fun enablePublicKeyPinningBypassForLocalTrustAnchors(value: Boolean): ICronetEngineBuilder = this
        override fun enableQuic(value: Boolean): ICronetEngineBuilder = this
        override fun enableSdch(value: Boolean): ICronetEngineBuilder = this
        override fun setLibraryLoader(loader: CronetEngine.Builder.LibraryLoader?): ICronetEngineBuilder = this
        override fun setStoragePath(value: String?): ICronetEngineBuilder = this
        override fun setUserAgent(userAgent: String?): ICronetEngineBuilder = this
        override fun getDefaultUserAgent(): String = "requestr-test-agent"
        override fun build(): ExperimentalCronetEngine =
            throw UnsupportedOperationException("not needed for this test -- no native engine in a JVM unit test")
    }

    @Test
    fun `addRequestrInterceptor writes an ssl_key_log_file experimental option pointing at the app's files dir`() {
        RequestrObserver.init(context, pairingToken = "unused", apiBaseUrl = "http://127.0.0.1:1")
        val delegate = RecordingCronetEngineBuilderDelegate()
        val builder = ExperimentalCronetEngine.Builder(delegate)

        builder.addRequestrInterceptor(context, RequestrObserver)

        val optionsJson = delegate.lastExperimentalOptions
        assertTrue("setExperimentalOptions must be called with a non-null JSON string", optionsJson != null)
        val parsed = JSONObject(optionsJson!!)
        assertTrue(parsed.has("ssl_key_log_file"))

        val expectedPath = File(context.filesDir, CronetInterceptor.KEYLOG_FILE_NAME).absolutePath
        assertEquals(expectedPath, parsed.getString("ssl_key_log_file"))
    }

    @Test
    fun `addRequestrInterceptor's keylog path lives under the app's own files directory, not a shared or external location`() {
        RequestrObserver.init(context, pairingToken = "unused", apiBaseUrl = "http://127.0.0.1:1")
        val delegate = RecordingCronetEngineBuilderDelegate()
        val builder = ExperimentalCronetEngine.Builder(delegate)

        builder.addRequestrInterceptor(context, RequestrObserver)

        val optionsJson = JSONObject(delegate.lastExperimentalOptions!!)
        val keylogPath = optionsJson.getString("ssl_key_log_file")
        assertTrue(
            "keylog file must be inside the app's private files dir: $keylogPath",
            keylogPath.startsWith(context.filesDir.absolutePath),
        )
        assertTrue(keylogPath.endsWith(CronetInterceptor.KEYLOG_FILE_NAME))
    }
}
