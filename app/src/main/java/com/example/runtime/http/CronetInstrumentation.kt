package com.example.runtime.http

import android.content.Context
import com.example.data.interception.InterceptionStateRepository
import com.example.runtime.InstrumentationEvent
import org.chromium.net.CronetEngine
import org.chromium.net.Proxy
import org.chromium.net.ProxyOptions
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * W2: requestr-owned Cronet engine, a real [RequestFinishedInfo.Listener] that turns finished
 * requests into [InstrumentationEvent]s, and a body-capturing [UrlRequest.Callback] helper so
 * bodies are genuinely observed rather than only request/response metadata.
 *
 * Replaces the previous `Class.forName("org.chromium.net.CronetEngine")` presence check
 * (`HttpStackAdapters.kt`) -- this object builds and caches a real engine, attaches proxy
 * routing through the same MITM proxy [com.example.interception.OkHttpProxyInterceptor]
 * targets when interception is active, and queues real events for [CronetAdapter.pollEvents].
 */
object CronetInstrumentation {
    private const val MAX_QUEUED_EVENTS = 500
    private const val HTTP_CACHE_DISK = 1 // CronetEngine.Builder.HTTP_CACHE_DISK
    private const val HTTP_CACHE_MAX_BYTES = 2L * 1024 * 1024

