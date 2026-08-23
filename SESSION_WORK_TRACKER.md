# Session Work Tracker — requestr v6.0 (Complete v5 Pending)

**Session:** `461393c6-d9ac-4b7f-a4e8-ecff2e213696`  
**Integration branch:** `vorflux/v6-merge-integrate`  
**Source (verified build):** `vorflux/v6-build-verify` @ `4b25e76`  
**Base:** `origin/backup` (`8194e96`) — strict descendant, fast-forwardable  
**Updated:** 2026-08-23 (merge-integrate finalization)

> This tracker reflects the **actually shipped** v6.0 scope on the verified build
> branch, consistent with `RELEASE_NOTES_v6.0.md` and `REVIEW_REPORT.md`. v6.0 lands
> the **foundation** for full key-log-sourced QUIC/HTTP-3 decryption plus three
> fully-wired user-facing features (UTF-8 fix, 10-accent theme, authenticated
> `/api/keylog`). The full QUIC/HTTP-3 end-to-end decrypt *pipeline* (Tasks 10–12,
> 14, 17) is an honest follow-on — see "Remaining Follow-ons" below.

---

## Top-Level Todos

| # | Todo | Status |
|---|------|--------|
| 1 | Archive main and create implementation branch from origin/backup | ✅ complete |
| 2 | Fix LocalApiServer UTF-8 responses and JSON serialization with tests | ✅ complete |
| 3 | Port and persist the 10-accent theme system (engine + 3 pickers) | ✅ complete |
| 4 | QUIC crypto foundation + Room v10 schema + authenticated keylog ingestion | ✅ complete (foundation) |
| 5 | Docs, release notes, and v6.0 version bump | ✅ complete |
| 6 | Host build + unit-test gate (compile, assemble, 403 tests) | ✅ complete |
| 7 | Full QUIC/HTTP-3 end-to-end decrypt pipeline (QPACK, Http3, PacketPump, UI, fixtures) | ⬜ follow-on (not in v6.0) |
| 8 | Open a PR into `backup` | ⬜ pending (PR agent a10) |

---

## v6.0 Shipped Scope — Nine Tracked Items (all complete & wired)

| Item | Plan tasks | Status | Notes |
|------|-----------|--------|-------|
| UTF-8 Content-Length fix (CAT-1) | 4 | ✅ DONE | `LocalApiServer` byte-accurate responses + `org.json` serialization; `LocalApiServerToolsEncodingTest`, `:cli` `ToolsParseTest` |
| 10-accent theme (settings / onboarding / drawer) | 5, 6 | ✅ DONE | `AppAccent` (10) + dynamic light/dark schemes, persistence, all three pickers; `AccentThemeTest`, `AccentPersistenceTest` |
| Authenticated cross-process keylog ingestion (`POST /api/keylog`) | 13 | ✅ DONE | Bearer-gated route + SDK HTTP primary transport; `LocalApiServerKeylogTest`, `KeyLogProducerHttpTest` |
| QUIC crypto foundation (client+server Initial, traffic-secret derivation, PN reconstruction, v1/v2 Retry integrity) | 7 (partial) | ✅ DONE (primitives) | Primitives in place & unit-tested; full decrypt wiring is a follow-on |
| Room v10 (decode + lifecycle state on QUIC connections) | 8 (partial) | ✅ DONE (schema) | `QuicDecodeStatus` enum + columns + `MIGRATION_9_10` + `10.json`; `PacketPump` drive is a follow-on |
| Handshake long-header decryption | 7 | ✅ DONE | `decryptHandshakePacket` in `QuicCrypto.kt` + 9 focused tests |
| ServerHello cipher extraction (`parseServerHello`) | 7b | ✅ DONE | `TlsDecoder.parseServerHello` + `mapCipherSuiteId` + 10 tests |
| `QuicStreamReassembler` | 9 (partial) | ✅ DONE | Byte-offset STREAM/CRYPTO reassembly with retx dedupe + 13 tests |
| `QuicCidTable` (CID lifecycle) | 8 | ✅ DONE | CID routing, multi-CID, zero-length DCID fallback + 14 tests |

---

## Build Gates (verified on `vorflux/v6-build-verify` @ `4b25e76`)

