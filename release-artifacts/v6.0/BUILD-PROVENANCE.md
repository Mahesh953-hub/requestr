# requestr v6.0 — Build Provenance

## Source

| Field | Value |
|-------|-------|
| Repository | `Mahesh953-hub/requestr` |
| Branch | `vorflux/v6-final-release` |
| Source SHA | `a500210ef4f89374cf6674c20b5496e5b0c2a1c4` |
| Design-freeze base | `48086785174663539d3a700c603e6a020c84b7d5` (PR #8) |
| Backup archival ref | `archive/backup-9b20a6c` → `9b20a6c` |
| Main archival ref | `archive/main-e5cd661` → `e5cd661` |

### Integration chain (task 15)

All four ownership-locked UI lanes merged into `vorflux/v6-final-release` from the
design-freeze base, in plan order A → D → B → C, with zero conflicts:

1. **Lane A — shared shell:** Home, Onboarding, Settings, accent scroll, 6-tier capture selector
2. **Lane D — interception:** HTTP/3 row indicators + detail sheet, TLS failure detail, relay health card
3. **Lane B — API/Tools:** API Testr response viewer redesign, Tools catalogue, JWT workspace
4. **Lane C — Flow/Music/Reqy:** Flow condition branches, Music permission states, Reqy Assistant rename

## Toolchain

| Tool | Version |
|------|---------|
| JDK | OpenJDK 17.0.19 (Temurin/Ubuntu 17.0.19+10-1-24.04.2-Ubuntu) |
| Android SDK Platform | 36 (android-36, minor API level 1) |
| Build-Tools | 36.0.0 |
| Gradle | 9.3.1 (via wrapper, SHA-256 `b5173cbc1029dbe2212de0ff1c6331940f1c841bb26a0685b7189615802bf365`) |
| ADB | 1.0.41 (Version 37.0.1-15733141) |
| apksigner | 36.0.0 |
| aapt2 | 36.0.0 |
| Node.js | v22.21.0 (browser-extension tests) |

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
`sdk.dir=/opt/android-sdk` (local.properties, gitignored)

## Build commands

```bash
# Full test suite (all modules)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :sdk:testDebugUnitTest :cli:test --console=plain
node browser-extension/test/run-all.mjs

# Release APK
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :app:compileReleaseKotlin :app:assembleRelease --console=plain

# Debug APK (regenerated local debug keystore)
keytool -genkey -v -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :app:assembleDebug --console=plain
```

## Application metadata

| Field | Value |
|-------|-------|
| Application ID | `com.kailashhh.requestr` |
| versionCode | 28 |
| versionName | 6.0 |
| minSdk | 24 |
| targetSdk | 36 |
| compileSdk | 36 (minor API level 1) |

Verified via `aapt2 dump badging` on both APKs.

## Signing

### Release (locked backup identity)

| Field | Value |
|-------|-------|
| Keystore | `requestr-release.jks` (from `origin/backup` @ `9b20a6c`) |
| Alias | `upload` |
| Certificate DN | `CN=requestr, OU=Dev, O=requestr, L=Unknown, ST=Unknown, C=US` |
| Certificate SHA-256 | `cb088bdea4853cd906a2cfef4a61b4fb5996c67578a6bede97181732c5da6ed8` |
| Certificate SHA-1 | `64fc6f49bc3a7ef715b9538f6c7e005c1546ef4b` |
| Signature scheme | APK Signature Scheme v2 |
| Key algorithm | RSA 2048, SHA256withRSA |

The Gradle `verifyReleaseSigning` task confirmed alias `upload` and certificate
SHA-256 match the locked release identity.

### Debug (regenerated, non-preserved)

The debug signing identity was regenerated locally with `keytool` and is **not
preserved** across builds or machines. The debug keystore (`debug.keystore`) is
gitignored. Before each device install, `adb uninstall com.kailashhh.requestr` is
run first because the debug identity is not stable.

| Field | Value |
|-------|-------|
| Keystore | `debug.keystore` (regenerated, gitignored) |
| Alias | `androiddebugkey` |
| Certificate DN | `CN=Android Debug, O=Android, C=US` |
| Certificate SHA-256 | `d25f74f48cb3d06e3cab05af144e4b53568d7481b9827542547e1888a985d257` |

No passwords are recorded in this document.

## Artifact hashes

| File | SHA-256 |
|------|---------|
| `requestr-v6.0-release.apk` | `d6f1d8ae1c2b2e736d0f3c77f7f2701bb7718ee9956514ffe69487c05fbd6891` |
| `requestr-v6.0-debug.apk` | `72b4550a99f81607cb627b825cd1a4643065ba1c183c12868bbfd3c590b62782` |

Sizes: release 40,538,933 bytes (R8 minified), debug 54,960,777 bytes.

## Test results (final clean validation)

| Module | Test classes | Discovered | Executed | Skipped | Failed |
|--------|-------------|------------|----------|---------|--------|
| app | 89 | 634 | 634 | 0 | 0 |
| sdk | 9 | 37 | 37 | 0 | 0 |
| cli | 1 | 4 | 4 | 0 | 0 |
| browser-extension | 3 | 31 | 31 | 0 | 0 |
| **Total** | **102** | **706** | **706** | **0** | **0** |

- 0 Robolectric sandbox failures — the previously known 18-class initialization
  failures (`Android SDK 36 requires Java 21`) are resolved by a
  `robolectric.properties` file pinning `sdk=29` and explicit `@Config(sdk=...)`
  annotations on Robolectric test classes. No failures to exclude.
- R8 minification and lintVital passed on the release build.
- Retired unsupported historical 403/446 test-count claims; the single reproducible
  definition is 706 discovered = 706 executed, 0 skipped, 0 failed.

## Design artifacts

Exactly 36 approved HTML mockups in `docs/designs/`:
- HTTP/3: 8
- Runtime/CDP: 12
- Relay health: 10
- TLS: 6

## Device smoke tests (Redroid)

| Property | Value |
|----------|-------|
| Device | Redroid at `localhost:5555` |
| Android version | 15 (API 35) |
| ABI | x86_64 |
| scrcpy | operational |

### Fixture/local-smoke journeys (all passed)

1. **Install/onboarding** — fresh install, 3-phase setup (name, permissions,
   appearance), completed; "Good morning, TestUser ☀️" on Home.
2. **All destinations** — Home, Intercept, APIs (API Testr), Tools, Flow, Music,
   Reqy Assistant, Settings all rendered correctly.
3. **Theme/accent** — cycle button changed MONOCHROME_DARK → MIDNIGHT_SAPPHIRE;
   Settings shows theme/accent selectors.
4. **API request** — GET `https://httpbin.org/get` returned 200 OK, H2, 762 ms,
   344 B, with DNS/TCP/TLS timing breakdown.
5. **Tool** — JWT Token Decoder workspace opened with pre-filled token.
6. **Flow** — Flow & Automation (n8n Engine) loaded with Auth Login Guard pipeline.
7. **Music** — empty state "No audio tracks detected" with Scan Device Storage.
8. **Reqy** — local mode (no provider configured); message sent successfully.
9. **Capture/relay** — Start capture triggered VPN permission dialog; graceful
   error handling on denial ("Capture could not start: VPN permission was
   declined"). No crash.
10. **Rotation** — landscape rotation survived; UI adapted with additional quick
    actions. Rotated back to portrait.
11. **Process death** — `am force-stop` + relaunch; state preserved (onboarding
    skipped, user name restored, Home screen intact).
12. **Offline** — airplane mode enabled; app remained stable and responsive.

Debug APK installed with `adb uninstall` first (regenerated identity not
preserved).

## Known limitations

- **Runtime validation used Android 15/API 35** while `compileSdk`/`targetSdk`
  and all build/toolchain gates remained 36. API-36-specific runtime behavior is
  **not validated** because Android 16 `system_server` requires `/dev/ashmem`,
  which is unavailable on kernel `7.0.0-1010-aws`.
- **Debug signing identity is not preserved.** Each build regenerates a local
  debug keystore. Database upgrade testing must use release-signed APKs.
- **VPN-based capture** requires user-approved VPN permission via the system
  dialog; the smoke test verified the dialog is triggered and denial is handled
  gracefully, but full capture was not exercised in the sandbox.

## Rollback

- `archive/main-e5cd661` → `e5cd661` (original main)
- `archive/backup-9b20a6c` → `9b20a6c` (backup lineage tip)
- To rollback main: `git push --force origin archive/main-e5cd661:main`
