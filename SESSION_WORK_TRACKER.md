# Session Work Tracker — requestr v6.0 (Complete v5 Pending)

**Session:** `461393c6-d9ac-4b7f-a4e8-ecff2e213696`  
**Canonical branch:** `origin/backup` (HEAD `76ef7bc`)  
**Updated:** 2026-08-24 (post-gap-fix merge + branch consolidation)

> This tracker reflects the v6.0 scope as of the gap-fix merge (`62329d3`) and branch
> consolidation, **reconciled by v3 plan task 4 (2026-08-24)** against the gap-closure PR
> (`vorflux/v6-gap-closure` @ `4bfcb4f`, verified, not yet merged into `vorflux/v6-integration`).
> v6.0 lands the **foundation** for full key-log-sourced QUIC/HTTP-3 decryption plus three
> fully-wired user-facing features (UTF-8 fix, 10-accent theme, authenticated `/api/keylog`)
> and five independently tested pipeline components (ChaCha20-Poly1305, key-phase rotation,
> production QPACK decoder, Http3StreamParser, QuicDatagramRouter, QuicFrameWalker).
>
> **Reconciled by task 4:** the gap-closure PR **wired the end-to-end decrypt pipeline**
> (keylog lookup → 1-RTT decrypt → `Http3StreamParser` → HTTP/3 `CapturedTrafficEntity`
> persistence) from `PacketPump` through the new `QuicConnectionDecoder`, with honest
> `QuicDecodeStatus` transitions and Room-driven UI badges — so the prior "is not yet wired"
> wording is superseded. **Reconciled test counts** (JDK 17 / SDK 36 / Build-Tools 36.0.0,
> at `4bfcb4f`): `:app` 547 / `:sdk` 37 / `:cli` 4 / browser-extension 31 (0 failures); the
> historical `403` (`:app` count at `4b25e76`) and `446` (unsupported aggregate) are retired.
> **Designs:** exactly **36** approved HTML mockups (HTTP/3 8, runtime/CDP 12, relay 10, TLS 6)
> — the prior "38" wording is corrected.
>
> **Real known limits kept open:** the approved design-plan UI (Task 0 `BadgePill` extraction
> + Domains A-D, incl. HTTP/3 detail sheets) is zero implementation (v3 plan tasks 7-14); live
> on-wire device capture (CAT-3) is not runnable on this non-rooted, no-kernel-TUN Redroid tier;
> API-36 runtime behavior is unvalidated (Android 16 `system_server` needs `/dev/ashmem`,
> unavailable on kernel `7.0.0-1010-aws`). See `PROCESS_VS_GAPS.md` and `BUILD_LOG.md`.

---

## Top-Level Todos

| # | Todo | Status |
|---|------|--------|
| 1 | Archive main and create implementation branch from origin/backup | ✅ complete |
| 2 | Fix LocalApiServer UTF-8 responses and JSON serialization with tests | ✅ complete |
| 3 | Port and persist the 10-accent theme system (engine + 3 pickers) | ✅ complete |
| 4 | QUIC crypto foundation + Room v10 schema + authenticated keylog ingestion | ✅ complete (foundation) |
| 5 | Docs, release notes, and v6.0 version bump | ✅ complete |
| 6 | Host build + unit-test gate (compile, assemble; reconciled counts) | ✅ complete |
| 7 | Full QUIC/HTTP-3 end-to-end decrypt pipeline (QPACK, Http3, PacketPump, UI, fixtures) | ✅ wired by gap-closure (`4bfcb4f`) — pipeline + fixtures done; UI detail sheets still pending (design plan) |
| 8 | Open a PR into `backup` | ✅ gap-closure PR open & verified (`vorflux/v6-gap-closure` @ `4bfcb4f`); merges into `vorflux/v6-integration` via dependent PRs |

---

## v6.0 Shipped Scope — Nine Tracked Items (all complete & wired)

