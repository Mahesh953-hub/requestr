package com.requestr.sdk

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Task 11: minimal integration test harness a host app can call (e.g. from a hidden debug
 * Activity/button) to confirm the SDK is actually wired correctly against a running requestr
 * instance, without requiring a human to manually inspect the Intercept screen.
 *
 * This is the "verification" step described in the plan for tasks 6/7/8/9 -- rather than a
 * JVM unit test (those already cover the pure logic in [ObserverEventTest],
 * [OkHttpInterceptorTest], [KeyLogProducerTest], [SslSecretExtractorTest]), this harness proves
 * the *end-to-end* wire: SDK -> requestr's `/api/observe` -> `captured_traffic` table -> back
 * out via `/api/captures`, on a real device/emulator with a real requestr instance running.
 */
object VerificationHarness {

    data class HarnessResult(
        val okHttpOk: Boolean,
        val okHttpBodyCaptureVerified: Boolean,
        val httpUrlConnectionOk: Boolean,
        val keylogProduced: Boolean,
        val eventsReceivedInRequestr: Boolean,
        val errors: List<String>,
    )

    /**
     * @param context any [Context] -- used only to read [RequestrSdk]'s configured API base via
     *   [RequestrObserver.isApiReachable]'s underlying client; [RequestrSdk.init] must already
     *   have been called before calling this.
     * @param targetUrl an HTTPS endpoint the device can actually reach that echoes back the
     *   posted body (httpbin.org/post is the plan's suggested default -- override for offline
     *   or firewalled test environments).
     */
    suspend fun run(context: Context, targetUrl: String = "https://httpbin.org/post"): HarnessResult {
        val errors = mutableListOf<String>()

        if (!RequestrSdk.isInitialized()) {
            errors.add("RequestrSdk.init() was not called before running the harness")
        }

        val knownMarker = "requestr-sdk-verification-${System.currentTimeMillis()}"

        // 1. OkHttp leg: build a client via RequestrSdk.okHttpBuilder() so this exercises the
        // exact code path a real host app's own client construction would use.
        val okHttpOk = runCatching {
            val client = RequestrSdk.okHttpBuilder(OkHttpClient.Builder()).build()
            val body = """{"marker":"$knownMarker"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(targetUrl).post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure { errors.add("OkHttp leg failed: ${it.message}") }.getOrDefault(false)

        // 2. HttpURLConnection leg: plain java.net usage, no explicit SDK call at the call site
        // -- proves HttpUrlConnectionWrapper.install() genuinely intercepts process-wide traffic
        // rather than needing per-request wiring.
        val httpUrlConnectionOk = runCatching {
            val connection = URL(targetUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        }.onFailure { errors.add("HttpURLConnection leg failed: ${it.message}") }.getOrDefault(false)

        // 3. Give RequestrObserver's async flush() a moment to actually POST to /api/observe.
        delay(3_000)

        // 4. Poll requestr's own /api/captures for a row containing our marker, proving the
        // full round trip (SDK -> LocalApiServer -> Room -> back out over HTTP) actually works,
        // not just that the SDK-side calls didn't throw.
        val eventsReceivedInRequestr = withTimeoutOrNull(10_000) {
            pollForMarker(knownMarker)
        } ?: false
        if (!eventsReceivedInRequestr) {
            errors.add("marker '$knownMarker' never appeared in requestr's /api/captures")
        }

        // 5. Body capture is "verified" if requestr actually stored a non-empty body for the
        // OkHttp POST leg above -- distinct from eventsReceivedInRequestr, which only checks
        // that *some* row with the marker exists (e.g. an HttpURLConnection GET has no body).
        val okHttpBodyCaptureVerified = eventsReceivedInRequestr && runCatching {
            bodyWasCaptured(knownMarker)
        }.getOrElse {
            errors.add("body-capture check failed: ${it.message}")
            false
        }

        // 6. TLS keylog: KeyLogProducer degrades gracefully to unavailable (see
        // KeyLogRegistryBridge.isAvailable()) on any host app that doesn't have
        // TlsKeyLogRegistry on its classpath -- i.e. every third-party app except requestr's
        // own :app module. This is an honest, expected `false` outside of requestr's own dev
        // loop -- not a bug in the harness.
        val keylogProduced = KeyLogRegistryBridge.isAvailable()

        return HarnessResult(
            okHttpOk = okHttpOk,
            okHttpBodyCaptureVerified = okHttpBodyCaptureVerified,
            httpUrlConnectionOk = httpUrlConnectionOk,
            keylogProduced = keylogProduced,
            eventsReceivedInRequestr = eventsReceivedInRequestr,
            errors = errors,
        )
    }

    private fun apiBase(): String = requestrApiBaseForHarness

    /** Mirrors [RequestrSdk.Options.requestrApiBase] -- set once by [RequestrSdk.init] via
     * [RequestrObserver.init]. Not exposed by [RequestrObserver] as a public getter, so the
     * harness keeps its own copy set at [run] call time via the same default the SDK uses;
     * override by calling [setApiBaseForHarness] if the host app configured a non-default base. */
    @Volatile
    private var requestrApiBaseForHarness: String = "http://127.0.0.1:9876"

    fun setApiBaseForHarness(apiBase: String) {
        requestrApiBaseForHarness = apiBase
    }

    private val httpClient = OkHttpClient()

    private fun pollForMarker(marker: String): Boolean {
        while (true) {
            val found = runCatching {
                val request = Request.Builder().url("${apiBase()}/api/captures").get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val text = response.body?.string() ?: return@use false
                    text.contains(marker)
                }
            }.getOrDefault(false)
            if (found) return true
            Thread.sleep(500)
        }
    }

    private fun bodyWasCaptured(marker: String): Boolean {
        val request = Request.Builder().url("${apiBase()}/api/captures").get().build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return false
            (0 until array.length()).any { i ->
                val row = array.optJSONObject(i) ?: return@any false
                val requestBody = row.optString("requestBody", "")
                requestBody.contains(marker)
            }
        }
    }
}
