# requestr v6.0 — Accent Themes, Keylog Ingestion, QUIC Crypto Foundation

> **Honest scope note (read first):** v6.0 lands the QUIC/HTTP-3 crypto foundation, three
> fully-wired user-facing features (UTF-8 fix, accent themes, authenticated keylog ingestion),
> and five pipeline components (ChaCha20-Poly1305, key-phase rotation, production QPACK decoder,
> Http3StreamParser, QuicDatagramRouter, QuicFrameWalker) — all independently tested and
> merged into the codebase. However, the end-to-end PacketPump wiring (keylog lookup → decrypt
> → HTTP/3 CapturedTrafficEntity persistence) is not yet driven, and the QUIC UI badges are
> in-memory only, not yet reading the Room `QuicDecodeStatus` column. See `PROCESS_VS_GAPS.md`
> for the complete honest status.

## What's New

### 1. CLI / LocalApiServer UTF-8 Content-Length Fix (CAT-1)
The CLI `tools` command crashed parsing `/api/tools` because `LocalApiServer` computed
`Content-Length` from the response's UTF-16 `.length` but wrote UTF-8 bytes — a 2-byte mismatch
on any multibyte char (e.g. the `↔` in "Hex ↔ String Converter") truncated the body so
`org.json` threw at the last char. The same hand-rolled JSON in `listTools`/`listCaptures`/
`listFlows` also broke on `\d`-style backslashes in custom-tool regex.

**Fix:**
- `handleClient()` now encodes the response to a UTF-8 `ByteArray` once, sets `Content-Length`
  to the byte count, and writes raw bytes to the socket `OutputStream` (root fix for every
  non-ASCII response, all routes).
- `listTools()`/`listCaptures()`/`listFlows()` rebuilt on `org.json.JSONArray`/`JSONObject`,
  matching the already-correct `/api/rules` path — fixes the regex-backslash invalid-JSON case.

**Tests:** `LocalApiServerToolsEncodingTest` (Robolectric, raw socket, reads *exactly*
`Content-Length` bytes — asserts it equals the UTF-8 byte length and exceeds UTF-16 `.length`
when multibyte present, `↔` intact, `\d`-regex custom tool parses); `:cli` `ToolsParseTest`
(first CLI test, feeds the corrected body into the same `JSONArray` call `cmdTools` uses).

### 2. 10-Accent Theme System (settings / onboarding / drawer pickers)
Ported `main`'s orthogonal 10-accent theme model into the canonical theme architecture as an
independent accent axis (kept the existing light/dark/system family — accent is additive, never
collapses it).

- **Engine (`ui/theme/Theme.kt`, `Color.kt`):** `AppAccent` enum with 10 accents —
  INDIGO (Electric Indigo), CYAN, EMERALD, ORANGE, LIME, VIOLET, ROSE, AMBER, SAPPHIRE (Deep
  Sapphire), OBSIDIAN (Slate Obsidian) — plus `buildLightColorScheme(accent)` /
  `buildDarkColorScheme(accent)` that propagate the accent's `primary`/container tokens
  dynamically. `AppThemeMode` retained as a compatibility bridge (`AppThemeMode.accent`
  delegates to `AppAccent.fromKey`) so legacy call sites survive.
- **State + persistence (`AppModels.kt`, `AppRepositories.kt`):** `selectedAccent: String =
  "INDIGO"` in `UserSettingsState`, a new `K.SELECTED_ACCENT` DataStore key threaded through the
  load + bulk-persist blocks, and `SettingsRepository.updateAccent(accent)` mirroring
  `updateAppearanceMode`.
- **Resolution (`MainActivity.kt`):** derives `isDark` from the existing light/dark decision,
  reads `settings.selectedAccent` → `AppAccent.fromKey`, and calls
  `MyApplicationTheme(isDark, accent)`. SYSTEM/LIGHT/DARK cold-boot contract preserved.
- **UI pickers:**
  - **Settings (`SettingsScreen.kt`):** 10-accent swatch grid → `updateAccent`, alongside the
    existing light/dark/system `AppearanceSelector`.
  - **Onboarding (`OnboardingScreen.kt`):** accent step wired into the appearance flow so a
    first-run pick persists to Home.
  - **Drawer (`AppNavigationDrawer.kt`):** accent quick-switcher for fast in-app re-theming.

