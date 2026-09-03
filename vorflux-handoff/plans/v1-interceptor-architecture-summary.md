# v1 — Interceptor Architecture Master Program · Summary

> **TL;DR:** One dependency-ordered program — **67 tasks, nine slices (S0–S8), 30 measurable gates, schema v19–v24, 14 locked decisions, three deliberate exclusions, no open questions** — that rebuilds requestr's capture foundation, terminates and translates HTTP/2, adds typed staged rules, normalized protocol persistence, API-Testr HTTP/3 sending, complete export/replay/report tooling, advanced pinning and trust setup, and 20 complete interceptor surfaces on a measured-overflow responsive-tab primitive.

## Current-state corrections

The dossier is a target, not a source-of-truth inventory. The plan preserves mature work and fixes the real gaps:

- Keep `specialUse` foreground-service typing, `onRevoke`, classic PCAP, Cronet, UID APIs, existing QUIC/QPACK codecs, chunked-trailer boundary parsing, `DiffView`, Rhino limits, chaos bounds, and current honest-state panels.
- Native-bridge mode currently drops all UDP, including DNS, and loses hostname/UID/raw-packet visibility; it needs a focused pinned tun2proxy fork rather than config-only changes.
- `MitmProxyEngine` remains CONNECT-only, unbounded, thread-heavy, and synchronous on persistence; transparent/root/device-proxy traffic and backpressure need a real front-door rewrite.
- HTTP/2 is observe-only; no rule runs on h2. The program adds downstream termination, h2↔h1.1 translation, flow control, ordered duplicate headers/trailers, serialized writes, and staged-rule parity.
- Persistence is v18 with inline/truncated bodies, map headers, no protocol child tables, single writer, FTS5, spill lifecycle, or quotas.
- Rules are unordered first-match rows with relay-path `runBlocking`; the replacement is a typed ordered Gateway → Mirror → Rewrite → Breakpoint → Script → Mock → BodyCrypto → Shaping pipeline plus Observe and per-flow provenance.
- Cronet exists, but API-Testr cannot select or guarantee H3, does not attach request bodies on that path, and loses negotiated-protocol evidence.
- Frida/root trust foundations exist only partially; the advanced pinning tier is completed rather than classified as research.
- Fixed/equal-width tab sites compress labels; all unbounded tab collections migrate to intrinsic, single-line, measured-overflow strips.

## Locked architecture

1. Vendor a focused pinned tun2proxy fork with authenticated SOCKS5 `+info`, virtual DNS, packet tee, explicit UDP policy, protected sockets, and stable JNI events.
2. Rebuild root transparent capture with `SO_ORIGINAL_DST` and transactional IPv4/IPv6 rule teardown; keep Shizuku control-plane only; retire `TunRelay` only after tee parity and rollback proof.
3. Ship breakpoint and scripting tools behind developer acknowledgement, selected-target restrictions, a persistent safety notification, pause caps, and a global kill switch; host Rhino in killable `android:process=":scripthost"`.
4. Use a Room-compatible bundled SQLite runtime to guarantee FTS5; normalize legacy WS/gRPC rows; include native PCAPng, all 20 snippet targets, and Report Server.
5. Verify on Firebase Test Lab plus a self-hosted rooted physical device with Wi-Fi and cellular.
6. Apply QUIC policy only to the capture scope: bypassed/out-of-scope UDP passes untouched and unrecorded; selected targets default to tee-then-drop UDP/443 for honest h2 fallback; Observe-QUIC tees then passes; only real keys permit a `decoded` state.
7. Terminate HTTP/2. Keep the in-house codec only if conformance/fuzz gates pass; otherwise use Netty for the client leg behind the same event model.
8. Migrate legacy rules into the typed engine; persistence owns normalized protocol tables and `applied_rules`.
9. Import API/workspace envelopes v1 or v2 and emit v2 only.
10. Keep new settings in `SettingsDataStore` or dedicated stores; do not expand `UnifiedDataStore`.
11. Responsive tabs use measured overflow, selected-item auto-scroll, no `weight`, no wrapping, and all six glass modes.
12. Include the script console, replay queue/results, and diff workspace as complete screens.
13. Ship opt-in Frida/system-CA pinning defeat with versioned checksummed scripts; expose Xposed only when a supported framework is detected; never claim decryption without decrypted bytes.
14. Treat user/system CA, emulator proxy, browser-first validation, and Chrome/Firefox trust remediation as product work with an acceptance gate and runbook.

