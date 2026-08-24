# .md File Audit Report — requestr

**Audited:** 2026-08-24 · **HEAD:** `1554f66` (`origin/backup`) · **Plan:** `/code/.plans/v1-complete-v5-pending.md`
**Scope:** every `.md` file at repo root (+ `.agent-briefs/`, `backup/`, `browser-extension/`, `sdk/`); `docs/` and `.git/` excluded.
**Method:** file contents cross-referenced against `git log origin/backup --oneline --all`, tags (`v2.1`…`v5.9-backup`), and the actual Kotlin/test sources at HEAD.

**Verdict at a glance:** 0 files are wholly FAKE; 27 files are STALE (historical/superseded, claims were honest when written); 2 files are DUPLICATEs; 3 files contain fake completion claims **within an otherwise-honest KEEP file** (`RELEASE_NOTES_v6.0.md` — the claims were added by commit `c5f8c31` and do not exist in code); 6 files are current and kept by policy.

---

## Summary

| File | Status | Lines | Issues |
|------|--------|-------|--------|
| `.agent-briefs/phase-a-foundation.md` | STALE | 139 | Phase-A brief for archived `main`; foundation long since built; superseded |
| `.release_v3.5_body.md` | STALE | 29 | v3.5 GitHub release body; superseded by v6.0; duplicates RELEASE_NOTES_v3.5.md claims |
| `.release_v3.6_body.md` | STALE | 32 | v3.6 release body; superseded |
| `.release_v3.7_body.md` | STALE | 51 | v3.7 release body (28/50 TODO); claims verified true; superseded |
| `.release_v3.9_body.md` | STALE | 53 | v3.9 release body (44/50 TODO); claims verified true (Flow canvas, Chats, HAR, Diff); superseded |
| `APP.md` | DUPLICATE | 766 | v1 design brief; near-identical to requestr_stitch_prompt.md (differs only in "Android 17" vs "Android 16") |
| `APP_v2.md` | STALE | 554 | v2.1 revision spec; premise rebutted in FIXLOG.md; fixes implemented |
| `AUDIT_REPORT.md` | STALE | 525 | v3.4 `main` deep audit + v5.8/SDK/pre-v8 addenda; superseded by trackers + PROCESS_VS_GAPS.md |
| `BUGS.md` | STALE | 110 | v2.4 registry "ALL 20 bugs verified fixed"; claim verified true; registry superseded |
| `FIXLOG.md` | STALE | 164 | v2.1–v2.3 action log ("all phases [x], 42 action rows"); completed; superseded |
| `GAP_ANALYSIS.md` | STALE | 423 | v3.4 gap matrix vs visions (~22%/35%/60%); most gaps implemented since |
| `MODERNIZATION_OF_INTERCEPTION.md` | STALE | 1619 | v3-era vision spec; largely implemented (browser/, runtime/, correlation/, grpc/, gateway/) |
| `NEWFIXES.md` | STALE | 1777 | v2.5 work-order "ready for implementation"; fixes landed in v2.5 tags |
| `README.md` | HONEST | 208 | Current project overview — KEEP (policy) |
| `RELEASE_NOTES_v3.5.md` | STALE | 172 | v3.5 release report; honest (NOT COMPLETED items match later commits); superseded |
| `RELEASE_NOTES_v4.0.md` | STALE | 60 | v4.0 notes; claims verified true (MediaPlayer, Chat, tools, GlassCard); superseded |
| `RELEASE_NOTES_v6.0.md` | **FAKE-IN-PARTS** | 282 | KEEP (policy) but contains false completion claims (see per-file findings) |
| `REVIEW_REPORT.md` | STALE | 101 | build-verify review (BLOCKED — stale docs); blocker fixed by `4b25e76`; superseded by docs/ reviews |
| `Requestr-Feature-Vision-V3.md` | STALE | 1345 | v3 vision spec (aspirational); superseded |
| `SESSION_WORK_TRACKER.md` | HONEST | 181 | v6.0 session tracker; conservative "follow-on" claims match code — KEEP (policy) |
| `TODO.md` | HONEST | 165 | Tracker; all 50 + 9 v6.0 items verified true — KEEP (policy) |
| `TODO_v5.md` | STALE | 228 | v5/v6 tracker at 2026-08-23 snapshot; superseded by gap-fix merge; not on keep list |
| `UNTESTED_AREAS_DEEP_ANALYSIS.md` | STALE | 486 | v3.4 deep analysis; many STUB/MISSING rows implemented since |
| `V3_IMPLEMENTATION_PLAN.md` | STALE | 45 | v3.0 session plan; implemented |
| `V3_MASTER_PLAN.md` | STALE | 24 | v3.0 "Building everything" plan; all 7 phases implemented (v3.0–v3.5) |
| `api_testr_screen_rewrite.md` | STALE | 353 | Screen rewrite spec; implemented |
| `requestr_nav_pill_spec.md` | STALE | 328 | Bottom-nav pill spec; implemented |
| `requestr_stitch_prompt.md` | DUPLICATE | 766 | v1 design brief; duplicate of APP.md |
| `requestr_v2.1_revision_spec.md` | STALE | 838 | v2.1 spec; superseded |
| `requestr_v3.1_stub_elimination_plan.md` | STALE | 641 | v3.1 stub-removal spec; Part A–C implemented (v3.2–v3.4 commits) |
| `requestr_v5_plan.md` | STALE | 1795 | v5 plan (26 missing feature groups); nearly all since implemented |
| `requestr_v5_plan_refined.md` | STALE | 540 | v5 plan analysis ("TODO.md is lying" — true at v4.3, verified); superseded by _v2 |
| `requestr_v5_plan_refined_v2.md` | STALE | 467 | v5 plan v2; superseded by implementation |
| `root_plan.md` | STALE | 578 | Root/non-root fix plan; its 6 bugs fixed in v5-era work (see TODO_v5 C1–C13) |
| `backup/*.md` | ARCHIVE | 15748 (27 files) | Archive copies from `main`; audited, NOT deleted per policy |
| `browser-extension/README.md` | HONEST | 230 | MV3 extension module doc; matches real code; NOT deleted per policy |
| `sdk/README.md` | HONEST | 149 | SDK module doc; matches real code; NOT deleted per policy |
| `PROCESS_VS_GAPS.md` | HONEST | 120 | Plan-vs-status report (2026-08-24); already present; KEEP, untouched |
| `MD_AUDIT_REPORT.md` | NEW | — | This file |

