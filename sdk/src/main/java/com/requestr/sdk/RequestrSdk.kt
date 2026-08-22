package com.requestr.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Task 11: one-line integration facade for a third-party app embedding this SDK.
 *
 * Instead of wiring [RequestrObserver.init], [HttpUrlConnectionWrapper.install], and
 * [KeyLogProducer.startCronetKeyLogMonitor] separately (each with its own opt-in/opt-out
 * knob and its own gotchas -- see each class's kdoc), an embedding app's
 * `Application.onCreate()` does just:
 *
 * ```kotlin
 * RequestrSdk.init(
 *     context = this,
 *     pairingToken = "copied-from-requestr-settings",
 *     options = RequestrSdk.Options(
 *         captureOkHttp = true,
 *         captureHttpUrlConnection = true,
 *         captureCronet = false,
 *         captureKtor = false,
 *         enableKeyLog = true,
 *         requestrApiBase = "http://127.0.0.1:9876",
 *     ),
 * )
 * ```
 *
 * `captureOkHttp` does NOT retroactively instrument OkHttpClients the host app already built
 * -- OkHttp has no global interceptor hook, only per-`OkHttpClient.Builder` ones (see
 * [OkHttpInterceptor]'s kdoc). What `captureOkHttp = true` actually does here is expose
 * [okHttpBuilder] as the one-line way to get an already-instrumented builder for any new
 * client the host app constructs after calling [init]. `captureHttpUrlConnection` and
 * `captureCronet`, by contrast, ARE genuinely process-global/automatic once installed (a
 * [java.net.URLStreamHandlerFactory] and Cronet's builder extension respectively) -- see
 * [HttpUrlConnectionWrapper] and [CronetInterceptor] for the exact mechanism and limits of
 * each.
 */
object RequestrSdk {
    private const val TAG = "RequestrSdk"

    /**
     * @param captureOkHttp when true, [okHttpBuilder] returns interceptor/event-listener-wired
     *   builders; when false, [okHttpBuilder] returns builders unmodified (source-compatible
     *   either way, so a host app can flip this without changing call sites).
     * @param captureHttpUrlConnection when true, installs [HttpUrlConnectionWrapper] to capture
     *   every `HttpURLConnection` in the process. Best-effort: fails silently (logged, not
     *   thrown) if another library already installed a [java.net.URLStreamHandlerFactory]
     *   first -- see [HttpUrlConnectionWrapper.install]'s kdoc for why that's a hard platform
     *   constraint, not a bug.
     * @param captureCronet when true, exposes [cronetEngineBuilder] to wire
     *   [CronetInterceptor.addRequestrInterceptor] onto the host app's own Cronet engine
     *   builder. Opt-in and false by default because it requires the host app to already
     *   depend on `com.google.android.gms:play-services-cronet` -- this SDK never forces that
     *   dependency onto an app that doesn't already use Cronet (`compileOnly`, see
     *   `sdk/build.gradle.kts`).
     * @param captureKtor when true, exposes [installKtorPlugin] to wire
     *   [KtorInterceptor.installRequestrPlugin] onto the host app's own Ktor
     *   `HttpClientConfig`. Opt-in and false by default for the same reason as [captureCronet]
     *   (`io.ktor:ktor-client-core` is `compileOnly`).
     * @param enableKeyLog when true, starts [KeyLogProducer.startCronetKeyLogMonitor] (Cronet's
     *   NSS keylog file tail) and enables [OkHttpInterceptor]'s `RequestrEventListener` TLS
     *   secret reflection (task 8) so requestr's own passive decoders can decrypt this app's
     *   TLS 1.3/QUIC traffic. Degrades gracefully to a no-op (see [KeyLogRegistryBridge]) when
     *   `TlsKeyLogRegistry` isn't on the host app's classpath -- i.e. in every third-party app
     *   except requestr's own `:app` module. Body capture via [RequestrObserver] works
     *   identically whether or not this is enabled -- keylog production is a bonus signal for
     *   requestr's independent wire-level decoders, never a prerequisite.
     * @param requestrApiBase base URL of requestr's embedded Local API server. Defaults to the
     *   loopback address requestr's `LocalApiServer` binds by default; override only if
     *   requestr is reachable at a different host/port (e.g. `adb reverse`, an emulator's
     *   `10.0.2.2` host alias, or a device farm's port-forward).
     */
    data class Options(
        val captureOkHttp: Boolean = true,
        val captureHttpUrlConnection: Boolean = true,
        val captureCronet: Boolean = false,
        val captureKtor: Boolean = false,
        val enableKeyLog: Boolean = true,
        val requestrApiBase: String = "http://127.0.0.1:9876",
    )

    @Volatile
    private var options: Options = Options()

    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Call once from the host app's `Application.onCreate()` (or as early as possible in the
     * process lifecycle -- [captureHttpUrlConnection] loses its race with any other
     * [java.net.URLStreamHandlerFactory] installer if called too late).
     *
     * @param context any [Context] -- only [Context.getApplicationContext] is retained.
     * @param pairingToken copied from requestr's Settings > SDK Pairing screen, revealed via
     *   `GET /api/pairing-info` while "Show Pairing Token" is on. See [RequestrObserver.init]'s
     *   kdoc for the exact header this becomes (`Authorization: Bearer <pairingToken>`).
     * @param options see [Options]. Every unset field defaults to the common case (OkHttp +
     *   HttpURLConnection + keylog on, Cronet/Ktor off since those require an extra host-app
     *   dependency).
     */
    fun init(context: Context, pairingToken: String, options: Options = Options()) {
        if (initialized) {
            Log.w(TAG, "init() called more than once -- ignoring, first call wins")
            return
        }
        initialized = true
        this.options = options

        RequestrObserver.init(context.applicationContext, pairingToken, options.requestrApiBase)

        if (options.captureHttpUrlConnection) {
            HttpUrlConnectionWrapper.install(RequestrObserver)
        }
        if (options.enableKeyLog) {
            KeyLogProducer.startCronetKeyLogMonitor(context.applicationContext, scope)
        }

        Log.i(
            TAG,
            "RequestrSdk initialized (okHttp=${options.captureOkHttp}, " +
                "httpUrlConnection=${options.captureHttpUrlConnection}, " +
                "cronet=${options.captureCronet}, ktor=${options.captureKtor}, " +
                "keyLog=${options.enableKeyLog}, apiBase=${options.requestrApiBase})",
        )
    }

    /**
     * Returns [builder] wired with requestr's OkHttp interceptor + event listener if
     * [Options.captureOkHttp] was true at [init] time, or [builder] unmodified otherwise --
     * always source-compatible, so a host app can call this unconditionally right after
     * building its `OkHttpClient.Builder()` and let the [Options] flag decide the behavior.
     * No-ops (returns [builder] unmodified, with a warning) if [init] hasn't been called yet.
     */
    fun okHttpBuilder(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        if (!initialized) {
            Log.w(TAG, "okHttpBuilder() called before init() -- returning builder unmodified")
            return builder
        }
        if (!options.captureOkHttp) return builder
        return builder.addRequestrInterceptor(RequestrObserver)
    }

    /**
     * Returns [builder] wired with requestr's Cronet interceptor if [Options.captureCronet]
     * was true at [init] time, or [builder] unmodified otherwise. Only usable if the host app
     * itself depends on `com.google.android.gms:play-services-cronet` -- see [Options.captureCronet].
     */
    fun cronetEngineBuilder(
        context: Context,
        builder: org.chromium.net.ExperimentalCronetEngine.Builder,
    ): org.chromium.net.ExperimentalCronetEngine.Builder {
        if (!initialized) {
            Log.w(TAG, "cronetEngineBuilder() called before init() -- returning builder unmodified")
            return builder
        }
        if (!options.captureCronet) return builder
        return builder.addRequestrInterceptor(context.applicationContext, RequestrObserver)
    }

    /**
     * Installs requestr's Ktor plugin on [config] if [Options.captureKtor] was true at [init]
     * time; a no-op otherwise. Only usable if the host app itself depends on
     * `io.ktor:ktor-client-core` -- see [Options.captureKtor].
     */
    fun installKtorPlugin(config: io.ktor.client.HttpClientConfig<*>) {
        if (!initialized) {
            Log.w(TAG, "installKtorPlugin() called before init() -- ignoring")
            return
        }
        if (!options.captureKtor) return
        config.installRequestrPlugin(RequestrObserver)
    }

    /** True once [init] has been called successfully. Exposed for [VerificationHarness] and
     * for host apps that want to guard optional instrumentation call sites. */
    fun isInitialized(): Boolean = initialized

    /** Test-only: resets all static state so a fresh [init] call behaves like a cold process.
     * Never call this from production code -- [RequestrObserver], [HttpUrlConnectionWrapper],
     * and [KeyLogProducer] hold real process-global state ([java.net.URLStreamHandlerFactory]
     * in particular can only ever be installed once per real JVM/process). */
    internal fun resetForTests() {
        initialized = false
        options = Options()
    }
}