## Schema and release sequence

| Version | Scope |
|---|---|
| v19 | Ordered duplicate headers/trailers, real timings, `applied_rules`, body references, API-Testr protocol preference, negotiated-protocol evidence |
| v20 | WebSocket, SSE, gRPC, and HTTP/2 event tables |
| v21 | Idempotent legacy WS/gRPC backfill |
| v22 | FTS5 `traffic_fts` with `unicode61`, triggers, and eager population |
| v23 | Typed rules, folders, paused flows, replay jobs/results; legacy rule table read-only |
| v24 | Drop legacy `intercept_rules` only after wire-parity evidence |

| Slice | Delivery |
|---|---|
| S0 | Vendored fork, JNI events, bundled SQLite, socket protector, CI and downgrade-safe DB builder |
| S1 | Multi-protocol `LocalGateway`, 32–64 relay pool, certificate engine, scoped UDP/QUIC policy, UID/lifecycle/PCAPng/trust hardening |
| S2 | v19, single-writer queue, 1–2 MB body spill, measured timings, recovery and quotas |
| S3 | v20–v22, protocol writers/backfill/FTS5, H2 conformance decision and termination/translation |
| S4 | v23–v24 typed rule pipeline, provenance, mirror/rewrite/mock/crypto/throttle/breakpoint, isolated script host, h1/h2 parity |
| S5 | HAR and full cURL, 20 snippets, PCAP/PCAPng, 255-cap replay and diff, authenticated Report Server single-shot repeat, API-Testr AUTO/H1/H2/H3 |
| S6 | Responsive-tab primitive, app-wide tab migration, `InterceptScreen` extraction, all 20 screens and API-Testr extension |
| S7 | Root/Shizuku rebuild, advanced pinning, system trust, guided setup, parity-gated `TunRelay` retirement |
| S8 | CI, migration/fuzz/screenshot/accessibility/benchmark/soak and physical-device lanes throughout |

## Complete screen and design program

The plan maps **22 paired light/dark design sections (44 validated HTML artifacts)** into production:

- capture workspace, transaction inspector, session workspace;
- full ten-stage rule library and Gateway/Mirror/Rewrite/Mock/Throttle editors;
- breakpoint workspace, script console, replay queue/results, diff workspace;
- WebSocket, SSE, gRPC, and HTTP/3 viewers;
- diagnostics, Pinning & System Trust, and guided setup/remediation;
- responsive-tab state board and API-Testr protocol extension.

Every screen uses the current theme, icons, density, motion, haptics, and six glass modes. Labels remain single-line at intrinsic width; overflow is measured with `scrollState.maxValue`, scrolls horizontally with an affordance, and auto-scrolls the selected tab into view. No stub or “coming soon” state is permitted; genuine device limitations must state the reason and remediation.

## Required gates

The 30 named gates include the dossier's exact quantitative targets:

- **32–64** active relay tasks with queued excess;
- body spill at **1–2 MB** and per-direction flow buffers capped at **512 KB–2 MB**;
- replay ceiling **255** with visible FIFO queue;
- **1,000 WebSocket messages under 2 seconds**;
- rule evaluation **p95 < 1 ms at 50 flows/s**;
- throttle within **±20%**;
- timing within **10 ms** of external capture;
- Phase-0 **≤20% CPU at 20 concurrent flows**;
- **60-minute API-34 soak** with no OOM or foreground-service death.

Additional gates cover H2/nghttp2 and mitmproxy parity, H3 hard-requirement versus recorded AUTO downgrade, capture-scoped QUIC states, 100k-row FTS5 correctness, migration/downgrade safety, script process kill/restart, paused-flow isolation, HAR/cURL/snippet/PCAPng parity, single-request Report Server repeat, rooted pinning fixtures, trust setup, root teardown, and tee parity before legacy retirement.

## Deliberate exclusions

Only three items are excluded:

1. Active QUIC/HTTP-3 MITM, which remains an open problem; tee-first passive decode, scoped h2 fallback, SDK keylogs, and the advanced pinning tier cover shippable behavior honestly.
2. eBPF capture, which is unavailable to a normal shipping Android app; rooted transparent capture covers the practical use case.
3. Emulator VPN/tun capture, because Redroid cannot create the interface; emulator proxy mode is supported and documented, and this limitation never blocks release.

Everything else identified by the dossier is included or explicitly retained because current source already implements it.