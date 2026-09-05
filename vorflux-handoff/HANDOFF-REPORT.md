# Vorflux Session Handoff — requestr Interceptor Architecture (S0)

This document is a complete handoff for the next agent. It covers what the main
agent was doing, what each sub-agent did, the exact state of the working tree,
what was verified, and what remains open. Read this first.

> Session ID: c0c37749-3a00-45dc-91a4-9cbae952b604
> Branch: `vorflux/interceptor-s0-runtime` (stacked on `vorflux/fix-audit-gaps`)
> Root commit for this branch: `b71d61f`
> Parent (PR #17): https://github.com/Mahesh953-hub/requestr/pull/17
> `origin/main` was at `48cb477` when S0 branched off.

---

## 0. Status update — 2026-09-05 (read this before the S0-era sections)

The sections below were written at the S0 handoff and are kept as history. Current truth:

- S0, S1, S2 and S3 are complete and merged into `main` at `4cf1226`
  (PRs https://github.com/Mahesh953-hub/requestr/pull/17,
  https://github.com/Mahesh953-hub/requestr/pull/18,
  https://github.com/Mahesh953-hub/requestr/pull/19,
  https://github.com/Mahesh953-hub/requestr/pull/20,
  https://github.com/Mahesh953-hub/requestr/pull/21,
  https://github.com/Mahesh953-hub/requestr/pull/22).
- Room schema is at v22; the MITM proxy terminates HTTP/2 (flag `h2Terminate`); FTS5 search
  and protocol child tables are live (flags `ftsSearch`, `protocolChildren`).
- The next slice is S4 (rule engine, tasks 24–35). Per-task outcomes, known limits and the
  verification baseline are in `todos.md` ("Program status at session close-out").
- Decision records: `plans/decisions/D7-http2-conformance.md` (in-house codec kept, `h2Netty`
  not armed).
- Every sub-agent task listed in `tasks.md` is historical; S1–S3 were implemented directly by the
  main agent without sub-agents.

---

## 1. Goal and approved scope

The user read all 468 lines of:
`docs/plans/dossiers/interceptor-architecture-research.md`

and requested a source-backed plan. Non-negotiable requirements:

- No simpler approaches, no partial implementations, no legacy-preserving
  compromises, no stubs, no "coming soon" screens.
- Dedicated screens complete and theme-consistent.
- Responsive tabs: intrinsic-width single-line labels, never wrap/compress,
  scroll horizontally on measured overflow, auto-scroll selected tab into view.
- All screens/icons/tabs/haptics/motion/density/glass use the current theme.

The plan was completed, submitted, and APPROVED. Scope is **67 tasks across
nine slices S0–S8**, schema v19–v24, 30 measurable gates, 14 locked decisions,
20 complete interceptor screens plus API-Testr protocol extension, 22 paired
light/dark design sections (44 validated HTML files).

Plan files (also copied into this repo under `vorflux-handoff/plans/`):
- `vorflux-handoff/plans/v1-interceptor-architecture.md` (detailed)
- `vorflux-handoff/plans/v1-interceptor-architecture-summary.md`
- `vorflux-handoff/plans/subplans/*.md` (5 pre-approval sub-plans)
- `vorflux-handoff/designs/design-plan.json` + 44 HTML mockups

No open questions remain in the approved plan.

## 2. Locked architecture decisions (14)

1. Vendor a focused pinned tun2proxy fork.
2. Rebuild root transparent capture with `SO_ORIGINAL_DST`; Shizuku
   control-plane only; retire `TunRelay` only after parity evidence.
3. Breakpoint/script tools behind developer acknowledgement; run Rhino in a
   killable `android:process=":scripthost"`.
4. Room-compatible bundled SQLite to guarantee FTS5; normalized protocol data,
   native PCAPng, 20 snippet targets, Report Server.
5. Firebase Test Lab + self-hosted rooted physical device (Wi-Fi + cellular).
6. Capture-scope QUIC semantics (out-of-scope bypassed untapable; in-scope
   default tee-then-drop UDP/443; Observe-QUIC tee-then-pass; only proven keys → `decoded`).
7. Terminate HTTP/2; keep in-house codec only if conformance/fuzz gates pass.
8. Migrate legacy rules into typed staged engine.
9. Import API/workspace envelopes v1/v2; emit v2 only.
10. New settings use `SettingsDataStore` or dedicated stores, not `UnifiedDataStore`.
11. Responsive tabs per the requirements above; all six `GlassMode` styles.
12. Complete Script Console, Replay Queue/Results, Diff Workspace screens.
13. Complete Frida/system-CA pinning defeat; Xposed only on detected framework.
14. User/system CA, emulator proxy, browser-first validation, Chrome/Firefox
    differences, Redroid limits are product work, not documentation-only work.

## 3. What has been implemented (all uncommitted on this branch)

S0 is the only slice started. **No commit or PR has been created yet.** All work
sits in the working tree (and is now preserved via this handoff commit).

### S0.1 — Vendor pinned tun2proxy fork
- `native/tun2proxy-bridge/vendor/tun2proxy/` (upstream 0.8.3, crate SHA-256
  `0c0c4cb74f5f7df1bdbe11ee8a8ec0202603e1caa5db51f0f757bc92c8886799`).
- Crate-type narrowed to `["lib"]`, `publish=false`, binary targets removed.
- Six focused patches documented in `vendor/README.md`:
  1. `requestr_hooks` module (session events, UDP policy, packet tee, socket
     protect with `protect_required` mode).
  2. `tee_device` raw-frame tee at the TUN boundary (before ipstack).
  3. Session lifecycle events with accurate tx/rx byte counts.
  4. UDP policy evaluated after DNS/53 (virtual-DNS always works).
  5. Socket protection before every egress connect/bind (queue + non-queue).
  6. `SessionInfo::id()` accessor.
- Vendor checksum manifest: `tools/ci/vendor-sha256.manifest` (23 files).

### S0.2 — Stable JNI event surface + config
- Canonical config: `app/src/main/java/com/example/interception/tun/Tun2ProxyConfig.kt`
  (duplicate `interception/bridge/Tun2ProxyConfig.kt` removed).
- `NativeTun2Proxy.kt` (JNI wrapper), `Tun2ProxyBridge.kt` (lifecycle:
  start/stop/awaitStoppedOrTimeout/finalize, registration return-code checks).
- `app/src/main/java/com/example/interception/bridge/NativeBridgeEvents.kt`
  (typed events via org.json; rejects unknown UDP verdicts; `PacketTee.rawFrame`).
- `app/src/main/java/com/example/interception/bridge/BridgeSupervisor.kt`.
- Native: `events.rs` (bounded `sync_channel(256)` consumer with
  producer-side `try_send` + `EVENTS_PRODUCED`/`EVENTS_DROPPED` metrics),
  `udp_policy.rs`, `tee.rs`, `config.rs`, `lib.rs`, `state.rs`, `jni_bridge.rs`.
- cbindgen header `include/requestr_tun2proxy.h` regenerated with event sink and
  protect callback contracts.
- `forMitmProxyEngine()` now defaults `protectAvailable = true` and
  `DnsMode.VIRTUAL`.

### S0.3 — Bundled SQLite + migrations + backup
- `androidx.sqlite:sqlite-bundled:2.5.0` added (`gradle/libs.versions.toml`).
- `RequestrDatabase.kt`: `SCHEMA_VERSION=18`, dual-mode builder
  (`buildDatabase(context, useBundledDriver)`), WAL, all ten migrations v8→v18,
  main-thread guard, generation-based callback.
- `DbDriverFlag.kt` (SharedPreferences bootstrap flag, defaults ON; measured ABI
  sizes: arm64-v8a 1.9 MB, armeabi-v7a 1.5 MB, x86_64/x86 2.0 MB; io.requery
  fallback documented).
- `DbBackup.kt` (WAL checkpoint, generation-tracked cleanup, checkpoint-failure
  abort, stale WAL/SHM deletion).
- New migration tests 9→10, 10→11, 13→14 (fills the three previously-missing
  direct migration test gaps), `RequestrDatabaseBuilderTest`, `DbBackupTest`.
- AndroidTest: `BundledSQLiteDriverInstrumentedTest` (FTS5/JSON1/WAL/callbacks).

### S0.4 — Process-wide socket protection
- `app/src/main/java/com/example/interception/net/SocketProtector.kt`.
- Integrated into `LazyUpstreamConnection`, `RemoteCertificateMirror`,
  `HttpProxyShared` (relay + forwardRequest), `ConnectReplay`, `RequestrVpnService`.
- Generation-owned `Registration` objects; fail-closed on bind/protect failure;
  `Unavailable` only permitted with no protector installed.
- `VpnServiceSocketProtectionInstrumentedTest` (device).
- Native fd protect callback installed from `Tun2ProxyBridge` (fail-closed in
  bridge mode when protection is required).

### S0.5 — CI foundation
- `.github/workflows/ci.yml` (two jobs: `kotlin`, `rust`).
- `tools/ci/check-schema-drift.sh`, `tools/ci/check-vendor-integrity.sh`,
  `tools/ci/vendor-sha256.manifest`.
- Kotlin job: `:app:testDebugUnitTest`, `:cli:test`, `:sdk:testDebugUnitTest`,
  `:sdk-noop:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug -x
  cargoBuildTun2ProxyBridge`, `:app:assembleRelease -x cargoBuildTun2ProxyBridge`.
- Rust job: rustfmt (maintained src only), host cargo check/test, aarch64
  Android cargo check with NDK env, vendor integrity check.

## 4. Final verification state (as of handoff)

All verified green before the final two sub-agents were cancelled:

| Check | Status |
|---|---|
| Bridge Rust `cargo test --lib` | 56 passed |
| Vendor Rust `cargo test --lib -p tun2proxy` | 17 passed |
| rustfmt `src/*.rs` | PASS |
| `cargo check --lib` | 0 maintained warnings |
| Kotlin bridge tests (`interception.bridge.*`) | 16 passed |
| Kotlin lifecycle (`RequestrVpnServiceLifecycleTest`) | PASS |
| cbindgen header | regenerated (event + protect contracts) |
| Vendor integrity | 23 files match |
| Schema drift | 11 schemas OK, no drift |
| `:app:compileDebugKotlin` | SUCCESS |
| `git diff --check` | PASS |
| Redroid device | `emulator-5554` + `localhost:5555` (API 35) available |

Logs are preserved under `vorflux-handoff/artifacts/s0-foundations/`.

## 5. Sub-agent roster and outcomes

| task_id | type | final state |
|---|---|---|
| build-s0-native-bridge | build | cancelled mid-follow-up (see open defects below) |
| build-s0-sqlite-recovery | build | cancelled mid-follow-up (see open defects below) |
| build-s0-ci-foundation | build | completed (3 edits: job-count comment, cache comment, manifest regen) |
| build-s0-socket-protection | build | completed (real-fd prep fix via debug pass) |
| debug-s0-socket-tests | debug | completed (real-fd `socket.bind(null)` materialization) |
| test-s0-foundations | testing | completed preparation only (did NOT run final tests) |
| review-s0-correctness | review | completed (initial risk 9/10, Block) — resolved except native |
| simplify-s0-foundations | simplify | completed (initial risk 7/10) |

The review/simplify tasks were already at their final allowed round and should
NOT be re-run for another round unless the user explicitly requests it.

## 6. OPEN DEFECTS — must be completed before S0 PR

These were the active corrective instructions when the two build sub-agents
were cancelled. The next agent must finish these in the working tree.

### Native bridge (`build-s0-native-bridge`)
- **D/E (release blocker)** — `RequestrVpnService.kt` still closes tun fd and
  clears `SocketProtector` on stop `ERR_TIMEOUT`, contrary to the contract. On
  timeout it must retain tun fd + MITM engine + callbacks + protector and
  retry on IO with finite escalation; only confirmed quiescence finalizes. On
  start `ERR_TIMEOUT`, `Tun2ProxyBridge` must stop+await and either return a
  safe fallback failure or an explicit `Indeterminate` state; service must NOT
  start legacy `TunRelay` or close/reuse fd in that state.
- **B (parser hardening)** — `NativeBridgeEvents.PacketTee` still returns
  `rawFrame=null` for malformed/oversized/missing data instead of rejecting as
  `Unknown`; needs packet_len ≤ 9000 cap, Base64 max-size cap, decode failure,
  length-mismatch → `Unknown`; valid → non-null ByteArray.
- **C (bounded/nonblocking dispatch)** — native `stop_event_consumer`
  still unbounded `join()`; `BridgeSupervisor.onNativeEvent` still synchronous
  over listeners. Needs bounded nonblocking Kotlin queue + owned worker + close
  lifecycle + overflow metric + tests.
- **Pending-write tee test** — `TeeDevice` needs a `Pending` write test
  (no tee on Pending, one tee on accepted retry).
- **J (partial counters)** — relay error path still discards bytes already
  transferred (0/0). Preserve real per-direction partial totals.
- **K (header compile)** — validate `include/requestr_tun2proxy.h` with both
  `cc -fsyntax-only` and `c++ -fsyntax-only`.
- **G** — `forMitmProxyEngine` must set `teeEnabled=true` (subject to rollout
  flag) so the tee surface is active in production path.

### Database (`build-s0-sqlite-recovery`)
- **Main-thread guard** — `RequestrDatabase.get()` is called from
  main/Compose and first I/O triggers a guarded crash instead of a real off-main
  init lifecycle. Needs process-wide Application-owned IO-dispatched
  preflight + splash/await, with post-ready main-thread `get()` returning
  immediately. See `app/src/main/java/com/example/RequestrApplication.kt`
  (new, added during this slice).
- **Bundled driver tests** — instrumentation uses `db.openHelper` which is not
  the SQLiteDriver API; rewrite with Room's `SQLiteConnection`/`useConnection`
  API + real DAO ops. Also add a genuine v8→v18 bundled migration fixture
  (open embedded v8 DB, migrate, verify data) rather than current-schema-open.
- **Execute connected tests** on Redroid (`localhost:5555`), not compile-only.
- Correct stale KDoc stating bundled is default-off (it now defaults ON).

## 7. Task-by-task completeness (relative to the 67-task approved plan)

Only S0 (tasks 1–5) started. Remaining approved tasks 6–67 are NOT STARTED
(see `vorflux-handoff/plans/v1-interceptor-architecture.md` for full list):
- 6 LocalGateway, 7 relay pool, 8 CertificateEngine, 9 UDP/QUIC policy,
  10 UID attribution, 11 VPN hardening, 12 PCAPng, 13 trust diagnostics,
  14–22 Room v19–v22 + persistence, 23 HTTP/2 conformance, 24–34 rule engine +
  breakpoints + script host + safety, 35 rules on HTTP/2, 36 HAR, 37–40 export +
  replay + diff, 41 Report Server, 42–47 UI + settings + nav, 48–50 root
  capture, 51 CI, 52–56 verification/coverage/device lanes, 57–62 HTTP/2 + API-Testr,
  63–67 pinning/system trust/guided setup.

## 8. Todos (platform) snapshot

See `vorflux-handoff/todos.md` for the full exported todo list. Highlight:
- S0.1 `6ab46` = in_progress; S0.2–S0.5 and S0-PR and S1–S8 = pending.

## 9. How to resume

1. `cd /code/Mahesh953-hub/requestr && git checkout vorflux/interceptor-s0-runtime`
2. Finish the native + DB open defects in §6 (both were mid-fix when cancelled).
3. Run the full S0 verification battery (exact commands in
   `vorflux-handoff/artifacts/s0-foundations/final-retest/command-manifest.md`).
4. Re-run the S0 testing agent, then submit ONE Test Report reusing title
   `Gaps audit remediation source verification`.
5. Commit, push, open the stacked S0 PR (parent PR #17).
6. Proceed to S1–S8.

## 10. Commands that already passed (for context)

```bash
PATH="$HOME/.cargo/bin:$PATH" rustfmt --edition 2021 --check src/*.rs
PATH="$HOME/.cargo/bin:$PATH" cargo check --lib --tests
PATH="$HOME/.cargo/bin:$PATH" cargo test --lib
PATH="$HOME/.cargo/bin:$PATH" cargo test --lib -p tun2proxy
./gradlew :app:compileDebugKotlin --no-daemon --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.example.interception.bridge.*' \
  --tests 'com.example.interception.RequestrVpnServiceLifecycleTest' \
  --no-daemon --console=plain
bash tools/ci/check-schema-drift.sh
bash tools/ci/check-vendor-integrity.sh
```
