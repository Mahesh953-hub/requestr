# Review Report — `vorflux/v6-build-verify` @ `008333b`

**Review type:** Plan implementation verification + integration review
**Plan:** `/code/.plans/v1-complete-v5-pending.md`
**Base:** `537230d` (`vorflux/complete-v5-pending`)
**Integrated branches (6):**
1. `vorflux/v6-accent-final` — accent picker UI wiring
2. `vorflux/v6-room-v10` — Room v10 compile fix
3. `vorflux/v6-quic-handshake-2` — decryptHandshakePacket + TlsDecoder.parseServerHello
4. `vorflux/v6-quic-streams` — QuicStreamReassembler
5. `vorflux/v6-quic-cids` — QuicCidTable
6. `vorflux/v6-docs-release` — RELEASE_NOTES_v6.0.md, TODO updates, version bump

**Build gates:** ✅ All green
- 64 test classes, 403 tests, 0 failures
- `:app:compileDebugKotlin` SUCCESS
- `:app:assembleDebug` SUCCESS
- `:sdk:testDebugUnitTest` 37 tests PASS
- `:cli:test` 4 tests PASS

---

## Verdict: BLOCKED — documentation is stale (1 actionable fix needed)

The code integration is clean and correct. However, `RELEASE_NOTES_v6.0.md` and the TODO trackers were written before four of the six branches were merged and still claim their deliverables are "⬜ Remaining" / "not present." This must be corrected before merge — it is misleading to publish release notes that say features don't exist when they do.

---

## BLOCKER: RELEASE_NOTES_v6.0.md is stale (4 features mislabeled as "Remaining")

Three sections of `RELEASE_NOTES_v6.0.md` claim the following four features are **not** present in this release, but they ARE present in this integrated build (new files landed, tests included):

| Feature | Release notes claim | Actual state |
|---|---|---|
| `decryptHandshakePacket` (QUIC Handshake long-header decryption) | "⬜ Remaining" (line 158) | **Present** in `QuicCrypto.kt` (line 389) |
| `TlsDecoder.parseServerHello` (ServerHello cipher extraction) | "⬜ Remaining" (line 159) | **Present** in `TlsDecoder.kt` (line 296) |
| `QuicStreamReassembler` | "⬜ Remaining — File not present" (line 160) | **Present** at 166 lines + 13 tests |
| `QuicCidTable` (CID lifecycle) | "⬜ Remaining — File not present" (line 161) | **Present** at 131 lines + 14 tests |

**Affected sections to fix:**
- **Lines 149–161:** The "Resolved v5 Pending Items" status table — move these four items from ⬜ to ✅ with updated notes.
- **Lines 163–179:** "Files Changed" — add the four new files + their test files.
- **Lines 181–197:** "Remaining / Known Limits" — remove the four now-delivered items from the "not yet done" list. Items that still genuinely remain (ChaCha20-Poly1305, key-phase, production QPACK, Http3StreamParser, PacketPump integration) should stay.
- It should also mention that `decryptHandshakePacket` uses an empty-AAD decryption primitive (`decryptProtectedPayload`), which means full-header AAD authentication (as `decryptInitialPacket` does) is not yet wired for Handshake/1-RTT — but the Handshake decrypt path itself does exist and is testable.

## MEDIUM: TODO.md and TODO_v5.md have matching stale sections

Both `TODO.md` (~line 24–28) and `TODO_v5.md` (~line 55–61) have a "⬜ Foundation follow-ons" section claiming `decryptHandshakePacket`, `parseServerHello`, `QuicStreamReassembler`, and `QuicCidTable` are "not present" / "file not present." These four items should move to the ✅ Done section in both files, with updated notes reflecting the actual scope of what landed.

Specifically:

**`TODO.md` (lines 24–28):**
```
- [ ] **Handshake long-header decryption** — `deriveKeysFromTrafficSecret` exists as a tested primitive but no decrypt path drives it yet.
- [ ] **ServerHello cipher extraction (`parseServerHello`)** — not present in `TlsDecoder.kt` at this snapshot.
- [ ] **`QuicStreamReassembler`** — file not present.
- [ ] **`QuicCidTable` (CID lifecycle)** — file not present.
```
All four should be marked `[x]` with updated notes.

**`TODO_v5.md` (lines 60–67):** Same pattern — update the table rows.

## INFO: Code integration is clean — no merge issues found

- No merge conflict markers remain in any `.kt` file.
- All 6 branches integrated without conflicts.
- The `@Entity` annotation fix (moving it after the `QuicDecodeStatus` enum) is correct — Room compilers require the annotation on the data class, not before an unrelated enum.
- The `QuicDecodeStatusConverter` was moved from `RequestrDatabase.kt` (where it was orphaned inside the `@Database` annotation block) to `RequestrTypeConverters.kt` — correct consolidation.
- The `AppNavigationDrawer` removal of the unused `AppThemeMode` import is safe (no references remain).
- The `OnboardingScreen` wiring of `selectedAccent`/`onAccentSelected` matches `AppearanceStep`'s updated signature exactly.

## INFO: Plan scope vs. delivered scope

The plan (`v1-complete-v5-pending.md`) describes a much larger QUIC/HTTP-3 implementation (Tasks 9–14: QuicDatagramRouter, production QPACK, Http3StreamParser, PacketPump wiring, QUIC/HTTP-3 UI). This integrated build delivers only the "foundation follow-ons" that were previously documented as remaining — it does not implement the full end-to-end pipeline. This is consistent with what the release notes *should* describe once corrected: the decrypt primitives are in place, but the full wiring (chaCha20, key-phase, production QPACK, Http3StreamParser, PacketPump integration) remains a follow-on.

No code changes are needed for this — only the docs need to accurately reflect which parts landed.

---

## Test quality (non-blocking observations)

All four new test files are substantive and well-structured:

| Test file | `@Test` methods | Coverage |
|---|---|---|
| `QuicCidTableTest` | 14 | add/lookup, retire, content-equality keying, duplicate add, bound enforcement, zero-length rejection, Retry re-key, multiple CIDs, unregister retired CIDs |
| `QuicCryptoTest` | 34 | Extended from baseline — includes handshake decrypt vectors, traffic-secret derivation for both suites |
| `QuicStreamReassemblerTest` | 13 | Feed ordering, gap tolerance, retransmission dedupe, FIN tracking, overlap rejection, final-size locking |
| `TlsServerHelloTest` | 10 | All three cipher suites (0x1301/0x1302/0x1303), malformed/truncated/unknown-suite rejection |

---

## Recommended actions

1. **Update `RELEASE_NOTES_v6.0.md`** — move `decryptHandshakePacket`, `parseServerHello`, `QuicStreamReassembler`, and `QuicCidTable` from "⬜ Remaining" to "✅ DONE" in the status table; add them to "Files Changed"; remove them from "Remaining / Known Limits." Be honest about what the Handshake decrypt does and doesn't cover (empty-AAD primitive, no ChaCha20, no key-phase — those remain follow-ons).

2. **Update `TODO.md`** — mark the four "⬜ Foundation follow-ons" as `[x]` done with updated descriptions.

3. **Update `TODO_v5.md`** — same: move the four items from "⬜" to "✅" in the table.

After these three docs fixes, the branch is APPROVED for merge.