---

## Per-file findings

### FAKE completion claims found (in a KEEP file)

**`RELEASE_NOTES_v6.0.md` — STALE/FALSE in sections 5(quote), 9, 10 and 2 table rows; recommended action: KEEP (policy) but CORRECT against code (PROCESS_VS_GAPS.md already holds the honest state).**

Verified against code at HEAD `1554f66` (commit `c5f8c31` "docs: reflect full QUIC pipeline scope" is the commit that added the false claims):

| Claim in file | Actual code state at HEAD |
|---|---|
| §9 "PacketPump QUIC Wiring": "Keylog-driven decrypt: Initial (extract ClientHello random) → Handshake (look up CLIENT/SERVER_HANDSHAKE_TRAFFIC_SECRET) → 1-RTT short-header (look up CLIENT/SERVER_TRAFFIC_SECRET_0, deriveKeysFromTrafficSecret, decryptProtectedPayload)" | **False.** `PacketPump.ingestQuicDatagram` → `QuicDatagramRouter.ingest` → `persistQuicConnection` only. No `TlsKeyLogRegistry` lookup (only a docstring mention at PacketPump.kt:128), no `deriveKeysFromTrafficSecret`/`decryptProtectedPayload`/`decryptShortHeaderPacket` call site anywhere in `PacketPump.kt`. Router comment: "1-RTT decryption is a separate, key-log-sourced path" (QuicDatagramRouter.kt:233). |
| §9 "Honest status tracking: no keys → OBSERVED_NO_KEYS; handshake decrypted → PARTIALLY_DECODED; 1-RTT decrypted → FULLY_DECODED; close → CLOSED lifecycle with decode status preserved" | **False.** Persisted row hardcodes `QuicDecodeStatus.DETECTED_ONLY` (PacketPump.kt:482); in-memory QuicState only reaches INITIALIZING/HANDSHAKE_IN_PROGRESS. |
| §9 "Persistence: decoded HTTP/3 → CapturedTrafficEntity with protocol='HTTP/3', wasDecrypted=true…; key-miss → isUndecoded=true row…" | **False.** `PacketPump.kt` never constructs a `CapturedTrafficEntity` and never instantiates `Http3StreamParser`. |
| §9 "Tests: 4 (key-miss → undecoded row + OBSERVED_NO_KEYS, duplicate Initial dedupe, key-present → FULLY_DECODED, CONNECTION_CLOSE preserves decode status)" | **False.** The real `PacketPumpQuicDatagramTest.kt` (3 tests) asserts `decodeStatus == QuicDecodeStatus.DETECTED_ONLY` and SNI persistence only. |
| §10 "QUIC tab… drives badges/detail off the authoritative QuicDecodeStatus column" | **False.** `InterceptScreen.kt:1946-1947` derives badges from in-memory `quicLiveMap[conn.connectionId]?.decodedFrames == true`; `TlsKeyLogRegistry.entryCount` (line 1749) is shown but the column is not the badge source. |
| §5 quote "PacketPump.ingestQuicDatagram now drives decodeStatus/lifecycleState end-to-end from real keylog-driven decryption — Initial → Handshake → 1-RTT with honest status transitions and persistent CapturedTrafficEntity rows." | **False.** Same evidence as §9. |
| "Resolved v5 Pending Items" rows "PacketPump QUIC decrypt wiring \| ✅ DONE \| Keylog → decrypt → HTTP/3 persistence + 4 tests" and "QUIC/HTTP-3 UI honest states \| ✅ DONE \| … + 7 tests" | **False** (evidence above). |
| "Files Changed — Tests: … `PacketPumpQuicTest` (+4), `QuicDecodeStatusBadgeTest` (+7), `AccentPersistenceTest`, `QpackDecoderTest` (+10), `Http3StreamParserTest` (+9)" | **Nonexistent test files:** `QuicDecodeStatusBadgeTest` and `AccentPersistenceTest` do not exist anywhere under `app/src/test` (only `AccentThemeTest`). Real counts: QpackDecoderTest 3 + QpackProductionTest 8 = 11; Http3StreamParserTest 6; PacketPumpQuicDatagramTest 3. |

