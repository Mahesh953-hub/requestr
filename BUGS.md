# requestr — Bug Registry

**Last updated:** 2026-08-18  
**Current version:** v2.4  
**Source:** FIXLOG.md, APP_v2.md, codebase audit  
**Status:** ALL 20 bugs verified fixed — full code audit completed 2026-08-18 13:42 UTC

---

## 1. Infrastructure / Missing Core (Fixed in v2.2)

These were not individual UI glitches — they were entire missing subsystems that
caused every on-device symptom the user observed.

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| INF-01 | No Room DB persistence | Zero Room DB existed despite deps declared. Captured traffic, sessions, and rules had nowhere to persist. | Critical | v2.2 |
| INF-02 | No DataStore for settings | `androidx.datastore.preferences` was commented out in `app/build.gradle.kts`. Theme/onboarding/port settings could not survive process death. | Critical | v2.2 |
| INF-03 | No real VpnService engine | No `VpnService` subclass, no `<service>` in manifest. Interception banner was purely cosmetic. | Critical | v2.2 |
| INF-04 | No foreground service + wake lock | Neither `FOREGROUND_SERVICE` service declaration nor `WAKE_LOCK` permission existed. Android would kill the capture process under memory pressure. | High | v2.2 |
| INF-05 | No real capability probes | Root, Shizuku, and Frida detection were absent — replaced by hardcoded `true` values (see FAKE-01 through FAKE-03). | High | v2.2 |

---

## 2. Fake / Fabricated State (Fixed in v2.2)

The v2.1 prototype fabricated interception state to make the UI look functional.
Every chip, counter, and banner lied about real system state.

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| FAKE-01 | Root state hardcoded `true` | `TrafficInterceptorRepository._isRootAvailable = MutableStateFlow(true)` — Settings showed "Ready" regardless of actual `su` availability. | High | v2.2 |
| FAKE-02 | Shizuku state hardcoded `true` | `_isShizukuPermissionGranted = MutableStateFlow(true)` — Shizuku chip was always green. | High | v2.2 |
| FAKE-03 | SSL pinning bypass hardcoded `true` | `_isSslPinningBypassed = MutableStateFlow(true)` — Frida chip always showed success. | High | v2.2 |
| FAKE-04 | Interception defaulted ON | `_isInterceptionActive = MutableStateFlow(true)` — app launched with interception "active" even though nothing was capturing. | High | v2.2 |
| FAKE-05 | Fake packet/byte/connection counters | `_vpnPacketsCount = 1420`, `_vpnBytesCount = 489200`, `_vpnConnectionCount = 6` — static numbers displayed as live stats. | High | v2.2 |
| FAKE-06 | Random traffic generator | `TrafficInterceptorRepository` ran a `delay(3500)` loop emitting `Random.nextInt(...)` rows instead of real parsed packets. | Critical | v2.2 |

---

## 3. Crashes / Runtime Exceptions

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| CRASH-01 | `NoSuchElementException` on fresh install | `AppRepositories.kt:461` called `_requests.value.first()` on an empty list after seed data was removed, crashing the API Testr screen on first launch. | High | v2.2 |
| CRASH-02 | `NoSuchAlgorithmException` in CA generation | `CertificateAuthority.kt` called `.setProvider("BC")` on `JcaContentSignerBuilder`, forcing Android's stripped platform BC provider which lacks `SHA256withRSA`. | High | v2.3 |

---

## 4. Persistence / Data Loss

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| PERSIST-01 | Settings lost on restart | `SettingsRepository` was a pure in-memory `MutableStateFlow`. Theme, username, and onboarding completion were ALL lost when the app process died. | High | v2.3 |
| PERSIST-02 | Blank-screen flicker on launch | Because theme was not persisted, the Compose tree rendered with a missing/default theme for one frame before `SettingsRepository` resolved, causing a visible white flash. | Medium | v2.3 |

---

## 5. UI / UX Bugs

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| UI-01 | Wrong theme default | Default was `"MIDNIGHT"` which mapped to Midnight Sapphire via fallback, not the intended universal dark starting point. | Medium | v2.3 |
| UI-02 | Onboarding skipped on first launch | `onboardingCompleted` defaulted to `true`, so the wizard never showed on a fresh install. | Medium | v2.3 |
| UI-03 | ThemeToggleButton hid 5 themes | Button only toggled between `EDITORIAL_LIGHT` and `MONOCHROME_DARK`, hiding Midnight Sapphire, Kyoto Matcha, Cyber Terminal, Dusk Terracotta, and Solar Amber. | Medium | v2.3 |

---

## 6. Seed / Demo Data

| ID | Title | Description | Severity | Fixed In |
|---|---|---|---|---|
| SEED-01 | Hardcoded "Kailash" greeting | `OnboardingScreen.kt:63` pre-filled the developer's own name. Every fresh install greeted the user as "Kailash" unless they noticed and retyped it. | Medium | v2.2 |
| SEED-02 | Sample requests on fresh install | v2.1 shipped sample requests (HttpBin JSON Echo, GitHub User Profile, Post JSON Payload) which surfaced on Home's "Recently opened" even on a fresh install. | Medium | v2.2 |

---

## 7. Remaining / Device-Only Verification Items

These are **not bugs** — they are spec requirements that cannot be verified in the
Redroid container and require a physical device.

| ID | Item | Why Unverified |
|---|---|---|
| DEV-01 | Real `su` / root grant handshake (`libsu`) | Redroid's root model is not a real Magisk/KernelSU device grant. |
| DEV-02 | `VpnService` tun interface capturing another app's TCP traffic | Requires real device hardware and target app pairing. |
| DEV-03 | Shizuku binder connection | Requires Shizuku app + ADB pairing on a real device. |
| DEV-04 | `frida-server` TCP handshake on port 27042 | Requires rooted device with frida-server binary deployed. |
| DEV-05 | System-store CA injection via iptables | Needs real root + writable `/system`. |

---

---

## 8. Verification Audit (2026-08-18)

Every bug in this registry was verified fixed by direct code inspection:

| Section | Bugs | Status |
|---|---|---|
| 1. Infrastructure / Missing Core | INF-01 – INF-05 | ✅ All 5: Room DB present (3 entities, 3 DAOs, singleton), DataStore active (6 keys), VpnService subclass registered, FOREGROUND_SERVICE + WAKE_LOCK in manifest, CapabilityProbe with real su/Shizuku/CA/Frida probes |
| 2. Fake / Fabricated State | FAKE-01 – FAKE-06 | ✅ All 6: Zero `MutableStateFlow(true)` in TrafficInterceptorRepository; dead code gutted (~300 lines); Random traffic generator removed |
| 3. Crashes | CRASH-01 – CRASH-02 | ✅ Both: `first()` on empty list replaced with safe default; `.setProvider("BC")` removed from CertificateAuthority |
| 4. Persistence / Data Loss | PERSIST-01 – PERSIST-02 | ✅ Both: SharedPreferences backend with `init()` called before `setContent()` |
| 5. UI / UX | UI-01 – UI-03 | ✅ All 3: Default theme `MONOCHROME_DARK`; `onboardingCompleted = false`; ThemeToggle cycles 7 themes |
| 6. Seed / Demo Data | SEED-01 – SEED-02 | ✅ Both: "Kailash" removed (explanatory comment only); sample requests removed |

**20/20 bugs verified fixed. No regressions. No new bugs found.**

*End of Bug Registry*
