# Runtime instrumentation & Browser Bridge (CDP) — design manifest

Two Settings surfaces from the runtime-instrumentation overhaul, drawn in requestr's
existing visual language: frosted-glass `GlassCard`s over the iOS-style blurred Settings
header, uppercase letter-spaced `SettingsSectionHeader`s, `BadgePill`s, monospace
numerics, and the app's status-color vocabulary (green `#10B981` healthy/active/decrypted,
amber `#F59E0B` degraded/partial, red `#EF4444` failed/blocked, gray `#64748B`
inactive/unknown). Each card is shown in page context at 390×844 — the card under review
sits between dimmed adjacent sections (LOCAL REST API / PLUGIN MANAGER) so density and
hierarchy are reviewable.

Tokens mirror the app's decoupled themes: **Obsidian Dark** (shipped default,
`themeMode = MONOCHROME_DARK`) — bg `#0A0A0C`, surface `#141417`, primary `#FFFFFF`,
dim `#A0A0A8`; and **Editorial Light** — bg `#F4F2EC`, surface `#FFFFFF`, primary
`#F28C28`, dim `#555555`. All 12 files are self-contained (inline CSS, inline SVG, no
external assets/network). No vision-capable inspector was available in this runtime, so
files were validated structurally: each has `<title>` + `<meta description>` + viewport,
balanced `<div>`/`<svg>` counts, and zero external resource references (the only
`http(s)://` strings are realistic sample data — curl strings, capture URLs, the
extension-bridge note — none are loaded assets).

A sibling agent owns the Intercept-screen surfaces and `design-plan.json`; this manifest
covers only the `runtime-` files below.

---

## Surface 1 — Runtime Instrumentation card (expanded)

Per-adapter rows (OkHttp / Cronet / WebView) each with a real state — CAPTURING /
IDLE / UNAVAILABLE — and live event counts, driven by `RequestFinishedInfo.Listener`
(Cronet) and `shouldInterceptRequest` + MAIN-world fetch/XHR bridge (WebView). An
offscreen WebView capture control (URL input + start/stop + live status + request
count). HTTP-stack detection replaced by multi-signal confidence-scored fingerprinting
(JA3, ALPN, HTTP/2 SETTINGS, header ordering, cache-validation shape) rendered as
verdict rows with DETECTED / LIKELY / POSSIBLE / UNKNOWN + a confidence % + per-signal
✓/✗ tally + a drill-in evidence panel for one verdict.

| File | State | Theme | Recommended |
|---|---|---|---|
| `runtime-instrumentation-healthy-dark.html` | all adapters healthy, OkHttp verdict evidence expanded | dark | **yes** |
| `runtime-instrumentation-healthy-light.html` | all adapters healthy, evidence expanded | light | |
| `runtime-instrumentation-cronet-unavailable-dark.html` | Cronet unavailable w/ reason, DEGRADED | dark | |
| `runtime-instrumentation-cronet-unavailable-light.html` | Cronet unavailable w/ reason, DEGRADED | light | |
| `runtime-instrumentation-webview-active-dark.html` | active offscreen WebView capture session | dark | |
| `runtime-instrumentation-webview-active-light.html` | active offscreen WebView capture session | light | |

**Recommended: `runtime-instrumentation-healthy-dark.html`** — the all-healthy state is
the canonical layout (all three adapter states visible at once: 2 capturing + 1 idle),
and it is the only mock that expands the per-signal evidence drill-in (JA3 raw + hash,
ALPN offers, H2 SETTINGS values, HPACK header order, cache-validation shape), so the
build agent can map the entire fingerprint block in one pass. Dark is the shipped
default theme.

---

## Surface 2 — Browser Bridge (CDP) card (expanded)

Connection state incl. a reconnecting/backoff state (not just connected/disconnected/
failed); multi-target attach with per-tab title, URL, and request counts plus one tab's
activity; rule-enforcement counts (blocked / modified / redirected) separating
response-stage modifications; storage streams (cookies, localStorage live change events,
IndexedDB real record counts); and service-worker registrations.

| File | State | Theme | Recommended |
|---|---|---|---|
| `runtime-cdp-connected-dark.html` | connected, 4 tabs (1 expanded), Fetch + storage + SW | dark | **yes** |
| `runtime-cdp-connected-light.html` | connected, 4 tabs (1 expanded), Fetch + storage + SW | light | |
| `runtime-cdp-reconnecting-dark.html` | reconnecting w/ backoff, stale last-known tabs | dark | |
| `runtime-cdp-reconnecting-light.html` | reconnecting w/ backoff, stale last-known tabs | light | |
| `runtime-cdp-disconnected-dark.html` | disconnected, editable host:port, empty state | dark | |
| `runtime-cdp-disconnected-light.html` | disconnected, editable host:port, empty state | light | |

**Recommended: `runtime-cdp-connected-dark.html`** — the connected-with-multiple-tabs
state carries every expanded surface at once (multi-target rows, the expanded tab's
Fetch-paused/response-modified activity, rule-enforcement tiles, the three live storage
streams, service workers), so it is the single most complete review artifact. Dark is
the shipped default theme.
