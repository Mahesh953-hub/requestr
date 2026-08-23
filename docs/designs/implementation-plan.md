# Implementation Plan: Interception Overhaul — UI Surfaces

> **TL;DR:** Build four independently testable Compose UI domains — VPN Relay Health, TLS Failure Detail, HTTP/3 Indicators, and Runtime CDP/Instrumentation — from 36 design mockups across dark/light themes, layered onto the existing InterceptScreen, SettingsScreen, and ConnectionsBottomSheet.

---

## 0. Pre-Work: Extract Shared BadgePill

**Blocker resolution:** `BadgePill` is a private `@Composable` function in `InterceptScreen.kt` (line ~3443), not in `CommonComponents.kt`. All four domains reference the `BadgePill` pattern in their design-to-code mappings (pill indicators, state badges, disposition labels). Without extracting it, every new composable file will fail to compile when attempting to use the `BadgePill` pattern.

| # | Task | File | Action |
|---|---|---|---|
| **0** | Extract `BadgePill` from `InterceptScreen.kt` to `CommonComponents.kt` as a **public** composable | `ui/components/CommonComponents.kt` | **Modify** — add `@Composable fun BadgePill(text: String, color: Long)` |
| | | `ui/screens/InterceptScreen.kt` | **Modify** — remove private `BadgePill`, replace all call-sites with import of the public version |

**Must be completed before any domain work starts.** All four domains depend on this.

---

## 1. Breakdown by Domain

### Domain A: HTTP/3 Indicator & QUIC Detail

**Mockups (8 files):**
| Mockup | Depicts | Theme |
|---|---|---|
| `http3-row-indicators-dark/light.html` | Traffic list rows: HTTP/3 (decoded, green), HTTP/3 (observed, amber), QUIC (gray) beside H2/H1.1 rows | Both |
| `http3-detail-decoded-dark/light.html` | Bottom-sheet detail for a fully decoded H3 transaction: QUIC version, ALPN, connection IDs, QPACK state, decoded headers | Both |
| `http3-detail-undecrypted-dark/light.html` | Detail for observed but undecrypted H3: QUIC facts, opaque frames, "To make this decodable" remedy block with Force TCP fallback + Install CA buttons | Both |
| `http3-detail-quic-only-dark/light.html` | Detail for QUIC-detected-only (no ALPN): partial ClientHello, DNS hostname, "To confirm HTTP/3" with Check relay MTU + Connections sheet buttons | Both |

**Existing code:**
- `InterceptScreen.kt` lines 1938-1993: QUIC tab in `ConnectionsBottomSheet` already renders three badge states (`HTTP/3` green, `HTTP/3` amber, `QUIC` gray) using `quicLiveMap` and `decodedFrames`. 
- `QuicConnection.kt`: Data model with `alpn`, `quicVersion`, `sniHost`, `state`, `streams`.
- InterceptScreen traffic list rows (`trow`) already exist but lack the H3-specific indicators.

**Files to create/modify:**

| File | Action | Purpose |
|---|---|---|
| `ui/components/Http3DetailSheet.kt` | **Create** | Composable for the H3 detail bottom sheet with three states: decoded, undecrypted, QUIC-only |
| `ui/components/Http3RowIndicator.kt` | **Create** | Row-level H3 indicator composable (green lock-open / amber key-off / gray QUIC pills) |
| `ui/screens/InterceptScreen.kt` | **Modify** | Add H3 indicators to traffic list rows; wire click-through to `Http3DetailSheet`; add detail sheet invocation from QUIC tab |
| `data/models/Http3State.kt` | **Create** | `Http3DetailState` sealed class with three subclasses for decoded/undecrypted/quic-only detail data |
| `interception/quic/QuicDetailProvider.kt` | **Create** | Maps `QuicConnection` + live state + key-log status into `Http3DetailState` |

**Backend/data dependencies:**
- `QuicConnection` (existing): ALPN, QUIC version, SNI, connection IDs
- `quicLiveMap` (existing in InterceptScreen): live `QuicLiveSummary` from PacketPump
- `decodedFrames` flag (existing)
- TLS key-log registry entry count (existing)
- QPACK decoder state (existing in `QpackDecoder.kt`)

