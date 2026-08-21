package com.example.runtime.http

import android.content.Context
import com.example.interception.OkHttpProxyInterceptor
import com.example.runtime.InstrumentationAdapter
import com.example.runtime.InstrumentationEvent

/** Adapter that instruments OkHttp via interceptor injection */
class OkHttpAdapter : InstrumentationAdapter {
    override val name = "OkHttp"
    private var active = false

    override fun start(context: Context) {
        active = true
        // The interceptor is already available — apps use it via OkHttpClient.Builder()
        // with .addInterceptor(OkHttpProxyInterceptor(context))
    }

    override fun stop() { active = false }

    override fun isActive() = active

    override fun pollEvents(): List<InstrumentationEvent> = emptyList()
}

/** Adapter for Cronet (Chrome's network stack) */
class CronetAdapter : InstrumentationAdapter {
    override val name = "Cronet"
    private var active = false

    override fun start(context: Context) {
        active = try {
            Class.forName("org.chromium.net.CronetEngine")
            true
        } catch (_: ClassNotFoundException) { false }
    }

    override fun stop() { active = false }

    override fun isActive() = active

    override fun pollEvents(): List<InstrumentationEvent> = emptyList()
}

/** Adapter for WebView */
class WebViewAdapter : InstrumentationAdapter {
    override val name = "WebView"
    private var active = false

    override fun start(context: Context) {
        active = try {
            Class.forName("android.webkit.WebView")
            true
        } catch (_: ClassNotFoundException) { false }
    }

    override fun stop() { active = false }

    override fun isActive() = active

    override fun pollEvents(): List<InstrumentationEvent> = emptyList()
}
