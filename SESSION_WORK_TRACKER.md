# Session Work Tracker — Complete requestr v5 pending

**Session:** `461393c6-d9ac-4b7f-a4e8-ecff2e213696`  
**Branch:** `vorflux/complete-v5-pending`  
**Updated:** 2026-08-23 09:45 UTC

---

## Top-Level Todos

| # | Todo | Status |
|---|------|--------|
| 1 | Archive main and create implementation branch from origin/backup | ✅ complete |
| 2 | Fix LocalApiServer UTF-8 responses and JSON serialization with tests | ✅ complete |
| 3 | Port and persist the 10-accent theme system | 🔄 in-progress |
| 4 | Implement full QUIC crypto, HTTP/3, QPACK, CID pipeline | 🔄 in-progress |
| 5 | Add Room v10 migration and authenticated cross-process keylog ingestion | 🔄 in-progress |
| 6 | Update v6 trackers, release notes, and stale audit documentation | ⬜ pending |
| 7 | Run full review, simplify, host builds, unit tests, and Redroid verification | ⬜ pending |
| 8 | Commit, push, and open a PR into backup | ⬜ pending |

---

## Detailed Breakdown

### ✅ 1. Git Setup
- Archive `main` as `archive/main-v5.7` tag
- Create `vorflux/complete-v5-pending` from `origin/backup` (8194e96)
- Preserve unique main content under `docs/legacy-main/`

### ✅ 2. CLI/API UTF-8 Fix
- Fix `LocalApiServer` UTF-8 Content-Length bug
- Replace unsafe hand-built JSON with `org.json` in listTools/listCaptures/listFlows
- Add `LocalApiServerToolsEncodingTest` and `ToolsParseTest`
- Verified: 17/17 focused tests, full suite 333/333

### 🔄 3. Accent Theme System — 75% done
- ✅ `AppAccent` enum with 10 accents
- ✅ Universal dynamic light/dark color schemes
- ✅ Settings persistence (selectedAccent in DataStore)
- ✅ SettingsScreen 10-accent picker grid
- ✅ MainActivity theme resolution
- 🔲 OnboardingScreen accent step wiring
- 🔲 AppNavigationDrawer accent quick-switcher
- 🔲 Compile and test validation

### 🔄 4. QUIC Crypto & Pipeline — 40% done
- ✅ Server Initial key derivation (v1/v2)
- ✅ Client Initial key derivation (v1/v2)
- ✅ Traffic-secret key derivation (AES-128-GCM, AES-256-GCM)
- ✅ RFC packet-number reconstruction
- ✅ Version-aware long-header packet classification
- ✅ v1/v2 Retry Integrity Tag validation
- 🔲 Handshake long-header decryption
- 🔲 TlsDecoder.parseServerHello cipher extraction
- 🔲 ChaCha20-Poly1305 support
- 🔲 Key-phase updates (prev/current/next)
- 🔲 QuicStreamReassembler
- 🔲 QuicCidTable (CID lifecycle)
- 🔲 QPACK production decoder
- 🔲 HTTP/3 stream parsing
- 🔲 PacketPump integration
- 🔲 QUIC UI states

### 🔄 5. Room v10 + Keylogs — 50% done
- ✅ Bearer-authenticated POST /api/keylog
- ✅ SDK HTTP keylog transport
- ✅ TlsKeyLogRegistry integration
- 🔲 QuicDecodeStatus enum (DETECTED_ONLY/OBSERVED_NO_KEYS/PARTIALLY_DECODED/FULLY_DECODED)
- 🔲 QuicConnectionEntity new fields (decodeStatus, lifecycleState, updatedAt, clientRandomHex)
- 🔲 MIGRATION_9_10
- 🔲 DB version to 10
- 🔲 TypeConverter for QuicDecodeStatus
- 🔲 Compile verification

### ⬜ 6. Documentation
- TODO_v5.md update
- RELEASE_NOTES_v6.0.md
- Bump versionCode to 28 / versionName to "6.0"
- Update stale audit/release docs

### ⬜ 7. Testing & Review
- Full app unit suite
- SDK and CLI tests
- Redroid device verification
- Simplify review
- Code review

### ⬜ 8. PR
- Commit all changes
- Push `vorflux/complete-v5-pending`
- Open PR into `backup`

---

## Isolated Worker Branches

| Directory | Branch | Status |
|-----------|--------|--------|
| `requestr-workers/accent-ui` | `vorflux/v6-accent-ui` | engine committed (934708a) |
| `requestr-workers/accent-app` | `vorflux/v6-accent-app` | settings committed (49b311d) |
| `requestr-workers/keylog-sdk` | `vorflux/v6-keylog-sdk` | committed (18c12e1) |
| `requestr-workers/quic-crypto` | `vorflux/v6-quic-crypto` | committed (4e9649c) |
| `requestr-workers/room-v10` | `vorflux/v6-room-v10` | clean, no commits yet |
| `requestr-workers/accent-final` | `vorflux/v6-accent-final` | clean |
| `requestr-workers/quic-handshake-2` | `vorflux/v6-quic-handshake-2` | clean |
| `requestr-workers/quic-streams` | `vorflux/v6-quic-streams` | clean |
| `requestr-workers/quic-cids` | `vorflux/v6-quic-cids` | clean |
| `requestr-workers/room-fields-2` | `vorflux/v6-room-fields-2` | clean |

---

## Integration Commits (vorflux/complete-v5-pending)

```
fb74bb0 feat(theme): apply accent selection in app and settings
e429cc3 Revert "feat(theme): apply accent selection in app and settings"
542382b feat(theme): apply accent selection in app and settings
a54ca26 feat(quic-crypto): Retry integrity + packet classification
70c8e20 feat(theme): AppAccent and dynamic color schemes
caea073 feat(quic-crypto): server Initial keys, traffic-secret, PN
5a50d3c feat(sdk): cross-process keylog ingestion
267a5ee feat(theme): persist accent selection
99f72af fix(api): UTF-8 responses and JSON serialization
8194e96 origin/backup baseline
```

---

## Uncommitted Changes

Working tree modifications on `vorflux/complete-v5-pending` (fb74bb0):

| File | Kind |
|------|------|
| `Entities.kt` | modified — QuicDecodeStatus + entity fields |
| `RequestrDatabase.kt` | modified — v10 migration + converter |
| `PacketPump.kt` | modified — updated constructor call |
| `AppNavigationDrawer.kt` | modified — accent quick-switcher |
| `OnboardingScreen.kt` | modified — accent step params |
| `QuicCrypto.kt` | modified — handshake decrypt |
| `TlsDecoder.kt` | modified — parseServerHello |
| `QuicStreamReassembler.kt` | new file |
| `QuicCidTable.kt` | new file |
| `schemas/.../10.json` | new (auto-generated) |

**Compile status:** FAILED — KSP Room processor errors due to missing schema migration. The `MIGRATION_9_10` and `QuicDecodeStatusConverter` were added but Room schema export requires a compile-and-generate cycle first.
