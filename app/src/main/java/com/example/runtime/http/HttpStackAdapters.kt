package com.example.runtime.http

import android.content.Context
import com.example.interception.OkHttpProxyInterceptor
import com.example.runtime.InstrumentationAdapter
import com.example.runtime.InstrumentationEvent

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
