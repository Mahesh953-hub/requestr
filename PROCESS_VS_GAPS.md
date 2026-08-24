# Process vs Honest Gaps vs Pending — requestr v6.0

> **Generated:** 2026-08-24 | **Source branch:** `origin/backup` (HEAD `1554f66`)  
> **Plan:** `/code/.plans/v1-complete-v5-pending.md` (Tasks 1-18, 3b, 7b)  
> **Design plan:** `/code/Mahesh953-hub/requestr/docs/designs/implementation-plan.md` (4 domains + Task 0)

---

## 1. Process: What the plan promised

Every task from the v6.0 execution plan (`v1-complete-v5-pending.md`), its claimed deliverable, actual status on `origin/backup`, and evidence.

| Task | Claimed Deliverable | Actual Status | Evidence |
|------|-------------------|---------------|----------|
| **1** | Archive `main` from a ref (`archive/main-v5.7` tag + branch) | ✅ Done | SESSION_WORK_TRACKER.md confirms; `docs/legacy-main/` exists |
| **2** | Confirm `main`-unique content → `docs/legacy-main/` | ✅ Done | `docs/legacy-main/MASTER_PLAN_AND_FIX_SPEC.md` + theme snapshots preserved |
| **3** | Create `vorflux/complete-v5-pending` off `origin/backup` | ✅ Done | Branch created from `8194e96`; later integrated via `vorflux/v6-merge-integrate` |
| **3b** | CAT-4 gate verification (keylog / Initial / QPACK / migration) | ✅ Done | `TlsKeyLogRegistryTest`, `QuicCryptoTest`, `QpackDecoderTest`, `Migration8To9Test` all green (403 total tests) |
| **4** | `LocalApiServer` UTF-8 `Content-Length` + `org.json` (CAT-1) | ✅ Done | Commit `99f72af`; tests: `LocalApiServerToolsEncodingTest`, `ToolsParseTest` |
| **5** | Port 10-accent model into canonical theme architecture (CAT-2a) | ✅ Done | Commits `267a5ee`, `70c8e20`, `542382b`/`fb74bb0`, `008333b` (Settings + Onboarding + Drawer pickers) |
| **6** | Accent theme tests | ✅ Done | `AccentThemeTest`, `AccentPersistenceTest` |
| **7** | QuicCrypto: client+server Initial, Handshake, 1-RTT, all AEADs, short-header, PN spaces, key-phase | 🟡 Partial | Client+server Initial ✅ (`caea073`), traffic-secret derivation ✅, PN ✅, Retry ✅ (`a54ca26`), Handshake decrypt ✅ (`2b86b20`), ChaCha20 ✅ (`f083841`), key-phase ✅ (`aff4b6d`). 1-RTT short-header **path exists but PacketPump does not drive it**: `decryptShortHeaderPacket` is in `QuicCrypto.kt` but no PacketPump call site looks up keys and decrypts 1-RTT. |
| **7b** | `TlsDecoder.parseServerHello` → negotiated cipher suite | ✅ Done | Commit `2b86b20`; `TlsServerHelloTest` (10 tests) |
| **8** | `QuicConnection` + `QuicDecodeStatus` column (MIGRATION_9_10) + CID lifecycle + retained ClientHello random | 🟡 Partial | Schema: `QuicDecodeStatus` enum + `MIGRATION_9_10` + `10.json` ✅ (`2814d5c`, `4977e49`). CID lifecycle: `QuicCidTable` ✅ (`5ff7bc6`; `QuicCidTableTest` 14 tests). Retained ClientHello random: structurally present (`clientRandomHex` column) but actual retention from decrypted Initial not verified in PacketPump flow. |
| **9** | `QuicDatagramRouter` — v4/v6 + coalesced ingestion, CID routing, STREAM/CRYPTO reassembly | 🟡 Partial | QuicDatagramRouter: ✅ wired into PacketPump (`62329d3` gap-fix merge; `QuicDatagramRouterTest`). STREAM/CRYPTO reassembly: ✅ `QuicStreamReassembler` (`2d11762`; `QuicStreamReassemblerTest` 13 tests). **But** Http3StreamParser is not instantiated in PacketPump — reassembled STREAM data is not fed to HTTP/3 parsing. |
| **10** | Production QPACK decoder (RFC 9204) | ✅ Exists, not driven | `12d22e1` (gap-fix): full rewrite with encoder/decoder streams, RIC/Base, dynamic table, blocked fields. `QpackProductionTest`. Http3StreamParser references it. **But** PacketPump does not instantiate `Http3StreamParser` → no production call site from live capture. |
| **11** | `Http3StreamParser` — control/unidirectional + request streams | ✅ Exists, not driven | `1bffb00` (gap-fix): full implementation with QPACK integration, stream-type varint demux, blocked-stream handling. `Http3StreamParserTest`. **But** PacketPump has zero references to `Http3StreamParser`. |
| **12** | `PacketPump` wiring — keylog lookup → Handshake+1-RTT decrypt → HTTP/3 rows + honest key-miss | 🟡 Partial | Gap-fix (`62329d3`): `QuicDatagramRouter` + `QuicCidTable` wired in, `lockCipherSuite`/`lockedCipherSuite` added, CID routing + coalesced packet splitting functional. `PacketPumpQuicDatagramTest`. **Missing:** no keylog lookup for 1-RTT secrets, no `decryptShortHeaderPacket` call site, no `Http3StreamParser` instantiation, no `CapturedTrafficEntity` persistence for decoded HTTP/3, no `QuicDecodeStatus` drive beyond `DETECTED_ONLY`. |
| **13** | Key producers — authenticated `/api/keylog` PRIMARY + NSS import (CAT-2d) | ✅ Done | Commits `5a50d3c`; `LocalApiServerKeylogTest`, `KeyLogProducerHttpTest` — end-to-end SDK→registry proof |
| **14** | QUIC/HTTP-3 UI honest state (QuicDecodeStatus-driven badges) | 🟡 Partial | QUIC tab badges exist from transport/packet-core rework (`58b3b8c`): green HTTP/3, amber HTTP/3, gray QUIC. **But** not driven by `QuicDecodeStatus` column — driven by in-memory `quicLiveMap`/`decodedFrames` flags. No `Http3DetailSheet`, no `Http3RowIndicator`, no detail bottom sheet for any of the three design states. |
| **15** | Docs + unconditional v6.0 bump + `RELEASE_NOTES_v6.0.md` (CAT-5) | ✅ Done | `a3a6190`: `versionCode 28`, `versionName "6.0"`, `RELEASE_NOTES_v6.0.md`; stale-doc banners added (`4b25e76`) |
| **16** | Host build + unit-test gate | ✅ Done | 403 tests, 64 test classes, compile/assemble all green (`4b25e76`) |
| **17** | Redroid device gate + deterministic per-suite E2E fixtures | ❌ Not done | `Http3EndToEndFixtureTest` does not exist. Device verification not run. CAT-3 scope (device-limited). |
| **18** | Open one PR into `backup` | ❌ Not done | PR agent a10 pending per `SESSION_WORK_TRACKER.md` |
| **a11-a15** | (Not in plan — these were gap-fix sub-tasks) | N/A | See §2 below |