| Item | Plan tasks | Status | Notes |
|------|-----------|--------|-------|
| UTF-8 Content-Length fix (CAT-1) | 4 | ✅ DONE | `LocalApiServer` byte-accurate responses + `org.json` serialization; `LocalApiServerToolsEncodingTest`, `:cli` `ToolsParseTest` |
| 10-accent theme (settings / onboarding / drawer) | 5, 6 | ✅ DONE | `AppAccent` (10) + dynamic light/dark schemes, persistence, all three pickers; `AccentThemeTest`, `AccentPersistenceTest` |
| Authenticated cross-process keylog ingestion (`POST /api/keylog`) | 13 | ✅ DONE | Bearer-gated route + SDK HTTP primary transport; `LocalApiServerKeylogTest`, `KeyLogProducerHttpTest` |
| QUIC crypto foundation (client+server Initial, traffic-secret derivation, PN reconstruction, v1/v2 Retry integrity) | 7 | ✅ DONE | Primitives + full 1-RTT decrypt wiring now driven by `QuicConnectionDecoder` (gap-closure `4bfcb4f`) |
| Room v10 (decode + lifecycle state on QUIC connections) | 8 | ✅ DONE | `QuicDecodeStatus` enum + columns + `MIGRATION_9_10` + `10.json`; `PacketPump` now drives `decodeStatus` honestly via `QuicConnectionDecoder` (gap-closure `4bfcb4f`) |
| Handshake long-header decryption | 7 | ✅ DONE | `decryptHandshakePacket` in `QuicCrypto.kt` + 9 focused tests |
| ServerHello cipher extraction (`parseServerHello`) | 7b | ✅ DONE | `TlsDecoder.parseServerHello` + `mapCipherSuiteId` + 10 tests |
| `QuicStreamReassembler` | 9 (partial) | ✅ DONE | Byte-offset STREAM/CRYPTO reassembly with retx dedupe + 13 tests |
| `QuicCidTable` (CID lifecycle) | 8 | ✅ DONE | CID routing, multi-CID, zero-length DCID fallback + 14 tests |

---

## Build Gates

Historical (verified on `vorflux/v6-build-verify` @ `4b25e76`): `:app` 64 classes / 403 tests.

✅ **Reconciled at gap-closure head `4bfcb4f` (JDK 17 / SDK 36 / Build-Tools 36.0.0; see `BUILD_LOG.md`):**
- `:app:testDebugUnitTest` — **80 test classes, 547 tests, 0 failures**
- `:sdk:testDebugUnitTest` — 9 classes, 37 tests PASS
- `:cli:test` — 1 class, 4 tests PASS
- browser-extension — 31 tests PASS (utils 10 / enforcer 13 / queue 8)
- `:app:assembleDebug` + `:app:assembleRelease` — SUCCESS; release signed with locked key (alias `upload`, cert SHA-256 `CB:08:8B:DE:…:6E:D8`), R8 minified, `:app:verifyReleaseSigning` OK
- Totals: 588 Gradle JVM tests; 619 incl. browser-extension. The `403`/`446` numbers are retired (see `PROCESS_VS_GAPS.md`).
- Version bump verified: `versionCode = 28`, `versionName = "6.0"`, `metadata.json` `"version": "6.0.0"`
- Room schema exported: `app/schemas/…/10.json`, `@Database(version = 10)`

---

## Plan Task Status (Tasks 1–18 + 3b/7b)

Legend: ✅ complete · ⚠️ partial (foundation done) · ⬜ not done (follow-on) · ⏳ pending

