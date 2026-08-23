# REVIEW_REPORT — requestr v6.0 Judge Review

**Reviewer:** a8-judge-review (session `461393c6-d9ac-4b7f-a4e8-ecff2e213696`)
**Reviewed branch:** `vorflux/v6-build-verify` (HEAD `537230d`)
**Base:** `origin/backup` (`8194e96`)
**Plan:** `/code/.plans/v1-complete-v5-pending.md`
**Verdict:** **BLOCKED — 5 actionable blockers**

---

## Summary

The branch at `537230d` is a **work-in-progress snapshot** (the commit message itself says "wip: snapshot of in-progress accent/room work pending compile fix"). Of the plan's 18 tasks plus subtasks, roughly 4 are complete, 3 are partially implemented with significant gaps, and 11 are entirely absent. The implementation **does not compile** due to a Room type-converter registration bug.

What *is* implemented is solid: the CLI/API UTF-8 fix, the 10-accent theme engine with persistence/UI, the authenticated `/api/keylog` endpoint with SDK HTTP transport, and the foundational QuicCrypto primitives (server/client Initial, traffic-secret derivation, Retry integrity, version-aware packet classification). But the core QUIC pipeline (datagram router, CID lifecycle, QPACK, HTTP/3 parsing, PacketPump integration) and the remaining integration tasks (docs, build verification, PR) are absent.

---

## Verification Results

| Check | Status | Detail |
|-------|--------|--------|
| No stubs/placeholder comments claiming completion | ⚠️ PARTIAL | QuicCrypto doc says ChaCha20 "lands in a follow-up change"; `deriveKeysFromTrafficSecret` says "no decrypt path… drives it yet" — these are honest but confirm incomplete |
| Every function has tests | ❌ FAIL | 10+ required test files don't exist (see BLOCKER-3) |
| Room migration is non-destructive | ✅ OK | MIGRATION_9_10 uses ALTER TABLE ADD COLUMN only; no DROP |
| QuicDecodeStatus is decode-only (never CLOSED) | ✅ OK | Enum has DETECTED_ONLY, OBSERVED_NO_KEYS, PARTIALLY_DECODED, FULLY_DECODED — no CLOSED |
| Accent persistence survives process death | ✅ OK | DataStore-backed `selectedAccent` with `stringPreferencesKey` |
| Keylog ingestion is authenticated | ✅ OK | POST /api/keylog gated by same `isAuthorized()` Bearer-token check as /api/observe |
| All original todos from SESSION_WORK_TRACKER.md addressed | ❌ FAIL | Tracker itself shows 3 "in-progress" and 3 "pending" items |

---

## BLOCKER-1: QuicDecodeStatusConverter not registered with Room (compile-breaking)

**Severity:** CRITICAL — prevents compilation.

`QuicDecodeStatusConverter` is defined as a nested class in `RequestrDatabase.kt` (lines 18-27) but the `@TypeConverters(RequestrTypeConverters::class)` annotation on line 77 references only `RequestrTypeConverters`, which contains only a `Map<String,String>` converter. Room will throw a compile error because it cannot convert the `QuicDecodeStatus` field on `QuicConnectionEntity`.

**Fix:** Either add `QuicDecodeStatusConverter::class` to the `@TypeConverters` annotation, or move the converter methods into `RequestrTypeConverters`.

**Evidence:** `grep -n "QuicDecodeStatusConverter\|RequestrTypeConverters"` confirms the converter lives in `RequestrDatabase.kt:18` while `@TypeConverters` on line 77 references only `RequestrTypeConverters::class`.

---

## BLOCKER-2: Core QUIC pipeline (Tasks 9-12) entirely absent

**Severity:** CRITICAL — the plan's central deliverable (full QUIC/HTTP-3 decode) has no implementation beyond Initial packet decryption.

None of these exist:
- `QuicDatagramRouter.kt` (Task 9 — v4/v6 coalesced ingestion, CID routing, STREAM/CRYPTO reassembly)
- `QuicStreamReassembler.kt` (Task 9 — byte-offset reassembly)
- `QuicCidTable.kt` (Task 8 — CID lifecycle, NEW/RETIRE_CONNECTION_ID, multi-CID, Retry re-key)
- Production `QpackDecoder` rewrite (Task 10 — RIC/Base, dynamic table, encoder/decoder streams)
- `Http3StreamParser.kt` (Task 11 — control streams, request HEADERS/DATA)

The only PacketPump change (Task 12) adds `decodeStatus`/`lifecycleState` to the initial `QuicConnectionEntity` creation — no Handshake decrypt, no 1-RTT decrypt, no HTTP/3 row persistence, no key-miss handling.

**Evidence:** `find app/src -name "QuicCidTable.kt"` and similar searches return empty; `QpackDecoder.kt` still has the old stub with `pos += 2` hardcoded skip.

---

## BLOCKER-3: 10+ required test files missing; no Migration9To10Test

**Severity:** HIGH — no proof the migration works, no proof of CID lifecycle, QPACK, HTTP/3 parsing, or end-to-end decrypt.

