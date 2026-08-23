# requestr v6.0 — Accent Themes, Keylog Ingestion, QUIC Crypto Foundation

> **Honest scope note (read first):** v6.0 lands the *foundation* for full key-log-sourced
> QUIC/HTTP-3 decryption, plus three fully-wired user-facing features. Of the v6 plan's nine
> tracked items, **five are complete and wired at this release** (UTF-8 fix, accent system,
> authenticated `/api/keylog` ingestion, QUIC crypto foundation, Room v10 decode/lifecycle
> schema). **Four are foundation follow-ons not yet wired at this snapshot** and are documented
> honestly as remaining below: handshake long-header decryption, `parseServerHello` cipher
> extraction, `QuicStreamReassembler`, and `QuicCidTable`. The crypto primitives those need
> (e.g. `deriveKeysFromTrafficSecret`) are in place and unit-tested, but no decrypt path drives
> them yet — `QuicCrypto.kt`'s own KDoc states this explicitly. Live QUIC/HTTP-3 capture is not
> device-verifiable on this Redroid tier (CAT-3); the correctness bar for the crypto layer is
> unit vectors, as noted in `TODO_v5.md` P2.

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

> Schema/migration is complete and wired. The `PacketPump` ingest path that *populates*
> `decodeStatus`/`lifecycleState` from real decrypted packets is a follow-on (see Remaining) —
> at this snapshot `PacketPump` carries the new constructor plumbing but does not yet drive the
> new columns end-to-end.

## Resolved v5 Pending Items (v6.0)

| Item | Status | Notes |
|------|--------|-------|
| UTF-8 Content-Length fix | ✅ DONE | `LocalApiServer` byte-accurate responses + `org.json` serialization |
| 10-accent theme (settings/onboarding/drawer) | ✅ DONE | `AppAccent` engine, persistence, all three pickers |
| Authenticated cross-process keylog ingestion (`POST /api/keylog`) | ✅ DONE | Bearer-gated route + SDK HTTP primary transport |
| QUIC crypto foundation (client+server Initial, traffic-secret derivation, PN reconstruction, v1/v2 Retry integrity) | ✅ DONE | Primitives in place & unit-tested; decrypt wiring is a follow-on |
| Room v10 (decode + lifecycle state on QUIC connections) | ✅ DONE | `QuicDecodeStatus` enum + columns + `MIGRATION_9_10` + `10.json` |
| Handshake long-header decryption | ⬜ Remaining | `deriveKeysFromTrafficSecret` exists; no decrypt call site drives it yet |
| ServerHello cipher extraction (`parseServerHello`) | ⬜ Remaining | Not present in `TlsDecoder.kt` at this snapshot |
| `QuicStreamReassembler` | ⬜ Remaining | File not present at this snapshot |
| `QuicCidTable` (CID lifecycle) | ⬜ Remaining | File not present at this snapshot |

## Files Changed
- `api/LocalApiServer.kt` — UTF-8 byte-accurate responses, `org.json` serialization, `POST /api/keylog` route
- `sdk/src/main/java/com/requestr/sdk/KeyLogProducer.kt`, `RequestrSdk.kt` — HTTP keylog transport (primary), reflective bridge optional
- `ui/theme/Theme.kt`, `Color.kt` — `AppAccent` (10) + universal dynamic light/dark color schemes
- `data/models/AppModels.kt`, `data/repository/AppRepositories.kt` — `selectedAccent` state + `K.SELECTED_ACCENT` persistence + `updateAccent`
- `MainActivity.kt` — accent-aware theme resolution
- `ui/screens/SettingsScreen.kt` — 10-accent picker grid
- `ui/screens/OnboardingScreen.kt`, `ui/components/AppNavigationDrawer.kt` — accent picker + quick-switcher
- `interception/quic/QuicCrypto.kt` — server Initial keys, `deriveKeysFromTrafficSecret`, `decodePacketNumber`, v1/v2 Retry integrity + packet-type classification
- `data/local/Entities.kt` — `QuicDecodeStatus` enum + `QuicConnectionEntity` fields
- `data/local/RequestrDatabase.kt` — `MIGRATION_9_10`, `version = 10`, `QuicDecodeStatusConverter`
- `interception/PacketPump.kt` — constructor plumbing for the new QUIC columns
- `app/schemas/…/10.json` — exported Room schema snapshot
- `app/build.gradle.kts` — version 5.9-backup → 6.0 (code 27 → 28)
- `metadata.json` — added `"version": "6.0.0"`
- Tests: `LocalApiServerToolsEncodingTest`, `:cli` `ToolsParseTest`, `AccentThemeTest`, `AccentPersistenceTest`, `LocalApiServerKeylogTest`, `KeyLogProducerHttpTest`, extended `QuicCryptoTest`
- Docs: `docs/legacy-main/` (archived `main`-unique `MASTER_PLAN_AND_FIX_SPEC.md` + 10-accent theme snapshots), `TODO_v5.md`, `TODO.md`, `SESSION_WORK_TRACKER.md`

## Remaining / Known Limits (honest)
- **QUIC decrypt wiring (not yet done):** Handshake long-header decryption, 1-RTT short-header
  decryption, `TlsDecoder.parseServerHello` (negotiated cipher extraction), `QuicStreamReassembler`
  (per-direction byte-offset STREAM/CRYPTO reassembly), `QuicCidTable` (full CID lifecycle:
  `NEW`/`RETIRE_CONNECTION_ID`, multiple CIDs, zero-length DCID fallback, Retry re-key), the
  `ChaCha20-Poly1305` `CipherSuite` (header-protection is a separate keystream construction),
  key-phase prev/current/next with auth-before-promotion, the production QPACK rewrite
  (`QpackDecoder.decode()` still has no production call site), `Http3StreamParser`, and the
  `PacketPump` integration that turns keylog lookups into decoded HTTP/3 rows. The crypto
  *primitives* and the key-ingestion path are in place; the decrypt *wiring* is the follow-on.
- **Device verification (CAT-3):** the tun/UDP/ICMP/IPv6 relay datapath, live QUIC/HTTP-3
  capture + real 1-RTT decrypt of on-wire traffic, system-CA MITM of third-party HTTPS, and
  root/Shizuku/Frida are not runnable on this non-rooted, no-kernel-TUN Redroid tier. The QUIC
  crypto layer's correctness bar here is unit vectors, not real capture.
- **`TODO_v5.md` P2** therefore remains an honestly-scoped PARTIAL: HTTP/3 frames still do not
  decode end-to-end, but v6 adds the crypto foundation + keylog path + connection-state schema
  that the remaining wiring builds on.