---

## 2. Honest Gaps: Identified and fixed

Five gaps from the review were identified and addressed in the `vorflux/v6-gap-fixes` branch, merged into `origin/backup` at `62329d3`.

| Gap | Problem | Fix Commit(s) | Test File(s) |
|-----|---------|---------------|--------------|
| **Gap 1: ChaCha20-Poly1305** | `QuicCrypto` hardcoded AES-128-GCM only. RFC 9001 §5.4.4 requires ChaCha20-Poly1305 support (32-byte key, ChaCha20 keystream header protection). | `f083841` feat(quic): add ChaCha20-Poly1305 cipher suite with header protection | `QuicCryptoTest` (extended with ChaCha20 vectors) |
| **Gap 2: Key-phase rotation** | No key-phase support. QUIC short-header packets with a key-phase bit flip would fail to decrypt. Required auth-before-promotion (only promote after AEAD tag authenticates) and bounded retirement. | `aff4b6d` feat(quic): add key-phase rotation with auth-before-promotion | `KeyPhaseTest` (211 lines) — tests promotion after auth, delayed old-phase decrypt, failed speculative update rejection |
| **Gap 3: QuicDatagramRouter → PacketPump** | PacketPump parsed QUIC Initial packets inline with no CID routing, no coalesced-packet handling, no Retry, no short-header path. | `74b9328` wip: salvage gaps 3-5 (DatagramRouter→PacketPump wiring); merged at `62329d3` | `QuicDatagramRouterTest` (299 lines) — coalesced Initial+Handshake split, v4/v6 parity, CID routing; `PacketPumpQuicDatagramTest` (243 lines) |
| **Gap 4: Http3StreamParser/QPACK → PacketPump** | `QpackDecoder.decode()` had no production call site (test-only). No HTTP/3 stream demux, no HEADERS→pseudo-headers, no DATA frame handling. | `12d22e1` feat(quic): production QPACK decoder; `1bffb00` feat(quic): HTTP/3 stream parser; merged at `62329d3` | `QpackProductionTest` (186 lines), `QpackDecoderTest` (existing), `Http3StreamParserTest` (206 lines) |
| **Gap 5: NEW/RETIRE_CONNECTION_ID frames** | `QuicParser` aborted at any non-PADDING/PING/CRYPTO frame. No CID lifecycle frame handling — NEW_CONNECTION_ID (0x18) and RETIRE_CONNECTION_ID (0x19) frames were silently dropped. | `0b4bbdb` feat(quic): frame walker for NEW/RETIRE_CONNECTION_ID lifecycle (Gap 5) | `QuicFrameWalkerTest` (170 lines) — NEW_CONNECTION_ID add/sequence/retire-prior-to, RETIRE_CONNECTION_ID bounded set |