**Complexity:** **Low** — mostly UI rework on existing data; rows already have badges, detail sheet is new.

---

### Domain B: Runtime CDP & Instrumentation

**Mockups (12 files):**

*CDP Card (6 files):*
| Mockup | Depicts | Theme |
|---|---|---|
| `runtime-cdp-connected-dark/light.html` | Connected: 4 tabs (1 expanded), Fetch activity, rule-enforcement tiles, storage streams, service workers | Both |
| `runtime-cdp-disconnected-dark/light.html` | Disconnected: editable host:port, empty state | Both |
| `runtime-cdp-reconnecting-dark/light.html` | Reconnecting w/ backoff, stale last-known tabs | Both |

*Runtime Instrumentation Card (6 files):*
| Mockup | Depicts | Theme |
|---|---|---|
| `runtime-instrumentation-healthy-dark/light.html` | All adapters healthy (OkHttp capturing, Cronet capturing, WebView idle), OkHttp verdict evidence expanded | Both |
| `runtime-instrumentation-cronet-unavailable-dark/light.html` | Cronet unavailable w/ reason, DEGRADED state | Both |
| `runtime-instrumentation-webview-active-dark/light.html` | Active offscreen WebView capture session w/ live URL + status | Both |

**Existing code:**
- `SettingsScreen.kt`: Has sections for LOCAL REST API, RUNTIME INSTRUMENTATION, BROWSER BRIDGE (CDP), PLUGIN MANAGER. The runtime instrumentation card and CDP card sections need to be expanded.
- `BrowserBridgeManager.kt`: CDP connection management
- `RuntimeInstrumentationEngine.kt`: Per-adapter instrumentation
- `CronetInterceptor.kt`, `OkHttpInterceptor.kt` (sdk): Adapter interceptors
- `WebViewInstrumentation.kt`: WebView capture support
- `StackSignalStore.kt`: HTTP-stack fingerprinting data
- `CdpClient.kt`, `CdpFetchAdapter.kt`: CDP protocol client

**Files to create/modify:**

| File | Action | Purpose |
|---|---|---|
| `ui/screens/SettingsScreen.kt` | **Modify** | Expand Runtime Instrumentation section and CDP section with new composables |
| `ui/components/RuntimeInstrumentationCard.kt` | **Create** | Per-adapter row composable (OkHttp/Cronet/WebView) with CAPTURING/IDLE/UNAVAILABLE states, event counts, fingerprint verdict rows with evidence drill-in |
| `ui/components/CdpCard.kt` | **Create** | CDP connection card: connected (multi-target tab rows w/ expanded activity), disconnected (editable host:port), reconnecting (backoff timer + stale state) |
| `ui/components/WebViewCaptureControl.kt` | **Create** | Offscreen WebView capture: URL input, start/stop, live status, request count |
| `ui/components/StackFingerprintPanel.kt` | **Create** | Fingerprint evidence panel: JA3 raw/hash, ALPN offers, H2 SETTINGS, header ordering, cache-validation shape, confidence % + per-signal ✓/✗ |
| `data/models/RuntimeInstrumentationState.kt` | **Create** | Data classes for adapter state, CDP connection state, fingerprint verdicts |
| `runtime/RuntimeInstrumentationStateProvider.kt` | **Create** | Flows/StateFlows exposing instrumentation data to UI |

**Backend/data dependencies:**
- `RuntimeInstrumentationEngine` (existing)
- `BrowserBridgeManager` (existing) — CDP connection state
- `StackSignalStore` (existing) — fingerprinting data
- `CronetInterceptor` / `OkHttpInterceptor` (existing, SDK) — adapter event counts
- `WebViewInstrumentation` (existing) — active capture state

**Complexity:** **High** — largest domain by surface area (12 mockups across 2 cards), requires new data flows from existing engines, fingerprint evidence drill-in is detailed.

---

### Domain C: VPN Relay Health

