# requestr

> Developer & security toolkit for Android — API testing, network interception, flow automation, and AI-assisted reverse engineering.

requestr is a Kotlin + Jetpack Compose application that puts a full API workspace, a six-tier capture engine, a visual flow automator, and an honest protocol diagnostics suite into one interface. It observes traffic at the packet level, classifies what it sees, and never claims more than it can prove.

---

## What requestr actually does

### Capture & Interception
Six capture tiers that auto-select based on device capability — VPN tunnelling, MITM proxy with on-the-fly CA cert issuance, Frida script injection, Shizuku elevation, and root-level iptables. The capture method watcher runs continuous health probes on every active engine. Traffic is classified by source (Native, WebView, Browser, Frida) and protocol (HTTP/1.1, HTTP/2, HTTP/3, WebSocket, SSE, QUIC).

### QUIC & HTTP/3 Passive Observation
Live QUIC datagram ingestion through the VPN tunnel with RFC 9001-compliant Initial/Handshake/1-RTT decryption for AES-128-GCM, AES-256-GCM, and ChaCha20-Poly1305. Keylog identity lookup, ServerHello cipher locking, key-phase rotation with auth-before-promotion, and QPACK stream decoding feed honest Room-persisted decode status — a connection is never claimed decoded without authenticated plaintext, and an undecryptable connection is explicitly labeled with its missing-key reason.

### API Testr
Full HTTP client with method selection, query parameters, custom headers, body editors (JSON, form-data, multipart, GraphQL), environment variables with local/dev/staging/prod presets, redirect policy control, WebSocket mode with live message console, and multi-format export (cURL, Python, JavaScript, Go, HAR, JSON). Response inspection shows status badges, timing breakdown tiles, TLS diagnostics, and a six-tab inspector (Overview, Headers, Body, Timing, TLS, Raw).

### Flow Automation
Visual node-graph canvas with Room-persisted multi-pipeline support. Nodes include API triggers, delays, conditions with true/false branching, transform steps, and intercept triggers. Export/import survives process restart. The executor walks the graph breadth-first and records a run summary.

### Reqy Assistant
Local AI chat with provider configuration, streaming responses, thinking indicators, and conversation history. Honest about its operational mode — shows provider status, error states, and clear-history confirmation.

### Tools
Built-in security utilities — JWT decoder (header/payload/signature), Base64/Hex/URL encoders, hash generators, regex tester, and a custom tool workspace with live preview.

### Music Lounge
Background lo-fi player with a LockedBigPlayerCard (vinyl spinner, waveform canvas, PLAYING badge), playlist controls, and a persistent mini-equalizer dock. Local-library scan with permission handling and empty/paused states.

---

## Design

### Theme System
Two product themes — **Obsidian Dark** (`#0A0A0C` surfaces) and **Editorial Light** (`#F4F2EC` paper) — with a decoupled 10-accent colour axis (Indigo, Cyan, Emerald, Orange, Lime, Violet, Rose, Amber, Sapphire, Obsidian). Accent selection is available in Settings, Onboarding, and the navigation drawer. Dynamic light/dark colour schemes are built per accent at runtime.

### Glassmorphism
Gradient-emulated frosted surfaces with hairline borders and directional shadow — no `RenderEffect` dependency. Four canonical glass styles (Frosted Glass, Solid M3, OLED Black, Translucent) with a global roundness slider (0–24dp across all cards, dialogs, and sheets).

### Navigation
Bottom-anchored frosted pill dock with staggered active-pill transitions, tab springs (0.75 stiffness / 350 damping), and configurable opacity/blur effects. Label enter/exit animations and hidden-item toggles per user preference.

### Design Philosophy
- **Honest states above all else.** A connection is observed, not decoded, unless the key material proves otherwise. A capture method is degraded, not healthy, when its probe fails. An empty library is an empty library, not a loading spinner.
- **Information density without clutter.** Dense rows, monospace network facts, hairline dividers, and inline detail preference over navigation-away patterns.
- **Actionable remedies.** Every failed or unavailable state offers a concrete next step — install CA, force TCP fallback, check relay MTU, grant permission.

---

## Architecture

```
requestr/
├── app/          # Compose UI, capture engines, Room persistence, VPN service
├── sdk/          # Embeddable host SDK — keylog HTTP producer, OkHttp interceptor
├── cli/          # Companion CLI for scripting against the local API server
└── browser-extension/  # CDP bridge extension
```

706 automated tests (app 634, SDK 37, CLI 4, browser-extension 31) — zero failures.

---

## Release Key

```
Keystore:  requestr-release.jks
Alias:     upload
Password:  requestr
Cert SHA-256:
  CB:08:8B:DE:A4:85:3C:D9:06:A2:CF:EF:4A:61:B4:FB
  59:96:C6:75:78:A6:BE:DE:97:18:17:32:C5:DA:6E:D8
Key type:  RSA 2048-bit
Validity:  2026-08-19 → 2054-01-04
```

---

> *"A tool is only as honest as its status line."*
>
> requestr will tell you it can't decrypt a packet, won't fabricate a decoded row from a missing key, and will never show "healthy" while a probe is failing. It's a diagnostic tool first, a pretty UI second.
