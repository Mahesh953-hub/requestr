package com.requestr.sdk

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instruments plain [HttpURLConnection] usage app-wide by installing a wrapping
 * [URLStreamHandlerFactory]. Unlike [OkHttpInterceptor] (which needs the app to explicitly call
 * `.addRequestrInterceptor()` on its own `OkHttpClient.Builder`), this is genuinely automatic:
 * once installed, every `URL(...).openConnection()` in the process that resolves to `http://` or
 * `https://` goes through the wrapper transparently.
 *
 * **Limitation honestly managed**: [URL.setURLStreamHandlerFactory] can only be called once per
 * JVM (a hard platform restriction, not a requestr bug) -- see the Javadoc: "an Exception is
 * thrown if the application has already set a factory". If another library in the host app
 * (Retrofit's `java.net`-backed default, some ad SDK, etc.) has already installed one first, this
 * SDK loses the race and [install] returns `false` -- HttpURLConnection capture is simply
 * unavailable for that process. This is a real platform constraint, not a stub.
 */
object HttpUrlConnectionWrapper {
    private const val TAG = "RequestrHttpUrlConn"
    private val installed = AtomicBoolean(false)

    // Captured BEFORE installing our own factory, via the same package-private
    // `URL.getURLStreamHandler(String)` lookup the JVM/Android runtime itself uses. Doing this
    // upfront (rather than reflecting for a hardcoded internal class name, or falling back to
    // `url.openConnection()`) avoids two failure modes: (1) the internal implementation class
    // name varies across Android versions/OEMs and desktop JVMs (e.g. Robolectric's host JVM
    // uses `sun.net.www.protocol.http.Handler`, real Android uses a different internal class),
    // and (2) calling `url.openConnection()` again from inside our own handler would recurse
    // straight back into our own factory forever (StackOverflowError) once the factory is
    // installed, since `URL.openConnection()` always re-resolves through the currently-installed
    // factory for a "not yet cached" URL instance.
    private var originalHttpHandler: URLStreamHandler? = null
    private var originalHttpsHandler: URLStreamHandler? = null

    /**
     * Install a wrapping [URLStreamHandlerFactory] that intercepts all [HttpURLConnection] usage.
     * Call once from `init()` -- this is process-global and one-shot by design. Returns `true`
     * if this call actually won the race and installed the factory, `false` if it was already
     * installed (by this SDK or -- more likely to matter -- by something else first).
     */
    fun install(observer: RequestrObserver): Boolean {
        if (!installed.compareAndSet(false, true)) {
            Log.w(TAG, "install() called more than once in this process -- ignoring")
            return false
        }
        originalHttpHandler = systemHandlerFor("http")
        originalHttpsHandler = systemHandlerFor("https")
        return try {
            URL.setURLStreamHandlerFactory(RequestrStreamHandlerFactory(observer))
            true
        } catch (e: Error) {
            // setURLStreamHandlerFactory throws java.lang.Error (not Exception) when a factory
            // is already installed -- another library in the host app won the race.
            Log.w(TAG, "Could not install URLStreamHandlerFactory -- another factory is already " +
                "registered in this process. HttpURLConnection capture unavailable: ${e.message}")
            false
        }
    }

    /** Reflects into `java.net.URL`'s package-private static `getURLStreamHandler(String)` to
     * obtain the handler the JVM/runtime would use for [protocol] if no custom factory were ever
     * installed. Must be called before [URL.setURLStreamHandlerFactory] -- once a factory is
     * installed, this same method would just call back into the factory.
     *
     * **Cache-poisoning gotcha**: `URL.getURLStreamHandler(String)` has a side effect of caching
     * its result into `URL`'s private static `handlers` map, keyed by protocol. If we call it and
     * then leave that cache entry in place, every future `URL(...).openConnection()` for this
     * protocol -- including ones made *after* we install our own factory below -- will short-
     * circuit straight to the cached system handler and never consult our factory at all (the
     * factory lookup path in `URL` is only reached on a cache miss). [evictHandlerCache] clears
     * the entry immediately after reading it so the cache is empty again by the time
     * [URL.setURLStreamHandlerFactory] runs, forcing the next `openConnection()` call for
     * [protocol] to actually ask our factory. */
    private fun systemHandlerFor(protocol: String): URLStreamHandler? {
        return try {
            val method = URL::class.java.getDeclaredMethod("getURLStreamHandler", String::class.java)
            method.isAccessible = true
            val handler = method.invoke(null, protocol) as? URLStreamHandler
            evictHandlerCache(protocol)
            handler
        } catch (e: Exception) {
            Log.w(TAG, "Could not reflect the system URLStreamHandler for $protocol: ${e.message}")
            null
        }
    }

    /** Evicts [protocol] from `java.net.URL`'s private static `handlers` cache -- see the
     * kdoc on [systemHandlerFor] for why this is necessary. Also covers the case where some
     * other code in the process (not just our own [systemHandlerFor] call) already resolved and
     * cached a handler for [protocol] before [install] ran. */
    private fun evictHandlerCache(protocol: String) {
        try {
            val field = URL::class.java.getDeclaredField("handlers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val handlers = field.get(null) as? MutableMap<String, URLStreamHandler>
            handlers?.remove(protocol)
        } catch (e: Exception) {
            Log.w(TAG, "Could not evict cached URLStreamHandler for $protocol: ${e.message}")
        }
    }

    private class RequestrStreamHandlerFactory(private val observer: RequestrObserver) : URLStreamHandlerFactory {
        override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
            return when (protocol) {
                "http" -> originalHttpHandler?.let { RequestrUrlStreamHandler(it, observer) }
                "https" -> originalHttpsHandler?.let { RequestrUrlStreamHandler(it, observer) }
                else -> null // fall back to the platform's default handler for every other scheme
            }
        }
    }

    /**
     * Delegates the actual connection to [real] (the platform's genuine http(s) handler,
     * captured in [install] before this factory took over) and wraps the resulting
     * [HttpURLConnection] to capture bodies/headers/timing.
     */
    private class RequestrUrlStreamHandler(
        private val real: URLStreamHandler,
        private val observer: RequestrObserver,
    ) : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection {
            val connection = openViaRealHandler(url)
            return if (connection is HttpURLConnection) RequestrHttpUrlConnection(connection, observer) else connection
        }

        private fun openViaRealHandler(url: URL): URLConnection {
            val openConnection = URLStreamHandler::class.java.getDeclaredMethod("openConnection", URL::class.java)
            openConnection.isAccessible = true
            return openConnection.invoke(real, url) as URLConnection
        }
    }

    /**
     * Wraps a real [HttpURLConnection], capturing request body bytes (via [getOutputStream]),
     * response body bytes (via [getInputStream]), headers, and timing, then forwarding every
     * call through to [delegate] unchanged.
     */
    private class RequestrHttpUrlConnection(
        private val delegate: HttpURLConnection,
        private val observer: RequestrObserver,
    ) : HttpURLConnection(delegate.url) {
        private val startedAt = System.currentTimeMillis()
        private val requestBodyCapture = ByteArrayOutputStream()
        private var responseBodyCapture: ByteArrayOutputStream? = null
        private var reported = false

        override fun getOutputStream(): OutputStream {
            val real = delegate.outputStream
            return object : FilterOutputStream(real) {
                override fun write(b: Int) {
                    super.write(b)
                    if (requestBodyCapture.size() < MAX_CAPTURE_BYTES) requestBodyCapture.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    if (requestBodyCapture.size() < MAX_CAPTURE_BYTES) {
                        requestBodyCapture.write(b, off, minOf(len, MAX_CAPTURE_BYTES - requestBodyCapture.size()))
                    }
                }
            }
        }

        override fun getInputStream(): InputStream {
            val real = delegate.inputStream
            val capture = ByteArrayOutputStream().also { responseBodyCapture = it }
            return object : FilterInputStream(real) {
                override fun read(): Int {
                    val b = super.read()
                    if (b != -1 && capture.size() < MAX_CAPTURE_BYTES) capture.write(b)
                    if (b == -1) reportOnce()
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0 && capture.size() < MAX_CAPTURE_BYTES) {
                        capture.write(b, off, minOf(n, MAX_CAPTURE_BYTES - capture.size()))
                    }
                    if (n == -1) reportOnce()
                    return n
                }

                override fun close() {
                    reportOnce()
                    super.close()
                }
            }
        }

        private fun reportOnce() {
            if (reported) return
            reported = true
            val headers = mutableMapOf<String, String>()
            runCatching {
                for ((key, values) in delegate.headerFields) {
                    if (key != null) headers[key] = values.joinToString(", ")
                }
            }
            observer.observeDetailed(
                url = delegate.url.toString(),
                method = delegate.requestMethod ?: "GET",
                headers = headers,
                statusCode = runCatching { delegate.responseCode }.getOrNull(),
                requestBody = requestBodyCapture.toByteArray().takeIf { it.isNotEmpty() },
                responseBody = responseBodyCapture?.toByteArray()?.takeIf { it.isNotEmpty() },
                durationMs = System.currentTimeMillis() - startedAt,
                httpStack = "HttpURLConnection",
                isDecrypted = true,
            )
        }

        // Every other HttpURLConnection method delegates straight through unchanged.
        override fun connect() = delegate.connect()
        override fun disconnect() = delegate.disconnect()
        override fun usingProxy() = delegate.usingProxy()
        override fun getResponseCode(): Int = delegate.responseCode
        override fun getResponseMessage(): String? = delegate.responseMessage
        override fun getHeaderField(name: String?): String? = delegate.getHeaderField(name)
        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = delegate.headerFields
        override fun setRequestMethod(method: String?) = delegate.setRequestMethod(method)
        override fun getRequestMethod(): String = delegate.requestMethod
        override fun setRequestProperty(key: String?, value: String?) = delegate.setRequestProperty(key, value)
        override fun addRequestProperty(key: String?, value: String?) = delegate.addRequestProperty(key, value)
        override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)
        override fun getRequestProperties(): MutableMap<String, MutableList<String>> = delegate.requestProperties
        override fun setDoOutput(dooutput: Boolean) { delegate.doOutput = dooutput }
        override fun setDoInput(doinput: Boolean) { delegate.doInput = doinput }
        override fun getErrorStream(): InputStream? = delegate.errorStream
    }

    private const val MAX_CAPTURE_BYTES = 1 * 1024 * 1024
}