**Mockups (10 files):**
| Mockup | Depicts | Theme |
|---|---|---|
| `relay-health-healthy-compact-dark/light.html` | Collapsed GlassCard on Intercept: status pill (HEALTHY), relayed/dropped bar, per-transport tiles, throughput, flow table line | Both |
| `relay-health-healthy-detail-dark/light.html` | Expanded bottom sheet: relay tile, flow tiles, throughput, drop reasons bar-chart, IPv4/IPv6 NAT rows, flow table pressure | Both |
| `relay-health-degraded-detail-dark/light.html` | Degraded state: 3.7% drops, 5 drop reasons with per-reason bars, flow table at cap (512/512), eviction count, DEGRADED pill | Both |
| `relay-health-down-compact-dark/light.html` | Collapsed card: RELAY DOWN, NOT ACTIVE pill, zeroed counters, dashed throughput | Both |
| `relay-health-down-detail-dark/light.html` | Expanded detail: honest zeroes, last-session summary, "Start capture" CTA button | Both |

**Existing code:**
- `TunRelay.kt`: `RelayStats` data class with `relayedFlows`, `relayBytesUp/Down`, `relayErrors`, `unrelayableCount`, `activeTcpV4/V6`, `activeUdpV4/V6`. Exposed via `StateFlow<RelayStats>`.
- `InterceptScreen.kt` lines ~1579–1721: **Already contains** private composables `RelayHealthCompactCard` and `RelayHealthDetailSheet` — they render a compact GlassCard with status pill, flow count, and throughput, plus a ModalBottomSheet with flow rows, bytes, errors, and ICMP data. Wiring exists at ~line 415 and state collection at ~lines 199–209. However, these composables are **missing** the mockup-specific states: degraded detail (per-reason drop bars, flow-table at cap, eviction count, DEGRADED pill) and down detail (zeroed counters, dashed throughput, "Start capture" CTA button).
- `RelayFlow.kt`: Has `TcpRelayFlow` and `UdpRelayFlow` with 5-tuple data but not the per-reason drop breakdown.

**Gap:** `TunRelay.RelayStats` does not yet have per-reason drop counters (EAGAIN, ENETUNREACH, checksum, flow-table-cap, MTU, ICMPv6 type). The design mockups show these breakdowns — the relay needs to track them.

**Files to create/modify:**

| File | Action | Purpose |
|---|---|---|
| `interception/tun/TunRelay.kt` | **Modify** | Add per-reason drop counters to `RelayStats` (dropReasonEagain, dropReasonNoRoute, dropReasonChecksum, dropReasonMtu, dropReasonFlowCap, dropReasonIcmpv6) and update in `handleTcpFromGuest`/`handleUdpFromGuest` |
| `ui/components/RelayHealthCard.kt` | **Extract + augment** | Extract private `RelayHealthCompactCard` from `InterceptScreen.kt`; augment with flow-table pressure, NAT mapping tiles, and DOWN-state dashed throughput |
| `ui/components/RelayHealthDetailSheet.kt` | **Extract + augment** | Extract private `RelayHealthDetailSheet` from `InterceptScreen.kt`; augment with degraded detail (per-reason drop bars, flow-table at cap 512/512, eviction count, DEGRADED pill) and down detail (zeroed counters, "Start capture" CTA with haptics) |
| `ui/screens/InterceptScreen.kt` | **Modify** | Remove private relay composables; replace with imports from `RelayHealthCard.kt` / `RelayHealthDetailSheet.kt`; existing wiring at ~line 415 stays intact |
| `data/models/RelayHealthState.kt` | **Create** | `RelayHealthState` sealed class (Healthy/Degraded/Down) with full detail data |

**Backend/data dependencies:**
- `TunRelay.stats` StateFlow (existing, needs augmentation)
- `TunRelay` flow table pressure (existing `MAX_FLOWS` constant)

**Complexity:** **Medium** — card is straightforward, detail sheet has many rows but all from a single data source; the `TunRelay` per-reason counter augmentation is the main non-UI work.

---

### Domain D: TLS Failure Detail

