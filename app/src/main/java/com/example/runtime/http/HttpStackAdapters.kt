package com.example.runtime.http

import android.content.Context
import com.example.interception.OkHttpProxyInterceptor
import com.example.runtime.InstrumentationAdapter
import com.example.runtime.InstrumentationEvent
import com.example.runtime.webview.WebViewInstrumentation

/**
 * Adapter that instruments OkHttp via interceptor injection.
 *
 * requestr cannot force an arbitrary third-party app to add
 * [OkHttpProxyInterceptor] to its own `OkHttpClient.Builder()` — that has to
 * happen in that app's source (or via the Requestr Observer SDK, see S1).
 * What this adapter CAN do honestly is report real activity for the cases
 * that are already wired: requestr's own outgoing OkHttp calls that go
 * through [OkHttpProxyInterceptor], whose `intercept()` now records every
 * real request/response pair into a shared, thread-safe queue. `pollEvents()`
 * drains that queue instead of returning an empty placeholder list.
 */
class OkHttpAdapter : InstrumentationAdapter {
    override val name = "OkHttp"
    private var active = false

    override fun start(context: Context) {
        // The interceptor is already available — apps use it via OkHttpClient.Builder()
        // with .addInterceptor(OkHttpProxyInterceptor(context)). Drop any events queued
        // from before this adapter was (re)started so pollEvents() only reports fresh data.
        OkHttpProxyInterceptor.drainEvents()
        active = true
    }

    override fun stop() { active = false }

    override fun isActive() = active

    override fun pollEvents(): List<InstrumentationEvent> {
        if (!active) return emptyList()
        return OkHttpProxyInterceptor.drainEvents().map { call ->
            InstrumentationEvent(
                adapter = name,
                eventType = "response_ready",
                method = call.method,
                url = call.url,
                headers = call.headers,
                statusCode = call.statusCode,
                timestamp = call.timestamp,
            )
        }
    }
}

/**
 * Adapter for Cronet (Chrome's network stack).
 *
 * W2: replaces the previous `Class.forName("org.chromium.net.CronetEngine")` presence-only
 * shell with a real requestr-owned engine ([CronetInstrumentation]), a genuine
 * `RequestFinishedInfo.Listener`, and proxy routing through the MITM proxy. `active` is true
 * only once the engine actually built; a missing/broken provider is a real, reported failure
 * via [status], not a silent false.
 */
class CronetAdapter : InstrumentationAdapter {
    override val name = "Cronet"
    private var active = false

    override fun start(context: Context) {
        active = CronetInstrumentation.engine(context) != null
    }

    override fun stop() {
        CronetInstrumentation.stop()
        active = false
    }

    override fun isActive() = active

    override fun status(): String = CronetInstrumentation.statusText()

    override fun pollEvents(): List<InstrumentationEvent> {
        if (!active) return emptyList()
        return CronetInstrumentation.drainEvents()
    }
}

/**
 * Adapter for WebView.
 *
 * W3: replaces the previous `Class.forName("android.webkit.WebView")` presence-only shell with
 * a real requestr-hosted, offscreen WebView ([WebViewInstrumentation]) driven from the Settings
 * "RUNTIME INSTRUMENTATION" card's URL field (decision DEC-2). `active` is true only once a
 * WebView instance genuinely exists and its client is installed; [status] reports which
 * `WebViewFeature`s resolved true and which WebView package/version backs it, so an
 * unsupported device produces a real diagnostic instead of a silent false.
 */
class WebViewAdapter : InstrumentationAdapter {
    override val name = "WebView"

    override fun start(context: Context) {
        WebViewInstrumentation.start(context)
    }

    override fun stop() {
        WebViewInstrumentation.stop()
    }

    override fun isActive() = WebViewInstrumentation.isActive()

    override fun status(): String = WebViewInstrumentation.statusText()

    override fun pollEvents(): List<InstrumentationEvent> {
        if (!isActive()) return emptyList()
        return WebViewInstrumentation.drainEvents()
    }
}
