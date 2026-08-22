package com.requestr.sdk

import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Installs both the body/header-capturing [Interceptor] and the timing-capturing [EventListener]
 * needed for full request observation on an app's own, direct (non-proxied) OkHttp client.
 *
 * **Key difference from [com.example.interception.OkHttpProxyInterceptor]** (in the requestr
 * `:app` module, not visible from this SDK module): that interceptor routes the request through
 * requestr's local MITM proxy so requestr's OWN capture engine sees the traffic. This one instead
 * observes the embedding app's OWN direct connection in-process and reports it to
 * [RequestrObserver] -- no proxy hop, no requestr CA needed on the wire, works for any
 * third-party app regardless of whether requestr's interception is even running.
 */
fun OkHttpClient.Builder.addRequestrInterceptor(observer: RequestrObserver): OkHttpClient.Builder {
    addInterceptor(RequestrOkHttpInterceptor(observer))
    eventListener(RequestrEventListener(observer))
    return this
}

/**
 * Application-layer interceptor: runs after OkHttp's own TLS/redirect/retry machinery, so it sees
 * the plaintext request body before encryption and the plaintext response body after decryption.
 */
class RequestrOkHttpInterceptor(private val observer: RequestrObserver) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.currentTimeMillis()

        // Buffering into a fresh okio.Buffer (rather than reading the request's own source)
        // never consumes anything OkHttp itself needs to send -- this is the same technique
        // OkHttp's own HttpLoggingInterceptor uses to peek a request body without disturbing it.
        val requestBody = runCatching {
            val body = request.body ?: return@runCatching null
            val peekBuffer = okio.Buffer()
            body.writeTo(peekBuffer)
            peekBuffer.readByteArray()
        }.getOrNull()

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            observer.observeDetailed(
                url = request.url.toString(),
                method = request.method,
                headers = headerMap(request.headers),
                statusCode = null,
                requestBody = requestBody,
                responseBody = null,
                durationMs = System.currentTimeMillis() - startedAt,
                httpStack = "OkHttp",
                tlsVersion = null,
                tlsCipher = null,
                serverCertSubject = null,
                isDecrypted = true,
            )
            throw e
        }

        val responseBody = runCatching { response.peekBody(MAX_PEEK_BODY_BYTES).bytes() }.getOrNull()
        val handshake = runCatching { response.handshake }.getOrNull()
        val timing = runCatching { RequestrObserver.consumeTiming(chain.call()) }.getOrNull()

        observer.observeDetailed(
            url = request.url.toString(),
            method = request.method,
            headers = headerMap(request.headers),
            statusCode = response.code,
            requestBody = requestBody,
            responseBody = responseBody,
            durationMs = System.currentTimeMillis() - startedAt,
            httpStack = "OkHttp",
            tlsVersion = handshake?.tlsVersion?.javaName,
            tlsCipher = handshake?.cipherSuite?.javaName,
            serverCertSubject = handshake?.peerCertificates?.firstOrNull()
                ?.let { (it as? java.security.cert.X509Certificate)?.subjectX500Principal?.name },
            isDecrypted = true,
            timing = timing,
        )
        return response
    }

    private fun headerMap(headers: okhttp3.Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (name in headers.names()) headers[name]?.let { map[name] = it }
        return map
    }

    companion object {
        /** Bounds body capture so a multi-GB upload/download can't blow up process memory. */
        private const val MAX_PEEK_BODY_BYTES = 1L * 1024 * 1024
    }
}

/**
 * Captures DNS/connect/TLS-handshake timing that the [Interceptor] alone cannot see (it only
 * runs at the application layer, after all of that has already happened). Not available on
 * OkHttp 3.x, which lacks [EventListener] entirely -- this whole class is `compileOnly`-safe
 * because it only exists in the SDK's own source, guarded by the `compileOnly("...okhttp...")`
 * dependency; if the host app ships OkHttp 3.x, this class simply never gets instantiated by
 * [addRequestrInterceptor]'s caller (that call site itself won't compile against 3.x, which is
 * the expected degrade -- OkHttp 3.x apps get body/header capture only via [RequestrOkHttpInterceptor]
 * called directly as a manual `addInterceptor`, not via this convenience extension).
 */
class RequestrEventListener(private val observer: RequestrObserver) : EventListener() {
    private val dnsStart = mutableMapOf<Call, Long>()
    private val connectStart = mutableMapOf<Call, Long>()
    private val secureConnectStart = mutableMapOf<Call, Long>()

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart[call] = System.currentTimeMillis()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        val start = dnsStart.remove(call) ?: return
        observer.recordTiming(call, "dns", System.currentTimeMillis() - start)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart[call] = System.currentTimeMillis()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) {
        val start = connectStart.remove(call) ?: return
        observer.recordTiming(call, "connect", System.currentTimeMillis() - start)
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStart[call] = System.currentTimeMillis()
    }

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        val start = secureConnectStart.remove(call) ?: return
        observer.recordTiming(call, "tls", System.currentTimeMillis() - start)
    }
}