Claims in the same file that **are true** (verified): UTF-8 Content-Length fix + `org.json` in `LocalApiServer.kt` (line 261 `/api/keylog`; `LocalApiServerToolsEncodingTest` exists); 10-accent theme (`AppAccent` in `ui/theme/Theme.kt`, `K.SELECTED_ACCENT`, pickers in Settings/Onboarding/Drawer); bearer-gated `POST /api/keylog` + SDK `KeyLogProducer` HTTP primary (`KeyLogProducerHttpTest` exists); Room v10 (`QuicDecodeStatus` enum in Entities.kt, `MIGRATION_9_10`, `10.json`); `decryptHandshakePacket` (3 refs in QuicCrypto.kt); `TlsDecoder.parseServerHello` (2 refs); `QuicStreamReassembler` (13 tests); `QuicCidTable` (14 tests); production `QpackDecoder` + `Http3StreamParser` + `QuicDatagramRouter` (files exist with tests); v1/v2 Retry integrity + PN reconstruction; ChaCha20-Poly1305 and key-phase rotation (commits `f083841`, `aff4b6d` — the "Remaining/Known Limits" section is therefore also slightly stale, under-claiming).

Recommended action: **KEEP** (per task policy) — flag for correction against `PROCESS_VS_GAPS.md`.

### HONEST — keep (policy)

