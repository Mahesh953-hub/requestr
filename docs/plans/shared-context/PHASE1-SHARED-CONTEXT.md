# Phase 1 (master-plan completion) — Shared Context for ALL build agents

Read this file first, then your own task description.

## Repo / branch

- Repo: `Mahesh953-hub/requestr`, checked out at `/code/Mahesh953-hub/requestr`
- Branch: `vorflux/master-plan-and-phased-design-implementation` (already created, already checked out). **Do NOT create, switch, or delete branches. Do NOT commit, do NOT push.** The orchestrator commits.
- Baseline at your start is fully green: 1156 unit tests / 0 failures, debug + release APK both build.

## Authoritative planning documents

- `docs/plans/MASTER-PLAN-IMPLEMENTATION-STATUS.md` — what is pending
- `docs/plans/MASTER-FEATURE-ADDITION-PLAN.md` — the full spec; §3 holds the LOW-priority items
- `/code/.plans/dossiers/master-plan-remaining.md` — a reconnaissance dossier

## ⚠️ The dossier contains INVENTED file names — verify before trusting

`/code/.plans/dossiers/master-plan-remaining.md` was written by an exploration
agent and **names three files that do not exist in this repository**:

- `TransactionDetailScreen.kt` — DOES NOT EXIST
- `TransactionListScreen.kt` — DOES NOT EXIST
- `AppPickerScreen.kt` — DOES NOT EXIST

The real equivalents are:

| Dossier's invented name | Real file(s) |
|---|---|
| `TransactionDetailScreen.kt` | `CapturedDetailBottomSheet` and the detail sheets inside `InterceptScreen.kt` |
| `TransactionListScreen.kt` | the traffic list inside `app/src/main/java/com/example/ui/screens/InterceptScreen.kt`, plus `CapturedTrafficHelpers.kt` |
| `AppPickerScreen.kt` | app-picker logic lives ONLY in `app/src/main/java/com/example/data/repository/TrafficInterceptorRepository.kt` (`queryIntentActivities` / `getInstalledApplications` / `loadLabel`) |

**Treat every file path in that dossier as a hypothesis.** Locate the real code with
`grep`/`rg` before editing, and **never create a new screen that duplicates an existing one**.
Inspect the current `app/src/main/java/com/example/ui/screens/` directory rather than relying on a
frozen count. ApiTestr response inspection is inline in `ApiTestrScreen.kt`, with pure helpers in
`ResponseInspectorHelpers.kt`; capture response inspection is in `InterceptScreen.kt`.

## ⚠️ Do NOT run full Gradle builds

Several agents share this one working tree. Concurrent `./gradlew` invocations
serialise on the configuration cache and file locks and produce confusing,
misleading failures. Therefore:

- **Allowed:** `. ~/.requestr-env && ./gradlew :app:compileDebugKotlin --no-daemon`
  to type-check, and a **narrowly filtered** test run for your own new tests, e.g.
  `./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.example.interception.MyNewTest'`
- **Forbidden:** `assembleDebug`, `assembleRelease`, unfiltered `testDebugUnitTest`,
  `build`, `clean`, and anything that touches the emulator or `adb`.
- The orchestrator runs the full build, the full test suite, and all emulator
  verification between waves.
- If a Gradle command fails with a lock/config-cache error, wait ~60s and retry
  once rather than escalating.

Build environment: always `cd /code/Mahesh953-hub/requestr && . ~/.requestr-env`
first (non-interactive shells do not source `~/.bashrc`).

## ⚠️ Strict file ownership — never edit a file another agent owns

Your task description lists the files/directories you own. Editing outside that
set risks another agent silently overwriting your work (same working tree, no
per-agent isolation). If you believe you must change a file you do not own,
**stop and report it** in your final output instead of editing it — the
orchestrator will sequence it in a later wave.

## Emulator capability — what is genuinely verifiable

The environment has MORE capability than earlier notes claimed:

- **Outbound internet: WORKS.** Redroid just booted without a default route;
  that has been fixed. Android reports `INTERNET&VALIDATED`, DNS resolves real
  hostnames, TCP 443 connects. Gotcha: the sandbox drops **ICMP** to public IPs,
  so `ping 8.8.8.8` fails even though TCP/UDP/DNS work — never use ping-to-IP
  as a connectivity test.
- **`/dev/net/tun` exists** and is world-writable, so `VpnService` very likely works.
- **`adb root` works**, BUT `/system/xbin/su` is mode `-rwsr-x--- root shell`, so
  the **app's own uid cannot exec `su`**. App-level root features must therefore
  degrade gracefully; their happy path cannot be exercised from inside the app.
- No Frida server and no Shizuku host are installed.

Consequence: do **not** pre-emptively declare something impossible. Implement the
real logic and real unit tests for everything. If you believe a *specific*
verification step is impossible here, say so in your final report with the reason,
and the orchestrator decides whether it is marked "not possible in emulator".

## Code conventions

- Tests mirror the source tree under `app/src/test/java/...`; test method names are
  backtick-quoted sentences, e.g.
  ``fun `rule with template variable expands to header value`() { ... }``
- For Room tests prefer `Room.inMemoryDatabaseBuilder` per test over the shared
  file-backed `RequestrDatabase.get(context)` singleton.
- Avoid capture-session IDs in the `9001`–`9008` range: they collide with
  `Http3EndToEndFixtureTest`.
- Compose BOM is **2024.09.00** → Foundation **1.7.2**. Verified by inspecting the
  resolved artifact: `OverscrollConfiguration` and `LocalOverscrollConfiguration`
  **exist**; `rememberOverscrollEffect()` **does not exist** in 1.7.x. Any dossier
  text recommending `rememberOverscrollEffect()` is wrong and will not compile.
- `Modifier.blur` / `RenderEffect` are **API 31+** while `minSdk = 24` — always
  guard with a version check and provide a graceful fallback.
- There are **two** `RuleAction` enums, do not confuse them:
  - `com.example.data.local.RuleAction` (persistence, in `Entities.kt`):
    `BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY, FORCE_MITM, MOCK_RESPONSE, SCRIPT_REWRITE, CHAOS`
  - `com.example.data.models.RuleAction` (UI display, in `AppModels.kt`):
    `LOG_ONLY, BLOCK, MODIFY_REQUEST, MODIFY_RESPONSE, BREAKPOINT, MOCK_RESPONSE`
- Room DB is currently `version = 14` in `RequestrDatabase.kt`. Only the agent that
  owns `data/local/**` may bump it, and it MUST add a real migration (no
  destructive fallback).

## Definition of done for your task

1. Every item in your list is fully implemented — no TODOs, no stubs, no dead code.
2. `./gradlew :app:compileDebugKotlin` passes.
3. New unit tests exist for the logic you added, and your filtered test run passes.
4. Your final report lists: each item, what you implemented, the real files you
   touched, the tests you added, any public API you created that another agent or
   the UI layer must call, and anything you deliberately did not do (with reason).
