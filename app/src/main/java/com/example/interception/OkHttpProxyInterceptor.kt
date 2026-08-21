package com.example.interception

import android.content.Context
import com.example.data.interception.InterceptionStateRepository
import okhttp3.Interceptor
import okhttp3.Response
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp interceptor that routes traffic through requestr's local MITM proxy.
 * Non-root path — own apps trust requestr's CA, proxy terminates TLS.
 */
class OkHttpProxyInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val port = InterceptionStateRepository.state.value.port
        val request = chain.request()
        val startedAt = System.currentTimeMillis()

        if (port <= 0) {
            val response = chain.proceed(request)
            recordEvent(request, response, startedAt)
            return response
        }

        val proxy = Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(AllTrustManager()), SecureRandom())

        val client = okhttp3.OkHttpClient.Builder()
            .proxy(proxy)
            .sslSocketFactory(ssl.socketFactory, AllTrustManager())
            .build()
        val response = client.newCall(request).execute()
        recordEvent(request, response, startedAt)
        return response
    }

    private fun recordEvent(request: okhttp3.Request, response: Response, startedAt: Long) {
        runCatching {
            val headers = mutableMapOf<String, String>()
            for (name in request.headers.names()) {
                request.header(name)?.let { headers[name] = it }
            }
            pendingEvents.add(
                RecordedOkHttpCall(
                    method = request.method,
                    url = request.url.toString(),
                    statusCode = response.code,
                    headers = headers,
                    durationMs = System.currentTimeMillis() - startedAt,
                    timestamp = startedAt,
                )
            )
            // Bound the queue so a runaway app can't leak memory if nothing drains it.
            while (pendingEvents.size > MAX_QUEUED_EVENTS) pendingEvents.poll()
        }
    }

    private class AllTrustManager : X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    companion object {
        private const val MAX_QUEUED_EVENTS = 500

        /**
         * Shared, thread-safe queue of real requests observed by any [OkHttpProxyInterceptor]
         * instance in the process. Drained by [com.example.runtime.http.OkHttpAdapter.pollEvents]
         * so the runtime instrumentation plane reports genuine app traffic instead of an empty
         * placeholder list (R3).
         */
        val pendingEvents = ConcurrentLinkedQueue<RecordedOkHttpCall>()

        /** Drain and return all currently queued events. */
        fun drainEvents(): List<RecordedOkHttpCall> {
            val out = mutableListOf<RecordedOkHttpCall>()
            while (true) {
                out.add(pendingEvents.poll() ?: break)
            }
            return out
        }
    }
}

/** A single OkHttp request/response observed through [OkHttpProxyInterceptor]. */
data class RecordedOkHttpCall(
    val method: String,
    val url: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val durationMs: Long,
    val timestamp: Long,
)