    private val pendingEvents = ConcurrentLinkedQueue<InstrumentationEvent>()
    private val listenerExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cronet-finished-listener")
    }

    @Volatile private var engine: CronetEngine? = null
    @Volatile private var lastFailureReason: String? = null
    private var proxyConfigured = false

    private val finishedListener = object : RequestFinishedInfo.Listener(listenerExecutor) {
        override fun onRequestFinished(info: RequestFinishedInfo) {
            enqueue(mapRequestFinishedInfo(info))
        }
    }

    /** Builds (once) and returns the requestr-owned Cronet engine, or null if it cannot start. */
    fun engine(context: Context): CronetEngine? {
        engine?.let { return it }
        return runCatching {
            val port = InterceptionStateRepository.state.value.port
            val builder = CronetEngine.Builder(context.applicationContext)
                .enableQuic(true)
                .enableHttp2(true)
                .enableBrotli(true)
                .setStoragePath(context.applicationContext.cacheDir.resolve("cronet").apply { mkdirs() }.absolutePath)
                .enableHttpCache(HTTP_CACHE_DISK, HTTP_CACHE_MAX_BYTES)
            if (port > 0) {
                builder.setProxyOptions(buildProxyOptions(port))
                proxyConfigured = true
            }
            val built = builder.build()
            built.addRequestFinishedListener(finishedListener)
            engine = built
            lastFailureReason = null
            built
        }.onFailure { e ->
            lastFailureReason = e.message ?: e::class.simpleName ?: "unknown Cronet failure"
        }.getOrNull()
    }

    private fun buildProxyOptions(port: Int): ProxyOptions {
        // Cronet 143 deprecates the 4-arg Proxy constructor in favour of the one that takes the
        // Executor the Callback runs on; reuse the finished-listener thread so proxy-tunnel
        // callbacks are serialised with the rest of this object's Cronet event handling.
        val proxy = Proxy(Proxy.HTTP, "127.0.0.1", port, listenerExecutor, object : Proxy.Callback() {
            override fun onTunnelHeadersReceived(
                headers: MutableList<MutableMap.MutableEntry<String, String>>,
                statusCode: Int,
            ): Boolean = true
        })
        return ProxyOptions(listOf(proxy))
    }

    /** Human-readable status for the Settings card: real engine state, not a presence flag. */
    fun statusText(): String {
        val e = engine
        return when {
            e != null -> "engine ${e.versionString} · proxy=${if (proxyConfigured) "127.0.0.1" else "off"} · queued=${pendingEvents.size}"
            lastFailureReason != null -> "failed to start: $lastFailureReason"
            else -> "not started"
        }
    }

    fun isEngineReady(): Boolean = engine != null

    fun drainEvents(): List<InstrumentationEvent> {
        val out = mutableListOf<InstrumentationEvent>()
        while (true) {
            out.add(pendingEvents.poll() ?: break)
        }
        return out
    }

    fun stop() {
        engine?.let { runCatching { it.removeRequestFinishedListener(finishedListener) } }
    }

    private fun enqueue(event: InstrumentationEvent) {
        pendingEvents.add(event)
        while (pendingEvents.size > MAX_QUEUED_EVENTS) pendingEvents.poll()
    }

    /**
     * Executes a real request through the cached engine and returns the response body once
     * fully read, so API Testr can genuinely route a call through Cronet (see
     * `ApiTestrRepository`'s engine selector) rather than the adapter being a no-op with
     * nothing to observe.
     */
    fun executeBlocking(
        context: Context,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Long = 30_000,
    ): CronetCallResult {
        val cronet = engine(context) ?: return CronetCallResult(
            statusCode = null,
            headers = emptyMap(),
            body = null,
            errorMessage = lastFailureReason ?: "Cronet engine unavailable",
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        val responseBuffer = ByteArrayOutputStream()
        var statusCode: Int? = null
        var responseHeaders: Map<String, String> = emptyMap()
        var errorMessage: String? = null
        val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "cronet-call") }
        val readBuf = ByteBuffer.allocateDirect(32 * 1024)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                statusCode = info.httpStatusCode
                responseHeaders = flattenHeaders(info.allHeadersAsList)
                readBuf.clear()
                request.read(readBuf)
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                responseBuffer.write(bytes)
                readBuf.clear()
                request.read(readBuf)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                latch.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: org.chromium.net.CronetException) {
                errorMessage = error.message
                latch.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                errorMessage = "canceled"
                latch.countDown()
            }
        }

        val builder = cronet.newUrlRequestBuilder(url, callback, callbackExecutor)
            .setHttpMethod(method)
            .setRequestFinishedListener(finishedListener)
        for ((name, value) in headers) builder.addHeader(name, value)
        builder.addRequestAnnotation(method)
        val request = builder.build()
        request.start()
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return CronetCallResult(
            statusCode = statusCode,
            headers = responseHeaders,
            body = responseBuffer.toByteArray(),
            errorMessage = errorMessage,
        )
    }

    /** Extracted as a pure function so it's testable without a device or a real Cronet provider. */
    internal fun mapRequestFinishedInfo(info: RequestFinishedInfo): InstrumentationEvent {
        val metrics = info.metrics
        val response = info.responseInfo
        val failed = info.finishedReason == RequestFinishedInfo.FAILED
        return InstrumentationEvent(
            adapter = "Cronet",
            eventType = if (failed) "exception" else "response_ready",
            method = info.annotations.filterIsInstance<String>().firstOrNull(),
            url = info.url,
            headers = response?.allHeadersAsList?.let { flattenHeaders(it) },
            statusCode = response?.httpStatusCode,
            errorMessage = info.exception?.message,
            protocol = response?.negotiatedProtocol,
            requestSizeBytes = metrics?.sentByteCount ?: 0L,
            responseSizeBytes = metrics?.receivedByteCount ?: 0L,
            latencyMs = metrics?.totalTimeMs ?: 0L,
            host = runCatching { java.net.URI(info.url).host }.getOrNull(),
        )
    }

    /** Extracted pure mapping, testable with plain values (see CronetEventMappingTest). */
    internal fun mapEvent(
        url: String,
        method: String?,
        statusCode: Int?,
        headerList: List<Pair<String, String>>,
        sentBytes: Long,
        receivedBytes: Long,
        totalTimeMs: Long,
        negotiatedProtocol: String?,
        exceptionMessage: String?,
    ): InstrumentationEvent {
        return InstrumentationEvent(
            adapter = "Cronet",
            eventType = if (exceptionMessage != null) "exception" else "response_ready",
            method = method,
            url = url,
            headers = flattenPairs(headerList),
            statusCode = statusCode,
            errorMessage = exceptionMessage,
            protocol = negotiatedProtocol,
            requestSizeBytes = sentBytes,
            responseSizeBytes = receivedBytes,
            latencyMs = totalTimeMs,
            host = runCatching { java.net.URI(url).host }.getOrNull(),
        )
    }

    private fun flattenHeaders(list: List<Map.Entry<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in list) out[entry.key] = entry.value
        return out
    }

    private fun flattenPairs(list: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in list) out[k] = v
        return out
    }
}

data class CronetCallResult(
    val statusCode: Int?,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val errorMessage: String?,
)
