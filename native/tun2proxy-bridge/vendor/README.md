# Vendored tun2proxy fork

## Upstream provenance

| Field | Value |
|---|---|
| Crate | `tun2proxy` |
| Version | `0.8.3` |
| Registry | crates.io |
| Repository | <https://github.com/tun2proxy/tun2proxy> |
| Crate checksum (SHA-256) | `0c0c4cb74f5f7df1bdbe11ee8a8ec0202603e1caa5db51f0f757bc92c8886799` |
| License | MIT |

Source extracted from the crates.io `tun2proxy-0.8.3.crate` archive. Binary
targets removed; crate-type narrowed to `["lib"]`; `publish = false`.

## Six focused patches

| # | Patch | Files |
|---|---|---|
| 1 | **requestr_hooks module**: trait-object hooks for session events (`SessionEventSink`: `Start`/`End`/`DnsVirtual`/`UdpPolicyApplied`), UDP policy (`UdpPolicyHook`: `Allow`/`TeeAndDrop`/`TeeAndPass`), packet tee (`PacketTeeSink`), socket protection (`SocketProtectHook` with `protect_required` mode — missing hook fails closed when required). Global `Mutex<Option<Arc<dyn Trait>>>` slots, `clear_all_hooks`. 11 tests. | `src/requestr_hooks.rs` (new) |
| 2 | **TeeDevice at TUN boundary**: `AsyncRead`/`AsyncWrite` wrapper inserted between `tun::create_as_async` and `IpStack::new`. Every raw IP frame read from TUN is teed outbound (`is_tx=true`); every write to TUN is teed inbound. `poll_write` tees only `buf[..n]` after inner write succeeds (no phantom/partial frames). | `src/tee_device.rs` (new), `src/general_api.rs` |
| 3 | **Session lifecycle events with accurate tx/rx**: TCP `SessionStart` before proxy handler, `SessionEnd` with real byte counts from `copy_and_record_traffic` return values. UDP sessions emit `Start`/`End`. Virtual-DNS emits `DnsVirtual`. | `src/lib.rs` |
| 4 | **UDP policy after DNS/53**: DNS/53 handling (virtual/over-tcp/direct) runs before policy — virtual-DNS always functions regardless of capture policy. `TeeAndDrop` emits `UdpPolicyApplied` event (raw frame already teed by TeeDevice, no duplicate payload tee). `TeeAndPass` emits `UdpPolicyApplied` and forwards normally. | `src/lib.rs` |
| 5 | **Socket protection before every egress connect/bind**: `create_tcp_stream` and `create_udp_stream` call `protect_socket(fd)` on Unix before `connect()`. Both queue and non-queue paths covered. Hook returns false → `PermissionDenied`. Poisoned mutex → fail closed. `protect_required` mode → missing hook fails closed. | `src/lib.rs` |
| 6 | **SessionInfo::id() accessor**: public getter for monotonic session id. | `src/session_info.rs` |

## SOCKS5 +info credential wiring

`config.rs::build_args` encodes `socks5_info` as `+info:{json}` in the SOCKS5
`UserKey` username. Activates when config specifies SOCKS5 proxy type (S1's
`LocalGateway`). Current production uses HTTP CONNECT (`forMitmProxyEngine`).

## Keeping the diff minimal

Only library source is vendored. When rebasing to a future upstream, compare
`vendor/tun2proxy/src/` and re-apply the six patches.
