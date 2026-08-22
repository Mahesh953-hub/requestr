package com.requestr.sdk

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.util.date.getTimeMillis
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.runBlocking

/**
 * Ktor client plugin: `createClientPlugin`'s `onRequest`/`onResponse` hooks run underneath
 * whichever real [io.ktor.client.engine.HttpClientEngine] the host app configured (CIO, OkHttp,
 * Android, Darwin, ...) -- Ktor's plugin system is itself engine-agnostic, so this one plugin
 * covers every engine without a separate wrapper per engine.
 *
 * `compileOnly("io.ktor:ktor-client-core:...")` means this file's classes only load if the host
 * app itself depends on Ktor -- an app that never touches Ktor never pays for it.
 */
fun HttpClientConfig<*>.installRequestrPlugin(observer: RequestrObserver) {
    install(RequestrKtorPlugin) {
        this.observer = observer
    }
}

class RequestrKtorPluginConfig {
    var observer: RequestrObserver? = null
}

val RequestrKtorPlugin = createClientPlugin("RequestrKtorPlugin", ::RequestrKtorPluginConfig) {
    val observer = pluginConfig.observer

    val startTimes = java.util.concurrent.ConcurrentHashMap<Any, Long>()

    onRequest { request, _ ->
        startTimes[request] = getTimeMillis()
    }

    onResponse { response ->
        if (observer == null) return@onResponse
        val startedAt = startTimes.remove(response.call.request as Any) ?: getTimeMillis()
        val headers = mutableMapOf<String, String>()
        for (name in response.headers.names()) {
            response.headers[name]?.let { headers[name] = it }
        }
        val responseBody = runCatching {
            runBlocking {
                response.bodyAsChannel().readRemaining(MAX_CAPTURE_BYTES.toLong()).readBytes()
            }
        }.getOrNull()

        observer.observeDetailed(
            url = response.call.request.url.toString(),
            method = response.call.request.method.value,
            headers = headers,
            statusCode = response.status.value,
            requestBody = null, // Ktor request bodies are OutgoingContent, not readable post-send here
            responseBody = responseBody,
            durationMs = getTimeMillis() - startedAt,
            httpStack = "Ktor",
            isDecrypted = true,
        )
    }
}

private const val MAX_CAPTURE_BYTES = 1 * 1024 * 1024