**Mockups (6 files):**
| Mockup | Depicts | Theme |
|---|---|---|
| `tls-rows-mixed-dark/light.html` | Mixed TLS tab rows: DECRYPTED (green, lock), SNI/JA3 ONLY (gray, lock), FAILED (red, warning, 3px left rail) | Both |
| `tls-failed-detail-unknown-ca-dark/light.html` | Expanded detail: plain-language headline, category "unknown_ca", "client rejected our CA", 3-step handshake-stage rail, raw alert record, remedy: "Install requestr CA" + "Bypass this host" | Both |
| `tls-failed-detail-handshake-failure-dark/light.html` | Expanded detail: headline "Handshake failed", category "handshake_failure", "server sent fatal alert", 3-step stage rail (ClientHello → ServerHello → Alert), raw alert, remedy: "Extend cipher set" + "Bypass this host" | Both |

**Existing code:**
- `InterceptScreen.kt` lines 1995-2077: TLS tab already renders three badge states (FAILED red, DECRYPTED green, SNI/JA3 ONLY gray) with a 3px left red rail for FAILED rows.
- `TlsFailureClassifier.kt`: `classifyTlsOutcome()` produces `TlsOutcome(reason)` from `TcpHandshakePhase`, `TlsAlert`, and `hasEstablishedRow`.
- `TlsSession.kt`: `TlsHandshakeState.FAILED` enum value, `TlsAlert` with `descriptionName`, `isFatal`.
- `TlsDecoder.kt`: Alert parsing.

**Gap:** The existing TLS tab renders a row-level failure reason but lacks the full detail sheet with handshake-stage rail, which side sent the alert, and remedy buttons. Per-stage timestamps are not stored in `TlsSessionEntity`.

**Files to create/modify:**

| File | Action | Purpose |
|---|---|---|
| `ui/components/TlsFailureDetailSheet.kt` | **Create** | Bottom sheet composable: headline, category label, which-side-sent, 3-step handshake-stage rail (ClientHello → ServerHello → Alert/Failure), raw alert record, remedy block with action buttons |
| `ui/screens/InterceptScreen.kt` | **Modify** | Wire tap on FAILED TLS rows to open `TlsFailureDetailSheet`; pass failure data |
| `data/models/TlsFailureDetail.kt` | **Create** | `TlsFailureDetail` data class: headline, category, which side, stages, raw alert, remedy type |
| `interception/tls/TlsFailureDetailProvider.kt` | **Create** | Maps `TlsSession` + `TlsAlert` + phase into `TlsFailureDetail` for UI consumption |

**Backend/data dependencies:**
- `TlsSession` (existing): `sniHost`, `handshakeState`, `failureReason`
- `TlsAlert` (existing): `descriptionName`, `isFatal`, `levelName`
- `TlsFailureClassifier` (existing): `classifyTlsOutcome()`
- `TlsHandshakePhase` (existing in `TcpHandshakePhase`)
- Per-stage timestamps: **not stored** — the stage rail uses inferred stage ordering based on the alert type rather than actual timestamps

**Complexity:** **Low** — detail sheet is a single new composable; row indicators already exist; the main work is building the stage-rail visualization and mapping existing data to the UI model.

---

## 2. Dependency Ordering

```
                         ┌─────────────────┐
                         │  Task 0:        │
                         │  Extract        │
                         │  BadgePill to   │
                         │  CommonComponents│
                         └────────┬────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          │                       │                       │
          ▼                       ▼                       ▼
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│  Domain D: TLS  │   │  Domain C:      │   │  Domain B:      │
│  Failure Detail │   │  Relay Health   │   │  Runtime CDP +  │
│                 │   │                 │   │  Instrumentation│
│  [PARALLEL 1]   │   │  [PARALLEL 1]   │   │  [PARALLEL 1]   │
└────────┬────────┘   └────────┬────────┘   └────────┬────────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               │
                               ▼
                    ┌─────────────────┐
                    │  Domain A:      │
                    │  HTTP/3 Detail  │
                    │                 │
                    │  [PARALLEL 2]   │
                    │  can also run   │
                    │  in parallel    │
                    │  with batch 1   │
                    └─────────────────┘
```

