# Todos (platform) snapshot

Exported from the platform todo list at handoff time.

## Completed (earlier phases)
- e2465 — Compare RichResponseInspector with the live ApiTestr response viewer — completed
- 805d8 — Harvest missing response-inspection features into ApiTestrScreen — completed
- ae127 — Remove the dead response inspector and four confirmed dead or stale blocks — completed
- 60ee5 — Correct remaining actionable audit drift — completed
- 535c0 — Review the diff and report source-level verification without a build — completed
- 59ad7 — Read the interceptor architecture dossier end to end — completed
- 81128 — Map every proposed interception capability to current source and tests — completed
- 1765c — Audit app-wide tab responsiveness and current design-system constraints — completed
- d8210 — Draft architecture and UI design artifacts for missing capabilities — completed
- 6faed — Synthesize and submit the full implementation plan for approval — completed

## S0 (interceptor runtime foundation)
- 6ab46 — S0.1 Vendor the focused pinned tun2proxy fork and document the patch baseline — in_progress
- dc2d3 — S0.2 Add stable native/Kotlin JNI bridge events and configuration — pending
- 77ef4 — S0.3 Switch Room to bundled SQLite with WAL and migration compatibility — pending
- 8cc9b — S0.4 Add process-wide socket protection across all egress paths — pending
- 59608 — S0.5 Add CI foundation and downgrade-safe database backup/restore — pending
- d5c8e — Verify, review, test, and open the S0 runtime-foundation PR — pending

## S1–S8 (approved interceptor architecture slices)
- cbaea — Execute S1–S8 approved interceptor architecture slices — in_progress
  - S0 closed on branch `vorflux/interceptor-s0-runtime` (PR #18, incl. the ci.yml
    header-check wiring `d37adf8`).
  - S1 task 6 (`LocalGateway` multi-protocol listener) — done on
    `vorflux/interceptor-s1-transport`: SOCKS5 (+info) / SOCKS4(a) / HTTP CONNECT /
    absolute-form / transparent TLS-by-SNI / transparent HTTP-by-Host entries;
    `MitmProxyEngine` + `HttpProxyEngine` demoted to `GatewaySessionHandler`s; bridge
    `+info` username encoding fixed to match vendored `socks.rs`; VPN tier routes via
    SOCKS5 `Tun2ProxyConfig.forLocalGateway`. `SO_ORIGINAL_DST` is exposed as the
    `OriginalDstResolver` hook and gets its implementation in task 48.
  - S1 tasks 7–13 — done on `vorflux/interceptor-s1-transport-2` (PR pending merge):
    - 7 bounded relay pool + `ConnectionStateMachine` + buffer pool + relay metrics
      (`interception/relay/*`, flag `relayPoolBounded`).
    - 8 `CertificateEngine` rewrite (`LeafProfile`, `ClientCertStore`, upstream trust policy,
      `CertificateAuthority.getOrCreate` Mutex race fix).
    - 9 `quic/UdpPolicyRouter` + SOCKS5 UDP ASSOCIATE (flag `quicTeePolicy`, G25 unit-tested;
      physical-device QUIC runs still owed).
    - 10 `uid/UidAttributor` ladder (+ tracked-session API for the tee).
    - 11 VPN lifecycle hardening (`VpnNetworkHandoff`, `VpnDnsPlan`, service hardening,
      `FOREGROUND_SERVICE_DATA_SYNC` removed).
    - 12 `packet/PcapNgWriter` + `PacketTeeRecorder` (flag `pcapng`, `pcap_format` setting).
      Finding: the vendored tee always reports `session_id == 0`; flow identity is recovered
      from the IP/transport header and matched to the attributor's client tuple.
    - 13 `tls/TrustDiagnostics` + `MutualTlsSignals`, `PinningFailureRegistry.snapshot()`,
      watcher DEGRADED reason, CA-sheet findings card.
    - Not verifiable in this environment (Redroid has no tun): G9/G10/G14, network handoff,
      tee/PCAPng on device, real trust-store scan. Unit suites cover the logic.
