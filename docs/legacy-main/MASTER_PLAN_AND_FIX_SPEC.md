# requestr — Master Architecture, Deep Error Analysis & Systematic Fix Specification
**Package:** `com.kailashhh.requestr` | **Engine:** Kotlin · Jetpack Compose M3 · Room DB · DataStore · Android VpnService · MITM TLS Engine · Coroutines

---

## §1 — Deep Root-Cause Analysis of Core Architectural Defects & Errors

### 1.1 The VPN Port Confusion: Why a VPN Does Not Need Ports vs. Why Port 9000 Was Present

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        ANDROID NETWORK CAPTURE COMPARISON                              │
├──────────────────────────────────────────┬─────────────────────────────────────────────┤
│   MODE A: Android VpnService (L3 TUN)    │   MODE B: Local HTTP/MITM Proxy (L7 TCP)    │
├──────────────────────────────────────────┼─────────────────────────────────────────────┤
│ • Operates at Linux Kernel Network Layer │ • Operates at Application Layer (Socket)    │
│ • Creates Virtual TUN Interface: `tun0`  │ • Binds to Local TCP Socket: 127.0.0.1:8080 │
│ • Kernel routes raw IP packets directly  │ • Requires client app or OS Wi-Fi proxy     │
│   via routing table rule `0.0.0.0/0`     │   settings pointing to specific PORT        │
│ • NO LOCAL LISTENING PORT REQUIRED       │ • PORT IS MANDATORY for TCP accept() loop   │
│ • Captures raw IP/TCP/UDP packets        │ • Captures HTTP CONNECT & plaintext HTTP    │
└──────────────────────────────────────────┴─────────────────────────────────────────────┘
```

#### The Root Cause in the Codebase:
1. **Conflation of Capture Modes**: The codebase mixed up **Mode A (Kernel TUN Interface)** with **Mode B (Local Socket Proxy Daemon / iptables REDIRECT Target)**.
2. **The 9000/8080 Port Display**: In `SettingsDataStore.kt`, `SettingsScreen.kt`, and `AppNavigationDrawer.kt`, the UI was displaying `"VPN Port: 9000"` or `"Port 9000 · 127.0.0.1"` under the VPN status card.
3. **Architectural Truth**:
   - `RequestrVpnService` is a `VpnService` utilizing `Builder.addAddress("10.0.0.2", 32)` and `Builder.addRoute("0.0.0.0", 0)`. It reads raw IP packets from `ParcelFileDescriptor` (`tunFd`). **It does not listen on or need any TCP port.**
   - The port (`8080` or `9000`) is exclusively required for:
     - The **Local HTTP Forward Proxy** (`HttpProxyEngine`) when a user configures Wi-Fi proxy settings manually on an external device or emulator.
     - The **MITM TLS Decryption Proxy** (`MitmProxyEngine`) when `RootIptablesEngine` or `ShizukuTunEngine` runs `iptables -t nat -A OUTPUT -p tcp --dport 80,443 -j REDIRECT --to-port {proxyPort}`.
4. **The Fix**:
   - Decouple the UI: VPN status card explicitly displays **"Virtual TUN Interface (tun0) · Direct L3 Capture"** (No port displayed for VPN).
   - Local Proxy & Root Redirect explicitly display **"Local Proxy Daemon · 127.0.0.1:{proxyPort}"**.

---

### 1.2 API Testr Screen — The Critical Defect & Feature Inventory

Based on `api_testr_screen_rewrite.md` and codebase analysis:

1. **URL & Query Params Bidirectional Desynchronization**:
   - *Bug*: Manually typing query parameters into the URL input field (e.g. `https://api.test/v1/users?limit=20&sort=desc`) did not parse or populate the `queryParams` table. Conversely, modifying the Query Params table failed to update the URL bar in real-time.
   - *Fix*: Implement a single bidirectional synchronization engine (`syncUrlAndParams`) where changes to the URL bar reactively parse query strings into `KeyValueItem`s, and table mutations construct the normalized query string back into the URL bar.
2. **Instant Draft State Persistence on Open**:
   - *Bug*: Requests were only saved when explicitly clicking the "Save" button. Swiping the app away or navigating tabs lost in-progress edits (unsaved URL, body, or headers).
   - *Fix*: The instant `ApiTestrScreen` is mounted or a new request is opened, automatically write/update a draft record in `RequestrDatabase` via `SavedApiRequestDao.upsertDraft()`. The "Save" button explicitly commits and promotes the draft to a named permanent collection request.
3. **Adaptive WebSocket (WS) Protocol Mode**:
   - *Bug*: Selecting `WS` method left the screen in HTTP mode with a static "Send" button that threw network failures.
   - *Fix*: When `HttpMethod.WS` is selected:
     - The primary action button morphs from **"Send"** to **"Connect"** (idle) and **"Disconnect"** (connected live state).
     - Tab navigation dynamically displays the **"Messages"** tab featuring a bidirectional live frame log (incoming/outgoing text and binary frames) with a real-time message composer and send trigger.
4. **Follow Redirects Rocket Toggle & Long-Press Popover**:
   - *Bug*: No inline visual redirect indicator on the URL bar.
   - *Fix*: Add a Lucide Rocket icon next to the Send button:
     - *Single tap*: Instant toggle of `followRedirects` boolean with accent color highlight.
     - *Long press*: Opens an advanced modal sheet for redirect policy (Max redirects count: 1-20, preserve method on 307/308 vs switch to GET on 301-303, allow cross-origin redirects).
5. **Top Bar & Dynamic Icon Damping**:
   - *Bug*: Generic buttons with standard ripples and missing favorite synchronization.
   - *Fix*: Top bar with 4 Lucide outline icons (**Star, Save, More ⋯, Settings**) equipped with `Modifier.dynamicIconPress()` (0.92x spring scale damping + haptic feedback).