| Task | Description | Status |
|------|-------------|--------|
| 1 | Archive `main` from a ref (`archive/main-v5.7` tag + branch) | ✅ |
| 2 | Confirm exact `main`-unique content → `docs/legacy-main/` | ✅ |
| 3 | Create implementation branch off `origin/backup` | ✅ |
| 3b | CAT-4 gate verification (keylog / Initial / QPACK / migration) | ✅ |
| 4 | `LocalApiServer` UTF-8 `Content-Length` + `org.json` (CAT-1) | ✅ |
| 5 | Port 10-accent model into canonical theme architecture (CAT-2a) | ✅ |
| 6 | Accent theme tests | ✅ |
| 7 | `QuicCrypto` — client+server Initial, Handshake, 1-RTT, all AEADs, short-header, PN, key-phase | ✅ (1-RTT wired by gap-closure `4bfcb4f`) |
| 7b | `TlsDecoder.parseServerHello` → negotiated cipher suite | ✅ |
| 8 | `QuicConnection` + `QuicDecodeStatus` column (MIGRATION_9_10) + CID lifecycle + retained ClientHello random | ✅ (decode status driven; ClientHello random retained — gap-closure `4bfcb4f`) |
| 9 | `QuicDatagramRouter` — v4/v6 + coalesced ingestion, CID routing, STREAM/CRYPTO reassembly | ✅ (reassembled STREAM fed to Http3StreamParser — gap-closure `4bfcb4f`) |
| 10 | Production QPACK decoder (RFC 9204) | ✅ (driven from live capture via Http3StreamParser — gap-closure `4bfcb4f`) |
| 11 | `Http3StreamParser` — control/unidirectional + request streams | ✅ (instantiated in QuicConnectionDecoder — gap-closure `4bfcb4f`) |
| 12 | `PacketPump` wiring — keylog lookup → Handshake+1-RTT decrypt → HTTP/3 rows + honest key-miss | ✅ (pipeline complete via QuicConnectionDecoder — gap-closure `4bfcb4f`) |
| 13 | Key producers — authenticated `/api/keylog` PRIMARY + NSS import; reflective bridge optional; no MitmProxyEngine (CAT-2d) | ✅ |
| 14 | QUIC/HTTP-3 UI honest state (`QuicDecodeStatus`-driven badges) | 🟡 partial (badges now Room-driven `9919aa8`; detail sheets pending = design plan Domain A) |
| 15 | Docs + unconditional v6.0 bump + `RELEASE_NOTES_v6.0.md` (CAT-5) | ✅ |
| 16 | Host build + unit-test gate (no device) | ✅ |
| 17 | Redroid device gate + deterministic per-suite end-to-end fixtures | 🟡 partial (deterministic fixtures now exist `ca1de13`; live device capture still pending CAT-3) |
| 18 | Open one PR into `backup` | ✅ merged via `backup` |

**Summary (reconciled by task 4):** 17 tasks fully complete (1-6, 7, 7b, 8, 9, 10, 11, 12, 13, 15, 16, 18 — 7-12 wired by gap-closure `4bfcb4f`); 2 partial (14 — badges Room-driven, detail sheets pending; 17 — fixtures exist, live device capture pending CAT-3).

---

## Integration History (`origin/backup` → `4b25e76`)

```
4b25e76 docs: mark handshake-decrypt/ServerHello/reassembler/CidTable as DONE in all docs
10ed458 review: add build-verify review report (BLOCKED — stale docs)
008333b feat(theme): wire accent picker into Onboarding + Drawer, fix call-site compile
2b86b20 feat(quic-handshake): key-log-sourced Handshake decrypt + TLS 1.3 ServerHello parsing
4977e49 fix(room): add missing QuicDecodeStatus import, regenerate 10.json, complete AppearanceStep call site
2814d5c fix(room): move enum before @Entity and converter into RequestrTypeConverters
a3a6190 docs(v6.0): release notes, tracker completion, version bump to 6.0
5ff7bc6 feat(quic-cids): add QuicCidTable CID-lifecycle routing table
2d11762 feat(quic): add byte-offset QuicStreamReassembler with retx dedupe + FIN tracking
537230d wip: snapshot of in-progress accent/room work pending compile fix
0ad1616 docs: session work tracker with full todo status
fb74bb0 feat(theme): apply accent selection in app and settings
e429cc3 Revert "feat(theme): apply accent selection in app and settings"
542382b feat(theme): apply accent selection in app and settings
a54ca26 feat(quic-crypto): version-aware v1/v2 Retry Integrity Tag validation + packet-type classification
70c8e20 feat(theme): port AppAccent/universal dynamic color scheme engine
caea073 feat(quic-crypto): server Initial keys, generic traffic-secret derivation, PN reconstruction
5a50d3c feat(sdk): authenticated cross-process keylog ingestion (Task 13 / CAT-2d)
267a5ee feat(theme): persist independent accent selection
99f72af fix(api): preserve main and repair UTF-8 responses
8194e96 origin/backup baseline
```