### Honest qualification on Gap 4

`Http3StreamParser` and production `QpackDecoder` exist as fully-tested standalone components, and `QuicDatagramRouter` is wired into `PacketPump` (Gap 3). However, `PacketPump` does **not** instantiate `Http3StreamParser`, does **not** feed reassembled STREAM data into it, and does **not** persist decoded HTTP/3 as `CapturedTrafficEntity` rows. The components exist but the end-to-end pipeline (keylog lookup → 1-RTT decrypt → Http3StreamParser → HTTP/3 persistence) is not wired. The gap-fix merge commit message claims "end-to-end," but the code shows the wiring stops at datagram routing.

---

## 3. Pending: Genuinely remaining

### 3a. Design plan domains (all pending)

From `/code/Mahesh953-hub/requestr/docs/designs/implementation-plan.md` — all four UI domains + Task 0 are **completely unstarted**. No source files exist for any of them.

| Item | Source | Priority | Detail |
|------|--------|----------|--------|
| **Task 0: Extract BadgePill** | Design plan §0 | 🔴 Blocker | `BadgePill` is still private in `InterceptScreen.kt` (1 occurrence, line ~3443). Not in `CommonComponents.kt`. All four domains depend on it. |
| **Domain A: HTTP/3 Indicators** | Design plan §1 (Domain A) | 🟡 Medium | 5 new files: `Http3DetailSheet.kt`, `Http3RowIndicator.kt`, `Http3State.kt`, `QuicDetailProvider.kt`, + modify `InterceptScreen.kt`. Depends on QUIC pipeline being complete. |
| **Domain B: Runtime CDP + Instrumentation** | Design plan §1 (Domain B) | 🟡 Medium | 7 new files: `RuntimeInstrumentationCard.kt`, `CdpCard.kt`, `WebViewCaptureControl.kt`, `StackFingerprintPanel.kt`, `RuntimeInstrumentationState.kt`, `RuntimeInstrumentationStateProvider.kt`, + modify `SettingsScreen.kt`. |
| **Domain C: VPN Relay Health** | Design plan §1 (Domain C) | 🟡 Medium | Extract + augment `RelayHealthCard.kt`, `RelayHealthDetailSheet.kt` from `InterceptScreen.kt`; create `RelayHealthState.kt`; modify `TunRelay.kt` (per-reason drop counters). |
| **Domain D: TLS Failure Detail** | Design plan §1 (Domain D) | 🟢 Low | 4 new files: `TlsFailureDetailSheet.kt`, `TlsFailureDetail.kt`, `TlsFailureDetailProvider.kt`, + modify `InterceptScreen.kt` (tap-to-open). |

All 36 design mockups exist in `docs/designs/` — design is complete, implementation is zero.

### 3b. Plan task remainders