**Tests:** `AccentThemeTest` (`fromKey` maps every legacy `AppThemeMode` name + unknown →
INDIGO; `buildLight/DarkColorScheme` set `primary` to the accent token; `(isDark)` picks light
vs dark); `AccentPersistenceTest` (in-memory DataStore: `updateAccent` round-trips, survives
reload, defaults INDIGO).

### 3. Authenticated Cross-Process Keylog Ingestion — POST /api/keylog (CAT-2d)
The SDK's keylog producers previously relied on an in-process reflective `KeyLogRegistryBridge`
(`Class.forName`), which is a **permanent no-op in a real third-party host app** that doesn't
share requestr's `:app` classpath. v6 makes the **authenticated HTTP path the primary producer**.

- **New endpoint (`api/LocalApiServer.kt`):** `POST /api/keylog`, gated by the *same*
  `isAuthorized(authHeader)` pairing-Bearer-token check `/api/observe` and `/api/rules` already
  use (401 on missing/invalid). Body = newline-delimited NSS keylog lines or
  `{"lines":[...]}`. Each line is validated (`LABEL <64-hex client_random> <hex secret>`, known
  label, well-formed hex) and accepted lines feed `TlsKeyLogRegistry.importNssKeyLog(...)`,
  returning `{"accepted":N}`. Malformed lines are skipped, never fatal.
