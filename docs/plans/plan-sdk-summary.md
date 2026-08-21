# SDK & CONNECT Scope — Summary

> **TL;DR:** Replace three opaque CONNECT tunnels with a rule-driven `ConnectControl` subsystem backed by Room persistence and surfaced in the Intercept UI; transform the thin `RequestrObserver` SDK into a real embeddable instrumentation library that captures decrypted HTTP bodies, produces TLS key material into the transport scope's `TlsKeyLogRegistry`, and authenticates to requestr via a token-pairing handshake.

## ITEM 1: CONNECT Control

### Problem
Every CONNECT tunnel in the codebase is blind: `MitmProxyEngine` accepts CONNECT and MITMs (checking only BLOCK), `HttpProxyShared` says "Opaque CONNECT tunnel (no MITM/inspection)" and byte-relays with zero observation, `GatewayEngine` inherits the same opacity. No CONNECT attempt is persisted, inspected, or rule-controlled.

### Approach
1. **Add `FORCE_MITM` to `RuleAction`** (in `Entities.kt`) — one new enum value, no new rule system.
2. **New `ConnectAttemptEntity` + `ConnectAttemptDao`** — Room-persisted CONNECT records with target host/port, client identity (UID or source IP), disposition, bytes, ALPN, and failure reason. The transport scope owns the final schema version — this is a table description only.
3. **New `ConnectControl` singleton** — evaluates CONNECT rules via the existing `RuleEnforcer` and `intercept_rules` table BEFORE the `200 Connection Established` response, returning `AllowOpaque | ForceMitm | Block | Redirect(host:port)`.
4. **Replace all three call sites** (`MitmProxyEngine.handleClient()`, `HttpProxyEngine.handleClient()`, `GatewayEngine.handleClient()`) to use `ConnectControl.evaluate()` + the new `HttpProxyShared.handleConnectAttempt()` with byte counting. The "opaque tunnel" comments are DELETED.
5. **"Print":** CONNECT tab in the existing ConnectionsBottomSheet (following the TCP/UDP/QUIC/TLS/Provenance tab pattern), with disposition badges, byte counts, and a detail sheet.
6. **"Iter":** `ConnectReplay` utility to re-issue a captured CONNECT with modified target, port, or force-MITM disposition.

## ITEM 2: Third-Party App Decryption SDK

### Problem
The `sdk/` module (`RequestrObserver`) only relays URL/method/statusCode — no body capture, no HTTP stack instrumentation, no TLS keylog, no authentication. `OkHttpProxyInterceptor` is attached only to requestr's own `ApiTestrRepository`. `LocalApiServer` accepts unauthenticated POSTs from any local process.

### Approach
1. **Token-based pairing:** `LocalApiServer` generates a random bearer token on startup, stored in DataStore. `/api/observe` requires `Authorization: Bearer <token>`. Settings UI exposes the token with a timed "Show" toggle and copy-to-clipboard. The MV3 browser extension shares the same scheme.
2. **Expanded SDK instrumentation:**
   - **OkHttp:** `OkHttpClient.Builder.addRequestrInterceptor()` — captures pre-encryption request bodies, post-decryption response bodies, headers, TLS session info, timing. A companion `EventListener` captures DNS/connect/TLS timing.
   - **HttpURLConnection:** `URL.setURLStreamHandlerFactory()` wrapper — intercepts all `http://`/`https://` connections in the process.
   - **Cronet:** `CronetEngine.Builder.addRequestrInterceptor()` — configures `ssl_key_log_file` for TLS keylog, wraps `UrlRequest.Callback` for body capture.
   - **Ktor:** plugin via `HttpClientEngine` wrapper.
   - Expanded event schema includes `requestBody`/`responseBody` (base64), `stackFrames`, `httpStack`, TLS metadata.
3. **TLS keylog production:**
   - **Cronet:** monitors the `ssl_key_log_file` and calls `TlsKeyLogRegistry.importNssKeyLog()`.
   - **OkHttp (Conscrypt):** reflects into `ConscryptEngineSocket` internals to extract master secret + client random, derives traffic secrets via HKDF, records via `TlsKeyLogRegistry.record()`.
   - The `TlsKeyLogRegistry` interface is consumed as frozen by the transport scope — never redefined.
4. **Trust of requestr's CA:** documentation for network security config (`debug-overrides` + `trust-anchors user`) or programmatic `AllTrustManager` for debug builds.
5. **First-class capture plane:** `ObserverSource.SDK` added to `CorrelationEngine`, `TrafficSource.SDK` badge in `InterceptScreen` (matching the GATEWAY badge pattern), SDK-persisted `CapturedTrafficEntity` rows under `CaptureTier.SDK`.
6. **One-line `init()` + verification harness:** `RequestrSdk.init(context, pairingToken, options)` — local AAR consumption, `VerificationHarness.run()` proving decrypted bodies actually arrive in requestr.

## Work Items

| # | Item | Dependencies |
|---|------|-------------|
| 1 | `FORCE_MITM` + `ConnectAttemptEntity` + DAO | — |
| 2 | `ConnectControl` evaluation point | 1 |
| 3 | Replace opaque tunnel in MitmProxyEngine, HttpProxyEngine, GatewayEngine | 2 |
| 4 | CONNECT tab in ConnectionsBottomSheet | 3 |
| 5 | CONNECT replay (`ConnectReplay`) | 3 |
| 6 | Token-based pairing/auth for `/api/observe` | — |
| 7 | Expand SDK: OkHttp, HttpURLConnection, Cronet, Ktor instrumentation | — |
| 8 | TLS keylog production → `TlsKeyLogRegistry` | 7 + transport scope |
| 9 | Trust helper + NSC guidance | — |
| 10 | SDK capture plane in UI + CorrelationEngine | 6, 7 |
| 11 | Integration story: init(), AAR, verification harness | 7, 8, 9, 10 |

Items 1 and 6 are parallel; items 7 and 9 are parallel with 6. Item 8 blocks on the transport scope shipping `TlsKeyLogRegistry`.
