# Phase A Shared Context — MANDATORY briefing for every Phase A build agent

Read this file completely before touching anything.

## Repo / git

- Repo: `/code/Mahesh953-hub/requestr`. Package `com.kailashhh.requestr`, namespace `com.example`.
- Branch: `vorflux/master-plan-and-phased-design-implementation` — already checked out, already pushed.
- **Do NOT** create branches, commit, push, `git checkout`, `git stash`, `git reset`, or `git restore`.
  The orchestrator owns all git operations. Leave your work uncommitted in the working tree.
- The master-plan completion pass landed as commit `4cbb04e`. The tree is clean at your start.

## Authoritative planning documents

Read the ones relevant to your assignment:

- `docs/plans/PHASE-A-APPLY-EXISTING-MOCKUPS.md` — the Phase A audit (the plan you are executing).
- `/code/.plans/dossiers/phase-a-verification.md` — the code-grounded verification of that audit.
  **This dossier supersedes the audit wherever they disagree**, because the audit was written at an
  older HEAD. Section 3 answers the audit's eight open questions with file/line evidence.
- `docs/DESIGN-SYSTEM.md` — frozen design tokens and component contracts. Section numbers cited by
  the dossier (§5 animation, §6 theme, §8 nav pill, §9.2 GlassActionPill, §9.3 FloatingCapsuleHeader,
  §10.3 BadgePill, §13 glass styles) are authoritative.
- `docs/designs/` — 62 mockups (54 HTML + 8 PNG). **The HTML is the source of truth; the PNGs are
  older reference images.** Where an HTML mockup and its companion PNG disagree, follow the HTML.

## Already-resolved facts — do NOT re-litigate these

