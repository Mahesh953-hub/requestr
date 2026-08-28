package com.requestr.sdk

import android.content.Context

/**
 * No-op counterpart to the real `:sdk` module's `RequestrSdk` one-line facade -- see that
 * module's kdoc for the full contract this mirrors call-site-for-call-site. [init] only records
 * that it was called (so [isInitialized] stays meaningful for host-app guard clauses); every
 * builder/config-wiring method below returns its input completely unmodified instead of
 * installing any interceptor/listener/plugin, and every [Options] flag is accepted but has no
 * effect. This is the module a host app's `releaseImplementation` (or equivalent build-variant
 * dependency) points at instead of `:sdk` to strip all interception/capture code out of a
 * production APK -- see this module's README for the exact wiring pattern and which classes are
 * (and are intentionally NOT) mirrored.
 */
object RequestrSdk {
    /** Identical shape to the real `:sdk` module's `RequestrSdk.Options` -- every flag is
     * accepted for source compatibility but has no effect here. */
    data class Options(
        val captureOkHttp: Boolean = true,
        val captureHttpUrlConnection: Boolean = true,
        val captureCronet: Boolean = false,
        val captureKtor: Boolean = false,
        val captureGrpc: Boolean = false,
        val captureWebSocket: Boolean = true,
        val enableKeyLog: Boolean = true,
        val requestrApiBase: String = "http://127.0.0.1:9876",
    )

    @Volatile
    private var initialized = false

    fun init(context: Context, pairingToken: String, options: Options = Options()) {
        initialized = true
    }

    /** Returns [builder] unmodified -- see class kdoc. */
    fun okHttpBuilder(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder = builder

    /** Returns [builder] unmodified -- see class kdoc. */
    fun cronetEngineBuilder(
        context: Context,
        builder: org.chromium.net.ExperimentalCronetEngine.Builder,
    ): org.chromium.net.ExperimentalCronetEngine.Builder = builder

    fun installKtorPlugin(config: io.ktor.client.HttpClientConfig<*>) {
        // Intentionally empty -- see class kdoc.
    }

    /** Returns [builder] unmodified -- see class kdoc. */
    fun <T : io.grpc.ManagedChannelBuilder<T>> grpcChannelBuilder(builder: io.grpc.ManagedChannelBuilder<T>): io.grpc.ManagedChannelBuilder<T> = builder

    /** Opens an uncaptured `client.newWebSocket(request, listener)` -- see class kdoc. */
    fun newWebSocket(client: okhttp3.OkHttpClient, request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket =
        client.newWebSocket(request, listener)

    fun isInitialized(): Boolean = initialized

    /** Test-only: resets state so a fresh [init] call behaves like a cold process -- see the
     * real `:sdk` module's equivalent kdoc for why this exists (this module has far less state
     * to reset, but keeps the same accessor for source compatibility with any shared test code). */
    internal fun resetForTests() {
        initialized = false
    }
}