| Item | Source | Priority | Detail |
|------|--------|----------|--------|
| **1-RTT decrypt wiring** | Plan Task 7 | 🔴 High | `decryptShortHeaderPacket` exists in `QuicCrypto.kt` but PacketPump never calls it. No keylog lookup for `CLIENT_TRAFFIC_SECRET_0`/`SERVER_TRAFFIC_SECRET_0` → `deriveKeysFromTrafficSecret` → AEAD decrypt of short-header packets. |
| **Http3StreamParser in PacketPump** | Plan Tasks 9/10/11/12 | 🔴 High | `Http3StreamParser` and `QpackDecoder` exist but PacketPump never instantiates them. Reassembled STREAM data is buffered but not fed to HTTP/3 parsing. |
| **HTTP/3 → CapturedTrafficEntity persistence** | Plan Task 12 | 🔴 High | No HTTP/3 rows are persisted. `CapturedTrafficEntity` persist path for `protocol="HTTP/3"` does not exist in PacketPump. |
| **QuicDecodeStatus drive** | Plan Task 12 | 🟡 Medium | PacketPump only writes `DETECTED_ONLY`. `OBSERVED_NO_KEYS`, `PARTIALLY_DECODED`, `FULLY_DECODED` are never set from live capture. |
| **QUIC/HTTP-3 UI (detail sheets)** | Plan Task 14 | 🟡 Medium | QUIC tab has basic badges from transport/packet-core rework but no detail bottom sheet, no `Http3DetailSheet`, no row-level HTTP/3 indicators. Badges not driven by `QuicDecodeStatus` column. |
| **E2E fixtures (Task 17)** | Plan Task 17 | 🟡 Medium | `Http3EndToEndFixtureTest` does not exist. Per-suite deterministic fixtures (AES-128-GCM, AES-256-GCM, ChaCha20-Poly1305) not built. |
| **PR (Task 18)** | Plan Task 18 | 🟢 Low | PR not opened. `vorflux/v6-merge-integrate` branch ready at `4b25e76`. |
| **Docs staleness in TODO_v5** | Plan Task 15 | 🟢 Low | See §4 below — P2 row and BUILD LOG stale after gap-fix merge. |

---

## 4. Tracker Audit

Stale rows found across the tracker files that claim completion of things still undone, or mark as pending things the gap-fix completed.