**Notes:**
- **Task 0 (Extract BadgePill)** must complete first. It modifies `CommonComponents.kt` and `InterceptScreen.kt` — removing the private `BadgePill` (line ~3443) and replacing all call-sites with imports. All four domains reference the `BadgePill` pattern.
- **Domain D (TLS Failure Detail)** is independent — TLS tab rows are already wired, detail sheet is additive.
- **Domain C (Relay Health)** requires modifying `TunRelay.kt` for per-reason drop counters, then extracting and augmenting the existing private composables from `InterceptScreen.kt`. No dependency on other domains beyond Task 0.
- **Domain B (Runtime CDP/Instrumentation)** is independent — both cards live on SettingsScreen and have no coupling to InterceptScreen domains.
- **Domain A (HTTP/3 Detail)** can run in parallel with B, C, D. The existing QUIC tab has all the data; the detail sheet and row indicators are additive UI.

**All four domains can start in parallel after Task 0 completes.** The only minor serialization: Domain C modifies `TunRelay.kt` which could conflict with Domain A if A also touches it (it doesn't). Domain A, C, D all modify `InterceptScreen.kt` — these changes are in different sections of the file and should merge cleanly with good diff boundaries.

---

## 3. Sub-Agent Allocation

### Pre-Work (must complete before any domain work)

| Agent | Task | Worktree Branch | Directory | Files |
|---|---|---|---|---|
| **agent-badgepill-extract** | Extract BadgePill | `feat/extract-badgepill` | `app/src/main/java/com/example/ui/components/` | Modify: `CommonComponents.kt` (add `BadgePill` as public `@Composable`), `InterceptScreen.kt` (remove private `BadgePill`, replace call-sites with import). |

### Parallel Batch 1 (all 4 domains concurrently)

| Agent | Domain | Worktree Branch | Directory | Files |
|---|---|---|---|---|
| **agent-tls-detail** | TLS Failure Detail | `feat/tls-failure-detail-sheet` | `app/src/main/java/com/example/ui/components/` | Create: `TlsFailureDetailSheet.kt`, `data/models/TlsFailureDetail.kt`, `interception/tls/TlsFailureDetailProvider.kt`. Modify: `InterceptScreen.kt` (wire tap-to-open). |
| **agent-relay-health** | Relay Health | `feat/relay-health-card` | `app/src/main/java/com/example/ui/components/` | Extract + augment: `RelayHealthCard.kt`, `RelayHealthDetailSheet.kt` from `InterceptScreen.kt`. Create: `data/models/RelayHealthState.kt`. Modify: `TunRelay.kt` (add per-reason counters), `InterceptScreen.kt` (remove private composables, replace with imports). |
| **agent-runtime-cdp** | Runtime CDP + Instrumentation | `feat/runtime-cdp-instrumentation` | `app/src/main/java/com/example/ui/components/` | Create: `RuntimeInstrumentationCard.kt`, `CdpCard.kt`, `WebViewCaptureControl.kt`, `StackFingerprintPanel.kt`, `data/models/RuntimeInstrumentationState.kt`, `runtime/RuntimeInstrumentationStateProvider.kt`. Modify: `SettingsScreen.kt` (expand sections). |
| **agent-http3-detail** | HTTP/3 Detail | `feat/http3-detail-indicators` | `app/src/main/java/com/example/ui/components/` | Create: `Http3DetailSheet.kt`, `Http3RowIndicator.kt`, `data/models/Http3State.kt`, `interception/quic/QuicDetailProvider.kt`. Modify: `InterceptScreen.kt` (add row indicators, wire detail sheet). |

Each agent gets a clean worktree from `origin/backup` (commit `6cff7fa`), creates its own branch, and works exclusively within its domain files.

---

## 4. Todo List

### Task 0: Extract BadgePill (pre-work, must complete before all domains)
| # | Task | Status |
|---|---|---|
| 0a | Add `BadgePill(text: String, color: Long)` as public `@Composable` in `ui/components/CommonComponents.kt` | Pending |
| 0b | Remove private `BadgePill` from `InterceptScreen.kt` (line ~3443); update all 15+ call-sites to use the CommonComponents import | Pending |
| 0c | Verify: `./gradlew :app:compileDebugKotlin` passes with no unresolved BadgePill references | Pending |

### Domain D: TLS Failure Detail
| # | Task | Status |
|---|---|---|
| D1 | Create `data/models/TlsFailureDetail.kt` — data class with headline, category, whichSide, stages, rawAlert, remedyType | Pending |
| D2 | Create `interception/tls/TlsFailureDetailProvider.kt` — map TlsSession + TlsAlert to TlsFailureDetail | Pending |
| D3 | Create `ui/components/TlsFailureDetailSheet.kt` — bottom sheet with headline, stage rail, raw alert, remedy buttons | Pending |
| D4 | Modify `InterceptScreen.kt` TLS tab — add click handler on FAILED rows to open `TlsFailureDetailSheet` | Pending |
| D5 | Test: verify each failure variant (unknown_ca, handshake_failure) renders correct headline, stage rail, buttons | Pending |

### Domain C: Relay Health
| # | Task | Status |
|---|---|---|
| C1 | Modify `TunRelay.kt` — add per-reason drop counters to `RelayStats`, instrument drop sites | Pending |
| C2 | Create `data/models/RelayHealthState.kt` — sealed class Healthy/Degraded/Down with full data | Pending |
| C3 | Extract `RelayHealthCompactCard` from `InterceptScreen.kt` to `ui/components/RelayHealthCard.kt`; augment with missing mockup elements (flow-table pressure, NAT mapping tiles, DOWN-state dashed throughput) | Pending |
| C4 | Extract `RelayHealthDetailSheet` from `InterceptScreen.kt` to `ui/components/RelayHealthDetailSheet.kt`; augment with degraded detail (per-reason drop bars, flow-table at cap, eviction count, DEGRADED pill) and down detail (zeroed counters, last-session summary, "Start capture" CTA with haptics) | Pending |
| C5 | Modify `InterceptScreen.kt` — remove private relay composables (~lines 1565–1721), replace with imports from extracted files; verify existing wiring (~line 415) stays intact | Pending |
| C6 | Test: verify healthy compact/detail, degraded with drop reasons, down with zeroes and CTA button | Pending |

### Domain B: Runtime CDP + Instrumentation
| # | Task | Status |
|---|---|---|
| B1 | Create `data/models/RuntimeInstrumentationState.kt` — adapter states, CDP state, fingerprint verdicts | Pending |
| B2 | Create `runtime/RuntimeInstrumentationStateProvider.kt` — StateFlows from existing engines | Pending |
| B3 | Create `ui/components/RuntimeInstrumentationCard.kt` — per-adapter rows (OkHttp/Cronet/WebView) with state pills and event counts | Pending |
| B4 | Create `ui/components/StackFingerprintPanel.kt` — fingerprint verdict rows with confidence % + per-signal ✓/✗ + evidence drill-in | Pending |
| B5 | Create `ui/components/WebViewCaptureControl.kt` — URL input, start/stop, live status, request count | Pending |
| B6 | Create `ui/components/CdpCard.kt` — CDP card with connected/disconnected/reconnecting states, tab rows, storage streams, SW | Pending |
| B7 | Modify `SettingsScreen.kt` — expand RUNTIME INSTRUMENTATION and BROWSER BRIDGE (CDP) sections with the new composables | Pending |
| B8 | Test: verify all 6 runtime instrumentation states (healthy, cronet-unavailable, webview-active × dark/light) | Pending |
| B9 | Test: verify all 3 CDP states (connected, disconnected, reconnecting) | Pending |

### Domain A: HTTP/3 Detail
| # | Task | Status |
|---|---|---|
| A1 | Create `data/models/Http3State.kt` — sealed class Decoded/Undecrypted/QuicOnly with detail data | Pending |
| A2 | Create `interception/quic/QuicDetailProvider.kt` — map QuicConnection + live state to Http3State | Pending |
| A3 | Create `ui/components/Http3RowIndicator.kt` — row-level pill composable (green lock-open / amber key-off / gray QUIC) with icons | Pending |
| A4 | Create `ui/components/Http3DetailSheet.kt` — bottom sheet with three states: decoded (headers+QPACK), undecrypted (remedy), QUIC-only (confirm) | Pending |
| A5 | Modify `InterceptScreen.kt` traffic list — add H3 row indicator pill to traffic rows | Pending |
| A6 | Modify `InterceptScreen.kt` QUIC tab — wire row tap to open `Http3DetailSheet` | Pending |
| A7 | Test: verify row indicators (decoded/undecrypted/QUIC-only) render correctly beside H2/H1.1 | Pending |
| A8 | Test: verify all 3 detail sheet states with correct content and action buttons | Pending |

---

## 5. Design-to-Code Mapping

### HTTP/3 Detail Domain

| Mockup | Screen Element | Composable | Key Patterns |
|---|---|---|---|
| `http3-row-indicators-*.html` (pill rows) | Traffic list row H3 badge | `Http3RowIndicator(pillState)` | `BadgePill` pattern from `CommonComponents.kt`, icon + text pills at 10sp monospace |
| `http3-detail-decoded-*.html` (decoded sheet) | Bottom sheet — decoded state | `Http3DetailSheet(state = Decoded(...))` | `ModalBottomSheet` with `GlassCard`, QPACK state as `kv` rows, headers in `code` block |
| `http3-detail-undecrypted-*.html` (no keys) | Bottom sheet — undecrypted | `Http3DetailSheet(state = Undecrypted(...))` | Amber note block, numbered remedy steps (`.num` class → numbered list), dual action buttons |
| `http3-detail-quic-only-*.html` (QUIC only) | Bottom sheet — QUIC only | `Http3DetailSheet(state = QuicOnly(...))` | Gray pill, partial ClientHello block, "TO CONFIRM HTTP/3" section header, action buttons |

**Color mapping (from design):**
- Decoded: green `#10B981` → `Status2xx` / `InterceptorActiveGreen`
- Observed (no keys): amber `#F59E0B` → `InterceptorIdleAmber`
- QUIC only: gray `#64748B` → `Color(0xFF64748B)`

### Runtime CDP + Instrumentation Domain

| Mockup | Screen Element | Composable |
|---|---|---|
| `runtime-instrumentation-healthy-*.html` | Expanded Runtime Instrumentation card with fingerprint evidence | `RuntimeInstrumentationCard` + `StackFingerprintPanel` |
| `runtime-instrumentation-cronet-unavailable-*.html` | Degraded card, Cronet UNAVAILABLE | `RuntimeInstrumentationCard` with `adapterState = Unavailable` |
| `runtime-instrumentation-webview-active-*.html` | Active WebView capture | `RuntimeInstrumentationCard` + `WebViewCaptureControl(state = Active)` |
| `runtime-cdp-connected-*.html` | CDP card — connected, 4 tabs, storage, SW | `CdpCard(state = Connected(...))` |
| `runtime-cdp-disconnected-*.html` | CDP card — disconnected, editable host:port | `CdpCard(state = Disconnected(...))` |
| `runtime-cdp-reconnecting-*.html` | CDP card — reconnecting w/ backoff | `CdpCard(state = Reconnecting(...))` |

**Component mapping:**
- `SettingsScreen.kt` → uses `SectionHeader` (existing `CommonComponents.kt`) for RUNTIME INSTRUMENTATION / BROWSER BRIDGE section labels
- Each card is a `GlassCard` composable (existing in `CommonComponents.kt`)
- Adapter state pills use `BadgePill` pattern
- Fingerprint verdict rows are `row` + `pill` combinations
- CDP tab rows are clickable `trow`-style composables within a `LazyColumn`

### Relay Health Domain

| Mockup | Screen Element | Composable |
|---|---|---|
| `relay-health-healthy-compact-*.html` | Collapsed card on Intercept screen | `RelayHealthCard(state = Healthy, expanded = false)` |
| `relay-health-healthy-detail-*.html` | Expanded bottom sheet | `RelayHealthDetailSheet(state = Healthy)` |
| `relay-health-degraded-detail-*.html` | Expanded bottom sheet, degraded | `RelayHealthDetailSheet(state = Degraded)` |
| `relay-health-down-compact-*.html` | Collapsed card, relay down | `RelayHealthCard(state = Down, expanded = false)` |
| `relay-health-down-detail-*.html` | Expanded bottom sheet, relay down | `RelayHealthDetailSheet(state = Down)` |

**Component mapping:**
- Compact card: `GlassCard` with status pill, `track` bar, 3-tile row (TCP/UDP/ICMP), throughput row, hairline divider, flow-table line with details chevron
- Detail sheet: `ModalBottomSheet` with grab handle, relay tile (2-tile: relayed/dropped), flow-by-transport tiles, drop-reason list (each reason is a label + count + thin `track` bar), IPv4/IPv6 NAT `flat` cards, flow-table pressure card, amber/red warning note block
- Status pill colors: Healthy `#10B981`, Degraded `#F59E0B`, Down `#64748B`

### TLS Failure Domain

| Mockup | Screen Element | Composable |
|---|---|---|
| `tls-rows-mixed-*.html` | TLS tab — mixed rows with FAILED rail | Existing in `InterceptScreen.kt` lines 2005-2017 (red 3px rail, FAILED badge) |
| `tls-failed-detail-unknown-ca-*.html` | Expanded detail — unknown CA | `TlsFailureDetailSheet(remedy = InstallCA)` |
| `tls-failed-detail-handshake-failure-*.html` | Expanded detail — handshake failure | `TlsFailureDetailSheet(remedy = ExtendCiphers)` |

**Component mapping:**
- Detail sheet: `ModalBottomSheet` with headline (plain-language, e.g. "requestr's CA was rejected"), category pill (e.g. "unknown_ca"), "which side" label, 3-step handshake-stage rail (`.stage > .step > .rail .g` + `.txt`), raw alert code block, remedy note with dual action buttons (primary filled, secondary ghost)

---

## 6. Gate Strategy

### Integration Order
1. Each sub-agent works in its own branch (`feat/*`), building and testing independently.
2. Once a domain passes its tests, the branch is pushed and a PR is opened against `origin/backup`.
3. Merge conflicts are resolved per-domain — each agent modifies a distinct section of `InterceptScreen.kt` (TLS tab ~lines 1995-2080, relay card ~after capture banner, H3 row indicators ~in traffic list items, H3 detail ~in QUIC tab) and different sections of `SettingsScreen.kt`.

### Merge Order (recommended)
1. **Task 0 merge first:** `feat/extract-badgepill` → `origin/backup`. All four domains rebase onto this before opening PRs.
2. **Batch 1 merges (parallel):** Domain D (TLS) → Domain C (Relay) → Domain B (Runtime CDP) → Domain A (HTTP/3) — all independent, merge any order
3. If merge conflicts arise, prioritize in this order for least rework: C (Relay, touches TunRelay.kt + InterceptScreen) → D (TLS, touches InterceptScreen TLS tab only) → A (HTTP/3, touches InterceptScreen QUIC tab + traffic rows) → B (CDP, touches SettingsScreen only)

### Verification Per Domain
- **Compile check:** `./gradlew :app:compileDebugKotlin` must pass
- **Unit tests:** Run existing tests in affected packages (e.g. `TlsFailureClassifierTest`, `TunRelayTest`, `QuicParserTest`)
- **UI preview:** Each new `@Composable` function should have `@Preview` annotations for both dark and light themes
- **Design parity:** Compare preview output against the recommended dark mockup for each domain
- **Integration test:** Launch the app, start a VPN capture, verify: relay health card appears, TLS tab shows FAILED rows with detail sheet, traffic rows show H3 indicators, Settings screen shows instrumentation/CDP cards

### Risk Items
- `InterceptScreen.kt` is a large file (~3522 lines). Three domains (A, C, D) modify it. Use surgical diffs with clear section boundaries to minimize merge conflicts.
- `TunRelay.kt` modification (Domain C) adds counter fields to `RelayStats`. This data class is consumed by tests — ensure existing tests still compile.
- Domain B (Runtime CDP) is the largest surface area. If resource-constrained, it can be split into two sub-agents: one for Instrumentation card + fingerprint panel, one for CDP card.
