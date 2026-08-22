# requestr Observer SDK (`:sdk`)

Zero-dependency-on-`:app` Android library that any third-party app embeds to have its own
outbound HTTP/HTTPS traffic show up inside a running [requestr](../README.md) instance's
Intercept screen, correlated alongside VPN/root/Shizuku-captured traffic.

Independent module: `com.requestr.sdk`, `minSdk = 24`. Never depends on requestr's `:app`
module — the relationship is the other way around, a host app depends on `:sdk`.

## Quick start

```kotlin
// Application.onCreate()
RequestrSdk.init(
    context = this,
    pairingToken = "copied-from-requestr-settings",   // see "Pairing" below
    options = RequestrSdk.Options(
        captureOkHttp = true,             // exposes RequestrSdk.okHttpBuilder(...)
        captureHttpUrlConnection = true,   // installs a process-wide URLStreamHandlerFactory
        captureCronet = false,             // opt-in, needs play-services-cronet on the host app
        captureKtor = false,               // opt-in, needs ktor-client-core on the host app
        enableKeyLog = true,               // produce TLS secrets into TlsKeyLogRegistry, if present
        requestrApiBase = "http://127.0.0.1:9876",
    ),
)
```

Then, wherever the host app builds its own `OkHttpClient`:

```kotlin
val client = RequestrSdk.okHttpBuilder(OkHttpClient.Builder())
    .build()
```

`HttpURLConnection`/`java.net.URL(...).openConnection()` usage needs no code changes once
`captureHttpUrlConnection = true` — it's genuinely process-wide once installed.

## Pairing

1. In requestr, go to Settings and enable **"Show Pairing Token"** (auto-disables again after
   5 minutes).
2. `GET /api/pairing-info` on requestr's embedded Local API (default `http://127.0.0.1:9876`)
   returns `{"token":"...","port":9876}` while the toggle is on; copy the token.
3. Pass it as `pairingToken` to [`RequestrSdk.init`]. Every subsequent `POST /api/observe` call
   this SDK makes sends `Authorization: Bearer <token>` — required, requestr rejects unauthenticated
   `/api/observe` requests with 401.

If requestr runs on a separate device/emulator instance than the host app under test, override
`requestrApiBase` (e.g. `http://10.0.2.2:9876` from an emulator to the host machine, or an
`adb reverse` tunnel).

## What gets captured, and how

