# Phase A — Interception-Core Foundation Brief (requestr)

Repo: `/code/Mahesh953-hub/requestr` · branch `main` · package `com.kailashhh.requestr`
Stack: Kotlin 2.2.10, Jetpack Compose, AGP 9.1.1, JDK 17, compileSdk 36, KSP configured.

Build env (set before any gradle command):
```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

## Read first
1. `FIXLOG.md` (repo root) — full ground-truth audit table.
2. `APP_v2.md` — §3 (interceptor rebuild) and §8 (logo dot shares interception state).

## Ground truth
`APP_v2.md` claims to be a QA pass on an implemented app. **It is not.** The app is a
UI-only prototype:
- No Room DB — `@Entity`/`@Dao`/`@Database` absent though room deps are declared.
- `androidx.datastore.preferences` is **commented out** in `app/build.gradle.kts`.
- No `VpnService`, no `WAKE_LOCK`, no foreground service.
- `data/repository/TrafficInterceptorRepository.kt` hardcodes `_isRootAvailable`,
  `_isShizukuPermissionGranted`, `_isSslPinningBypassed` to `MutableStateFlow(true)`,
  `_isInterceptionActive` to `true`, `_vpnPacketsCount` to `1420`, and fabricates traffic
  in a `delay(3500)` + `Random.nextInt()` loop.

## USER-CHOSEN SCOPE: INTERCEPTION CORE ONLY
The user explicitly chose **"Interception core only"** — make traffic interception genuinely
real and **defer all cosmetic/UI work**. So:

**IN scope:** persistence for traffic/sessions/rules, interception settings, real
`InterceptionState`, VpnService + MITM proxy, foreground service + wake lock, real
root/Shizuku/Frida/CA probes, CA generation + install flow, capture-tier selection, app
picker with real UID filtering, real capture log UI with real empty states.

**OUT of scope (do NOT build):** the nine-theme system, Home redesign, pill navigation,
gesture drawer, custom wordmark SVG, Lucide icon migration, Flow canvas rebuild, flow
execution runtime, deeplink ecosystem, Music fixes, full Settings rebuild. Leave those
screens compiling and untouched.

Exception: you must still add a **minimal** `SettingsDataStore` because interception needs
persisted config (port, tier preference, app filter). Do not build the theme/appearance keys.

## Scope: Phase A ONLY — foundation, not the engine
Do **not** implement the VpnService packet loop or the probes — a separate engine agent owns
those. Build only the substrate they bind to. Your APIs must be clean and must compile.

### A1 — Dependencies
Add/wire in `gradle/libs.versions.toml` + `app/build.gradle.kts`:
- `androidx.datastore:datastore-preferences` (uncomment/add)
- `com.github.topjohnwu.libsu:core` + `:io` (root shell)
- `dev.rikka.shizuku:api` + `:provider`
- `org.bouncycastle:bcpkix-jdk18on` (real X.509 CA generation)
- optionally `androidx.security:security-crypto` (CA key storage)

Skip Media3 and WorkManager — those belong to out-of-scope features. Versions must be
compatible with Kotlin 2.2.10 / AGP 9.1.1 / compileSdk 36. If one will not resolve,
substitute the nearest stable version and note the substitution.

### A2 — Room persistence layer
Under `data/local/`. Entities for:
- **Captured traffic** — method, url, host, statusCode, requestHeaders, responseHeaders,
  requestBody, responseBody, protocol, latencyMs, request/response sizes, timestamp,
  sessionId, appPackage/uid
- **Capture sessions** — id, startedAt, endedAt, tier used, port, packetCount, bytes
- **Intercept/block rules** — pattern, action, enabled, domain (so "Block this domain" can
  be gated on a real existence check per §3.8)

DAOs **must** expose `Flow<List<T>>` observable queries (this is what replaces the fake
`Random` generator) plus insert/update/delete, and a query to check whether a block rule
exists for a given domain. Add a `RequestrDatabase` `@Database` class with a singleton
provider and `TypeConverters` as needed.

Skip notes/flow/API-request/activity-log entities — out of scope this pass.

### A3 — Minimal DataStore
Create `data/prefs/SettingsDataStore.kt` wrapping `DataStore<Preferences>` with typed `Flow`
accessors + `suspend` setters for interception config only:
- `proxy_port` — Int, default 8080
- `capture_tier_preference` — auto vs force-VPN-only
- `app_filter_mode` (all apps vs selected) + selected package set
- `remember_last_app_filter` — Boolean
- `ca_installed_hint` — Boolean (a hint only; the real check must always re-probe the trust
  store, never trust this value)

Do **not** add theme/onboarding/font/density keys — out of scope.

### A4 — SKIPPED (themes are out of scope this pass)
Leave `ui/theme/` alone. Do not create the nine-theme system.

### A5 — InterceptionState (single source of truth)
Create `data/interception/InterceptionStateRepository.kt` exposing
`StateFlow<InterceptionState>` where `InterceptionState` carries:
- `status`: `NEVER_CONFIGURED` / `CONFIGURED_IDLE` / `ARMED` / `ACTIVE` / `ERROR`
- `tier`: `ROOT` / `SHIZUKU` / `VPN` / `NONE`
- real `port`, live `packetCount`, `bytes`, `activeConnections`, `sessionId`, `errorText`

**Defaults must be off/zero — never `true`, never `1420`.** Both the Intercept FAB colour
(yellow = armed, green = active) and the wordmark logo dot (green = active, red = inactive)
bind to this one flow so they cannot drift (§3.5, §8). Provide updater methods for the engine
to push real values in.

Also define the **capability-state contract** the probe layer will fill, so the UI can bind to
real values instead of hardcoded `true`:
- `RootState`: `UNKNOWN` / `UNAVAILABLE` / `AVAILABLE` / `GRANTED` / `DENIED`
- `ShizukuState`: `NOT_INSTALLED` / `NOT_RUNNING` / `PERMISSION_DENIED` / `GRANTED`
- `CaState`: `NOT_INSTALLED` / `USER_STORE` / `SYSTEM_STORE`
- `FridaState`: `NOT_RUNNING` / `RUNNING`
All default to the unknown/negative variant. Expose them on a `StateFlow` too.

Delete or clearly quarantine the fake `Random` loop and hardcoded `true` flags in
`TrafficInterceptorRepository.kt` while keeping the file compiling (other agents rewire
callers). Do **not** implement the probes themselves — the engine agent owns that.

### A6 — Manifest and permissions
In `AndroidManifest.xml`:
- Declare a `VpnService` subclass with `android:permission="android.permission.BIND_VPN_SERVICE"`
  and the `VpnService` intent-filter. Create a minimal stub subclass so the declaration
  compiles, marked with a clear `TODO` for the engine agent — do not write the packet loop.
- Declare the foreground service with the correct `android:foregroundServiceType` for a VPN.
- Add `WAKE_LOCK` and `QUERY_ALL_PACKAGES` (use `tools:ignore` if lint complains).
- `POST_NOTIFICATIONS` and `READ_MEDIA_AUDIO` already present — keep them.
- Keep the existing `requestr://` deeplink intent-filter as-is.

## Hard requirements
- **The project MUST compile.** Run `./gradlew assembleDebug` and iterate until it passes.
  A non-compiling foundation blocks the engine and UI agents — most important criterion.
- Do **not** `git commit` or `git push`. Leave changes in the working tree.
- **Never fabricate state**: no hardcoded `true`, no `Random` data, no seeded sample rows.
  Real defaults only — off, zero, empty, unknown.
- Append a concise summary to the ACTION LOG table in `FIXLOG.md`, preserving existing content.

## Final report must include
1. Deps + versions added (and any substitutions)
2. Room schema — entities and DAOs, including the block-rule existence query
3. DataStore keys
4. The exact `InterceptionState` + capability-state API surface other agents must bind to
5. Manifest changes
6. Confirmation that `./gradlew assembleDebug` passes