1. **Domain B (Runtime CDP + Instrumentation) IS IMPLEMENTED.** The Phase A audit claims the 12
   `runtime-cdp-*` / `runtime-instrumentation-*` mockups were never built. That claim is **stale, not
   wrong**: the audit was written at HEAD `81a7e25`, and Domain B landed afterwards in `d1b4476`
   ("feat(settings): implement v6.1 runtime diagnostics", PR #13, merge `4062300`). All of
   `RuntimeInstrumentationCard.kt`, `CdpCard.kt`, `WebViewCaptureControl.kt`,
   `StackFingerprintPanel.kt` and `RuntimeInstrumentationState.kt` exist and are wired into
   `SettingsScreen.kt`. **Do not re-implement Domain B.**
2. **Domains A, C and D are implemented and merged** (HTTP/3, Relay Health, TLS Failure), except the
   one backbone gap called out below for Relay Health.
3. **`FlowScreen.kt` already has** both the all-flows picker (`FlowRepository.allFlows` rendered as
   `PipelinePillItem` chips, ~line 257) and the Canvas/List view-mode toggle (`viewMode`, ~line 128,
   toggle IconButton ~line 199). Verified. No work needed.
4. **`MusicScreen.kt` has no Import-MP3 action.** Verified by grep. Matches the HTML mockup. No work.
5. **`gesturesEnabled` conflict is RESOLVED in favour of the current code.**
   `navigation_showcase_dark.html` says side-swipe must be hardcoded `gesturesEnabled = false`;
   `AppNavigationDrawer.kt:90` uses `settings.gestureNavigationEnabled`, matching
   `DESIGN-SYSTEM.md` §10.4's explicit decision to reject the hardcoded approach. **Keep the user
   toggle.** Do not change it. The divergence is being documented in the PR.
6. **Onboarding stays as-is.** `OnboardingScreen.kt`'s Identity / Theme / Diagnostics steps are
   correct and are NOT being replaced by the mockup's VPN-permission / CA-install steps. VPN consent
   lives in `CaptureController.kt` and CA install in `InterceptScreen.kt`, where the user has
   immediate context for why they are needed. Do not touch onboarding.

## Product decisions made by the user for this pass

1. **Sensitive response headers are MASKED BY DEFAULT with a per-value reveal.** `Set-Cookie`,
   `Authorization` and `Cookie` values in the captured-traffic response/request header views render
   as a masked placeholder with a tap-to-reveal action per header row. This answers the mockup's own
   open question. Reveal state must be per-header and must NOT persist across app restarts.
2. **Binary-file input for the Base64 tool is OUT OF SCOPE.** No mockup exists for it; it is being
   recorded in the PR as an undesigned gap. Do not build a file picker for Base64.

## Header / nav compliance — DO NOT do this yourself

The global "floating glass capsule header, no shared filled toolbar surface" requirement
(`PHASE-A-APPLY-EXISTING-MOCKUPS.md` §6, dossier §4) is being handled in ONE later cross-cutting
design-system pass that rewrites headers, the nav pill, blur/refraction, parallax, overscroll and
theming app-wide. **Do not modify any screen's header composable, `FloatingCapsuleHeader`,
`DynamicGlassHeader`, `CollapsibleNavPill`, `GlassActionPill`, `Theme.kt` or `Color.kt`.** If you
notice a header violation, report it in your final message instead of fixing it.

## Build rules (hard)

Several agents share this one working tree. Concurrent Gradle invocations serialise on the
configuration cache and file locks and produce confusing, misleading failures.

- Allowed: `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env && ./gradlew :app:compileDebugKotlin --no-daemon`
- Allowed: narrowly `--tests`-filtered unit runs, e.g. `./gradlew :app:testDebugUnitTest --tests 'com.example.ui.screens.MyNewTest' --no-daemon`
- **Forbidden:** `assembleDebug`, `assembleRelease`, `build`, `clean`, unfiltered `testDebugUnitTest`,
  and any adb / emulator / scrcpy use.
- Non-interactive shells do not source `~/.bashrc`. Always `. ~/.requestr-env` first (it exports
  `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, and PATH additions).
- If a compile fails in a file you do not own, a sibling agent is probably mid-edit. Retry once
  after a short wait before concluding anything.

## Strict file ownership

Edit ONLY the files your task assigns you. If you need a change in a file another agent owns, STOP
and report it as a cross-agent dependency in your final message — do not make the edit.

## Code conventions

- 15 real screen files live in `app/src/main/java/com/example/ui/screens/`: `ApiTestrScreen.kt`,
  `CaptureController.kt`, `CapturedTrafficHelpers.kt`, `ChatScreen.kt`, `FlowScreen.kt`,
  `HomeScreen.kt`, `InterceptScreen.kt`, `MusicScreen.kt`, `NotesScreen.kt`, `OnboardingScreen.kt`,
  `ResponseInspectorView.kt`, `SavedApisScreen.kt`, `SettingsScreen.kt`, `ToolWorkspaceScreen.kt`,
  `ToolsScreen.kt`, plus `ImageGalleryScreen.kt`, `NetworkConditionUiHelpers.kt`,
  `ReplacementValueUiHelpers.kt`, `RuleTemplateUiHelpers.kt`, `StatsDashboardScreen.kt`.
  **Beware: an earlier dossier invented three file names that do not exist** —
  `TransactionDetailScreen.kt`, `TransactionListScreen.kt`, `AppPickerScreen.kt`. The real
  equivalents are `ResponseInspectorView.kt` plus the detail sheets inside `InterceptScreen.kt`, the
  traffic list inside `InterceptScreen.kt` plus `CapturedTrafficHelpers.kt`, and the app-picker logic
  in `data/repository/TrafficInterceptorRepository.kt`.
- `ResponseInspectorView.kt`'s `RichResponseInspector` is **dead code scoped to ApiTestr**, not the
  capture-log renderer. Capture-log body/header rendering lives in `InterceptScreen.kt`'s detail
  sheets and `CapturedTrafficHelpers.kt`.
- Extract non-trivial logic into **pure functions** and unit-test them. `CapturedTrafficHelpers.kt`
  is the established home for pure helpers used by the intercept screens; add a new small file for a
  new concern rather than bloating a screen file.
- Tests go under `app/src/test/java/com/example/...` mirroring the source package. Use
  `Room.inMemoryDatabaseBuilder` for DAO tests, never the shared file-backed
  `RequestrDatabase.get(context)` singleton (cross-test row leakage).
- **Never use capture-session IDs `9001`–`9008`** in tests; they collide with
  `Http3EndToEndFixtureTest` on the shared file-backed DB.
- `minSdk = 24`, `compileSdk = 36`, `targetSdk = 36`. So: no `InputStream.readNBytes` (API 33), and
  `Modifier.blur` / `RenderEffect` are API 31+ and must be version-gated.
- Compose Foundation is **1.7.2**. `LocalOverscrollConfiguration` and `OverscrollConfiguration`
  exist; `rememberOverscrollEffect` does **NOT** (it is a 1.8+ API). Mixing them will not compile.
- There are **two** `RuleAction` enums — `com.example.data.local.RuleAction` (the persisted one, 11
  values) and `com.example.data.models.RuleAction` (a smaller UI enum with `label`/`colorHex`). Know
  which one you are in.
- Room is at `version = 15` (`RequestrDatabase.kt`) with a checked-in schema at
  `app/schemas/com.example.data.local.RequestrDatabase/15.json`. **If you add a column you must bump
  to 16, write the migration, and let KSP regenerate the schema JSON.** Coordinate through the
  orchestrator — only ONE agent may own a schema bump per wave.

## Emulator capability facts (for honest UI copy)

Verified empirically on this Redroid container, not assumed:

| Capability | State |
|---|---|
| Outbound internet / DNS / TCP | ✅ works (live `https://httpbin.org/get` → 200, H2, 173 ms) |
| ICMP to public IPs | ❌ blocked by the sandbox egress filter — never use ping as a test |
| HTTP-proxy-tier capture | ✅ works (port 9000, real HTTPS CONNECT captured) |
| VPN / tun capture | ❌ **cannot establish** — `IllegalStateException: Cannot create interface`; no VPN feature, no `dumpsys vpn`, `tun0` never created. Marked "not possible in emulator" in the status doc |
| App-level `su` (root tier) | ❌ `/system/xbin/su` is `-rwsr-x--- root shell`; the app's uid cannot exec it |
| Shizuku / Frida hosts | ❌ not installed / not running |
| System-store CA | ❌ not installed, so the CA-untrusted blind-relay path is the DEFAULT here |

Consequence for UI copy: **never claim a capability works when it cannot be demonstrated.** Where a
feature only functions on the VPN tier, say so plainly rather than showing an always-empty surface.