6. **Code Snippet & cURL Importer / Exporter**:
   - Full support for 9 languages (cURL, Python Requests, JavaScript Fetch, Node Axios, Kotlin OkHttp, Go, Rust, Dart/Flutter, Swift).

---

### 1.3 Interceptor Screen — The Critical Defect & Interception Pipeline Inventory

Based on `UNTESTED_AREAS_DEEP_ANALYSIS.md` and `MODERNIZATION_OF_INTERCEPTION.md`:

1. **The MITM Proxy Engine TLS Decryption Blind Spot**:
   - *Bug*: `MitmProxyEngine.kt` was fully implemented with BouncyCastle certificate generation, but `CaptureController` never launched it. As a result, every HTTPS connection on device only showed SNI hostnames with 0 bytes, empty headers, and empty bodies.
   - *Fix*: Integrate `MitmProxyEngine` into `CaptureController`. When CA certificate is installed, spin up `MitmProxyEngine` on `127.0.0.1:{proxyPort}` to terminate client TLS, issue per-host certificates signed by `CertificateAuthority`, and decrypt full request/response payloads.
2. **Root & Shizuku Engine Dispatch**:
   - *Bug*: `RootIptablesEngine` and `ShizukuTunEngine` were dead code; clicking start always launched `RequestrVpnService` even when Root or Shizuku was granted.
   - *Fix*: `CaptureController` dispatches dynamically based on the active `CaptureMethod` / `Tier`:
     - `ROOT_IPTABLES` → `RootIptablesEngine.start(proxyPort)` + start local `MitmProxyEngine(proxyPort)`.
     - `SHIZUKU_TUN` → `ShizukuTunEngine.start(proxyPort)` + start local `MitmProxyEngine(proxyPort)`.
     - `VPN_TUN` → `RequestrVpnService.start(context)`.
     - `HTTP_PROXY` → `HttpProxyEngine.start(proxyPort)`.
3. **App Filter Hot-Reloading**:
   - *Bug*: Package filtering was read once at startup.
   - *Fix*: Observe `SettingsDataStore.selectedPackages` and trigger dynamic filter reloads without losing ongoing session logs.
4. **Live Settings Flow Subscription in CaptureEngine**:
   - *Bug*: `CaptureEngine` read `.value` snapshots of settings rather than observing reactive updates.
   - *Fix*: Collect `SettingsDataStore` flows directly within `CaptureEngine` coroutine scope.

---

## §2 — Fragment Planning & Dependency Matrix

| Fragment | Category | Depends on | Real System Touched | Fix Order |
| :--- | :--- | :--- | :--- | :--- |
| **F-01: VPN vs Proxy Architecture & Port Separation** | CAT-1 | None | `SettingsDataStore`, `RequestrVpnService`, `CaptureController` | 1 |
| **F-02: Room Database & Draft Persistence Engine** | CAT-1 | None | `RequestrDatabase`, `SavedApiRequestDao`, `ApiRequestItem` | 2 |
| **F-03: MITM Engine & Transparent Proxy Dispatch** | CAT-1 | F-01 | `MitmProxyEngine`, `RootIptablesEngine`, `CaptureController` | 3 |
| **F-04: API Testr Bidirectional URL/Query Synchronizer** | CAT-2 | F-02 | `ApiTestrScreen.kt`, `ApiTestrRepository.kt` | 4 |
| **F-05: API Testr WebSocket (WS) Adaptive Live Engine** | CAT-2 | F-02 | `ApiTestrScreen.kt`, `WebSocketParser.kt`, OkHttp WebSocket | 5 |
| **F-06: API Testr Top Bar, Draft Auto-Save & Redirect Popover** | CAT-2 | F-02, F-04 | `ApiTestrScreen.kt`, `DynamicIconPress.kt` | 6 |
| **F-07: Interceptor Screen Multi-Tier Dispatch & Honest UI** | CAT-2 | F-01, F-03 | `InterceptScreen.kt`, `CaptureController.kt`, `CapabilityProbe.kt` | 7 |
| **F-08: Global Theme Single Source of Truth & Corner Radii** | CAT-4 | None | `Theme.kt`, `SettingsRepository.kt`, `GlassCard.kt` | 8 |
| **F-09: Pill Navigation Dock & Deep-link Routing** | CAT-3 | F-08 | `CollapsibleNavPill.kt`, `MainActivity.kt` | 9 |
| **F-10: Lo-Fi Music Mini-Player Lifecycle & Dismissal** | CAT-3 | None | `MusicScreen.kt`, `CommonComponents.kt` | 10 |

---

## §3 — Execution Plan & Verification Standard

### 3.1 Foundations & Interception Architecture (CAT-1)
- Verify `SettingsDataStore` clearly separates `proxyPort` (used for MITM/Proxy) from `vpnRoutingMode`.
- Ensure `RequestrVpnService` handles packet pump on `tun0` without referencing port 9000.
- Verify `MitmProxyEngine` is initialized and actively handles SSL sockets using root CA keypair.

### 3.2 API Testr Complete Overhaul (CAT-2)
- Bidirectional URL <-> Query params table with live string formatting.
- Auto-draft saving on screen mount into Room DB.
- Adaptive WebSocket live frame logger and sender.
- Follow redirects toggle with popover sheet.
- Outline Lucide icons with spring damping press feedback.

### 3.3 Interceptor & UI Polish (CAT-2 / CAT-4)
- Honest status indicators across Root, Shizuku, VPN, and MITM.
- 7-theme palette verification with single write path in `SettingsRepository.updateThemeSelection()`.
- Floating pill dock with direct dashboard navigation and dynamic corner radius.
- Compilation and test verification.

---
