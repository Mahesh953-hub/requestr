# requestr v6.0 — Final Release Report

**Date:** 2026-08-24  
**Release branch:** `vorflux/v6-final-release` → `main`  
**Head SHA:** `fccb183092541c72414ea58d1715ed4eeaa4c737`  
**Plan:** `/code/.plans/v3-requestr-unified-release.md` (tasks 16-17)

---

## 1. Branch History Rationale

`main` had unrelated roots to `origin/backup` (the v6.0 product lineage). The v3 plan
required building the final product from `origin/backup`, not from the unrelated `main`
history. The release was assembled as follows:

1. **Archival (task 2):** `archive/main-e5cd661` → `e5cd661` and
   `archive/backup-9b20a6c` → `9b20a6c` pushed before any lineage change.
2. **Gap-closure (task 3):** QUIC/HTTP-3 end-to-end decrypt pipeline wired via
   `QuicConnectionDecoder`, merged through PR #5.
3. **Tracker baseline (task 4):** Test count reconciliation, signing overrides,
   `.gitignore` fix, merged through PR #6/#7.
4. **Main transplant (task 5-6):** Valid main UX semantically ported onto backup
   lineage (10 transplant slices), merged through PR #8.
5. **Design system freeze (task 7):** `DESIGN-SYSTEM.md` frozen at `4808678`.
6. **UI lanes (tasks 10-15):** Four ownership-locked lanes (A/D/B/C) branched from
   `4808678`, each with disjoint file ownership, merged in plan order A→D→B→C.
7. **Final release (tasks 16-17):** Full validation, APK builds, artifact commit,
   tracker updates, and `main` replacement with `fccb183`.

## 2. Archival Refs (verified intact)

| Ref | SHA |
|-----|-----|
| `archive/main-e5cd661` | `e5cd66151d87499f44aeb8c7dcd37abf39ce5d28` |
| `archive/backup-9b20a6c` | `9b20a6c8c42905cad7bc661bf83c26f75f4705b1` |

## 3. Dependent PR Chain

PR #5 (gap-closure) → PR #6/#7 (tracker baseline + transplant) → PR #8 (design freeze)
→ Lane PRs (A/D/B/C, integrated via task 15 merge commits) → final artifact commit
(`e0f9dfa`) + tracker update (`fccb183`) → `main` force-with-lease replacement.

## 4. Requirement / Test Matrix

### Test Results (final clean validation at `fccb183`)

| Module | Test classes | Discovered | Executed | Skipped | Failed |
|--------|-------------|------------|0----------|---------|--------|
| app | 89 | 634 | 634 | 0 | 0 |
| sdk | 9 | 37 | 37 | 0 | 0 |
| cli | 1 | 4 | 4 | 0 | 0 |
| browser-extension | 3 | 31 | 31 | 0 | 0 |
| **Total** | **102** | **706** | **706** | **0** | **0** |

- 0 Robolectric sandbox failures (previously known 18-class issue resolved by
  `robolectric.properties` pinning `sdk=29`)
- R8 minification + lintVital passed on release build
- Fresh clone of new main: `:app:clean :app:testDebugUnitTest` = 634 tests, 0 failures

### Design Artifacts (36 approved mockups, all implemented)

| Domain | Count | Lane |
|--------|-------|------|
| HTTP/3 | 8 | D |
| Runtime/CDP | 12 | A (shared) + D |
| Relay health | 10 | D |
| TLS | 6 | D |
| **Total** | **36** | |

## 5. APK Paths, Hashes, and Signing

| File | Size | SHA-256 |
|------|------|---------|
| `release-artifacts/v6.0/requestr-v6.0-release.apk` | 40,538,933 | `d6f1d8ae1c2b2e736d0f3c77f7f2701bb7718ee9956514ffe69487c05fbd6891` |
| `release-artifacts/v6.0/requestr-v6.0-debug.apk` | 54,960,777 | `72b4550a99f81607cb627b825cd1a4643065ba1c183c12868bbfd3c590b62782` |

### Release signing (locked backup identity)

- **Keystore:** `requestr-release.jks` (from `origin/backup` @ `9b20a6c`)
- **Alias:** `upload`
- **Cert SHA-256:** `cb088bdea4853cd906a2cfef4a61b4fb5996c67578a6bede97181732c5da6ed8`
- **Cert DN:** `CN=requestr, OU=Dev, O=requestr, L=Unknown, ST=Unknown, C=US`
- **Scheme:** APK Signature Scheme v2, RSA 2048, SHA256withRSA
- **`verifyReleaseSigning` task:** OK

### Application metadata

- **Application ID:** `com.kailashhh.requestr`
- **versionCode:** 28, **versionName:** 6.0
- **minSdk:** 24, **targetSdk:** 36, **compileSdk:** 36

## 6. Device Smoke Tests (Redroid Android 15/API 35, x86_64)

12/12 journeys passed: install/onboarding, all 8 destinations, theme/accent cycle,
API request (200 OK), tool (JWT decoder), flow (n8n engine), music (empty state),
Reqy (local mode), capture/relay (VPN dialog + graceful denial), rotation, process
death/restart (state preserved), offline (stable).

## 7. Known Limits

1. **Runtime validation used Android 15/API 35** while `compileSdk`/`targetSdk` and
   build gates remained 36. API-36-specific runtime behavior is **not validated**
   because Android 16 `system_server` requires `/dev/ashmem`, unavailable on kernel
   `7.0.0-1010-aws`.
2. **Debug signing identity is not preserved.** Each build regenerates a local debug
   keystore (gitignored). Database upgrade testing must use release-signed APKs.
3. **Live on-wire QUIC/HTTP-3 device capture (CAT-3)** is not runnable on this
   non-rooted, no-kernel-TUN Redroid tier (correctness bar = unit vectors + fixtures).

## 8. Rollback Instructions

```bash
# Restore original main
git push --force origin archive/main-e5cd661:main

# Restore backup lineage tip
git push --force origin archive/backup-9b20a6c:backup
```

Archival refs `archive/main-e5cd661` and `archive/backup-9b20a6c` are preserved on
the remote and verified intact.

## 9. Main Replacement Verification

- **Force-with-lease:** `git push --force-with-lease=main:e5cd661...fccb183:main`
- **Remote main verified:** `origin/main` = `fccb183092541c72414ea58d1715ed4eeaa4c737`
- **Archival refs:** both intact (verified post-replacement)
- **Fresh clone smoke:** `:app:clean :app:testDebugUnitTest` = 634/0/0 from clean clone