Missing test files the plan requires:
- `Migration9To10Test` (Task 8 — v9 rows survive, defaults, FULLY_DECODED + CLOSED round-trip)
- `QuicCidTableTest` (Task 8 — NEW/RETIRE_CONNECTION_ID, multi-CID, zero-length DCID)
- `QuicRetryTest` (Task 8 — v1/v2 valid+invalid integrity tags)
- `QuicDatagramRouterTest` (Task 9 — coalesced, v6, STREAM reassembly, retx dedupe)
- `QpackProductionTest` (Task 10 — RIC/Base, dynamic table, blocked→unblock)
- `Http3StreamParserTest` (Task 11 — SETTINGS, HEADERS, DATA)
- `PacketPumpQuicTest` (Task 12 — key-miss, key-present, close, duplicate dedupe)
- `TlsServerHelloTest` (Task 7b — each suite, malformed returns null)
- `AccentPersistenceTest` (Task 6 — updateAccent round-trip, survives reload, defaults INDIGO)
- `Http3EndToEndFixtureTest` (Task 17 — per-suite, Retry, key-phase, key-miss)

The 25 tests in `QuicCryptoTest` cover Initial keys, Retry integrity, and PN reconstruction well — but no ChaCha20, no short-header decrypt, no key-phase, no handshake-secret decrypt.

**Evidence:** `find app/src/test` confirms none of these filenames exist.

---

## BLOCKER-4: QuicCrypto implementation gaps (Task 7 incomplete)

**Severity:** HIGH — blocks all downstream QUIC work.

- **ChaCha20-Poly1305**: explicitly deferred. `CipherSuite` enum only lists AES-128-GCM and AES-256-GCM; the enum's kdoc says "TLS_CHACHA20_POLY1305_SHA256 is intentionally not listed yet… lands in a follow-up change."
- **No `decryptShortHeaderPacket`**: 1-RTT packets cannot be decrypted.
- **No key-phase**: prev/current/next with auth-before-promotion not implemented.
- **No Handshake decrypt path driven by traffic secrets**: `deriveKeysFromTrafficSecret` exists but its kdoc says "no decrypt path in this file drives it yet."
- **`hkdfExpandLabel` is private**: the plan requires it to be `internal` so consumers can use it with context.

**Evidence:** grep for `decryptShortHeader\|keyPhase\|ChaCha20` returns no relevant matches; `deriveKeysFromTrafficSecret` doc (line 183) confirms it's unused.

---

## BLOCKER-5: Tasks 7b, 14-18 entirely absent (TlsDecoder, QUIC UI, docs, build, PR)

**Severity:** HIGH — the plan's remaining deliverables are untouched.

- **Task 7b**: `TlsDecoder.parseServerHello` does not exist — `grep parseServerHello TlsDecoder.kt` returns empty. Without this, the negotiated cipher suite cannot be determined.
- **Task 14**: QUIC/HTTP-3 UI in `InterceptScreen.kt` not implemented — the plan requires decode-status-driven badges and detail sheets.
- **Task 15**: No `RELEASE_NOTES_v6.0.md`, no v6.0 version bump (still `versionCode=27`, `versionName="5.9-backup"`), no `TODO_v5.md` update, no superseded banners on audit docs.
- **Tasks 16-18**: No host build, no Redroid verification, no PR.

---

## What passed verification

These areas are correctly implemented and well-tested:

1. **CLI/API UTF-8 fix** (Task 4): `handleClient()` now encodes to UTF-8 `ByteArray`, measures byte length for `Content-Length`, writes raw bytes. `listCaptures`/`listFlows`/`listTools` rebuilt on `org.json.JSONArray`/`JSONObject`. Tests: `LocalApiServerToolsEncodingTest` (2 tests), `ToolsParseTest` (4 tests).

2. **Accent theme** (Tasks 5-6): `AppAccent` enum with 10 accents, `buildLightColorScheme`/`buildDarkColorScheme`, DataStore-backed `selectedAccent` persistence, `MainActivity` resolution via `MyApplicationTheme(isDark, accent)`, Settings/Onboarding/Drawer accent UI. Tests: `AccentThemeTest` (8 tests).

3. **Keylog authentication** (Task 13): `POST /api/keylog` gated by `isAuthorized()` Bearer-token check (same as `/api/observe`), SDK HTTP transport with Bearer header, NSS line validation delegated to `TlsKeyLogRegistry.importNssKeyLog`. Tests: `LocalApiServerKeylogTest` (5 tests), `KeyLogProducerHttpTest` (6 tests).

4. **QuicCrypto primitives**: Server/client Initial key derivation, RFC 9001 A.1/A.2/A.3 test vectors, `deriveKeysFromTrafficSecret` for AES-128-GCM and AES-256-GCM, `decodePacketNumber`, `classifyLongHeaderPacketType` (v1/v2), `validateRetryIntegrityTag` (v1/v2 valid + corrupted). Tests: `QuicCryptoTest` (25 tests).

5. **QuicDecodeStatus design**: Correctly decode-only (no CLOSED), separate lifecycle on `QuicConnectionEntity`, migration SQL is non-destructive ALTER TABLE.

6. **git setup** (Tasks 1-3): `archive/main-v5.7` tag, `docs/legacy-main/` with `main`'s unique content preserved.