Six feature branches were integrated by the build-verify agent (per `REVIEW_REPORT.md`):
`vorflux/v6-accent-final`, `vorflux/v6-room-v10`, `vorflux/v6-quic-handshake-2`,
`vorflux/v6-quic-streams`, `vorflux/v6-quic-cids`, `vorflux/v6-docs-release` — all conflict-free,
plus the room compile fixes and the final stale-docs correction.

---

## Isolated Worker Branches (all integrated into build-verify)

| Directory | Branch | Outcome |
|-----------|--------|---------|
| `requestr-workers/accent-ui` | `vorflux/v6-accent-ui` | engine integrated |
| `requestr-workers/accent-app` | `vorflux/v6-accent-app` | settings integrated |
| `requestr-workers/accent-final` | `vorflux/v6-accent-final` | Onboarding + Drawer wiring integrated |
| `requestr-workers/keylog-sdk` | `vorflux/v6-keylog-sdk` | keylog ingestion integrated |
| `requestr-workers/quic-crypto` | `vorflux/v6-quic-crypto` | server Initial + Retry integrated |
| `requestr-workers/quic-handshake-2` | `vorflux/v6-quic-handshake-2` | Handshake decrypt + ServerHello integrated |
| `requestr-workers/quic-streams` | `vorflux/v6-quic-streams` | `QuicStreamReassembler` integrated |
| `requestr-workers/quic-cids` | `vorflux/v6-quic-cids` | `QuicCidTable` integrated |
| `requestr-workers/room-v10` | `vorflux/v6-room-v10` | Room v10 compile fix integrated |
| `requestr-workers/room-fields-2` | `vorflux/v6-room-fields-2` | superseded by room-v10 integration |
| `requestr-workers/docs-release` | `vorflux/v6-docs-release` | release notes + version bump integrated |
| `requestr-workers/build-verify` | `vorflux/v6-build-verify` | verified final build (`4b25e76`, 403 tests) |
| `requestr-workers/merge-integrate` | `vorflux/v6-merge-integrate` | this branch (reset to `4b25e76` + this tracker commit) |
| `requestr-workers/pr-open` | `vorflux/v6-pr-open` | reserved for PR agent a10 |

---

## Remaining Follow-ons (honest — reconciled by task 4)

The QUIC/HTTP-3 **decrypt wiring** is now **complete** (gap-closure PR `4bfcb4f`); the items below that were previously pending are marked resolved, and the genuinely remaining work is listed honestly.

### ✅ Done via gap-fix merge (`62329d3`)
- **Task 7 remainder — ChaCha20-Poly1305:** `f083841` — AES-128/256-GCM now co-exists with
  ChaCha20-Poly1305 (ChaCha20 keystream header protection per RFC 9001 §5.4.4). `QuicCryptoTest`.
- **Task 7 remainder — key-phase rotation:** `aff4b6d` — prev/current/next key with
  auth-before-promotion (old phase accepts packets for a grace window). `KeyPhaseTest` (211 lines).
- **Task 9 remainder — QuicDatagramRouter:** `74b9328` — v4/v6 coalesced-packet ingestion +
  CID routing wired into `PacketPump`. `QuicDatagramRouterTest` + `PacketPumpQuicDatagramTest`.
- **Task 10 — Production QPACK decoder:** `12d22e1` — dynamic table, RIC/Base, blocked streams,
  encoder/decoder stream integration. `QpackProductionTest` + `QpackDecoderTest`. Called by
  `Http3StreamParser`. **Now driven from live capture via `QuicConnectionDecoder` (gap-closure `4bfcb4f`).**
