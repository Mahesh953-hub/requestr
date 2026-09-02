# Gaps / Keep / Remove Audit — requestr (master-plan + both design-plan phases)

**Scope:** every gap, deferred item, divergence, and dead-code block that has accumulated
across the master-plan (VPN/tun, data layer) and both design-plan phases (Phase A shared
shell/theme, Phase B Home rebrand + design system), reconciled against the **current code** at
head `4ef9b02` (versionName `6.1`, versionCode 29).

**Rule applied throughout:** **trust code over docs.** Every time a status doc, handoff, or
dossier disagrees with the current tree, the current tree wins and the discrepancy is called out
explicitly. No code was modified — this is a read-only audit.

**Evidence key.** Every "Current state" cell is a verified `file:line` in the current tree, not a
quote from a plan doc. Where a doc is stale, the row says so and names the contradicting field,
with the reason the doc drifted (almost always: the doc was written *before* Phase A / Phase B
landed).

**Verdict legend**

- **KEEP** — the behavior/code/doc is correct as-is (deliberate, documented, or the fix is already in the tree); do not change code.
- **REWORD** — the *code* is fine; a doc/comment/string says something stale or misleading and should be edited.
- **REMOVE** — delete the code/comment (dead code or an obsolete shim). Blocked removal is noted.
- **RESOLVED** — previously flagged as outstanding, now already fixed in the tree; no action except marking the ledger/checklist done.

---

## Master table