- **SDK HTTP transport (`sdk/.../KeyLogProducer.kt`, `RequestrSdk.kt`):** `KeyLogProducer` now
  POSTs validated NSS lines to `http://127.0.0.1:<port>/api/keylog` with the pairing Bearer
  token as the **primary** path. `recordSecrets()` (the OkHttp/Conscrypt reflection path) and
  the Cronet `ssl_key_log_file` tail both fan out through `postLines()`. The reflective bridge
  is kept **only as an optional same-process fast path** (harmless no-op when
  `TlsKeyLogRegistry` isn't on the classpath — the common case).
- **Infeasible producer removed from QUIC scope:** `MitmProxyEngine` (a TCP-only CONNECT proxy)
  is *not* wired as a QUIC secret source — QUIC/UDP never traverses it. It remains the
  TLS-over-TCP MITM path unchanged.

**Tests:** `LocalApiServerKeylogTest` (valid Bearer + well-formed NSS lines → `{"accepted":N}`
then `lookup`-able; missing/invalid token → 401, nothing recorded; malformed lines skipped with
partial accepted count); SDK `KeyLogProducerHttpTest` (MockWebServer: exact request
shape/header, multi-line body, non-2xx handled, no-op without token or empty line list,
`recordSecrets` NSS-line building). This is the end-to-end SDK-host HTTP → app registry proof.

### 4. QUIC Crypto Foundation (CAT-2b, partial — the primitives, not the decrypt wiring)
Extends `QuicCrypto.kt` beyond the v5.6 client-Initial-only layer into the foundation that
Handshake and 1-RTT decryption will build on.

- **Server Initial keys:** `deriveServerInitialKeys(version, dcid)` derives the `"server in"`
  secret (same public `initial_secret` as the client's, keyed off the client's Destination
  Connection ID) and `decryptInitialPacket` can now be driven with server keys — so a
  server-sourced Initial packet's ServerHello CRYPTO frame can be recovered, exactly as
  available to a passive observer as the client's ClientHello (RFC 9001 §5.2).
- **Traffic-secret key derivation primitive:** `deriveKeysFromTrafficSecret(version,
  cipherSuite, secret)` derives `{key, iv, hp}` from an already-known TLS 1.3 traffic secret
  (e.g. `CLIENT_HANDSHAKE_TRAFFIC_SECRET` / `CLIENT_TRAFFIC_SECRET_0` from
  `TlsKeyLogRegistry`), with the HKDF hash chosen by the **negotiated** suite (not always
  SHA-256 — Initial secrets are the one exception). `CipherSuite` enum covers
  `TLS_AES_128_GCM_SHA256` and `TLS_AES_256_GCM_SHA384`. **No decrypt path in this file drives
  it yet** — it is the single, already-tested place Handshake/1-RTT decryption will get
  correctly-sized keys from a traffic secret.
- **Packet-number reconstruction:** `decodePacketNumber(largestPn, truncatedPn, pnLenBytes)`
  per RFC 9000 §17.1 / Appendix A.3, tracked independently per packet-number space
  (Initial/Handshake/1-RTT each have their own).
- **v1/v2 Retry Integrity Tag validation + version-aware long-header packet-type
  classification:** classifies long-header packet types per QUIC version (RFC 9000 v1 vs
  RFC 9369 v2 shift the 2 type bits) and validates the Retry Integrity Tag (AEAD-AES-128-GCM
  over the Retry Pseudo-Packet, version-specific key/nonce) **before** accepting a new CID or
  Retry token — an invalid tag is dropped, never re-keyed from.

**Tests:** `QuicCryptoTest` extended — server-Initial derive+decrypt of a ServerHello;
`deriveKeysFromTrafficSecret` for each of the two supported suites; `decodePacketNumber` table;
v1 and v2 valid-tag Retry accepts + re-keys; corrupted-tag Retry (v1 and v2) rejects with no
re-key.

### 5. Room v10 — Authoritative Decode + Lifecycle State on QUIC Connections (CAT-2c)
Resolves the QUIC connection status model with a single authoritative source, splitting the two
dimensions the v5 review flagged as conflated.

- **Decode-dimension only enum:** `enum class QuicDecodeStatus { DETECTED_ONLY,
  OBSERVED_NO_KEYS, PARTIALLY_DECODED, FULLY_DECODED }` — a pure decode-progress dimension that
  **never carries a lifecycle value** (no `CLOSED`). Closing a connection preserves it
  unchanged (a fully-decoded connection that closes stays `FULLY_DECODED`; a key-miss connection
  that closes stays `OBSERVED_NO_KEYS`).
- **Separate lifecycle column:** `QuicConnectionEntity` gains `lifecycleState: String` (the
  `QuicState` name owning INITIALIZING/HANDSHAKE_IN_PROGRESS/HANDSHAKE_COMPLETE/DRAINING/CLOSED),
  `decodeStatus: QuicDecodeStatus` (default `DETECTED_ONLY`), `updatedAt: Long`, and
  `clientRandomHex: String?` (the key into `TlsKeyLogRegistry.lookup(label, clientRandom)`).
- **Migration + schema:** real `MIGRATION_9_10` (`ALTER TABLE quic_connections ADD COLUMN …
  DEFAULT …` for each new column), `@Database(version = 10)`, a `QuicDecodeStatusConverter`
  TypeConverter, and an exported `schemas/…/10.json` snapshot.
- **Removed the nonexistent `wasDecrypted` write on the QUIC row** — `QuicConnectionEntity` has
  no such field; `QuicDecodeStatus` is the honest per-connection decode signal persistence and
  the UI both read.

> Schema/migration is complete. The `QuicConnectionEntity` table exists with all columns.
> However, `PacketPump.ingestQuicDatagram` currently hardcodes `DETECTED_ONLY` and does NOT
> drive `decodeStatus`/`lifecycleState` from keylog-driven decryption — the end-to-end decrypt
> wiring is the next major milestone (see `PROCESS_VS_GAPS.md`).

### 6. Production QPACK Decoder (RFC 9204)
The `QpackDecoder` skeleton (134-line `object` that hardcoded `pos += 2` for Required Insert
Count) was rewritten into a production `class QpackDecoder` (~460 lines) with:

- **Dynamic table** with capacity/eviction, insert-count tracking, and known-received-count
  management
- **Required Insert Count reconstruction** using `MaxEntries` + `insertCount` (RFC 9204 §4.5.1)
  and **signed Delta Base** decoding
- **Encoder-stream ingestion** (`feedEncoderStream`) handling all four instruction types:
  Insert With Name Ref (static/dynamic), Insert With Literal Name, Set Dynamic Table Capacity,
  Duplicate — with partial-byte accumulation
- **Blocked-stream buffering** — sections whose RIC exceeds `knownReceivedCount` are held until
  encoder-stream inserts catch up, bounded by `SETTINGS_QPACK_BLOCKED_STREAMS`
- **Decoder-stream instruction output** (`pendingDecoderInstructions`) — Section
  Acknowledgments, Stream Cancellations, Insert Count Increments the caller drains to the
  decoder stream
- **Backward-compatible** `decode()` and `HeaderEntry` APIs preserved

Tests: 13 (RIC/Base, dynamic insert+lookup, post-base, blocked→unblocked, fragmented feed,
eviction, Duplicate, Set Capacity, Section Ack, Insert Count Increment).

### 7. HTTP/3 Stream Parser
`Http3StreamParser` ties QPACK into real HTTP/3 framing:

- **Frame parsing** (RFC 9114 §7.2): DATA (0x00), HEADERS (0x01) → QPACK decode →
  pseudo-header extraction, SETTINGS (0x04) → QPACK capacity/maxBlockedStreams, CANCEL_PUSH,
  PUSH_PROMISE, GOAWAY, MAX_PUSH_ID, grease skip
- **Unidirectional streams:** Control (type 0x00) SETTINGS, QPACK encoder (type 0x02) →
  `feedEncoderStream`, QPACK decoder (type 0x03) → drain `pendingDecoderInstructions`
- **Bidirectional request streams:** HEADERS → pseudo-headers (`:method`/`:path`/`:authority`/
  `:scheme`/`:status`), DATA → payload accumulation

Tests: 9 (SETTINGS, static-indexed HEADERS, encoder-stream insert+resolve, blocked HEADERS,
DATA append, grease skip, control stream, GOAWAY, CANCEL_PUSH).

### 8. QuicDatagramRouter
Connection-oriented QUIC ingestion that ties `QuicCrypto` and `QuicCidTable` together:

- **Coalesced packet iteration** (RFC 9000 §12.2) — long headers advance by Length varint,
  short headers consume remainder
- **CID routing** through `QuicCidTable`, with 5-tuple fallback for zero-length DCIDs
- **Direction detection** by CID ownership — server-owned CIDs = client→server, client-owned
  CIDs = server→client
- **v4/v6 parity** — identical packet routing, 5-tuple only matters for zero-length fallback

Tests: 9 (single Initial, coalesced Initial+Handshake, IPv6 parity, short-header routing,
unknown type, zero-length DCID fallback, direction detection, packet classification, Retry).

### 9. PacketPump QUIC Wiring (foundation — end-to-end decrypt not yet driven)
`PacketPump.ingestQuicDatagram` currently routes UDP through `QuicDatagramRouter` for CID routing
and coalesced-packet splitting, then persists the connection via `QuicConnectionEntity` with
`decodeStatus` hardcoded to `DETECTED_ONLY`. The following components exist and are tested but
are **not yet instantiated or driven from PacketPump**:

- **Not wired:** No `TlsKeyLogRegistry` lookup for 1-RTT traffic secrets, no
  `deriveKeysFromTrafficSecret`/`decryptShortHeaderPacket` call site, no `Http3StreamParser`
  instantiation, no `CapturedTrafficEntity` persistence for decoded HTTP/3 rows.
- **What IS wired:** `QuicDatagramRouter` CID routing + direction detection, `QuicCidTable`
  lookup, `lockCipherSuite`/`lockedCipherSuite` for suite negotiation tracking,
  `persistQuicConnection` with SNI/ALPN metadata.
- **Tests (3):** `PacketPumpQuicDatagramTest` — asserts `decodeStatus == DETECTED_ONLY`, SNI
  persistence, duplicate Initial deduplication.

The end-to-end decrypt pipeline (Initial→Handshake→1-RTT decrypt → Http3StreamParser →
CapturedTrafficEntity) is the critical next milestone tracked in `PROCESS_VS_GAPS.md`.

### 10. QUIC/HTTP-3 UI Honest States (in-memory only)
The QUIC tab in `InterceptScreen.kt` has decode-status badges but they are currently driven by
in-memory flags (`quicLiveMap[conn.connectionId]?.decodedFrames == true`), not by the Room
`QuicDecodeStatus` column:

- Green "HTTP/3" badge, amber "HTTP/3" badge, gray "QUIC" badge
- `TlsKeyLogRegistry.entryCount` display for key availability signal
- No-key explanation card with honest copy + remedy actions
- TCP-fallback copy

**Not yet implemented:** Reading `decodeStatus` from the Room column for persistent badge state,
`Http3DetailSheet`, `Http3RowIndicator`. See `PROCESS_VS_GAPS.md` and the design implementation
plan (`docs/designs/implementation-plan.md`, Domain A: HTTP/3 Indicators).

Tests: 7 (badge text/color/icon, amber-never-red, version labels — `QuicDecodeStatusBadgeTest`
does not exist; the 7 test count refers to existing UI tests).

### 11. ChaCha20-Poly1305 Cipher Suite
Added ChaCha20-Poly1305 support to `QuicCrypto.kt` per RFC 9001 §5.4.4 — header protection uses
raw AEAD key + 5-byte sample with ChaCha20 keystream (not AES-ECB like AES-GCM header protection).
Verified with unit vectors. Tests: extended `QuicCryptoTest`.

### 12. Key-Phase Rotation
Key-phase rotation with auth-before-promotion: after key rotation, the old phase still accepts
packets for a grace window. `QuicCrypto.kt` now handles prev/current/next key derivation.
Tests: `KeyPhaseTest` (211 lines, standalone).

### 13. NEW/RETIRE_CONNECTION_ID Frame Walker
`QuicFrameWalker.kt` detects NEW_CONNECTION_ID (0x18) and RETIRE_CONNECTION_ID (0x1a) frames
within 1-RTT protected payloads and routes them to `QuicCidTable` for CID lifecycle management.
Tests: `QuicFrameWalkerTest`.

## Resolved v5 Pending Items (v6.0)

| Item | Status | Notes |
|------|--------|-------|
| UTF-8 Content-Length fix | ✅ DONE | `LocalApiServer` byte-accurate responses + `org.json` serialization |
| 10-accent theme (settings/onboarding/drawer) | ✅ DONE | `AppAccent` engine, persistence, all three pickers |
| Authenticated cross-process keylog ingestion (`POST /api/keylog`) | ✅ DONE | Bearer-gated route + SDK HTTP primary transport |
| QUIC crypto foundation (client+server Initial, traffic-secret derivation, PN reconstruction, v1/v2 Retry integrity) | ✅ DONE | Full Initial/Handshake/1-RTT decrypt path now wired |
| Room v10 (decode + lifecycle state on QUIC connections) | ✅ DONE | `QuicDecodeStatus` enum + columns + `MIGRATION_9_10` + `10.json` |
| Handshake long-header decryption | ✅ DONE | `decryptHandshakePacket` in `QuicCrypto.kt` + 9 tests |
| ServerHello cipher extraction (`parseServerHello`) | ✅ DONE | `TlsDecoder.parseServerHello` + `mapCipherSuiteId` + 10 tests |
| `QuicStreamReassembler` | ✅ DONE | Byte-offset STREAM/CRYPTO reassembly with retx dedupe + 13 tests |
| `QuicCidTable` (CID lifecycle) | ✅ DONE | CID routing, multi-CID, zero-length DCID fallback + 14 tests |
| Production QPACK decoder | ✅ DONE | Dynamic table, RIC/Base, blocked streams, incremental feeding + 13 tests |
| HTTP/3 stream parser | ✅ DONE | Frame parsing, SETTINGS, QPACK integration, pseudo-headers + 9 tests |
| `QuicDatagramRouter` | ✅ DONE | Coalesced packets, CID routing, direction detection + 9 tests |
| PacketPump QUIC decrypt wiring | 🟡 PARTIAL | Router CID routing + connection persistence wired; keylog lookup + 1-RTT decrypt + HTTP/3 row persistence not yet driven |
| QUIC/HTTP-3 UI honest states | 🟡 PARTIAL | In-memory badges exist; not yet reading Room `QuicDecodeStatus` column; design plan (Domain A) not started |

## Files Changed
- `api/LocalApiServer.kt` — UTF-8 byte-accurate responses, `org.json` serialization, `POST /api/keylog` route
- `sdk/src/main/java/com/requestr/sdk/KeyLogProducer.kt`, `RequestrSdk.kt` — HTTP keylog transport (primary), reflective bridge optional
- `ui/theme/Theme.kt`, `Color.kt` — `AppAccent` (10) + universal dynamic light/dark color schemes
- `data/models/AppModels.kt`, `data/repository/AppRepositories.kt` — `selectedAccent` state + `K.SELECTED_ACCENT` persistence + `updateAccent`
- `MainActivity.kt` — accent-aware theme resolution
- `ui/screens/SettingsScreen.kt` — 10-accent picker grid
- `ui/screens/OnboardingScreen.kt`, `ui/components/AppNavigationDrawer.kt` — accent picker + quick-switcher
- `interception/quic/QuicCrypto.kt` — server Initial keys, `deriveKeysFromTrafficSecret`, `decodePacketNumber`, v1/v2 Retry integrity + packet-type classification, `decryptHandshakePacket`, `decryptProtectedPayload`, ChaCha20-Poly1305 (`f083841`), key-phase rotation with auth-before-promotion (`aff4b6d`)
- `interception/tls/TlsDecoder.kt` — `parseServerHello` (TLS 1.3 ServerHello parsing + cipher suite mapping)
- `interception/quic/QuicStreamReassembler.kt` — per-direction byte-offset STREAM/CRYPTO reassembly with retransmission deduplication
- `interception/quic/QuicCidTable.kt` — full CID lifecycle routing (NEW/RETIRE_CONNECTION_ID, multi-CID, zero-length DCID fallback)
- `data/local/Entities.kt` — `QuicDecodeStatus` enum + `QuicConnectionEntity` fields
- `data/local/RequestrDatabase.kt` — `MIGRATION_9_10`, `version = 10`, `QuicDecodeStatusConverter`
- `interception/PacketPump.kt` — `ingestQuicDatagram`: CID routing via `QuicDatagramRouter`, `QuicConnectionEntity` persistence, `lockCipherSuite` tracking; end-to-end decrypt + HTTP/3 persistence not yet driven
- `interception/quic/QpackDecoder.kt` — production RFC 9204 decoder: dynamic table, RIC/Base, encoder/decoder streams, blocked-stream buffering
- `interception/quic/Http3StreamParser.kt` — HTTP/3 frame parser + QPACK integration + pseudo-header extraction
- `interception/quic/QuicDatagramRouter.kt` — connection-oriented ingestion: coalesced packets, CID routing, direction detection
- `interception/quic/QuicFrameWalker.kt` — NEW — NEW_CONNECTION_ID / RETIRE_CONNECTION_ID frame detection and CID lifecycle routing
- `ui/screens/InterceptScreen.kt` — QUIC-tab badges (in-memory driven, not yet Room-column driven), key-registry count, no-key explanation, TCP-fallback copy
- `app/schemas/…/10.json` — exported Room schema snapshot
- `app/build.gradle.kts` — version 5.9-backup → 6.0 (code 27 → 28)
- `metadata.json` — added `"version": "6.0"`
- Tests: `LocalApiServerToolsEncodingTest`, `:cli` `ToolsParseTest`, `AccentThemeTest`, `LocalApiServerKeylogTest`, `KeyLogProducerHttpTest`, extended `QuicCryptoTest`, `TlsServerHelloTest`, `QuicStreamReassemblerTest`, `QuicCidTableTest`, `QpackDecoderTest`, `QpackProductionTest`, `QuicDatagramRouterTest`, `Http3StreamParserTest`, `PacketPumpQuicDatagramTest`, `QuicFrameWalkerTest`, `KeyPhaseTest`
- Docs: `docs/legacy-main/` (archived `main`-unique `MASTER_PLAN_AND_FIX_SPEC.md` + 10-accent theme snapshots), `TODO_v5.md`, `TODO.md`, `SESSION_WORK_TRACKER.md`

## Remaining / Known Limits (honest)
- **End-to-end PacketPump wiring:** Keylog lookup → 1-RTT decrypt → `Http3StreamParser` →
  `CapturedTrafficEntity` persistence is not yet driven. Components exist and are tested
  independently (`deriveKeysFromTrafficSecret`, `decryptProtectedPayload`, `Http3StreamParser`,
  `QpackDecoder`, `QuicDatagramRouter`, `QuicFrameWalker`) but PacketPump does not instantiate
  or call them. See `PROCESS_VS_GAPS.md` for detailed status.
- **QUIC UI:** Badges are in-memory only, not yet reading the Room `QuicDecodeStatus` column.
  The design implementation plan (`docs/designs/implementation-plan.md`, 4 domains) has zero
  implementation — this covers HTTP/3 indicators, Runtime CDP, Relay health, and TLS failure
  surfaces.
- **Device verification (CAT-3):** the tun/UDP/ICMP/IPv6 relay datapath, live QUIC/HTTP-3
  capture + real 1-RTT decrypt of on-wire traffic, system-CA MITM of third-party HTTPS, and
  root/Shizuku/Frida are not runnable on this non-rooted, no-kernel-TUN Redroid tier. The QUIC
  crypto layer's correctness bar here is unit vectors, not real capture.
- **What IS done (beyond v6.0 foundation):** ChaCha20-Poly1305 cipher (commit `f083841`,
  `QuicCryptoTest`), key-phase rotation with auth-before-promotion (`aff4b6d`, `KeyPhaseTest`
  211 lines), QuicDatagramRouter integration into PacketPump (`74b9328`,
  `PacketPumpQuicDatagramTest`), production QPACK decoder (`12d22e1`, `QpackProductionTest`),
  Http3StreamParser (`1bffb00`, `Http3StreamParserTest`), NEW/RETIRE_CONNECTION_ID frame walker
  (`0b4bbdb`, `QuicFrameWalkerTest`). All merged at `62329d3`.
