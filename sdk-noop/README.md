# requestr Observer SDK — no-op counterpart (`:sdk-noop`)

Same-package (`com.requestr.sdk`), empty-behavior counterpart to [`:sdk`](../sdk/README.md).
A host app selects **exactly one** of `:sdk` / `:sdk-noop` as a build-variant dependency, e.g.:

```kotlin
// host app's build.gradle.kts
dependencies {
    debugImplementation(project(":sdk"))
    releaseImplementation(project(":sdk-noop"))
}
```

so a release/production build compiles against `:sdk-noop`'s identical class/function
signatures but pulls in none of `:sdk`'s actual interception/capture logic (no OkHttp
interceptor, no `URLStreamHandlerFactory` install, no gRPC/WebSocket capture, no network calls
to requestr's Local API at all) -- the equivalent of `jainsahab/AndroidSnooper`'s
`Snooper-Okhttp-No-Op` module pattern (see `docs/plans/MASTER-FEATURE-ADDITION-PLAN.md` §3).

Every method mirrored below has **the exact same signature** as `:sdk`'s -- host-app source
code that calls `RequestrSdk.init(...)`, `RequestrSdk.okHttpBuilder(...)`,
`RequestrObserver.observe(...)`, etc. compiles and runs unchanged against either module. The
only behavioral difference is that this module never captures, batches, or transmits anything.

## What's mirrored, and what's intentionally not

Mirrored (the documented embedding surface a host app's own source code calls or references):

- `RequestrSdk` (facade) + `RequestrSdk.Options` -- every flag accepted, none has any effect.
- `RequestrObserver` + `FlushStatus` / `TimingBreakdown` / `ObserverEvent` -- `observe`/
  `observeDetailed`/`flush` are no-ops; `lastFlushStatus` stays `Idle` forever;
  `isApiReachable()` always returns `false`.
- Per-stack capture entry points, each a no-op mirror of its `:sdk` equivalent:
  `OkHttpInterceptor.kt` (`addRequestrInterceptor`, `RequestrOkHttpInterceptor`,
  `RequestrEventListener`), `HttpUrlConnectionWrapper.kt` (`install` always returns `false`,
  never installs a factory), `CronetInterceptor.kt` (`addRequestrInterceptor`,
  `RequestrCronetCallback`, `CronetInterceptor.isCronetAvailable` always `false`),
  `KtorInterceptor.kt` (`installRequestrPlugin`, `RequestrKtorPluginConfig`,
  `RequestrKtorPlugin`), `GrpcInterceptor.kt` (`addRequestrInterceptor`,
  `RequestrGrpcInterceptor` -- Phase 6), `WebSocketInterceptor.kt` (`newRequestrWebSocket`,
  `RequestrWebSocketListener`, `RequestrWebSocket` -- Phase 6).

Intentionally **not** mirrored -- `KeyLogProducer`, `SslSecretExtractor`, `TrustHelper`,
`VerificationHarness`. These are internal implementation details (TLS keylog production, CA
trust helpers, and a self-test harness) that `:sdk`'s own README documents as either purely
internal (`KeyLogProducer`/`SslSecretExtractor`, only ever reached through
`RequestrSdk.init(..., enableKeyLog = true)`) or orthogonal to interception/capture entirely
(`TrustHelper` is about making the *host app's own* HTTPS calls trust a custom CA -- nothing to
do with reporting traffic to requestr, so there's no "no-op" version of it that would make
sense). A host app that genuinely needs `TrustHelper` in a release build should depend on it
directly rather than through this swap.

## Dependency shape

Same `compileOnly` shape as `:sdk` for the optional third-party stacks (`ktor-client-core`,
`play-services-cronet`, `io.grpc:grpc-api`) -- for the exact same reason: an app that never
touches one of those stacks never pays for (or crashes on) a missing class, and this module's
entire point is to cost *even less* than `:sdk`, never more.
