package com.requestr.sdk

import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * No-op counterpart to the real `:sdk` module's `OkHttpInterceptor.kt` -- see there for the full
 * contract this mirrors. Adds nothing to [builder]: no [Interceptor], no [EventListener].
 */
fun OkHttpClient.Builder.addRequestrInterceptor(observer: RequestrObserver): OkHttpClient.Builder = this

/** No-op counterpart to the real `:sdk` module's `RequestrOkHttpInterceptor` -- passes every
 * request straight through unobserved. */
class RequestrOkHttpInterceptor(private val observer: RequestrObserver) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

/** No-op counterpart to the real `:sdk` module's `RequestrEventListener` -- every callback keeps
 * [EventListener]'s own empty default implementation. */
class RequestrEventListener(private val observer: RequestrObserver) : EventListener()
