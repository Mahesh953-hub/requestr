# V3 Master Plan — Complete Implementation

## Status: LOCKED. Building everything. No stubs.

## Phase 1 — Root iptables Capture Engine
User has root. Build ROOT_IPTABLES engine: su-based iptables rule injection, transparent redirect to proxy port, rule cleanup on stop.

## Phase 2 — Shizuku Tun Capture Engine
User has Shizuku. Build SHIZUKU_TUN engine: Shizuku API binder, elevated VPN-like tun creation without user consent dialog.

## Phase 3 — MITM Proxy with TLS Interception
Real MITM: per-host certificate generation using existing CA, dynamic KeyStore, TLS termination + re-encryption.

## Phase 4 — JWT Decoder
Inline JWT inspection: decode header/payload/signature, show claims, detect expiry, highlight algorithm.

## Phase 5 — Traffic Replay
Capture a request, modify any field, replay it, diff the responses side-by-side.

## Phase 6 — Background Capture
Foreground service with persistent notification so capture survives app kill.

## Phase 7 — ROOT_FRIDA Engine
Frida script injection via su, script management, real-time hook output in InterceptScreen.