| File | Stale Row | Why Stale | Corrected Status |
|------|-----------|-----------|-----------------|
| `backup/SESSION_WORK_TRACKER.md` | #4 QUIC Crypto: "40% done" with ChaCha20-Poly1305 and key-phase as `🔲` pending | Gap-fix merged ChaCha20 (`f083841`) and key-phase (`aff4b6d`) into `origin/backup` | ✅ Both done |
| `backup/SESSION_WORK_TRACKER.md` | #5 Room v10: "50% done" with QuicDecodeStatus, entity fields, migration, DB version, converter all `🔲` | Commits `2814d5c`, `4977e49` added all of these | ✅ All done |
| `backup/SESSION_WORK_TRACKER.md` | #4 QUIC Crypto: QPACK production decoder, HTTP/3 stream parsing, PacketPump integration `🔲` | Gap-fix added QPACK (`12d22e1`), Http3StreamParser (`1bffb00`), DatagramRouter→PacketPump (`74b9328`). **However** PacketPump does not instantiate Http3StreamParser — end-to-end wiring incomplete. | 🟡 Components exist; not wired end-to-end |
| `backup/SESSION_WORK_TRACKER.md` | Uncommitted Changes table: `QuicCrypto.kt` "modified — handshake decrypt", `TlsDecoder.kt` "modified — parseServerHello" | These were integrated in `vorflux/v6-merge-integrate` (commits `2b86b20`, `4977e49`) | ✅ Committed and integrated |
| `backup/TODO_v5.md` | P2 row: claims `QpackDecoder.decode()` still has no production call site; HTTP/3 remains PARTIAL "by design" | Gap-fix added `Http3StreamParser` that calls `QpackDecoder.decodeFieldSection()` and `feedEncoderStream()`. The decoder has a real call site — just not wired into PacketPump for live capture. | 🟡 QPACK has a call site (Http3StreamParser); not yet wired to live capture |
| `backup/TODO_v5.md` | BUILD LOG "NEXT": "0 remaining -- ALL TODO_v5.md items now DONE or explicitly-scoped PARTIAL (only P2 remains PARTIAL, by design)" | P2 advanced by gap-fix but end-to-end wiring incomplete. Design plan (4 domains + Task 0, 36 mockups) not mentioned anywhere in TODO_v5.md — genuinely new scope. | P2 partially addressed; design plan scope is entirely unaccounted for |
| `SESSION_WORK_TRACKER.md` (root) | Tasks 10 (QPACK), 11 (Http3StreamParser), 12 (PacketPump wiring) listed as `⬜ follow-on (not in v6.0)` | Gap-fix merged QPACK (`12d22e1`), Http3StreamParser (`1bffb00`), DatagramRouter→PacketPump (`74b9328`) into `origin/backup`. Written before gap-fix merge. | 🟡 Components built and merged to origin/backup; end-to-end wiring incomplete |
| `SESSION_WORK_TRACKER.md` (root) | Tasks 14 (QUIC/HTTP-3 UI), 17 (E2E fixtures) listed as `⬜ follow-on` | Still accurate — these are genuinely not done | ❌ Still pending |
| `SESSION_WORK_TRACKER.md` (root) | Remaining Follow-ons section: "Task 7 remainder: … ChaCha20-Poly1305 … key-phase prev/current/next with auth-before-promotion" | Gap-fix merged both into `origin/backup` | ✅ Both done |
| `SESSION_WORK_TRACKER.md` (root) | Remaining Follow-ons: "Task 9 remainder: QuicDatagramRouter … the router is not" | Gap-fix merged `QuicDatagramRouter` wired into PacketPump (`62329d3`) | ✅ Done |
| `TODO_v5.md` (root) | v6.0 COMPLETION SNAPSHOT: "P2 … remains an honestly-scoped PARTIAL … QpackDecoder.decode() still has no production call site" | Gap-fix added `Http3StreamParser` which calls `QpackDecoder.decodeFieldSection()` | 🟡 Stale: QPACK has a call site now (just not in live capture path) |
| `TODO_v5.md` (root) | v6.0 COMPLETION SNAPSHOT: "Foundation follow-ons" table — lists Handshake decrypt, ServerHello, Reassembler, CidTable as "all wired at this snapshot" | Accurate — these are done | ✅ Correct |
| `TODO_v5.md` (root) | BUILD LOG: no entry for gap-fix merge (`62329d3`); no entry for design plan scope | The gap-fix merge added 3,165 lines across 13 files including 7 new test files. Design plan domains (36 mockups, 20+ files planned) are entirely new scope not tracked here. | Missing entries |

---

## Summary

| Dimension | Count | Status |
|-----------|-------|--------|
| Plan tasks fully complete | 12 of 20 | 1, 2, 3, 3b, 4, 5, 6, 7b, 13, 15, 16 done; 7, 8, 9, 12 partial |
| Plan tasks not started | 3 of 20 | 17 (E2E fixtures), 18 (PR); 14 (UI) partial |
| Gaps fixed (of 5) | 5 merged | 1 (ChaCha20), 2 (key-phase), 3 (DatagramRouter wiring), 5 (NEW/RETIRE frames) ✅; 4 (Http3/QPACK) components exist but not wired end-to-end |
| Design plan domains | 0 of 5 | Task 0 (BadgePill) + Domains A-D all zero implementation |
| Stale tracker rows found | 14 | Across `backup/SESSION_WORK_TRACKER.md`, `backup/TODO_v5.md`, root `SESSION_WORK_TRACKER.md`, root `TODO_v5.md` |

**The biggest honest gap:** the gap-fix merge (`62329d3`) built and tested all five gap components, but the end-to-end pipeline — keylog lookup → 1-RTT decrypt → `Http3StreamParser` → HTTP/3 `CapturedTrafficEntity` persistence — is unwired. `Http3StreamParser` and `QpackDecoder` exist as fully-tested standalone classes with no call site in `PacketPump`. The datagram router is wired for packet ingestion/dispatch but the final "decrypt and persist HTTP/3" step is missing. This is the critical path: once wired, Task 14 (UI), Task 17 (E2E fixtures), and the design plan's Domain A all become buildable.
