# Interceptor Architecture Master Program — requestr

> **TL;DR:** Close every real gap between the interceptor dossier and the code on `vorflux/fix-audit-gaps` (`b71d61f`) by rebuilding the relay/persistence foundation first, then the rule engine, protocol persistence, inspector/export, replay, the 15-screen UI program with a responsive-tab invariant, and the test/CI/device harness that proves the numeric gates.

## What the audits got wrong (corrected against source)

| Audit claim | Truth in source | Disposition |
|---|---|---|
| FGS must change `specialUse` → `dataSync` ("release blocker") | `AndroidManifest.xml:71` declares `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; `dataSync` carries the API-34 6 h/day cap | **Keep `specialUse`.** No change. Remove the unused `FOREGROUND_SERVICE_DATA_SYNC` permission (line 18) only. |
| PCAP export missing | `packet/PacketCaptureEngine.kt` writes real `.pcap` via `MediaStoreExporter`; `PacketPump.kt:341/449`; prefs + UI wired | Keep; harden (UID attribution metadata + Wireshark gate). |
| `getConnectionOwnerUid` / `onRevoke` missing | `MitmProxyEngine.kt:524`, `ProcNetUidResolver.kt`; `RequestrVpnService.kt:591` | Keep. |
| Cronet instrumentation missing | `runtime/http/CronetInstrumentation.kt`, `HttpStackDetector.kt:45`, registered in `RuntimeInstrumentationEngine.kt` | Keep. |
| WebSocket/gRPC rows missing | WS frames already persist as rows (`CaptureEngine.persistWsFrame`, `protocol="WEBSOCKET"`); gRPC trailers at `CaptureEngine.kt:118/493/510` | Keep rows; add message-level tables + viewers. |
| Chunked trailers / DiffView / HTTP3 honest states missing | `HttpParser.kt:269–308`; `ui/components/DiffView.kt`; `Http3DetailSheet.kt`, `TlsFailureDetailSheet.kt`, `RelayHealthCard.kt` | Keep. |
| "FTS5 search exists" / "body spill exists" | No FTS anywhere; only `Daos.kt:50` `LIKE` query. `HttpBodyDecoder.kt:92` truncates at 2 MiB with a flag — no file spill | Both are **new work**. |
| "Gateway/Mirror/regex groups complete" | `RuleAction` (`Entities.kt:214`) has no MIRROR/BREAKPOINT/THROTTLE/SUSPEND/ALLOW_ONLY/BYPASS; no `MirrorEngine`; no `$group` substitution; `GatewayEngine.kt` is LAN proxy sharing, a different feature | New work. |
| Upstream cert SAN extraction missing | `RemoteCertificateMirror` + `CertificateAuthority.issueMirroredLeaf` exist but gated behind default-off `remote_cert_mirror_enabled` (`MitmProxyEngine.kt:275`) | Harden into the automatic IP-CONNECT path. |

Audit percentages and effort-hour figures are discarded; ordering here follows architectural dependency, not audit severity.

## Biggest real gaps

- **No bounded relay concurrency.** `MitmProxyEngine.kt:177` does `launch { handleClient() }` per connection on `Dispatchers.IO`; zero `Semaphore`/`limitedParallelism` in the repo. Room writes happen inline via `runBlocking { dao.insert() }` on relay coroutines.
- **No spill, no index, no pruning.** Bodies truncate; search is an in-memory filter (`data/search/TrafficSearch.kt`) over a `LIKE` query; sessions grow unbounded.
- **Rule engine is first-match, single-action.** `RuleEnforcer.getMatchingRule` iterates `getEnabledRules()` with no ordering column, no provenance (`appliedRules` absent), and two divergent `RuleAction` enums (`data/local/Entities.kt:214` persisted vs `data/models/AppModels.kt:381` UI-only with a dead `BREAKPOINT`).
- **No mirror, breakpoint, access-gate, or bandwidth throttle.** `NetworkConditionInjector` does latency/jitter/loss only — no token bucket, so the ±20 % throttle gate is unmeasurable today.
- **No test/CI infrastructure.** No `.github/workflows`, no macrobenchmark module, `androidTest` holds only `ExampleInstrumentedTest.kt`, and roborazzi is a dependency with zero screenshot tests.
- **Tab invariant unimplemented.** `RequestrSegmentedTabs` (`CommonComponents.kt:1474`) is a plain `Row` with no horizontal scroll or trailing fade; Material3 `TabRow` is still used in `ApiTestrScreen.kt:463/834` and `FlowScreen.kt:57`.

## Release slices

| Slice | Content | Gate that closes it |
|---|---|---|
| **S1 Foundation** | Bounded relay dispatcher, per-flow buffer caps, write queue, body spill, Room v19, backup/rollback, relay health metrics | 32–64 relay tasks; 512 KB–2 MB per-flow; spill 1–2 MB; Phase-0 ≤ 20 % CPU @ 20 flows; 60-min soak, no OOM, no FGS death on API 34 |
| **S2 Index & sessions** | FTS5 + query language, session pruning/quotas, session workspace UI | Body/header search on 100 k rows; pruning keeps caps; 1,000 WS messages render < 2 s |
| **S3 Rule engine** | Unified rule model v20, ordering/priority, provenance badges, access gate, mirror, rewrite regex groups, mock, throttle shaper, breakpoint, script hardening | Rule eval p95 < 1 ms @ 50 flows/s; throttle ±20 % on 10 MB; paused flow blocks only itself; script cannot hang a relay past timeout |
| **S4 Protocol & inspector** | WS/SSE/gRPC/HTTP3 message tables + four viewers, protocol-adaptive inspector, timings, HAR import/export, ≥5 snippet languages, PCAP UID metadata | Timings within 10 ms of tcpdump; HAR round-trip parity; mitmproxy diff clean; nghttp2 fuzz corpus green |
| **S5 Replay & UI program** | Replay 255-cap + queue, responsive tab row rollout, remaining screens from the design program | 255 concurrent replay with 100 flows + queue drain; tab invariant screenshot tests at 1.0/1.3/2.0 font scale |

Feature flags exist **only** to activate finished work safely (`relayPoolBounded`, `bodySpill`, `ftsSearch`, `ruleEngineV2`, `breakpoints`, `replayQueue`). Each flag ships with its UI complete, defaults off for exactly one slice, flips on after its gate passes, and is deleted in the following slice. No flag may hide partial UI.

## Rollback and recovery

- Migrations are additive only (nullable columns, new tables); `MIGRATION_18_19` and `MIGRATION_19_20` follow the existing Robolectric `MigrationXToYTest` pattern.
- Before the first migration of a slice, copy the DB to `databases/requestr-pre-v<N>.bak`; ship `fallbackToDestructiveMigrationOnDowngrade()` in S1 so an APK rollback cannot brick the app, and restore the backup when a downgrade wipe is detected.
- Spill files live under `filesDir/captures/<session>/<id>.<req|res>.bin`; a startup sweep deletes orphans whose row is gone, and a row whose file is missing renders "body evicted", never a crash.
- Every new engine is a separate object behind its flag, so rollback is a flag flip plus a data-compatible previous build.

## UI program

All 15 sections in `/code/.plans/designs/design-plan.json` become real screens (capture workspace, inspector, filters/search/sessions, rule library, five rule editors, breakpoint workspace, WS/SSE/gRPC/HTTP3 viewers, diagnostics). `InterceptScreen.kt` (5,095 lines) splits along those seams. The invariant is enforced by a new `RequestrResponsiveTabRow`: intrinsic width, single line, never wrap, horizontal scroll with a visible trailing fade/peek; `RequestrSegmentedTabs` stays for ≤4 equal-weight segments.

## Blocking questions for the user

1. **Breakpoint + script exposure on mobile** — ship enabled, ship behind an explicit developer opt-in with a per-session cap, or build engine now and expose in a later release?
2. **UDP/443 (QUIC) policy default** — drop with a visible "QUIC blocked → h2 fallback" badge (Reqable's behaviour), tee-and-passively-decode when keys exist, or make it a user setting defaulting to drop?
3. **Device lab for the gates** — the CPU/soak/throttle/timing gates need real hardware; Firebase Test Lab, a self-hosted device, or emulator proxy-mode only with device gates deferred?

## Excluded on purpose

Already complete (evidence above): `specialUse` FGS, PCAP export, Cronet instrumentation, `onRevoke`, UID resolution, chunked trailer parsing, DiffView, HTTP/3 honest-state components, WS/gRPC generic rows.
Research-only by dossier design: active QUIC/HTTP-3 MITM (D.1), eBPF capture, root/Shizuku/Frida tiers beyond existing probes, and emulator VPN/tun capture (Redroid cannot create an interface — never a release blocker).
