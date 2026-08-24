# Build Log — requestr v6.0 baseline (v3 plan task 4)

> **Branch:** `vorflux/v6-tracker-baseline` (dependent on `vorflux/v6-gap-closure` @ `4bfcb4f`).
> **Integration base:** `vorflux/v6-integration` @ `9b20a6c`.
> This log records the reproducible test counts, the retired historical numbers, the verified
> design count, the `.gitignore` fix, the release-signing wiring, and the build/signature/smoke
> baseline gate. Final APK commit/naming is deferred to v3 plan task 17 (see
> `/code/.generated_artifacts/requestr-task4-baseline/BUILD-PROVENANCE.md` for artifact hashes;
> APKs are intentionally NOT committed to `release-artifacts/` yet).

## 1. Reconciled test counts (reproducible)

Toolchain: JDK 17.0.19, Android SDK 36, Build-Tools 36.0.0, Gradle 9.3.1. A test-only
`app/src/test/resources/robolectric.properties` pins the Robolectric sandbox to API 29 so the
34 Robolectric test classes run under JDK 17 (Robolectric 4.16's SDK 36 sandbox requires Java
21; this changes no production or QUIC code).

Command: `./gradlew :app:testDebugUnitTest :sdk:testDebugUnitTest :cli:test --no-daemon --console=plain`

| Module | Task | Test classes | Tests | Skipped | Failed | Errors |
|--------|------|--------------|-------|---------|--------|--------|
| `:app` | `testDebugUnitTest` | 80 | 547 | 0 | 0 | 0 |
| `:sdk` | `testDebugUnitTest` | 9 | 37 | 0 | 0 | 0 |
| `:cli` | `test` | 1 | 4 | 0 | 0 | 0 |
| browser-extension | `node test/unit/{utils,enforcer,queue}.test.mjs` | 3 files | 31 | 0 | 0 | 0 |

- **Totals:** 588 Gradle JVM tests (547 + 37 + 4); **619** including the 31 browser-extension tests.
- App has 82 `*Test.kt` source files = 80 test classes + 2 shared helpers
  (`QuicConnectionScenario.kt`, `QuicWireFixtures.kt` — 0 `@Test` each).
- Counts parsed from JUnit XML in `build/test-results/` (Gradle) and `PASS:` lines (Node).

### Retired historical numbers
- **`403`** — the `:app:testDebugUnitTest` count at the gap-fix state (`4b25e76`, 64 classes). Real
  for that SHA but superseded: the gap-closure PR added 144 `:app` tests (16 new classes).
- **`446`** — an undocumented aggregate in `TODO.md`'s v6.0-gapfix row. It does not reconcile with
  any clean per-module sum (`403 + 37 + 4 = 444`, not 446) and has no recorded command or SHA, so it
  is retired as unsupported.
- Both are replaced by the reproducible per-module table above. No single unsupported aggregate is
  used anywhere in the trackers after this reconciliation.

## 2. Approved design mockups — exactly 36

Verified by `find docs/designs -name '*.html' | wc -l` = 36, broken down as:

| Domain | Count |
|--------|-------|
| HTTP/3 | 8 |
| Runtime / CDP | 12 |
| Relay health | 10 |
| TLS | 6 |
| **Total** | **36** |

All prior "38 mockups" wording in `PROCESS_VS_GAPS.md`, `SESSION_WORK_TRACKER.md`, `TODO.md`, and
`RELEASE_NOTES_v6.0.md` is corrected to 36.

## 3. `.gitignore` typo fix

The accidental single line `*.pemcli/build/` was separated into `*.pem` and `cli/build/` (the
latter already existed on the next line). `sdk/build/`, `debug.keystore`, local/config, and
ordinary build-output ignores are retained; the approved `release-artifacts/` tree is not ignored.

## 4. Release signing wiring

`app/build.gradle.kts` now resolves release signing from Gradle properties **or** environment
variables (`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD`), with the existing literal credentials kept only as a documented
compatibility fallback. A `DEBUG_STORE_FILE` override points the debug config at the regenerated
keystore. The new `:app:verifyReleaseSigning` task (wired to `assembleRelease`) loads the resolved
keystore in-process and asserts:
- the resolved alias is `upload`; and
- the certificate SHA-256 equals the locked release identity
  `CB:08:8B:DE:…:6E:D8` (full: `CB:08:8B:DE:A4:85:3C:D9:06:A2:CF:EF:4A:61:B4:FB:59:96:C6:75:78:A6:BE:DE:97:18:17:32:C5:DA:6E:D8`).

Passwords are never logged or printed; only the public certificate fingerprint is emitted.

Verified output:
```
verifyReleaseSigning OK: alias='upload', certificate SHA-256=CB:08:8B:DE:A4:85:3C:D9:06:A2:CF:EF:4A:61:B4:FB:59:96:C6:75:78:A6:BE:DE:97:18:17:32:C5:DA:6E:D8 (matches the locked release identity).
```

## 5. Debug keystore (regenerated, out-of-tree, identity NOT preserved)

A fresh PKCS12 debug keystore was generated outside the repo
(`/code/.generated_artifacts/requestr-task4-baseline/debug.keystore`, alias `androiddebugkey`,
`CN=Android Debug,O=Android,C=US`, validity 10000 days). Its signing identity
(SHA-256 `32:D4:98:61:…:16:C1`) is unrelated to any prior debug key, so
`com.kailashhh.requestr` is uninstalled before every debug install.

## 6. APK build + verification (baseline gate)

Commands (DEBUG_STORE_FILE points at the regenerated keystore):
```
./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --console=plain
```
Result: BUILD SUCCESSFUL; `:app:minifyReleaseWithR8` ran (release minified).

| Check | Debug | Release |
|-------|-------|---------|
| applicationId | `com.kailashhh.requestr` | `com.kailashhh.requestr` |
| versionCode / versionName | 28 / 6.0 | 28 / 6.0 |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 | 24 / 36 / 36 |
| launchable-activity | `com.example.MainActivity` | `com.example.MainActivity` |
| R8 minified | no | yes (40.2 MB vs 54.1 MB) |
| apksigner verify | Verifies | Verifies |
| signer cert SHA-256 | `32d49861…16c1` (regenerated debug) | `cb088bde…c5da6ed8` (locked release, alias `upload`) |
| APK SHA-256 | `c5ce43a1…adecfc3` | `88baae5c…793e419` |

## 7. Redroid smoke install/launch (Android 15 / API 35, `localhost:5555`)

```
adb -s localhost:5555 uninstall com.kailashhh.requestr   # identity not preserved
adb -s localhost:5555 install -r app-debug.apk            # Success
adb -s localhost:5555 shell am start -n com.kailashhh.requestr/com.example.MainActivity
```
Result: `topResumedActivity = com.kailashhh.requestr/com.example.MainActivity`; process running;
no FATAL/ANR/AndroidRuntime entries in `logcat -d`. **Smoke pass.**

## 8. Known limits (kept open, honest)

- **API-36 runtime behavior** not validated — Android 16 `system_server` requires `/dev/ashmem`,
  unavailable on kernel `7.0.0-1010-aws`. Build/toolchain gates remain SDK 36; runtime validation
  used Android 15 / API 35.
- **Live on-wire QUIC/HTTP-3 device capture (CAT-3)** not runnable on this non-rooted, no-kernel-TUN
  Redroid tier; correctness bar = JVM unit vectors + deterministic fixtures.
- **Approved design-plan UI** (Task 0 `BadgePill` extraction + Domains A-D, incl. HTTP/3 detail
  sheets) remains zero implementation — v3 plan tasks 7-14.