- **Task 11 — Http3StreamParser:** `1bffb00` — frame parsing, SETTINGS, QPACK integration,
  pseudo-header extraction. `Http3StreamParserTest`. **Now instantiated in `QuicConnectionDecoder`, wired from `PacketPump` (gap-closure `4bfcb4f`).**
- **Task 5 (Gap) — QuicFrameWalker:** `0b4bbdb` — NEW_CONNECTION_ID / RETIRE_CONNECTION_ID frame
  detection and CID lifecycle routing. `QuicFrameWalkerTest`.

### ✅ Resolved by gap-closure PR (`4bfcb4f`)
- **End-to-end pipeline:** `PacketPump` now drives `QuicConnectionDecoder`, which does keylog
  lookup → Handshake + 1-RTT decrypt → `Http3StreamParser` → `CapturedTrafficEntity`
  persistence with `protocol = "HTTP/3"`. `decodeStatus` is a monotonic high-water mark raised
  to `OBSERVED_NO_KEYS` / `PARTIALLY_DECODED` / `FULLY_DECODED` (no longer stuck at
  `DETECTED_ONLY`). Tests: `Http3PersistenceDurabilityTest`, `Http3EndToEndFixtureTest`.
- **parseServerHello → lockCipherSuite:** `QuicConnectionDecoder` calls
  `lockCipherSuiteFromIana(serverHello.cipherSuiteId)` from the decrypted Initial ServerHello —
  1-RTT AEAD selection unblocked.
- **Task 17 fixtures:** deterministic per-AEAD-suite fixtures now exist
  (`Http3EndToEndFixtureTest`, `QuicOneRttSuiteTest`, `QuicRetryTest`, `QuicStatePoisoningTest`).

### 🟡 Genuinely still pending (kept open)
- **Task 14 — QUIC/HTTP-3 UI:** Badges now read the Room `QuicDecodeStatus` column
  (`9919aa8`), but no detail bottom sheet, no `Http3DetailSheet`, no `Http3RowIndicator`.
  Design plan Domain A (HTTP/3 Indicators) has zero implementation — v3 plan UI tasks.
- **Task 17 device gate (CAT-3):** live on-wire QUIC/HTTP-3 capture/decrypt is not runnable on
  this non-rooted, no-kernel-TUN Redroid tier (correctness bar = JVM fixtures).
- **Design plan (4 domains + Task 0):** exactly **36** design mockups exist in `docs/designs/`
  (HTTP/3 8, runtime/CDP 12, relay 10, TLS 6). Implementation: zero. Task 0 (BadgePill
  extraction to CommonComponents.kt) not started.

The crypto primitives, handshake decrypt, ServerHello parsing, stream reassembler, CID lifecycle
table, the key-ingestion path, the Room v10 schema, and all three user-facing features are in place
and unit-tested. The gap-fix added five more independently tested components, and the gap-closure
PR (`4bfcb4f`) wired the full end-to-end decrypt *pipeline*. The critical remaining milestone is the
approved design-plan UI (Task 0 + Domains A-D) plus live device capture (CAT-3).

---

## Next Steps

The branch consolidation is complete (see `docs/MERGE_REPORT.md`). Only `main` and `backup` remain
on origin. All 34 feature branches deleted. The critical path forward:

1. **End-to-end pipeline wiring:** Keylog lookup → 1-RTT decrypt → `Http3StreamParser` →
   `CapturedTrafficEntity` persistence in `PacketPump`. All components exist as tested
   standalone units — the integration is the gap.
2. **`parseServerHello` → `lockCipherSuite`:** Feed the parsed TLS 1.3 cipher suite from live
   capture into PacketPump's suite lock for 1-RTT AEAD selection.
3. **Design implementation plan (Task 0 + 4 domains):** Extract `BadgePill` to
   `CommonComponents.kt`, then build HTTP/3 Indicators, Runtime CDP, VPN Relay health, and TLS
   failure surfaces. See `docs/designs/implementation-plan.md` (36 mockups, APPROVED).

See `PROCESS_VS_GAPS.md` for complete honest status across all 20 plan tasks + 5 gaps.