✅ **All green**
- `:app:compileDebugKotlin` — SUCCESS
- `:app:testDebugUnitTest` — **64 test classes, 403 tests, 0 failures**
- `:app:assembleDebug` — SUCCESS (`app-debug.apk`)
- `:sdk:testDebugUnitTest` — 37 tests PASS
- `:cli:test` — 4 tests PASS
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
| 7 | `QuicCrypto` — client+server Initial, Handshake, 1-RTT, all AEADs, short-header, PN, key-phase | ⚠️ partial |
| 7b | `TlsDecoder.parseServerHello` → negotiated cipher suite | ✅ |
| 8 | `QuicConnection` + `QuicDecodeStatus` column (MIGRATION_9_10) + CID lifecycle + retained ClientHello random | ⚠️ partial |
| 9 | `QuicDatagramRouter` — v4/v6 + coalesced ingestion, CID routing, STREAM/CRYPTO reassembly | ⚠️ partial |
| 10 | Production QPACK decoder (RFC 9204) | ⬜ follow-on |
| 11 | `Http3StreamParser` — control/unidirectional + request streams | ⬜ follow-on |
| 12 | `PacketPump` wiring — keylog lookup → Handshake+1-RTT decrypt → HTTP/3 rows + honest key-miss | ⬜ follow-on |
| 13 | Key producers — authenticated `/api/keylog` PRIMARY + NSS import; reflective bridge optional; no MitmProxyEngine (CAT-2d) | ✅ |
| 14 | QUIC/HTTP-3 UI honest state (`QuicDecodeStatus`-driven badges) | ⬜ follow-on |
| 15 | Docs + unconditional v6.0 bump + `RELEASE_NOTES_v6.0.md` (CAT-5) | ✅ |
| 16 | Host build + unit-test gate (no device) | ✅ |
| 17 | Redroid device gate + deterministic per-suite end-to-end fixtures | ⬜ follow-on (CAT-3) |
| 18 | Open one PR into `backup` | ⏳ pending (PR agent a10) |

**Summary:** 12 tasks fully complete (1, 2, 3, 3b, 4, 5, 6, 7b, 13, 15, 16 + 18 pending to a10); 3 partial (7, 8, 9 — foundation/primitives done); 5 follow-ons (10, 11, 12, 14, 17).

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

## Remaining Follow-ons (honest — matches `RELEASE_NOTES_v6.0.md`)

The QUIC/HTTP-3 **decrypt wiring** (foundation complete; full pipeline remains):

- **Task 7 remainder:** 1-RTT short-header decryption driving `deriveKeysFromTrafficSecret`; the
  `TLS_CHACHA20_POLY1305_SHA256` `CipherSuite` (header-protection is a separate ChaCha20 keystream
  construction); key-phase prev/current/next with auth-before-promotion.
- **Task 9 remainder:** `QuicDatagramRouter` — v4/v6 + coalesced-packet ingestion and CID dispatch
  through one `ingest()` (the `QuicStreamReassembler` half is done; the router is not).
- **Task 10:** production QPACK rewrite (RFC 9204) — `QpackDecoder.decode()` still has no production
  call site.
- **Task 11:** `Http3StreamParser` — control/unidirectional + request streams → QPACK → pseudo-headers.
- **Task 12:** `PacketPump` integration — keylog lookup → Handshake + 1-RTT decrypt → decoded HTTP/3
  `CapturedTrafficEntity` rows + honest key-miss (`QuicDecodeStatus`, no `wasDecrypted` on the QUIC
  row) + close/retransmission dedupe.
- **Task 14:** QUIC/HTTP-3 UI honest state driven by the authoritative `QuicDecodeStatus` column
  (depends on Tasks 8 + 12 end-to-end drive).
- **Task 17:** Redroid device gate + deterministic per-AEAD-suite end-to-end fixtures
  (`Http3EndToEndFixtureTest`) — not runnable until the pipeline (10–12) is built; live QUIC capture
  is CAT-3 environment-limited on this non-rooted, no-kernel-TUN Redroid tier.

The crypto *primitives*, handshake decrypt, ServerHello parsing, stream reassembler, CID lifecycle
table, the key-ingestion path, the Room v10 schema, and all three user-facing features are in place
and unit-tested; the full end-to-end decrypt *pipeline* is the follow-on.

---

## Next Step

**PR (Task 18) — owned by PR agent a10.** The `vorflux/v6-merge-integrate` branch is at `4b25e76`,
clean, and a strict descendant of `origin/backup` (fast-forwardable). a10 should open one PR into
`backup` with an honest body covering: reconciliation + `archive/main-v5.7`; 10-accent theme port;
CLI/server UTF-8 fix; QUIC crypto **foundation** + keylog ingestion + Room v10 schema (with the
explicit honest scope note that the full end-to-end decrypt pipeline remains a follow-on); v6.0 bump
+ `RELEASE_NOTES_v6.0.md`; full gate results (403 tests, compile/assemble green).
