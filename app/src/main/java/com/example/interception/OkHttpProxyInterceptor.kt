package com.example.interception

import android.content.Context
import com.example.data.interception.InterceptionStateRepository
import okhttp3.Interceptor
import okhttp3.Response
import java.net.Proxy
import java.security.SecureRandom
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
        if (port <= 0) return chain.proceed(chain.request())

        val proxy = Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(AllTrustManager()), SecureRandom())

        val client = okhttp3.OkHttpClient.Builder()
            .proxy(proxy)
            .sslSocketFactory(ssl.socketFactory, AllTrustManager())
            .build()
        return client.newCall(chain.request()).execute()
    }

    private class AllTrustManager : X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }
}
