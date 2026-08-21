# Runtime instrumentation, browser bridge, and lineage diagnostics

> **TL;DR:** Replace the four remaining fake/heuristic subsystems in the runtime scope — presence-only Cronet/WebView adapters, User-Agent-only stack detection, time-windowed connection lineage, and the single-tab request-stage-only CDP bridge — with real capture paths that emit real events, persist real rows, and are visible/controllable in Settings.

## What is broken today

| Area | Current behaviour (evidence) | Target |
|---|---|---|
| Cronet adapter | `start()` is `Class.forName("org.chromium.net.CronetEngine")`, `pollEvents()` returns `emptyList()` (`HttpStackAdapters.kt:56-68`). Cronet is not even a dependency. | requestr-owned `CronetEngine` with `RequestFinishedInfo.Listener` + `UrlRequest.Callback` body capture, feeding real `InstrumentationEvent`s and Room rows. |
| WebView adapter | Same `Class.forName` shell (`HttpStackAdapters.kt:70-84`). There is no WebView anywhere in the app. | requestr-hosted inspector WebView: `shouldInterceptRequest` capture, MAIN-world `fetch`/`XHR` bridge for bodies, `ProxyController` routing through the MITM proxy, cookie visibility. |
| S2 stack detection | `detectFromHeaders` is a `when` over User-Agent prefixes; custom UA → `UNKNOWN`; Volley deliberately not distinguished (`HttpStackDetector.kt:59-90`). | Multi-signal, confidence-scored verdict: JA3, ALPN, HTTP/2 SETTINGS, header order, cache-validation shape, runtime stack frames, plus an on-device self-calibration probe that records each stack's real fingerprint. |
| D2 lineage | HTTP hop matched by `getByHostInWindow(sessionId, host, anchorTs, anchorTs + 30_000)`; TLS hop falls back to `getLatestBySniHost` because MITM keys TLS rows `"$host:$port"` while the tun path uses `TcpStream.key` (`ConnectionLineage.kt:190-235`, `Daos.kt:30-40,308-313`). | One canonical `ConnectionKey` used by every plane, an explicit alias registry for the MITM/gateway/SDK legs, and exact key joins. No time window. |
| Browser bridge | `Fetch.enable` uses `requestStage:"Request"` only, so `MODIFY_RESPONSE` silently falls through to `continueRequest` (`CdpFetchAdapter.kt:113`). Client attaches to the first `type=="page"` target, never sends a `sessionId`, and has no reconnect. Initiator is smuggled as `"X-Requestr-Initiator"` (`CdpNetworkAdapter.kt:121`). Bodies are never fetched (`requestSize = 0`). | Response-stage `Fetch` with `getResponseBody`/`fulfillRequest`, flattened multi-target attach, reconnect with backoff, a first-class `initiator` field on `HttpEvent`, real IndexedDB records and DOMStorage change events. |

## Approach

Nine work items in three independent tracks that can be built in parallel:

- **Runtime track (W1-W3):** extend the adapter event contract so it can carry bodies and sizes, add a Room sink so runtime captures are visible in the traffic list, then build the real Cronet and WebView adapters on top.
- **Detection track (W4):** a new `StackFingerprinter` with a capture-time signal store and a device-local calibration probe, replacing the single-signal detector while keeping its API shape.
- **Correlation track (W5-W9):** canonical connection identity and exact-key lineage, then the CDP work (initiator schema field, multi-target client, response-stage Fetch, storage streaming).

Two shared-file collisions to coordinate: `SettingsScreen.kt` (three cards change) and the transport plan's `CapturedTrafficEntity.connectionId` column plus Room version bump, which W6 consumes but does not define.

## Confirmed decisions (no open blockers)

| # | Decision | Why |
|---|---|---|
| DEC-1 | Ship `org.chromium.net:cronet-embedded:143.7445.0` | The only artifact where `setProxyOptions`/`ProxyOptions`/`Proxy` all exist (verified by `javap`), and proxy routing is the whole point of W2. Self-contained, so it works without GMS on rooted/Redroid devices. |
| DEC-2 | WebView host = offscreen `WebView` driven from a URL field in the Settings card | Gets real `shouldInterceptRequest` capture, proxy routing, and cookie visibility with no new navigation surface. |
| DEC-3 | Add `CaptureTier.RUNTIME` | Column is TEXT, so no migration. In-process instrumentation and third-party SDK reports are different planes with different blind spots; folding them together is the blurring that made the trackers untrustworthy. |
| DEC-4 | Lineage joins on exact `connectionId` only — the 30-second window is removed from the lineage path | A window that silently attaches the wrong HTTP row fabricates a causal chain the data doesn't support. Honestly reporting an unattached row is strictly better. |

**DEC-1 size implication.** Roughly +13.7 MB on the 28 MB debug APK, accepted. The cost is entirely the four `libcronet` ABI slices (`classes.jar` is only 651 KB), so for a release variant an ABI split, per-variant `abiFilters`, or an App Bundle reduces it to one slice — but `x86`/`x86_64` must stay in debug or Cronet breaks on emulators and x86 Redroid. The AAR's `proguard.txt` is consumed automatically, so no manual R8 keep rules are expected; a minified release build should still be smoke-tested against a live Cronet request. Configuring splits/bundles is out of scope and flagged, not silently added. Do not downgrade the Cronet version to save size: `141.7340.3` lacks the proxy classes and `119.6045.31` lacks `setProxyOptions` entirely.

**DEC-2 follow-up, recorded so it reads as a choice not an omission.** The full in-app inspector screen — a twelfth `Screen` route beside the eleven in `MainActivity.kt:261-279` — is deliberately deferred. The reason is sequencing, not feasibility: a new full-screen destination needs its own design cycle, and gating the capture work on it would leave the Cronet/WebView adapters presence-only shells for another release. W3 keeps `WebViewInstrumentation`'s public surface free of Settings-specific assumptions so that screen later becomes pure presentation over already-working capture, with no rework.

**DEC-4 conditions, both binding requirements of W6.** First, `unattachedHttpRows` is user-facing, not just a struct field: the count *and* its cause are aggregated through `VisibilityAnalyzer` and rendered in the Settings "OBSERVATION REPORT" card, so a change that adds the field but skips the render does not satisfy the decision. Second, a routinely non-zero count is a threading bug, not a steady state — only HAR imports and SDK `/api/observe` reports are legitimately unattached; anything else means a producer is writing null and the fix is that producer, never widening the match. Both are pinned by tests, including a zero-unattached assertion across the threaded planes. The `getByHostInWindow` DAO method may survive if another caller legitimately uses it, but no path reachable from `ConnectionLineage.build` may call it, and a later commit reinstating it there is a regression.

## UI handoff

A design subagent is dispatched for the WebView inspector surface and the expanded Runtime Instrumentation / Browser Bridge Settings cards. W2, W3, and W9 are written to treat those mockups as the visual spec: match them for layout, labels, control placement, and status affordances. Where a mockup and the plan disagree on presentation the mockup wins; where they disagree on behaviour or data the plan wins. One behavioural rule overrides presentation everywhere: no label or counter may imply capture that isn't happening — that defect is exactly what `SettingsScreen.kt:736` encodes today.

## Cross-plan sequencing

W5/W6 land after the transport plan's `connectionId` threading. This plan consumes transport's schema and never defines it: Room version 9, `MIGRATION_8_9`, `CapturedTrafficEntity.connectionId`, `TlsSessionEntity.failureReason`/`failedAt`, and the `TlsHandshakeState.FAILED` writers all belong to transport.