| # | Gap / divergence | Current state (file:line) | Verdict | Justification |
|---|---|---|---|---|
| A1 | VPN `establish()` null | `RequestrVpnService.kt:205–209` — null → `fail("Could not establish VPN interface…")` | **KEEP** | Honest failure path; `fail()` (380–383) surfaces the real emulator error. |
| A2 | Native tun2proxy bridge → hand-rolled fallback | `RequestrVpnService.kt:255–302` (fallback 281–301, `DEGRADED` 300, `usingNativeBridge=false` 282) | **KEEP** | Deliberate graceful degradation; not a bug. |
| A3 | "VPN/tun can't run" on emulator | `RequestrVpnService.kt:380–383` catch → honest ERROR | **KEEP** | Environmental (Redroid), correctly marked "not possible in emulator". |
| B1 | `gesturesEnabled` divergence from nav mockup | `AppNavigationDrawer.kt:94` = `settings.gestureNavigationEnabled`; mockup `navigation_showcase_dark.html:418` hardcodes `false`; documented `DESIGN-SYSTEM.md` §10.4 L440 | **KEEP** | Deliberate, user-controlled toggle (default on); the "reject mockup" decision is recorded in-design. |
| B2 | set-cookie/Authorization/Cookie masking + reveal + copy-real | `CapturedTrafficHelpers.kt:404–440`; `InterceptScreen.kt:4800–4831, 4971–5016` | **KEEP** | Implements the recorded user decision: mask by default, per-header reveal, copy copies the *real* value, mask never length-leaks. |
| B3 | Base64 binary-file input deferred | `ToolsRepository.kt:65` desc "text & binary converter" vs text-only `encodeBase64`/`decodeBase64` (195/201); `ToolWorkspaceScreen.kt:407` | **KEEP** deferral + **REWORD** description | Deferral is the recorded decision; the description string "binary converter" is now misleading and should drop "binary". |
| B4 | "N environments" count omitted | `SavedApisScreen.kt:616–625`; `CollectionSummary` has no env field (`SavedApisGrouping.kt:31–39`) | **KEEP** | Honest omission; cannot source the count from the data model without inventing it. |
| B5 | "Honestly disabled Environments drawer row" — **location discrepancy** | Row is in **Reqy drawer** `ChatScreen.kt:1476` (KDoc 1364–1376), *not* `AppNavigationDrawer.kt` | **KEEP** row + **REWORD** doc pointer | The row exists and is honestly disabled; every doc/handoff claiming "main AppNavigationDrawer" names the wrong file. |
| B6 | Onboarding steps | Code = Identity / Permissions / Appearance (`OnboardingScreen.kt:196, 201, 221`); docs say "Identity/Theme/Diagnostics"; mockup still says VPN Service / Install Root CA (`onboarding_editorial_light.html:185,192`) | **REWORD** docs | Core decision holds (no VPN/CA steps — KEEP); the specific step list in docs is stale. |
| B7 | cURL importer refuses instead of guessing | `CurlParser.kt:62,80,230–231` (`VALUE_FLAGS`, `-F/--form/-T`, `unsupportedFlag`) | **KEEP** | Correct refusal; `-F/-T` consumed-and-discarded is intentional. |
| C1 | Dead `RichResponseInspector` block (~1312-line file) | `ResponseInspectorView.kt:402` (+`ParsedCookie` 92, `parseCookiesFromHeaders` 103, `HexRow` 163, `generateHexRows` 169, `UniversalSyntaxHighlighter`, `CookieFlagBadge`); zero production callers; superseded by `CapturedDetailBottomSheet` (`InterceptScreen.kt:4637`) | **REMOVE** | Dead. Flagged to user already (`62e5a42a-3f2f-478c-adce-eb505ca64826`, pending user call). |
| C2 | Dead `DynamicIconPress` composable | `DynamicIconPress.kt:30` (73-line file), zero callers | **REMOVE** | Newly found dead code, not in any doc. |
| C3 | Dead private `KeyValueTableRow` | `ApiTestrScreen.kt:2282`, zero callers even in-file | **REMOVE** | Newly found dead code ("v6.0 transplant"). |
| D1 | Stale `CaP12Exchange` "unreachable from UI" KDoc | `CaP12Exchange.kt:30–33`; actually wired at `SettingsScreen.kt:1959, 1324, 2032, 2043–2044` | **REMOVE** comment | Comment claims a feature is unwired that is now fully wired. |
| D2 | Stale reflection shim `connectionIdOrNull()` | `HttpStackDetector.kt:151–169` claims "this worktree has not merged that branch"; `connectionId` is a real field (`Entities.kt:55`, `Index` 15), caller 119 | **REMOVE** | Obsolete reflection; replace with `row.connectionId`. |
| E1 | TunRelay per-reason drop counters ("deferred" in ledger) | Now implemented: `TunRelay.kt:72–92` (`evictionCount`, `dropProtectFail`, `dropUpstreamOpen/WRITE`, `dropUnrelayableProto`, `dropTcpUpstreamWriteFail`) | **KEEP** code + **REWORD** ledger | Status doc "Remaining known issues #3" is stale — the breakdown is an honest partition of `relayErrors`/`unrelayableCount`. |
| E2 | ChaosInjector "describes itself as unwired" (ledger #4) | KDoc now correctly documents live wiring (`ChaosInjector.kt:5–27`) | **RESOLVED** | Doc was already corrected; ledger entry is stale. |
| E3 | §2.17 NSC cleartext stays strict | `app/src/main/res/xml/network_security_config.xml`; status doc 260–264 | **KEEP** | Deliberate: app-as-client must not use cleartext; proxy relays it via raw sockets, not NSC. |
| E4 | §14.7 "no real blur" | `DESIGN-SYSTEM.md:570` now documents tiered `REAL_BLUR`/`GRADIENT_FALLBACK`; `BlurModifier.kt:80–86, 71–77` | **RESOLVED** | Was "must update when glass lands"; glass landed and the doc was updated. |
| E5 | AGSL RuntimeShader refraction deferred | `BlurModifier.kt:77` (refraction via `1.06×` offset/scale, non-AGSL) | **KEEP** | Out-of-scope by design; current refraction is AGSL-free and suffices. |
| F1 | M4 blank no-op rules | `ReplacementValueUiHelpers.kt:199+` (`ruleConfigError`, blank SCRIPT_REWRITE 207, blank URL pattern 226–229) | **RESOLVED** | Closed by commit `88ee80c`; do not re-open. |
| F2 | 3 deprecation warnings | AutoMirrored icons `ChatScreen.kt:768` / `ApiTestrScreen.kt:2599`; `resume(3-arg)` `ChatScreen.kt:1245,1248` | **RESOLVED** | Closed; do not re-open. |
| G1 | TlsFailureDetailSheet remedy buttons deferred | `InterceptScreen.kt:2441–2442` (CA-install / bypass-rule → dismiss only) | **KEEP** | Deferred and documented in-code; honest. |
| G2 | Http3DetailSheet action buttons deferred | `InterceptScreen.kt:2455–2458` (force-TCP rule / CA install → dismiss only) | **KEEP** | Deferred and documented in-code; honest. |
| H1 | Home greeting emoji + search placeholder rebrand | `HomeScreen.kt:206–209` (`🍃`), 352 (`"Search APIs, notes, chats, tools..."`) | **RESOLVED** (matches Phase B spec) | Rebrand reconciliation is done; nothing to fix. |

---

## Per-item reasoning

### A — VPN / tun (master plan)

**A1 — `establish()` null → fail (KEEP).** `RequestrVpnService.kt` calls
`builder.establish()` (line 205) and, on null, invokes `fail("Could not establish VPN
interface…")` (206–209). This is the correct honest path: a null `ParcelFileDescriptor` means the
tun could not be brought up, and the service must not proceed in a half-configured state. On the
Redroid emulator this manifests as the environmental `IllegalStateException("Cannot create
interface")`, which is caught and surfaced at 380–383 as an honest ERROR rather than a crash or a
silent false "running" state. No code change needed.

**A2 — native bridge fallback (KEEP).** The native tun2proxy bridge is only attempted at
255–302; when it returns `Unavailable`/`Failed`, the service falls back to the hand-rolled
`PacketPump`/`TunRelay` pipeline (281–301), sets `WatcherStatus.DEGRADED` (300) and
`usingNativeBridge=false` (282). This is designed graceful degradation, not an accidental
divergence, and mirrors the master plan's "native first, pure-Kotlin fallback" intent.

**A3 — "not possible in emulator" (KEEP).** The master-plan status ledger correctly classifies
the tun/VPN path as *not possible* in the Redroid environment. That is an environmental limit,
not an app defect, and the plan already distinguishes it from "not built". Keep as a provenance
note; do not attempt to "fix" it on this host.

### B — Phase A / design divergences

**B1 — `gesturesEnabled` (KEEP, deliberate divergence).** The nav mockup
(`navigation_showcase_dark.html:418`) hardcodes side-swipe-for-drawer off
(`gesturesEnabled=false`). The app instead binds the user-visible setting
`settings.gestureNavigationEnabled` (`AppNavigationDrawer.kt:94`, toggle at
`SettingsScreen.kt:477`, model `AppModels.kt:569`). The design doc *already recorded this as a
rejection* (`DESIGN-SYSTEM.md` §10.4 L440: "Reject main's hardcoded `gesturesEnabled=false` —
keep backup `settings.gestureNavigationEnabled`"). This is a sanctioned divergence, so KEEP.

**B2 — sensitive-header masking (KEEP, matches the user decision).** The full decision is in
the code, not just the doc. `SENSITIVE_HEADER_NAMES` (`CapturedTrafficHelpers.kt:404–409`) covers
`set-cookie`, `cookie`, `authorization`, and `proxy-authorization`; `isSensitiveHeaderName` (418)
is case-insensitive; `maskHeaderValue` (435) renders a fixed
`HEADER_MASK_PLACEHOLDER` (`•••••••••`, 440) plus a *byte-length* suffix so the mask never
length-leaks per-character. In `InterceptScreen.kt`, `SensitiveAwareHeaderRow` (4982) renders the
mask when un-revealed and the real value when revealed, with per-header ephemeral reveal state
(`revealedHeaders` `mutableStateMapOf` keyed per header, `remember(item.id)`, 4811), a
tap-to-toggle eye icon (5002–5005), and copy coded to always copy the *real* value (the comment at
5013–5016 explicitly calls out that clamping copy to the masked placeholder would "defeat copy as
a workflow"). Every requirement in the decision is implemented. KEEP.

**B3 — Base64 binary-file input (KEEP deferral, REWORD description).** The deferral recorded in
the handoff (§3.2/§10) is intact: `encodeBase64`/`decodeBase64` are String↔UTF-8 only
(`ToolsRepository.kt:195,201`), and `Base64Workspace` (`ToolWorkspaceScreen.kt:407`) offers no
binary/file input. That deferral is correct to keep. However, the tool description
`ToolsRepository.kt:65` reads "RFC 4648 Base64 text & binary converter" — since binary input was
deferred, the word "binary" is now misleading and should be removed from the description string.
**Verdict split: KEEP the deferral; REWORD the description.**

**B4 — "N environments" omitted (KEEP).** `CollectionSummary` (`SavedApisGrouping.kt:31–39`)
exposes `name` and `endpointCount` but no environment field (folders have no per-folder
environment binding; `EnvironmentRepository` models one global list). `CollectionGroupCard`
therefore renders only "N endpoints" (`SavedApisScreen.kt:621–625`) and the in-code comment
(616–620) documents *why* the mockup's "2 environments" line is intentionally dropped. This is a
correct "don't invent data" decision. KEEP.

**B5 — Environments drawer row, location discrepancy (KEEP row; REWORD doc pointer).** The
"honestly disabled" Environments row does exist and is indeed honestly disabled
(`DrawerItem("Environments", "No dedicated screen in requestr yet — managed only as request
variables", onClick = null)`). But it is **not** in `AppNavigationDrawer.kt` (whose navItems,
212–221, are home/intercept/apis/tools/notes/flow/chat/music). It lives in the **Reqy
Assistant's own side drawer**, `ChatScreen.kt:1476` (KDoc 1364–1376). Every doc/handoff that says
"main AppNavigationDrawer" (e.g. CONTINUITY-HANDOFF §8.4/§11) names the wrong file — REWORD those
pointers. The row itself is correct: KEEP.

**B6 — onboarding reconciliation (REWORD docs; KEEP the decision).** The core product decision —
*no VPN Service Permission / Install Root CA onboarding steps* — is intact: current code has three
steps, `IdentityStep` (196), `PermissionsStep` (201, storage/notification/music), and
`AppearanceStep` (221, theme mode + light/dark accent). No Diagnostics step exists (verified by
grep). Docs/handoff saying "stays Identity/Theme/Diagnostics" and the mockup still listing
VPN/CA steps (185, 192) are both stale. The code is fine; REWORD the docs to "Identity /
Permissions / Appearance". This is a diagnosis of *doc drift*, not a code bug — the decision to
exclude VPN/CA is preserved and should be stated as such when reworded.

**B7 — cURL importer (KEEP).** `CurlParser.kt` refuses flags it cannot faithfully import rather
than silently guessing: `VALUE_FLAGS` (62), `BOOLEAN_SHORT_FLAGS`, and `-F/--form/--form-string
/-T/--upload-file` (80), surfaced via `unsupportedFlag` (230–231) so the caller can issue "I can't
import this flag". `-F/-T` are consumed-and-discarded deliberately. Correct. KEEP.

### C — Dead code

**C1 — `RichResponseInspector` (REMOVE; pending user call).** `ResponseInspectorView.kt` is a
1312-line file whose composition root `RichResponseInspector` (402) has **zero** production
callers. Its helpers `ParsedCookie` (92), `parseCookiesFromHeaders` (103), `HexRow` (163),
`generateHexRows` (169), `UniversalSyntaxHighlighter`, and `CookieFlagBadge` are used only by
that dead composable and by `ResponseInspectorHelpersTest.kt`. The live capture-inspection UI is
now `CapturedDetailBottomSheet` (`InterceptScreen.kt:4637`), which uses the new helpers in
`CapturedTrafficHelpers.kt` (whose header comment at 15–17 explicitly says the old inspector was
kept *out* of scope). This block is dead and should be REMOVED — but it was already surfaced to
the user with finding `62e5a42a-3f2f-478c-adce-eb505ca64826` (idempotency-key
`requestr-dead-response-inspector`, status PENDING per handoff §9). **Do not delete without the
user's call.**

**C2 — `DynamicIconPress` (REMOVE).** `DynamicIconPress.kt:30` declares a public composable with
no callers anywhere in the tree (verified by a word-boundary grep over all of `ui/`; no false
positives). It is not referenced by any doc — newly-found dead code. REMOVE.

**C3 — `KeyValueTableRow` (REMOVE).** `ApiTestrScreen.kt:2282` declares a private composable
whose own in-file comment says it's a "v6.0 transplant (manifest §5.5)", and it has zero call
sites even inside `ApiTestrScreen.kt`. REMOVE.

*(Exempt from C: the `@Preview`-annotated composables in `Http3DetailSheet.kt`,
`RelayHealthCard.kt`, `RelayHealthDetailSheet.kt`, `TlsFailureDetailSheet.kt`,
`Http3RowIndicator.kt` are intentional Android Studio previews, not dead code.)*

### D — Stale comments / reflection shims

**D1 — `CaP12Exchange` stale KDoc (REMOVE comment).** The KDoc at `CaP12Exchange.kt:30–33` says
the `.p12` import path is "currently unreachable from the UI … intentionally left out of this
engine-fix PR". That is now false: the feature is fully wired through `P12ImportPasswordDialog`
(`SettingsScreen.kt:1959`), the "Import .p12 (with password)" affordance (1324), the
`importP12` call (2032), and `P12ImportErrorClassifier` (2043–2044). The stale comment will
mislead; REMOVE it (and any "left out of this PR" phrasing).

**D2 — `connectionIdOrNull()` reflection shim (REMOVE).** `HttpStackDetector.kt:151–169` still
uses `getDeclaredField("connectionId")` reflection and a comment claiming "this worktree has not
merged that branch in yet" (Room v9 / `c6b7069`). That is obsolete: `CapturedTrafficEntity.
connectionId: String?` is a real, direct field (`Entities.kt:55`, with an `Index` at 15), and the
caller at line 119 could read it directly. REMOVE the shim and simplify to `row.connectionId`.

### E — "Remaining known issues" ledger reconciliation

**E1 — TunRelay per-reason drop counters (KEEP code; REWORD ledger).** The status doc's
"Remaining known issues #3" lists per-reason drop counters as deferred. They are now implemented:
`RelayStats` (`TunRelay.kt:62–92`) carries `evictionCount`, `dropProtectFail`,
`dropUpstreamOpen`, `dropUpstreamWrite`, `dropUnrelayableProto`, and
`dropTcpUpstreamWriteFail`, each incremented at the same site that already bumps the aggregate
`relayErrors`/`unrelayableCount` — an honest partition, never an invented stream. The fifth
mockup category (tun-MTU over-length) and TCP EAGAIN are *deliberately absent* (documented,
lines 75–80), which is correct. Mark the ledger entry done. KEEP the code.

**E2 — ChaosInjector "unwired" KDoc (RESOLVED).** The ledger's #4 ("KDoc describes itself as
unwired, but it is in fact wired") is stale: the KDoc at `ChaosInjector.kt:5–27` now documents the
full live wiring (`RuleEnforcer.parseChaosConfig`, `applyChaosToRequest`, `applyChaosToResponse`,
`CHAOS_ATTRIBUTION_HEADER`, and which paths don't run chaos). No action.

**E3 — §2.17 NSC cleartext (KEEP).** `network_security_config.xml` still sets
`base-config cleartextTrafficPermitted="false"` and permits cleartext only for `localhost`. The
status doc explains (260–264) that this is deliberate: the app-as-client must not use cleartext,
while the app-as-proxy relays cleartext via raw sockets that are not governed by NSC. This is the
correct distinction. KEEP — the `⚠️` is intentional.

**E4 — §14.7 "no real blur" (RESOLVED).** The handoff §6 flagged that `DESIGN-SYSTEM.md` §14.7
would need updating when glass landed. Glass landed (`BlurModifier.kt`), and §14.7 (L570) now
documents the tiered `REAL_BLUR` (API 31+) / `GRADIENT_FALLBACK` (API 24–30) behavior, including
the note that `Modifier.blur` is never used. Resolved; no change.

**E5 — AGSL RuntimeShader refraction (KEEP).** `BlurModifier.kt:77` documents that refraction is
realized via a `1.06×` offset/scale rather than an AGSL `RuntimeShader`, which was explicitly
out of scope. The fallback gradient+hairline tier (71–77) is correct. KEEP.

### F — Previously flagged, now closed (do NOT re-open)

**F1 — M4 blank no-op rules (RESOLVED).** `ReplacementValueUiHelpers.kt` now fully validates rule
configs (`ruleConfigError` 199+, blank SCRIPT_REWRITE check 207, AES key alias, JSON shape, blank
URL pattern 226/229). Closed by `88ee80c`. Do not re-open.

**F2 — deprecations (RESOLVED).** `Icons.Filled.Reply` → `Icons.AutoMirrored.Filled.Reply`
(`ChatScreen.kt:768`); `Icons.Filled.FormatListBulleted` → AutoMirrored (`ApiTestrScreen.kt:2599`);
3-arg `resume(value, onCancellation)` migrated (`ChatScreen.kt:1245,1248`). All three closed. Do
not re-open.

### G — Intercept detail sheets (deferred, honest)

**G1 — TlsFailureDetailSheet (KEEP).** Remedy buttons (CA-install flow, bypass-rule creation) are
deliberately deferred (`InterceptScreen.kt:2441–2442`) and dismiss only. This is honest
half-implemented UI, documented in-code. KEEP.

**G2 — Http3DetailSheet (KEEP).** Action buttons (force-TCP rule, CA install) are deliberately
deferred (`InterceptScreen.kt:2455–2458`) and dismiss only. KEEP.

### H — Phase B rebrand confirmations

**H1 — Home greeting + search placeholder (RESOLVED).** The Phase B rebrand reconciliation is
done: greeting emoji is `🍃` across all time-of-day branches (`HomeScreen.kt:206–209`, replacing
`🌤️`) and the search placeholder is `"Search APIs, notes, chats, tools..."` (352, adding "chats",
dropping flow nodes, matching the spec). No change.

---

## Top 10 highest-impact items to fix first

Ranked by (a) whether the item actively misleads a future reader, (b) the size/risk of the dead
weight, and (c) whether it can be fixed without a user decision.

1. **D1 — Remove the stale `CaP12Exchange` "unreachable from UI" KDoc.** Action: delete the
   `CaP12Exchange.kt:30–33` paragraph claiming `.p12` is unimplemented. *Why first:* it asserts a
   shipped feature doesn't exist and will derail anyone trusting the KDoc.
2. **D2 — Remove the `connectionIdOrNull()` reflection shim.** Action: in `HttpStackDetector.kt`,
   replace lines 151–169 with a direct `row.connectionId` read. *Why:* obsolete reflection on a
   now-real column; a subtle future-proofing hazard.
3. **C1 — Delete the dead `RichResponseInspector` block.** Action: remove
   `ResponseInspectorView.kt` (1312 lines) plus the now-orphaned `ResponseInspectorHelpersTest.kt`
   and the `CapturedTrafficHelpers.kt:15–17` reference — **only after the user's call** on finding
   `62e5a42a…`. *Why:* largest dead-code weight and the single biggest maintenance tax.
4. **C2 / C3 — Delete `DynamicIconPress` and `KeyValueTableRow`.** Action: remove
   `DynamicIconPress.kt` and `ApiTestrScreen.kt:2282`. *Why:* small, unambiguous, no user decision
   needed — quickest wins.
5. **B5 — Correct the "Environments drawer row" location in every doc.** Action: in
   CONTINUITY-HANDOFF §8.4/§11 (and any dossier repeating it), change "AppNavigationDrawer" to
   "Reqy Assistant drawer (`ChatScreen.kt:1476`)". *Why:* wrong-file pointer makes the audit's
   otherwise-honest finding impossible to locate.
6. **B6 — Reword onboarding docs to Identity / Permissions / Appearance.** Action: update the
   dossiers (and the stale mockup references) so the step list matches `OnboardingScreen.kt`,
   while explicitly restating that VPN/CA steps remain excluded. *Why:* doc drift on a
   user-facing flow.
7. **B3 — Drop "binary" from the Base64 tool description.** Action: rewrite
   `ToolsRepository.kt:65` to "RFC 4648 Base64 text converter" so it no longer promises the
   deferred binary-input feature. *Why:* one-line fix that removes a false capability claim.
8. **E1 — Mark TunRelay per-reason drop counters done in the status ledger.** Action: remove
   "Remaining known issues #3" and note the counters now live in `TunRelay.kt:72–92`. *Why:*
   keeps the canonical "done/partial/not-built" ledger truthful about what's actually shipped.
9. **E2 — Mark ChaosInjector "unwired KDoc" done in the ledger.** Action: remove "Remaining
   known issues #4". *Why:* same ledger-truth rationale as #8.
10. **B2 — (already correct) Leave the sensitive-header masking as-is, but add the reveal/copy
    contract to the audit trail.** Action: in the maintained design doc, record *"mask by default;
    per-header ephemeral reveal; copy always copies the real value"* so a future "fix" doesn't
    clamp copy to the placeholder (the trap the code comment at `InterceptScreen.kt:5013–5016`
    warns about). *Why:* prevents a well-meaning regression on a security-sensitive feature.

---

## Bottom-line classification

- **REMOVE (code):** C1 (`RichResponseInspector`, pending user call), C2 (`DynamicIconPress`), C3
  (`KeyValueTableRow`), D1 (stale KDoc), D2 (reflection shim).
- **REWORD (docs/comments/strings, no code behavior change):** B3 (Base64 description), B5
  (Environments-row location), B6 (onboarding steps), E1 (TunRelay ledger entry).
- **KEEP (correct as-is):** A1–A3, B1, B2, B4, B7, E3, E5, G1–G2.
- **RESOLVED (already fixed, mark done):** E2, E4, F1, F2, H1.

**Key doc-vs-code contradictions to trust code over docs:** Continuity-handoff is stale in at
least four places (Environments-row file, onboarding step list, TunRelay counters, M4/deprecation
"outstanding"), each covered above. In every case the current tree is authoritative.
