package com.example.runtime.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.data.interception.InterceptionStateRepository
import com.example.interception.OkHttpProxyInterceptor
import com.example.runtime.InstrumentationEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * W3: requestr-owned offscreen [WebView], driven by the Settings "RUNTIME INSTRUMENTATION" card
 * URL field (DEC-2 -- there is no full in-app inspector screen; this object's public surface
 * stays free of any Settings-specific assumption so it can be reused elsewhere later).
 *
 * Replaces the previous `Class.forName("android.webkit.WebView")` presence-only shell with two
 * genuine capture paths:
 *  1. [WebViewClient.shouldInterceptRequest] -- every resource load (main document, subresources,
 *     same-document XHR/fetch that the engine issues natively) is re-executed through an OkHttp
 *     client routed at the MITM proxy (mirrors [OkHttpProxyInterceptor]'s trust setup) so the
 *     event carries real status/headers/body bytes/latency, and the fetched bytes are handed back
 *     to the WebView so the page still renders.
 *  2. A MAIN-world JS hook (`assets/webview_hook.js`, installed via
 *     [WebViewCompat.addDocumentStartJavaScript]) posts request/response pairs — including bodies
 *     `shouldInterceptRequest` cannot always reconstruct (e.g. same-origin `fetch` responses
 *     already consumed by page JS) — back over a [WebViewCompat.addWebMessageListener] bridge
 *     named `requestrBridge`.
 *
 * Hard constraint: [WebView] must be constructed and driven on the main thread. The engine's poll
 * loop runs on `Dispatchers.Default` (see `RuntimeInstrumentationEngine`), so every call here that
 * touches the [WebView] instance is posted to [mainHandler] instead of executing on the caller's
 * thread.
 */
object WebViewInstrumentation {
    private const val TAG = "WebViewInstrumentation"
    private const val MAX_QUEUED_EVENTS = 500
    private const val MAX_RECENT_CAPTURES = 5

    /** Which of the two real capture paths produced an event (see [CapturedSummary.source]). */
    const val SOURCE_INTERCEPT = "shouldInterceptRequest"
    const val SOURCE_BRIDGE = "MAIN-world bridge"
    private const val BRIDGE_NAME = "requestrBridge"
    private const val HOOK_ASSET_PATH = "webview_hook.js"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingEvents = ConcurrentLinkedQueue<InstrumentationEvent>()

    @Volatile private var webView: WebView? = null
    @Volatile private var scriptHandle: androidx.webkit.ScriptHandler? = null
    @Volatile private var lastFailureReason: String? = null
    @Volatile private var proxyOverrideActive = false
    @Volatile private var appContext: Context? = null

    private val _cookies = MutableStateFlow<List<String>>(emptyList())
    val cookies: StateFlow<List<String>> = _cookies.asStateFlow()

    /**
     * v6.1 Domain B: one request this offscreen WebView genuinely captured, retained only so the
     * Settings card's "RECENT CAPTURES" peek can show real rows. Bounded to [MAX_RECENT_CAPTURES];
     * the authoritative event stream is still the drained [InstrumentationEvent] queue.
     */
    data class CapturedSummary(
        val method: String?,
        val url: String?,
        val statusCode: Int?,
        /** Which capture path produced it: `shouldInterceptRequest` or the MAIN-world JS bridge. */
        val source: String,
        val atMs: Long,
    )

    /**
     * Live capture-session telemetry for the Settings "OFFSCREEN WEBVIEW CAPTURE" control. Every
     * field is measured: [requestedUrl]/[startedAtMs] are set by the real [loadUrl] call the user
     * triggered, and [requestCount]/[responseBytes] accumulate from actually-captured events. A
     * null [requestedUrl] honestly means no capture session has been started in this process.
     */
    data class CaptureStatus(
        val requestedUrl: String? = null,
        val startedAtMs: Long? = null,
        val requestCount: Int = 0,
        val responseBytes: Long = 0,
        val recentCaptures: List<CapturedSummary> = emptyList(),
    )

    private val _captureStatus = MutableStateFlow(CaptureStatus())
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus.asStateFlow()

    // Reused across every shouldInterceptRequest call and the WebView's own proxy override --
    // built once, lazily, once we know a real proxy port (or lack of one).
    private val interceptClient: OkHttpClient by lazy { buildInterceptClient() }

    /** Starts the offscreen WebView on the main thread. Idempotent -- safe to call repeatedly. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (webView != null) return
        mainHandler.post { startOnMainThread(context.applicationContext) }
    }

    /** Loads a URL into the offscreen WebView (must already be started). Posts to main thread. */
    fun loadUrl(url: String) {
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                // No live WebView means no capture session exists: publishing one anyway made the
                // Settings control report "Capturing" for a page nothing was loading.
                lastFailureReason = "WebView not started — cannot load $url"
                Log.w(TAG, "loadUrl($url) ignored: no offscreen WebView is running")
                return@post
            }
            // A new URL is a new capture session: real counters restart from zero rather than carrying
            // the previous page's totals forward under a new URL label.
            _captureStatus.value = CaptureStatus(requestedUrl = url, startedAtMs = System.currentTimeMillis())
            runCatching { wv.loadUrl(url) }
                .onFailure { lastFailureReason = it.message ?: "loadUrl failed" }
        }
    }

    /**
     * Ends the current capture session without tearing down the WebView itself: the adapter
     * ([com.example.runtime.http.WebViewAdapter]) owns the instance's lifecycle via [start]/[stop],
     * so the Settings "Stop" control navigates away and clears session telemetry instead of
     * destroying an engine-owned object behind the engine's back.
     */
    fun stopCapture() {
        _captureStatus.value = CaptureStatus()
        mainHandler.post {
            runCatching { webView?.stopLoading() }
            runCatching { webView?.loadUrl("about:blank") }
        }
    }

    fun isActive(): Boolean = webView != null

    fun stop() {
        _captureStatus.value = CaptureStatus()
        mainHandler.post {
            runCatching { scriptHandle?.remove() }
            runCatching { webView?.stopLoading() }
            runCatching { webView?.destroy() }
            webView = null
            scriptHandle = null
            if (proxyOverrideActive && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                runCatching {
                    ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
                }
                proxyOverrideActive = false
            }
        }
    }

    fun drainEvents(): List<InstrumentationEvent> {
        val out = mutableListOf<InstrumentationEvent>()
        while (true) out.add(pendingEvents.poll() ?: break)
        return out
    }

    /** Human-readable status for the Settings card: real WebView package + feature support. */
    fun statusText(): String {
        val wv = webView
        if (wv == null) {
            return lastFailureReason?.let { "failed to start: $it" } ?: "not started"
        }
        val pkg = runCatching {
            WebViewCompat.getCurrentWebViewPackage(wv.context)?.let { "${it.packageName} ${it.versionName}" }
        }.getOrNull() ?: "unknown package"
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val msgListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        return "loaded via $pkg · bridge=${if (docStart && msgListener) "ready" else "unsupported"} " +
            "· proxy=${if (proxyOverrideActive) "override" else "off"} · queued=${pendingEvents.size}"
    }

    private fun startOnMainThread(context: Context) {
        runCatching {
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true

            installProxyOverrideIfPossible(context)
            installJsHook(context, wv)
            installBridgeListener(wv)

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = interceptRequest(request)
            }
            webView = wv
            lastFailureReason = null
        }.onFailure { e ->
            lastFailureReason = e.message ?: e::class.simpleName ?: "unknown WebView failure"
            Log.e(TAG, "Failed to start WebView instrumentation", e)
        }
    }

    private fun installProxyOverrideIfPossible(context: Context) {
        val port = InterceptionStateRepository.state.value.port
        if (port <= 0) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                .removeImplicitRules()
                .bypassSimpleHostnames()
                .build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }, {
                proxyOverrideActive = true
            })
        }.onFailure { proxyOverrideActive = false }
    }

    private fun installJsHook(context: Context, wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = runCatching {
            context.assets.open(HOOK_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        scriptHandle = WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
    }

    private fun installBridgeListener(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                wv,
                BRIDGE_NAME,
                setOf("*"),
            ) { _, message, _, _, _ ->
                message.data?.let { onBridgeMessage(it) }
            }
        }
    }

    /** Parses a `webview_hook.js` payload into an [InstrumentationEvent]. Never throws. */
    internal fun onBridgeMessage(raw: String) {
        val event = parseBridgePayload(raw) ?: return
        enqueue(event, SOURCE_BRIDGE)
        // Cookies for the captured URL, if we can resolve one -- surfaced to the Settings card.
        event.url?.let { url ->
            runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.let { _cookies.value = it }
        }
    }

    /** Pure JSON->event mapping, exercised directly by WebViewCaptureMappingTest. */
    internal fun parseBridgePayload(raw: String): InstrumentationEvent? {
        return runCatching {
            val json = JSONObject(raw)
            val requestHeaders = mutableMapOf<String, String>()
            json.optJSONObject("requestHeaders")?.let { headers ->
                for (key in headers.keys()) requestHeaders[key] = headers.optString(key)
            }
            val statusCode = if (json.has("status") && !json.isNull("status")) json.optInt("status") else null
            val error = json.optString("error", "").ifBlank { null }
            InstrumentationEvent(
                adapter = "WebView",
                eventType = if (error != null) "exception" else "response_ready",
                method = json.optString("method", "GET").ifBlank { "GET" },
                url = json.optString("url", "").ifBlank { null },
                headers = requestHeaders,
                statusCode = statusCode,
                errorMessage = error,
                requestBody = json.optString("requestBody", "").ifBlank { null },
                responseBody = json.optString("responseBody", "").ifBlank { null },
                latencyMs = json.optLong("durationMs", 0),
                host = json.optString("url", "").ifBlank { null }?.let { u ->
                    runCatching { java.net.URI(u).host }.getOrNull()
                },
            )
        }.onFailure { Log.w(TAG, "Malformed bridge payload: ${it.message}") }.getOrNull()
    }

    /**
     * Re-executes a `shouldInterceptRequest` load through OkHttp (proxy-aware, same trust setup
     * as [OkHttpProxyInterceptor]) so the response is genuinely observed. Runs synchronously on
     * WebView's own resource-loading thread, which is explicitly NOT the main thread -- this is
     * one of the few call sites here allowed to block, since WebView expects a blocking answer.
     */
    private fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val builder = Request.Builder().url(url).method(
                request.method ?: "GET",
                null,
            )
            for ((name, value) in request.requestHeaders) builder.header(name, value)
            val call = interceptClient.newCall(builder.build())
            val response = call.execute()
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)
            val mimeType = response.header("Content-Type")?.substringBefore(";")
            val encoding = response.header("Content-Type")?.substringAfter("charset=", "utf-8")
            val headerMap = mutableMapOf<String, String>()
            for (name in response.headers.names()) response.header(name)?.let { headerMap[name] = it }

            enqueue(
                InstrumentationEvent(
                    adapter = "WebView",
                    eventType = "response_ready",
                    method = request.method,
                    url = url,
                    headers = headerMap,
                    statusCode = response.code,
                    responseSizeBytes = bodyBytes.size.toLong(),
                    latencyMs = System.currentTimeMillis() - startedAt,
                    protocol = response.protocol.toString(),
                    host = request.url.host,
                ),
                SOURCE_INTERCEPT,
            )
            WebResourceResponse(
                mimeType,
                encoding,
                response.code,
                response.message.ifBlank { "OK" },
                headerMap,
                bodyBytes.inputStream(),
            )
        }.onFailure { e ->
            enqueue(
                InstrumentationEvent(
                    adapter = "WebView",
                    eventType = "exception",
                    method = request.method,
                    url = url,
                    errorMessage = e.message,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    host = request.url.host,
                ),
                SOURCE_INTERCEPT,
            )
        }.getOrNull()
    }

    private fun buildInterceptClient(): OkHttpClient {
        val port = InterceptionStateRepository.state.value.port
        val builder = OkHttpClient.Builder()
        if (port > 0) {
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(AllTrustManager()), SecureRandom())
            builder
                .proxy(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)))
                .sslSocketFactory(ssl.socketFactory, AllTrustManager())
        }
        return builder.build()
    }

    private fun enqueue(event: InstrumentationEvent, source: String) {
        pendingEvents.add(event)
        while (pendingEvents.size > MAX_QUEUED_EVENTS) pendingEvents.poll()
        recordCaptureTelemetry(event, source)
    }

    /**
     * Accumulates the real per-session counters the Settings card renders. Kept separate from
     * [pendingEvents] because that queue is *drained* by the engine's poll loop, so it cannot double
     * as "what has this capture session seen so far".
     */
    private fun recordCaptureTelemetry(event: InstrumentationEvent, source: String) {
        val summary = CapturedSummary(
            method = event.method,
            url = event.url,
            statusCode = event.statusCode,
            source = source,
            atMs = event.timestamp,
        )
        val current = _captureStatus.value
        _captureStatus.value = current.copy(
            requestCount = current.requestCount + 1,
            responseBytes = current.responseBytes + event.responseSizeBytes,
            recentCaptures = (listOf(summary) + current.recentCaptures).take(MAX_RECENT_CAPTURES),
        )
    }

    private class AllTrustManager : X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }
}