- **`README.md`** — current project overview; no false claims. KEEP.
- **`TODO.md`** — tracker. All 50 numbered items are `[x]` and every sampled claim was verified in source (FlowExecutor DELAY/TOOL/CONDITION/LOOP/TRANSFORM/INTERCEPT_TRIGGER at FlowExecutor.kt:109-120; WebSocketParser/SseParser/DnsParser/HarImporter/TextDiffer/ChatScreen/plugin/WorkspaceExporter all present; ProGuard + keyboard shortcuts in MainActivity.kt:138-157). The 9 v6.0 items it lists as "wired at this snapshot" are all true at HEAD. KEEP per policy.
- **`SESSION_WORK_TRACKER.md`** — v6.0 tracker; says Tasks 10-12/14/17 are follow-ons — this is *more* accurate than the release notes and matches code (QPACK/Http3/DatagramRouter exist but are not driven by PacketPump). KEEP per policy.
- **`PROCESS_VS_GAPS.md`** — already present (2026-08-24, HEAD `1554f66`); documents the honest plan-vs-status including the exact PacketPump/QPACK/1-RTT gaps above. KEEP, do not touch.
- **`browser-extension/README.md`** — MV3 extension README describing real shipped code (`src/`, sdk modules, DNR enforcement). HONEST. NOT deleted per policy.
- **`sdk/README.md`** — Observer SDK README (`com.requestr.sdk` module, `RequestrSdk.init`, keylog producers) matching real code. HONEST. NOT deleted per policy.

### STALE — historical/superseded (claims were honest when written; code has moved on)

