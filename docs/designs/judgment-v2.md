# Judgment v2: Interception Overhaul — UI Surfaces

**Verdict: APPROVED** — Both prior blockers are resolved. No new blockers found.

**Re-evaluated plan:** `/code/.plans/designs/implementation-plan.md` (revised)

---

## Resolution of Prior Blockers

### Blocker 1 (CRITICAL) — Domain C: Relay Health composables → RESOLVED

**Original issue:** Plan presented `RelayHealthCompactCard` and `RelayHealthDetailSheet` as new creations when they already exist as private composables in `InterceptScreen.kt` (~lines 1579–1721).

**Resolution applied:**
- Domain C "Existing code" section now acknowledges both private composables, their current capabilities, and the specific missing mockup states (degraded detail, down detail).
- Tasks C3 and C4 rephrased from **Create** to **Extract + augment** — extract the private composables to their own files, then add the missing mockup elements.
- Task C5 rephrased to "remove private relay composables, replace with imports" instead of "insert card."
- Sub-agent allocation updated to match: `agent-relay-health` now shows "Extract + augment" actions.

**Remaining gap:** The `Start capture` CTA button with haptics from the down-detail mockup is explicitly called out in the augmented task descriptions. The per-reason drop counters (C1) remain genuine new work.

**Verdict:** Resolved.

---

### Blocker 2 (MODERATE) — BadgePill location claim → RESOLVED

**Original issue:** Plan's design-to-code mapping (Section 5) said `BadgePill` is in `CommonComponents.kt`, but it's actually a private composable in `InterceptScreen.kt` (line ~3443). All four domains would fail to compile if agents tried to import it.

**Resolution applied:**
- **New Section 0: Pre-Work** added before all domains. This extracts `BadgePill` to `CommonComponents.kt` as a public composable and removes the private version from `InterceptScreen.kt`.
- **Task 0** added to dependency diagram as the prerequisite gate for all four domains.
- **Task 0** added to Todo List (tasks 0a–0c) with explicit verification step.
- **Task 0** added to Sub-Agent Allocation as `agent-badgepill-extract`.
- Merge order updated: Task 0 merges first, all domains rebase onto it before opening PRs.

**Design-to-code mapping:** The mapping table in Section 5 still references "BadgePill pattern from CommonComponents.kt" — this is now **accurate post-Task-0** since BadgePill will live there by the time domain agents consume it.

**Verdict:** Resolved.

---

## Re-Evaluation of All 5 Criteria

### 1. Completeness — PASS

All 36 design mockups across 4 domains are accounted for:
- Domain A: 8 mockups (3 row-indicator themes × 2 themes + 3 detail states × 2 themes) ✓
- Domain B: 12 mockups (3 CDP states × 2 themes + 3 instrumentation states × 2 themes) ✓
- Domain C: 10 mockups (3 states × 2 themes for compact, 2 states × 2 themes for detail) ✓
- Domain D: 6 mockups (1 mixed-row theme × 2 + 2 failure detail states × 2) ✓

Every mockup maps to a specific composable or composable state in Section 5. Task 0 now covers the shared `BadgePill` dependency that all domains need.

### 2. Accuracy — PASS

| Claim | Before | After | Status |
|---|---|---|---|
| `BadgePill` location | CommonComponents.kt | InterceptScreen.kt → extracted by Task 0 | Fixed |
| Relay Health composables | Create from scratch | Extract + augment existing private composables | Fixed |
| `InterceptScreen.kt` line count | ~2200 | ~3522 | Fixed |
| `QuicState` type name | `QuicState` | `QuicLiveSummary` | Fixed |
| `GlassCard` location | CommonComponents.kt ✓ | (unchanged) | Correct |
| `SectionHeader` location | CommonComponents.kt ✓ | (unchanged) | Correct |
| TLS tab line ranges | 1995–2077 ✓ | (unchanged) | Correct |
| QUIC tab line ranges | 1938–1993 ✓ | (unchanged) | Correct |

### 3. Feasibility — PASS

All 25 tasks (1 pre-work + 24 domain) have concrete file paths and clear scope boundaries. No ambiguous "figure it out" tasks. Each domain has a specific data model, provider/mapper, and UI composable pattern that follows existing codebase conventions (`GlassCard`, `ModalBottomSheet`, `BadgePill`).

**File conflict surface:**
- Task 0 touches `CommonComponents.kt` + `InterceptScreen.kt` (BadgePill removal only)
- Domain C touches `InterceptScreen.kt` (relay composable removal, lines ~1565–1721) + `TunRelay.kt`
- Domain D touches `InterceptScreen.kt` (TLS tab click handler, lines ~2000–2077)
- Domain A touches `InterceptScreen.kt` (row indicators + QUIC tab detail wiring)
- Domain B touches `SettingsScreen.kt` only

Conflict surface is manageable with the recommended merge order (Task 0 → C → D → A → B).

### 4. Dependencies — PASS

```
Task 0 (BadgePill extract) ─── gates ───┬── Domain C (Relay Health)
                                        ├── Domain D (TLS Failure)
                                        ├── Domain B (Runtime CDP)
                                        └── Domain A (HTTP/3)
```

All four domain branches rebase onto Task 0's merge. After that, all domains are independent and can proceed in parallel. No hidden cross-domain data dependencies exist.

### 5. Gaps — PASS (non-blocking observations only)

No blocking gaps remain. One non-blocking observation carries forward:

**N1. Settings accent selection mockups not covered.** Two files (`settings-accent-selection-dark.html`, `settings-accent-selection-light.html`) exist in `.plans/designs/` but are not part of the 36 mockups in `docs/designs/`. These are not covered by any domain in this plan. If accent selection is in scope, it needs its own task outside this plan.

---

## Non-Blocking Observations

These are informational — no action required on the plan:

### N2. Extra QUIC mockups
`quic-connection-decoded-with-keys.html` and `quic-connection-observed-without-keys.html` in `.plans/designs/` appear to be duplicates or supersets of Domain A's `http3-detail-decoded-dark.html` / `http3-detail-undecrypted-dark.html`. Domain A already covers these.

### N3. Edge case: relay health DOWN "Start capture" CTA
The existing `RelayHealthCompactCard` handles `!pumpRunning` as "NOT ACTIVE" with gray styling. The mockup's specific "Start capture" button with haptics is now explicitly listed in the augmented task C4 description ("Start capture CTA with haptics").

### N4. Per-stage TLS timestamps
The TLS Failure Detail stage rail uses inferred stage ordering (ClientHello → ServerHello → Alert) based on alert type rather than actual timestamps, since `TlsSessionEntity` doesn't store per-stage timestamps. The plan acknowledges this gap and the inferred approach is acceptable for the UI.

### N5. Merge conflict risk
Domain A, C, D all modify `InterceptScreen.kt` but in different sections. Domain B modifies `SettingsScreen.kt` only. With Task 0 merging first (removing the private BadgePill), the remaining diffs are cleanly separated by section boundaries. Risk remains manageable.

---

## Summary

| Criterion | Result |
|---|---|
| Completeness | ✓ PASS |
| Accuracy | ✓ PASS |
| Feasibility | ✓ PASS |
| Dependencies | ✓ PASS |
| Gaps | ✓ PASS |

**Final verdict: APPROVED.** The plan is ready for agent dispatch. Task 0 must execute first; all four domain agents rebase onto Task 0's merge before starting.
