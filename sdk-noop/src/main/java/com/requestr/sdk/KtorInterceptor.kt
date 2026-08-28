package com.requestr.sdk

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin

/**
 * No-op counterpart to the real `:sdk` module's `KtorInterceptor.kt` -- see there for the full
 * contract this mirrors. Installs [RequestrKtorPlugin], which itself does nothing (no
 * `onRequest`/`onResponse` hooks) -- kept installable rather than simply not-installed so the
 * plugin's lifecycle (install/uninstall on client close, etc.) behaves identically either way.
 */
fun HttpClientConfig<*>.installRequestrPlugin(observer: RequestrObserver) {
    install(RequestrKtorPlugin) {
        this.observer = observer
    }
}

/** Identical shape to the real `:sdk` module's `RequestrKtorPluginConfig`. */
class RequestrKtorPluginConfig {
    var observer: RequestrObserver? = null
}

/** No-op counterpart to the real `:sdk` module's `RequestrKtorPlugin` -- registers no
 * `onRequest`/`onResponse` hooks at all. */
val RequestrKtorPlugin = createClientPlugin("RequestrKtorPlugin", ::RequestrKtorPluginConfig) {
    // Intentionally empty -- see file kdoc.
}