- **`.agent-briefs/phase-a-foundation.md`** — Phase A interception-core foundation brief pinned to branch `main` (now archived `archive/main-v5.7`). The substrate it specifies (Room, DataStore, InterceptionState, probes contract, manifest) exists today; brief is execution history. DELETE.
- **`.release_v3.5_body.md` / `.release_v3.6_body.md` / `.release_v3.7_body.md` / `.release_v3.9_body.md`** — GitHub release bodies for v3.5–v3.9. Every claimed completion was verified in code+commits (v3.5 engine dispatch `e07cb7e`; v3.6 rules-engine all-actions/VPN hot-reload `0717762`; v3.7 FlowExecutor/WS/SSE/deeplinks/ProGuard `d2d4592`; v3.9 44/50 TODO + Flow rewrite + Chats + HAR + Diff `a8627c3`/`5d90bdf`; FlowScreen pan/zoom/mini-map/long-press at FlowScreen.kt). Project is now v6.0 → superseded. DELETE.
- **`APP_v2.md`** — v2.1 revision spec whose "post-build QA pass" premise was formally refuted in `FIXLOG.md` ("high-fidelity UI prototype with no backing infrastructure"); the missing core was then genuinely built (Room/DataStore/VpnService/real probes in v2.2-v2.4 commits). Recommendation: DELETE (kept only for history if desired — a copy lives in `backup/`).
- **`AUDIT_REPORT.md`** — v3.4 `main` deep audit (2026-08-20) with v5.8-backup / SDK / pre-v8 addenda. The addenda honestly disclose real fixes (TunRelay subsystem verified present under `interception/tun/`; keystore-password split verified; destructive migration verified). Base audit describes a v3.4 snapshot; superseded by `TODO_v5.md`→`PROCESS_VS_GAPS.md`. DELETE.
- **`BUGS.md`** — v2.4 registry, "ALL 20 bugs verified fixed — full code audit completed 2026-08-18 13:42 UTC". Verified: the 20 INF/FAKE/CRASH/PERSIST bugs are genuinely fixed in today's code. Honest at the time, but the registry is frozen at v2.4 and no longer maintained. DELETE.
- **`FIXLOG.md`** — v2.1–v2.3 fix log ("Complete FIXLOG: all phases [x], 42 action rows" — commit `f44cb6f`). Completed action log. DELETE.
- **`GAP_ANALYSIS.md`** — v3.4 gap matrix (MODERNIZATION ~22%, Vision V3 ~35%, v3.1 plan ~60%). Almost every "MISSING/STUB" row (HTTP/2 #13 via Http2Parser.kt, WS #14, SSE #15, DNS #16, QUIC #19, rules #29, flow-integration #41, plugins, Local API, CLI, workspace) is now implemented. Honest snapshot; superseded. DELETE.
- **`MODERNIZATION_OF_INTERCEPTION.md`** — v3-era vision spec (46 sections). Aspirational; no completion claims; the architecture it targets now substantially exists (browser/CDP `com/example/browser/`, runtime `com/example/runtime/`, correlation `com/example/correlation/`, gateway `GatewayEngine.kt`, gRPC `GrpcDetector.kt`, MV3 extension in `browser-extension/`). DELETE.
- **`NEWFIXES.md`** — v2.5 work-order ("Status: Active — 10 fixes documented, ready for implementation"). Fixes landed across the v2.5 commit series (`354617f`, `b51c78a`, `631dd39`, `17a14bb`, `f1ee64a`, `a25fc9d`). DELETE.
- **`RELEASE_NOTES_v3.5.md`** — v3.5 release report with honest IMPLEMENTED/NOT-COMPLETED matrix; matches commits; superseded by v6.0. DELETE.
- **`RELEASE_NOTES_v4.0.md`** — v4.0 notes (ProGuard `-dontoptimize`, MediaPlayer in AppRepositories/AppModels, ChatScreen, custom tools, GlassCard fix — all verified in source). Superseded. DELETE.
- **`REVIEW_REPORT.md`** — build-verify integration review at `008333b` with verdict **BLOCKED — documentation is stale**. The blocker it raised was resolved by `4b25e76` (docs now mark the 4 items DONE). The report's own claim ("BLOCKED") no longer reflects state; superseded by the docs/ review lineage (`docs/REVIEW_REPORT_JUDGE.md`). DELETE.
- **`Requestr-Feature-Vision-V3.md`** — v3 vision spec (Capture→Understand→…→Automate). Aspirational; superseded. DELETE.
- **`TODO_v5.md`** — v5/v6 implementation tracker (2026-08-23 completion snapshot). Its P2 rows claim QPACK/Http3/DatagramRouter/PacketPump integration "remain follow-ons" — honest at write-time; HEAD has since merged those (gap-fix `62329d3`, `12d22e1`, `1bffb00`, `0b4bbdb`) though still not PacketPump-driven (see PROCESS_VS_GAPS). Superseded by kept `TODO.md` + `PROCESS_VS_GAPS.md`; not on the keep list. DELETE.
- **`UNTESTED_AREAS_DEEP_ANALYSIS.md`** — v3.4 deep analysis of device-limited areas (root/shizuku/frida/CA). Its "never instantiated engines" findings were fixed by the dispatch wiring (v3.5, `e07cb7e`). Honest at the time; superseded. DELETE.
- **`V3_IMPLEMENTATION_PLAN.md`** — v3.0 session plan (traffic model, inspector tabs, search, exporters, environments). All phases correspond to implemented code (`TrafficClassifier.kt`, `TrafficSearch.kt`, `CodeExporter.kt`, `Environment.kt`, 6-tab inspector). DELETE.
- **`V3_MASTER_PLAN.md`** — v3.0 "Status: LOCKED. Building everything. No stubs." — all 7 phases (root iptables, shizuku, MITM, JWT decoder, replay, foreground capture, Frida engine) implemented in v3.0-v3.5 commits. DELETE.
- **`api_testr_screen_rewrite.md`** — API Testr builder screen rewrite spec (v3.x era). The rewritten screen is in code; spec is inert. DELETE.
- **`requestr_nav_pill_spec.md`** — bottom-nav "collapse-to-icon+label pill" spec; the nav pill exists in the app ("CollapsibleNavPill" per commit `e39bdbf`). DELETE.
- **`requestr_v2.1_revision_spec.md`** — v2.1 QA spec; superseded (v2.1 fixes shipped in v2.2-v2.5 tags). DELETE.
- **`requestr_v3.1_stub_elimination_plan.md`** — v3.1 "remove all stubs" spec; Parts A/B/C implemented in v3.1-v3.4 commits (`4e98535`, `7b5f961`, `91d6867`, `40c0b37`, `d78d223`). DELETE.
- **`requestr_v5_plan.md`** — v5 plan (26 missing feature groups: CDP, browser ext, correlation, provenance, HTTP/3, gRPC, DoH/DoT, gateway, pcap, native sockets, runtime instrumentation). Nearly all groups now have code (browser/, runtime/, correlation/, grpc/, gateway/, protobuf, MV3 ext). DELETE.
- **`requestr_v5_plan_refined.md`** — v5 plan analysis; contains the (then-true) claim "TODO.md is lying" — verified against v4.3-era code; superseded by `_v2` and by implementation. DELETE.
- **`requestr_v5_plan_refined_v2.md`** — v5 plan v2 (implementation plan). Superseded by the v5.0-v5.7 + v6.0 implementation. DELETE.
- **`root_plan.md`** — "COMPLETE ROOT + NON-ROOT FIX PLAN" (bug 1-6 analysis: TLS discard, no MITM in VPN chain, hardcoded wasDecrypted, tier default, unused CA, watcher). All six fixed in v5-era work (see TODO_v5 C1-C13: `if (stream.isTls && !stream.decryptedByMitm) return`, `wasDecrypted`/`isUndecoded` markers, decryptedTransactionCount, etc.). DELETE.

### DUPLICATE

- **`APP.md` / `requestr_stitch_prompt.md`** — identical 766-line v1 design briefs; `diff` shows a single differing line ("Android 5+ to Android 17" vs "…16"). Both STALE/DUPLICATE. DELETE both (archived copies exist in `backup/`).

### ARCHIVE / OUT OF DELETION SCOPE (audited only)

- **`backup/*.md` (27 files)** — archive copies of the root documents (verified byte-identical for 11 sampled files; TODO.md/TODO_v5.md differ slightly). Untracked archive of `main`-branch docs. Per policy: NOT deleted.
- **`docs/`** — `MERGE_REPORT.md`, `REVIEW_REPORT_JUDGE.md`, `legacy-main/` (archived `main`-unique docs incl. `MASTER_PLAN_AND_FIX_SPEC.md` + 10-accent theme snapshots), `plans/`, `designs/`. NOT in audit scope, NOT deleted.

---

## Deletion list

Files recommended for deletion (all tracked; executed via `git rm` below):

```
.agent-briefs/phase-a-foundation.md
.release_v3.5_body.md
.release_v3.6_body.md
.release_v3.7_body.md
.release_v3.9_body.md
APP.md
APP_v2.md
AUDIT_REPORT.md
BUGS.md
FIXLOG.md
GAP_ANALYSIS.md
MODERNIZATION_OF_INTERCEPTION.md
NEWFIXES.md
RELEASE_NOTES_v3.5.md
RELEASE_NOTES_v4.0.md
REVIEW_REPORT.md
Requestr-Feature-Vision-V3.md
TODO_v5.md
UNTESTED_AREAS_DEEP_ANALYSIS.md
V3_IMPLEMENTATION_PLAN.md
V3_MASTER_PLAN.md
api_testr_screen_rewrite.md
requestr_nav_pill_spec.md
requestr_stitch_prompt.md
requestr_v2.1_revision_spec.md
requestr_v3.1_stub_elimination_plan.md
requestr_v5_plan.md
requestr_v5_plan_refined.md
requestr_v5_plan_refined_v2.md
root_plan.md
```

**Kept:** `README.md`, `TODO.md`, `SESSION_WORK_TRACKER.md`, `RELEASE_NOTES_v6.0.md` (flagged for correction above), `MD_AUDIT_REPORT.md` (this file), `PROCESS_VS_GAPS.md`, everything under `docs/`, `backup/`, `browser-extension/README.md`, `sdk/README.md`.

---

## Deletion execution (run at 2026-08-24)

The 30 files above were removed from the repository working tree and index on branch `backup`:

```text
(see `git status` output in the audit session transcript — each file above listed as "deleted:")
```