| Stack | Mechanism | Automatic? |
|---|---|---|
| OkHttp | `RequestrSdk.okHttpBuilder(builder)` adds an `Interceptor` (body/headers) + `EventListener` (DNS/connect/TLS timing, TLS keylog wiring) | No — host app must call `okHttpBuilder` on each `OkHttpClient.Builder` it constructs. OkHttp has no global interceptor hook. |
| `HttpURLConnection` | `HttpUrlConnectionWrapper.install()` (via `captureHttpUrlConnection = true`) installs a `URLStreamHandlerFactory` | Yes, process-wide, but **one-shot per JVM** — loses the race if another library installs a factory first (logged, not thrown; see `HttpUrlConnectionWrapper`'s kdoc). |
| Cronet | `RequestrSdk.cronetEngineBuilder(context, builder)` (via `captureCronet = true`) | No — host app must call it on its own `ExperimentalCronetEngine.Builder`. Requires the host app to depend on `play-services-cronet` (`compileOnly` in this module). |
| Ktor | `RequestrSdk.installKtorPlugin(config)` (via `captureKtor = true`) | No — host app must call it inside its own `HttpClient { ... }` block. Requires the host app to depend on `ktor-client-core` (`compileOnly` in this module). |

Every stack funnels into the same `RequestrObserver` batching/auth/retry pipeline and posts to
requestr's `POST /api/observe`, which persists rows under a dedicated `CaptureTier.SDK` capture
session so they appear in the Intercept traffic list with a cyan **SDK** badge.

## TLS trust (so the host app's own HTTPS calls succeed against requestr's MITM CA)

Only relevant if the host app is also being intercepted by requestr's own VPN/root/Shizuku
capture (i.e. requestr is MITM-ing the host app's traffic on the wire, not just receiving SDK
reports of it). See `TrustHelper.kt`'s full kdoc for three options:

- **Network Security Config, debug builds**: trust user-installed CAs via `<debug-overrides>`
  (see `sdk/src/main/res/xml/network_security_config.xml` for a ready-to-copy template).
- **Bundle requestr's exported CA PEM** into `res/raw/requestr_ca.pem` and reference it from a
  `<base-config>` — works in release builds too, no on-device CA install needed.
- **Programmatic**: `TrustHelper.createTrustManagerWithCa(caPem)` / `createSslContextWithCa(caPem)`
  for apps that construct their `SSLContext`/`OkHttpClient` without a manifest-driven NSC.
  `TrustHelper.allTrustingTrustManager()` is an explicit, documented escape hatch for apps that
  can't do either — debug/QA only, never production.

## TLS keylog (bonus signal for requestr's own wire-level decoders)

`enableKeyLog = true` starts `KeyLogProducer`'s Cronet keylog file tail and wires TLS-secret
reflection into the OkHttp `EventListener`. Every call into
`com.example.interception.tls.TlsKeyLogRegistry` (owned by requestr's `:app` module, not a
compile-time dependency of this SDK) goes through a `Class.forName` reflective shim
(`KeyLogRegistryBridge`) that degrades to a silent no-op if that class isn't on the host app's
classpath — the common case for every third-party app. **Body capture works identically with
or without this enabled** — keylog production is purely a bonus signal for requestr's
independent QUIC/TLS 1.3 decoders (which can't compute post-handshake secrets by passively
observing the wire), never a prerequisite for the SDK's own reporting pipeline.

Honest caveat, confirmed via a live JVM handshake probe against `conscrypt-openjdk-uber:2.5.2`
(see `SslSecretExtractorTest`'s kdoc): that exact build's `NativeSsl` class exposes no
client-random/master-secret/traffic-secret accessor at all, so `SslSecretExtractor` correctly
returns `null` against it. Android's own bundled Conscrypt fork may differ; this has not been
verified on a real device.

## Local AAR / module consumption

If consuming `:sdk` as a Gradle module from a sibling repo/checkout:

```kotlin
// settings.gradle.kts
includeBuild("../requestr") {
    dependencySubstitution {
        substitute(module("com.requestr:sdk")).using(project(":sdk"))
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":sdk"))
}
```

Or build a standalone AAR and drop it into `libs/`:

```
./gradlew :sdk:assembleRelease   # -> sdk/build/outputs/aar/sdk-release.aar
```

```kotlin
dependencies {
    implementation(files("libs/sdk-release.aar"))
    // plus this module's own runtime deps: okhttp, kotlinx-coroutines-android/core
}
```

## Verification harness

`VerificationHarness.run(context, targetUrl)` exercises the full round trip end-to-end against
a real running requestr instance: an OkHttp POST + an `HttpURLConnection` GET, then polls
requestr's own `GET /api/captures` for a unique marker to confirm the events actually made it
through `LocalApiServer` into the `captured_traffic` table and back out over HTTP — not just
that the SDK-side calls didn't throw. Call it from a hidden debug Activity/button in a test
harness app; see its kdoc for the exact fields on `HarnessResult`.

## Module layout

- `RequestrSdk.kt` — one-line `init()` facade (this file).
- `RequestrObserver.kt` — batching/auth/retry core; every stack-specific interceptor calls into
  this.
- `OkHttpInterceptor.kt`, `HttpUrlConnectionWrapper.kt`, `CronetInterceptor.kt`,
  `KtorInterceptor.kt` — per-stack instrumentation.
- `KeyLogProducer.kt` / `SslSecretExtractor.kt` — TLS keylog production (task 8).
- `TrustHelper.kt` — CA trust helpers (task 9).
- `VerificationHarness.kt` — end-to-end integration check (task 11).
