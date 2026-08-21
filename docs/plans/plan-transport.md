### Summary

Four critical transport/packet-core items in `Mahesh953-hub/requestr` on branch `backup` (HEAD `0db606c`, versionCode 26): the VPN_TUN tier reads every packet and drops it (full connectivity outage while capture is active), `TlsHandshakeState.FAILED` is a dead enum value nothing ever sets, `QpackDecoder.decode()` has no production call site so HTTP/3 semantics are invisible, and the Room schema still ships `.fallbackToDestructiveMigration(dropAllTables = true)` with no `connectionId` column so connection lineage is a 30-second timestamp guess.

Twelve tasks. Every claim is grounded in a file/line actually read. No task may land a stub, a no-op, a TODO comment, or a "documented limitation" in place of working behaviour.

Baseline to preserve: `./gradlew :app:assembleDebug --no-configuration-cache` is BUILD SUCCESSFUL at HEAD.

Decisions applied from the user: ICMP is really relayed (not labelled); QUIC 1-RTT keys come from a key-log registry this scope owns and the SDK scope produces into; IPv6 is implemented with the relay generic over address family; design mockups are the visual spec for task 14; this scope owns Room version 9 and the single migration, absorbing five app-scope tables; this scope lands before the app scope.

---

### Tasks

1. **[parallel] Real TCP three-way-handshake tracking in `TcpReassembler`**

   Files: `app/src/main/java/com/example/interception/tcp/TcpReassembler.kt` (174 lines), `app/src/main/java/com/example/interception/tcp/TcpStream.kt` (123 lines).

   Why: `feedTcp` lines 98–102 contain a SYN-ACK branch whose body is a comment only —
   ```
   if ((flags and FLAG_SYN) != 0) {
       if (!stream.handshakeComplete && (flags and FLAG_ACK) != 0) {
           // SYN-ACK observed → handshake in progress
       }
   }
   ```
   and `handshakeComplete` is then inferred from `(flags and FLAG_ACK) != 0 && stream.clientToServer.size() > 0`, i.e. from *data* arriving rather than from the handshake. Task 8 needs to distinguish "TLS handshake failed" from "never started", which requires the real handshake phase. Task 4's relay needs it to know when to synthesize a SYN-ACK.

   Changes:
   - Add to `TcpStream`: `enum class TcpHandshakePhase { NONE, SYN_SENT, SYN_ACK_SEEN, ESTABLISHED, CLOSING, CLOSED, RESET }`, plus `var handshakePhase`, `var synSeenAt: Long?`, `var establishedAtHandshake: Long?`, `var resetByPeer: Boolean`.
   - In `feedTcp`: SYN without ACK → `SYN_SENT`; SYN+ACK → `SYN_ACK_SEEN`; bare ACK while `SYN_ACK_SEEN` → `ESTABLISHED` + record timestamp; FIN → `CLOSING` then `CLOSED`; RST → `RESET` and `resetByPeer = true`. Keep `handshakeComplete` as a derived property so existing callers keep compiling — `CaptureEngine.persistTcpConnection` maps it to `"ESTABLISHED"`/`"SYN_SENT"`.
   - **Bug fix — dead prune predicate.** `prune()`'s second statement is `streams.entries.removeAll { (_, s) -> System.currentTimeMillis() - s.establishedAt > cutoff }` where `cutoff = System.currentTimeMillis() - idleTimeoutMs`. That compares a *duration* (thousands of ms) against an *absolute epoch timestamp* (~1.7e12), so the predicate is never true and the statement is dead code — idle-but-unclosed streams are never pruned by it. Only the first statement (closed streams past the timeout) does anything. Correct it to compare `lastActivityAt < cutoff`.
   - **Bug fix — `establishedAt` is a last-activity stamp.** `feedTcp` does `stream.establishedAt = System.currentTimeMillis()` on every accepted write, yet `persistTcpConnection` writes it into `TcpConnectionEntity.establishedAt`. Split into `establishedAt` (set once, at flow creation or real establish) and `lastActivityAt` (bumped per write); point `evictOldest`/`prune` at `lastActivityAt` and keep `persistTcpConnection` writing the real establish time.

   Tests: new `app/src/test/java/com/example/interception/tcp/TcpReassemblerTest.kt` — synthetic IPv4+TCP byte arrays (hand-rolled helper, same style as `CaptureEngineParserTests.kt`) driving SYN → SYN-ACK → ACK → data → FIN, asserting the phase at each step; a RST case asserting `RESET` + `resetByPeer`; a retransmission case asserting `retransmissions` increments and `write` returns -1; a prune case asserting a fresh stream survives and a stale one is dropped (this test fails against the current dead predicate, which is the point).

2. **[parallel] Address-family abstraction + IP/TCP/UDP/ICMP packet builder**

   Files: new `app/src/main/java/com/example/interception/tun/IpAddressCodec.kt`, new `app/src/main/java/com/example/interception/tun/IpPacketBuilder.kt`.

   Why: writing a reply into the tun fd requires synthesizing a complete IP datagram, and nothing in the repo can. A grep for `checksum`/`buildIpv4`/`ByteBuffer.allocate` across `app/src/main/java` returns only `ToolsRepository`'s SHA-1 tool entry, a UI placeholder string, and `PacketPump.kt:96`'s read buffer. `PacketCaptureEngine` writes PCAP record headers, not IP packets.

   Also, addresses are not in a parseable form today. `PacketPump.parseIpv6` (line 299) and `TcpReassembler.feedIpv6` both build the address as `packet.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }` — a bare 32-hex-char string that `InetAddress.getByName(...)` rejects. IPv4 uses dotted-quad from bytes 12–19. The relay needs both directions of both formats.

   Changes:
   - `IpAddressCodec`: `formatIpv4(bytes, offset)`, `formatIpv6(bytes, offset)` (must reproduce the **existing** 32-hex-char form byte-for-byte so flow keys and the `TcpConnectionEntity.connectionId` values already in users' databases stay stable), `parseIpv4(String): ByteArray`, `parseIpv6(String): ByteArray` (accepts the 32-hex form), and `toInetAddress(String, family): InetAddress` via `InetAddress.getByAddress(ByteArray)` — which takes raw bytes and so sidesteps the unparseable-string problem entirely.
   - `IpPacketBuilder`: `internetChecksum(buf, offset, length)` (RFC 1071 one's-complement); `buildIpv4Tcp(...)`, `buildIpv4Udp(...)`, `buildIpv4Icmp(...)`, `buildIpv6Tcp(...)`, `buildIpv6Udp(...)`, `buildIpv6Icmp(...)`. IPv4 emits the 20-byte header (version/IHL, total length, TTL 64, protocol 6/17/1, header checksum). IPv6 emits the 40-byte fixed header (no header checksum by design — a genuine format difference, not an omission) with next-header 6/17/58. TCP and UDP checksums use the respective pseudo-headers, which differ between v4 and v6 — implement both. ICMPv4 checksum covers the ICMP message; ICMPv6 checksum covers a pseudo-header too.
   - Cap emitted datagrams at `RequestrVpnService.MTU = 1500`; callers segment above that.

   Tests: new `app/src/test/java/com/example/interception/tun/IpPacketBuilderTest.kt` — checksum against a hand-computed IPv4 header; **round-trip every builder output back through `TcpReassembler.feed(...)`** and assert the reassembler recovers the same 5-tuple, flags and payload (the project's own parser is the strongest available oracle); assert a built header's recomputed checksum is 0; assert IPv6 has no header checksum field but a correct TCP pseudo-header checksum; assert UDP length fields. New `IpAddressCodecTest.kt` — assert `formatIpv6` output is byte-identical to the existing `copyOfRange(8,24).joinToString{"%02x"}` expression for a known address (guards flow-key stability), and assert parse/format round-trips for both families.

3. **[parallel] IP fragment reassembly**

   Files: new `app/src/main/java/com/example/interception/tun/IpFragmentReassembler.kt`.

   Why: neither parser handles fragmentation. `parseIpv4` only requires `length >= ihl + 4` and reads ports at `ihl`/`ihl+2`, so a non-first IPv4 fragment yields garbage ports. `parseIpv6` reads `nextHeader` at byte 6 and then assumes the transport header starts at offset 40, so any extension header (hop-by-hop 0, routing 43, fragment 44, destination-options 60) breaks it. A non-first fragment carries no transport header at all, so its 5-tuple is unknowable until reassembled — this is the one IPv6 sub-case called out in the summary, handled rather than dropped, because dropping fragments would recreate the blackhole in miniature.

   Changes:
   - `walkIpv6ExtensionHeaders(packet, length): Pair<Int, Int>?` returning (transport offset, final next-header), following the chain and stopping safely on unknown types.
   - `offer(packet, length, version): ByteArray?` — returns the packet unchanged when unfragmented, buffers when fragmented, and returns the reassembled datagram once complete. Keyed by (src, dst, identification/fragment-id, protocol). Bounded and TTL-pruned with the same shape as `PacketPump.activeFlows` (`MAX_TRACKED_FLOWS = 512`, `FLOW_TTL_MS = 30_000L`) so a fragment flood cannot exhaust memory. Incomplete groups past TTL are dropped and counted, never silently forgotten.

   Tests: new `app/src/test/java/com/example/interception/tun/IpFragmentReassemblerTest.kt` — split a known IPv4 datagram into two fragments, offer out of order, assert the reassembled bytes equal the original; same for an IPv6 fragment-header pair; assert an unfragmented packet passes through untouched and identity-equal; assert an incomplete group is dropped after TTL and counted; assert extension-header walking finds the right transport offset for hop-by-hop and destination-options chains.

4. **[after 1, 2, 3] Userspace TCP+UDP relay — fixes the VPN_TUN blackhole**

   Files: new `app/src/main/java/com/example/interception/tun/TunRelay.kt`, new `app/src/main/java/com/example/interception/tun/RelayFlow.kt`, new `app/src/main/java/com/example/interception/tun/TunWriter.kt`; edit `app/src/main/java/com/example/interception/PacketPump.kt` (370 lines), `app/src/main/java/com/example/interception/RequestrVpnService.kt` (401 lines).

   Why (the core finding): `PacketPump`'s constructor takes `private val output: FileOutputStream` at line 42 and `output` occurs nowhere else in the file except the line-79 doc comment about `PacketCaptureEngine`. With `.addRoute("0.0.0.0", 0)` in effect, every routed IPv4 packet is read and dropped. `AUDIT_REPORT.md`'s v5.8-backup addendum records this as a full outage, broader than the older "no TLS MITM" gap. `VpnService.protect(...)` appears nowhere in `RequestrVpnService.kt`, so relay sockets would loop back into the tun.

   Changes:
   - `TunWriter`: single owner of writes to the tun `FileOutputStream`. The fd is shared with the read side, so every write must be serialized through one lock/queue.
   - `RelayFlow`: per-flow state — 5-tuple, address family, per-direction seq/ack counters, upstream channel, `lastActivityAt`, bounded pending-write queue. TCP and UDP variants. Generic over address family; the family is a field, not a subclass, so there is one flow table and one selector loop for both.
   - `TunRelay`: flow table bounded and LRU-evicted mirroring `TcpReassembler.maxStreams = 512` / `idleTimeoutMs = 120_000L`; a `java.nio.channels.Selector` loop on `Dispatchers.IO`.
     - TCP: on SYN for an unknown flow, open a `SocketChannel` to the real destination, protect it, synthesize a SYN-ACK back into tun; forward client payload; wrap channel reads into synthesized data segments with correct seq/ack; translate FIN/RST both ways.
     - UDP: on first datagram per 5-tuple, open a `DatagramChannel`, protect it, forward, wrap replies. **This is what restores DNS.**
     - Every packet passes through `IpFragmentReassembler.offer(...)` first, so fragmented flows work.
     - Counters: `relayedFlows`, `relayBytesUp`, `relayBytesDown`, `relayErrors`, `unrelayableCount`, per-family flow counts. Expose a Compose-observable `MutableStateFlow`/`StateFlow` pair following the `GatewayEngine._isRunning`/`isRunningFlow` pattern (`interception/gateway/GatewayEngine.kt`, 304 lines) — **not** a plain `@Volatile var`, which is the exact mistake recorded for `LocalApiServer.isRunning` in `/memory/testing/requestr/known-issues.md`.
   - `PacketPump`: after `parseAndTrackFlow` (so capture/PCAP behaviour stays byte-identical), hand the same packet to `relay.offer(packet, read)`. Construct the relay from the existing `output` parameter — it finally has a use. Expose the relay so the service can stop it and the UI can observe it. Do not change `MAX_PACKET = 32767`, `IDLE_BACKOFF_MS = 10L`, `FLUSH_INTERVAL_MS = 1000L`, `FLOW_TTL_MS = 30_000L`, `MAX_TRACKED_FLOWS = 512`, or `UDP_PERSIST_INTERVAL_MS = 2_000L`.
   - `RequestrVpnService`: inject a protect hook into `PacketPump`. **Mechanism detail:** `VpnService.protect` has overloads for `Socket`, `DatagramSocket` and raw `int` fd. `SocketChannel.socket()` / `DatagramChannel.socket()` give the first two, so those are direct. For the ICMP socket in task 6, which comes from `Os.socket(...)` as a `FileDescriptor`, use `ParcelFileDescriptor.dup(fd).getFd()` to obtain the `int` and call `protect(int)` — both are public API. Protect must happen **before** connect/send or traffic loops. Stop the relay in `cleanup()` beside `PacketCaptureEngine.stop()`. Update the foreground notification text (currently `"VPN active — HTTPS bodies not captured without proxy mode"`) to state that traffic is relayed.

   Tests: new `app/src/test/java/com/example/interception/tun/TunRelayTest.kt` (JVM; Robolectric 4.16.1 already on the test classpath). Drive the relay with a loopback `ServerSocket` as the "real destination", a `ByteArrayOutputStream`-backed fake tun sink, and a no-op protect hook. Assert: a synthetic SYN produces a SYN-ACK whose fields parse back via `TcpReassembler`; a payload segment arrives at the loopback server; the reply is emitted as a valid datagram with expected seq/ack; UDP round-trips against a local `DatagramSocket`; the same four assertions pass for IPv6 flows over loopback `::1`; flow-table eviction respects the bound; a fragmented inbound datagram is reassembled then relayed.

5. **[parallel] `TlsKeyLogRegistry` — the key-material ingest interface (SDK scope unblocker)**

   Files: new `app/src/main/java/com/example/interception/tls/TlsKeyLogRegistry.kt`.

   Why: this is the interface the SDK scope produces into, so it is deliberately sequenced early and has no dependencies. Task 11 consumes it.

   **FROZEN CONTRACT — do not change without telling the SDK scope.** The user has frozen this interface so the SDK scope can bind to a fixed signature. The exact declarations below are the contract; SDK-side work may assume them verbatim. Any later change to a signature here requires notifying that scope.

   ```kotlin
   package com.example.interception.tls

   enum class KeyLogLabel {
       CLIENT_HANDSHAKE_TRAFFIC_SECRET,
       SERVER_HANDSHAKE_TRAFFIC_SECRET,
       CLIENT_TRAFFIC_SECRET_0,
       SERVER_TRAFFIC_SECRET_0,
       CLIENT_EARLY_TRAFFIC_SECRET,
   }

   object TlsKeyLogRegistry {
       fun record(label: KeyLogLabel, clientRandom: ByteArray, secret: ByteArray)
       fun lookup(label: KeyLogLabel, clientRandom: ByteArray): ByteArray?
       fun importNssKeyLog(lines: Sequence<String>): Int
       fun clear()
       val entryCount: kotlinx.coroutines.flow.StateFlow<Int>
   }
   ```

   Contract semantics the SDK scope may rely on:
   - `record` is **idempotent** for an identical `(label, clientRandom)` pair and overwrites on a differing secret (last writer wins). It is **safe to call from any thread**.
   - `clientRandom` is the raw **32 bytes** from the ClientHello, not hex. `secret` is raw bytes. Callers pass copies; the registry does not retain caller-mutable references.
   - `record` silently ignores a `clientRandom` of wrong length rather than throwing, so a producer bug can never crash a capture.
   - `lookup` returns `null` on miss — a miss is a normal, expected state (the "no key material" case task 14 surfaces), never an error.
   - `importNssKeyLog` accepts the standard `LABEL <64-hex client_random> <hex secret>` line format, returns the count accepted, and ignores malformed lines without throwing.

   Changes: implement the above as `object TlsKeyLogRegistry`, shaped like the NSS keylog format so all three producers (MITM path, SDK plane, `SSLKEYLOGFILE` import) speak one vocabulary.
   - The `KeyLogLabel` set covers what QUIC handshake and 1-RTT key derivation need (task 13).
   - Keyed on client random, which is the NSS convention and is visible in the ClientHello that `TlsDecoder.parseClientHello` already parses.
   - Bounded and TTL-pruned like `PacketPump.activeFlows`. **Secrets never touch disk** — in-memory only, cleared on capture stop. Wire `clear()` into the VPN service's `cleanup()`.
   - `entryCount` follows the `GatewayEngine` observable pattern so task 14's UI can show whether any key material is present.

   Tests: new `app/src/test/java/com/example/interception/tls/TlsKeyLogRegistryTest.kt` — record/lookup round-trip; lookup miss returns null; bound enforcement evicts oldest; TTL pruning drops stale entries; `importNssKeyLog` accepts a well-formed multi-line sample and counts it, rejects malformed lines without throwing, and is case-insensitive on hex; `clear()` empties the registry and `entryCount` emits 0.

6. **[after 2, 4] ICMP echo relay**

   Files: new `app/src/main/java/com/example/interception/tun/IcmpRelay.kt`; edit `app/src/main/java/com/example/interception/tun/TunRelay.kt`.

   Why: `.addRoute("0.0.0.0", 0)` captures ICMP too, so ping is currently part of the blackhole. Per the decision, ICMP gets a real echo path, isolated in its own class so it cannot destabilize TCP/UDP.

   Changes:
   - **Primary path:** `Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` for IPv4 and `Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)` for IPv6 — the unprivileged ping-socket path, gated by the kernel's `ping_group_range`, needing no `CAP_NET_RAW`. `OsConstants.IPPROTO_ICMP`/`IPPROTO_ICMPV6` are API 21+, under this project's `minSdk 24`. Protect the fd via `ParcelFileDescriptor.dup(fd).getFd()` → `protect(int)` before any send. Forward the guest's echo request, read the real reply, and synthesize an echo-reply datagram back into tun via `IpPacketBuilder.buildIpv4Icmp`/`buildIpv6Icmp`. Note the ping socket rewrites the ICMP identifier field, so the relay must map kernel-assigned id ↔ guest id per outstanding request.
   - **Fallback path — mechanism correction.** The decision named `InetAddress.isReachable()` on a protected socket. That exact combination is not achievable: `isReachable` creates its probe socket internally in the platform, so no descriptor exists for us to hand to `protect()`, and an unprotected probe would route back into our own tun and be re-handled by our own relay — a loop, not a test. Android documents `isReachable` as ICMP echo first, then a TCP connect to port 7. So implement that same probe on a `SocketChannel` we own and protect: attempt a connect to the destination (port 7, then a short list of commonly-open ports), and on success synthesize a genuine echo reply. Same fidelity as `isReachable`, and actually protectable. This preserves the decision's intent — a working reachability determination producing a real echo reply — with a mechanism that functions.
   - Only when both paths fail is the packet counted as unrelayable (`unrelayableCount`, surfaced in task 14). That is a real runtime state, not a documented limitation. Log the concrete `ErrnoException` so a `ping_group_range` refusal is diagnosable rather than generic.
   - Isolation: `IcmpRelay` owns its own sockets, its own bounded outstanding-request table, and its own failure counter. `TunRelay` calls it for protocol 1 / next-header 58 and never lets an ICMP failure propagate into the TCP/UDP selector loop.

   Tests: new `app/src/test/java/com/example/interception/tun/IcmpRelayTest.kt` — the primary path cannot be exercised in a JVM unit test (no ping socket in the test JVM), so test the pure logic: id↔id mapping round-trip and eviction; echo-reply synthesis asserted by parsing the built datagram back and checking type 0 / code 0 / matching identifier+sequence and a valid checksum; a checksum-mismatch input rejected; the fallback probe against a local `ServerSocket` producing a synthesized reply; both-paths-fail incrementing `unrelayableCount` exactly once per packet. Device verification is task 14's manual pass.

7. **[parallel] TLS alert-record parsing**

   Files: `app/src/main/java/com/example/interception/tls/TlsDecoder.kt` (293 lines), `app/src/main/java/com/example/interception/tls/TlsSession.kt` (82 lines).

   Why: `TlsRecord.contentTypeName` already maps `21 -> "alert"`, but `TlsDecoder`'s entire public surface is `looksLikeTls` (line 27), `extractSni` (57), `versionName` (132), `parseClientHello` (148). No alert parsing exists anywhere — which is exactly why nothing can ever set `FAILED`.

   Changes:
   - Add `data class TlsAlert(val level: Int, val description: Int, val levelName: String, val descriptionName: String, val isFatal: Boolean)` to `TlsSession.kt`, beside the existing `TlsRecord`.
   - Add `TlsDecoder.parseAlert(bytes, offset = 0): TlsAlert?` — require content type 21 and a legal record version (reuse the `0x0301..0x0304` range `looksLikeTls` already uses), then read the 2-byte body. Map RFC 8446 §6 description codes to names (`close_notify` 0, `unexpected_message` 10, `bad_record_mac` 20, `handshake_failure` 40, `bad_certificate` 42, `certificate_expired` 45, `unknown_ca` 48, `protocol_version` 70, `inappropriate_fallback` 86, `no_application_protocol` 120, …) with an `unknown(N)` fallback so an unmapped code is still reported honestly.
   - Add `TlsDecoder.findAlertInRecords(bytes): TlsAlert?` walking record boundaries via the existing `readU16` helper, so an alert following a handshake record in the same buffer is found.
   - Record the real constraint in behaviour, not prose: TLS 1.3 encrypts alerts once handshake keys are up, so only pre-key alerts are readable on the undecoded path. The code must not claim otherwise — and task 8's MITM and RST signals cover the post-key cases, so there is no residual gap.

   Tests: new `app/src/test/java/com/example/interception/tls/TlsDecoderAlertTest.kt` — `15 03 03 00 02 02 28` asserts fatal `handshake_failure`; `15 03 03 00 02 01 00` asserts warning `close_notify` with `isFatal == false`; an unmapped code asserts `unknown(N)`; a handshake record (type 22) asserts null; a two-record buffer asserts `findAlertInRecords` locates the second.

8. **[parallel] Fix mutually-exclusive VpnService app-filter setters**

   Files: `app/src/main/java/com/example/interception/RequestrVpnService.kt`.

   Why: `startCapture()` calls `builder.addDisallowedApplication(packageName)` unconditionally and then, when `filterMode == AppFilterMode.SELECTED_APPS && selected.isNotEmpty()`, also calls `builder.addAllowedApplication(pkg)` per package. `VpnService.Builder` permits an allowed set *or* a disallowed set, never both, and throws `UnsupportedOperationException` when mixed — so selected-apps mode cannot establish a tun at all. The broad `catch (t: Throwable)` funnels it into the generic `fail("Capture failed to start: ...")`, which is why it presents as a vague failure rather than a specific defect.

   Changes: choose exactly one mode. When apps are selected, use only `addAllowedApplication` per package, skipping `packageName` — that achieves self-exclusion without the disallow call. Otherwise use only `addDisallowedApplication(packageName)`. Keep the existing per-package `runCatching { }.onFailure { Log.w(TAG, "Cannot filter missing package $pkg") }` so an uninstalled package degrades gracefully. If the allowlist ends up empty after filtering, fall back to blocklist mode rather than establishing a tun that routes nothing.

   Tests: a tun cannot be established in a JVM unit test, so extract the decision into a pure `internal fun resolveFilterPlan(mode: AppFilterMode, selected: Set<String>, self: String): FilterPlan` and unit-test it in `app/src/test/java/com/example/interception/VpnFilterPlanTest.kt` across four cases: all-apps → blocklist containing self; selected-with-others → allowlist excluding self; selected-containing-only-self → blocklist fallback; selected-empty → blocklist fallback.

9. **[after 4] Add IPv6 route to the tun builder and relay IPv6 flows**

   Files: `app/src/main/java/com/example/interception/RequestrVpnService.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/tun/TunRelay.kt`.

   Why: IPv6 is effectively dark today. The builder adds only `.addAddress(TUN_ADDRESS, TUN_PREFIX)` (`TUN_ADDRESS = "10.111.222.1"`, `TUN_PREFIX = 32`) and `.addRoute("0.0.0.0", 0)` — no IPv6 address, no IPv6 route. And `PacketPump.parseIpv6` (lines 296–309) only calls `trackFlow`: no `captureEngine.ingest` reachable through a v6-specific path, no DNS handling, no QUIC handling, no UDP flow row. Leaving it that way means IPv6-capable networks silently lose traffic — the blackhole bug in a smaller costume.

   Changes:
   - Builder: add an IPv6 address on a documentation/ULA prefix and `.addRoute("::", 0)` so v6 traffic actually enters the tun. Guard with `runCatching` so a device or ROM without v6 support degrades to v4-only rather than failing `establish()` outright — and count that case rather than hiding it.
   - `parseIpv6`: bring it to parity with `parseIpv4` — walk extension headers via `IpFragmentReassembler.walkIpv6ExtensionHeaders` (task 3) to find the true transport offset instead of assuming 40, then run the same DNS-on-53, QUIC-on-443, and `persistUdpFlow` logic `parseIpv4` runs. Note `parseAndTrackFlow` already calls `captureEngine.ingest(packet, length, version)` for both families and `TcpReassembler.feedIpv6` already handles v6 TCP, so TCP capture needs no change — only the UDP/DNS/QUIC side is missing.
   - `persistUdpFlow`'s `flowId` is `"17|$src:$sPort>$dst:$dPort"`; with a 32-hex v6 address that stays unambiguous, so the key scheme needs no change. Confirm `UdpFlowEntity.srcIp`/`destIp` (plain `String`) accommodate it — they do.
   - `TunRelay`: relay v6 TCP/UDP through the same flow table and selector, with the family as a `RelayFlow` field (task 4 already built it generic). Use `IpAddressCodec.toInetAddress` + `InetAddress.getByAddress(ByteArray)` so the 32-hex address form is usable.
   - Fragmented v6 datagrams route through `IpFragmentReassembler` (task 3) rather than being dropped.

   Tests: extend `TunRelayTest` with the IPv6 TCP and UDP round-trips over loopback `::1` (already listed in task 4's assertions); new `app/src/test/java/com/example/interception/PacketPumpIpv6Test.kt` — feed a synthetic IPv6+UDP:53 datagram and assert `dnsCallback` fires with the v6 source address in the existing 32-hex form; feed an IPv6+UDP:443 datagram carrying a known QUIC Initial (reuse `QuicCryptoTest`'s RFC 9001 A.2 packet bytes) and assert a QUIC connection is tracked; feed a datagram with a hop-by-hop extension header and assert ports are read from the correct offset rather than byte 40.

10. **[after 7] Room schema v9 — real migration, `connectionId`, TLS failure columns, app-scope tables**

    Files: `app/src/main/java/com/example/data/local/Entities.kt`, `app/src/main/java/com/example/data/local/RequestrDatabase.kt` (75 lines), `app/src/main/java/com/example/data/local/Daos.kt`, `app/build.gradle.kts`.

    Why: `RequestrDatabase` is `@Database(version = 8, exportSchema = false)` and its builder uses `.fallbackToDestructiveMigration(dropAllTables = true)` (line 70) under a comment reading "Schema is young; destructive migration is acceptable pre-1.0 ... Replace with real migrations post-release." `CapturedTrafficEntity` (Entities.kt lines 17–47) has no `connectionId`, which is why `CapturedTrafficDao.getByHostInWindow` exists and why `ConnectionLineage.httpSteps()` (line 234) matches HTTP to connections through `HTTP_WINDOW_MS = 30_000L`. `TlsSessionEntity` (lines 277–292) has no failure-reason column. There is no `app/schemas` directory.

    **This scope owns the version number and the single migration.** The app-level plan (`/code/.plans/plan-app.md`) explicitly defers: "the sibling schema plan owns the final version number and migration code."

    Changes:
    - `CapturedTrafficEntity`: add `val connectionId: String? = null`; add `Index("connectionId")` to the existing `indices = [Index("sessionId"), Index("host"), Index("timestamp")]`.
    - `TlsSessionEntity`: add `val failureReason: String? = null` and `val failedAt: Long? = null`. Update the `handshakeState` comment (currently "CLIENT_HELLO, ESTABLISHED, FAILED (TlsHandshakeState.name)") so it no longer implies FAILED is unreachable.
    - `RequestrDatabase`: bump to `version = 9`, set `exportSchema = true`, **remove `.fallbackToDestructiveMigration(dropAllTables = true)` entirely**, and add `.addMigrations(MIGRATION_8_9)`. The real `Migration(8, 9)` fully replaces the destructive fallback — **upgrading from v8 preserves all user data, where today every table would be wiped.** Structure the migration body in two clearly delimited sections:

      ```
      // ── Section A: transport/packet-core columns (this scope) ──
      //   ALTER TABLE captured_traffic ADD COLUMN connectionId TEXT
      //   ALTER TABLE tls_sessions     ADD COLUMN failureReason TEXT
      //   ALTER TABLE tls_sessions     ADD COLUMN failedAt INTEGER
      //   CREATE INDEX IF NOT EXISTS index_captured_traffic_connectionId
      //       ON captured_traffic(connectionId)

      // ── Section B: app-scope tables (columns owned by /code/.plans/plan-app.md H1) ──
      //   CREATE TABLE IF NOT EXISTS notes (
      //     id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL,
      //     format TEXT NOT NULL, folderId TEXT, folderName TEXT NOT NULL,
      //     folderColorHex INTEGER NOT NULL, isPinned INTEGER NOT NULL,
      //     createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)
      //   CREATE INDEX IF NOT EXISTS index_notes_isPinned  ON notes(isPinned)
      //   CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)
      //
      //   CREATE TABLE IF NOT EXISTS note_folders (
      //     id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
      //     colorHex INTEGER NOT NULL, emoji TEXT NOT NULL)
      //
      //   CREATE TABLE IF NOT EXISTS custom_tools (
      //     id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, operation TEXT NOT NULL,
      //     pattern TEXT NOT NULL, replacement TEXT NOT NULL, createdAt INTEGER NOT NULL)
      //
      //   CREATE TABLE IF NOT EXISTS chat_messages (
      //     id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL,
      //     content TEXT NOT NULL, timestamp INTEGER NOT NULL)
      //   CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)
      //
      //   CREATE TABLE IF NOT EXISTS chat_providers (
      //     id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, baseUrl TEXT NOT NULL,
      //     model TEXT NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)
      ```

      Section B's columns are taken from `/code/.plans/plan-app.md` **H1** ("Schema request — 5 new tables"), which the app scope has finalized. `chat_providers` deliberately has **no API-key column** — that scope's H2 keeps the key out of Room and DataStore entirely; do not add one.

      **Nullability and defaults above are inferred from H1's column list and must be cross-checked against the app scope's actual `@Entity` declarations before this code lands — do not transcribe loosely.** Room compares the migrated SQLite schema against the generated entity schema on open and throws `IllegalStateException` on any mismatch, including a differing `NOT NULL` or a missing index name. Room's generated index naming convention is `index_<table>_<column>`; match it exactly or validation fails. H1 states the column shapes mirror `NoteItem`/`NoteFolder` (`AppModels.kt:316-330`), `ToolsRepository.CustomTool` (`:384-390`), and `ChatMessage` (`AppModels.kt:438-443`), so those are the definitive references. The cheapest cross-check is to let the app scope's entities generate `schemas/9.json` and diff the `CREATE TABLE` text against this migration.
    - Register the five app-scope entities in the `@Database` entity list alongside the existing eleven, and add their DAO accessors. (Entity classes themselves are authored by the app scope; this task only owns registration and DDL.)
    - Keep the version-history comment block and append the 8→9 entry in the same style as the existing 4→5 / 5→6 / 6→7 / 7→8 notes.
    - `app/build.gradle.kts`: add the KSP `room.schemaLocation` argument pointing at `$projectDir/schemas` so the exported schema is checked in and future migrations are verifiable. Room is 2.7.0 (`gradle/libs.versions.toml` lines 15–17).
    - `Daos.kt`: add `CapturedTrafficDao.getByConnectionId(connectionId: String): List<CapturedTrafficEntity>` — this becomes the **only** query the lineage path uses (see task 12). `getByHostInWindow` may stay as a DAO method for any other legitimate caller, but rewrite its comment: it must say it is **not** for lineage attachment and must not be reintroduced there, instead of claiming the `connectionId` column does not exist.

    Tests: new `app/src/test/java/com/example/data/local/Migration8To9Test.kt` (Robolectric already a test dependency). Create the v8 schema, insert one `captured_traffic` and one `tls_sessions` row, run the migration, then assert: the new columns exist; pre-existing rows survived with the new fields null (**this is the proof the destructive fallback is gone — the test fails if the wipe still happens**); the new index is present; the five app-scope tables exist; a subsequent insert carrying the new fields round-trips. Add `MigrationTestHelper` usage now that `exportSchema = true` makes the v8/v9 JSON available.

11. **[after 1, 7, 10] Set `TlsHandshakeState.FAILED` from real signals**

    Files: `app/src/main/java/com/example/interception/CaptureEngine.kt` (743 lines), `app/src/main/java/com/example/interception/MitmProxyEngine.kt` (679 lines), `app/src/main/java/com/example/correlation/graph/ConnectionLineage.kt` (274 lines), `app/src/main/java/com/example/diagnostics/VisibilityAnalyzer.kt` (133 lines).

    Why: the only two writers of `handshakeState` are `CaptureEngine.kt:186` (`CLIENT_HELLO.name`) and `MitmProxyEngine.kt:625` (`ESTABLISHED.name`). So `ConnectionLineage.tlsStep()` carries an apology at lines 222–224 above `success = tls.handshakeState != "FAILED"` — "TlsHandshakeState.FAILED is defined but never set anywhere in the codebase today ... so this can only ever be true given that a row exists at all" — and `VisibilityAnalyzer` line 73 unconditionally appends a `limitingReasons` string stating its own `tlsFailedReported` counter (line 37) "is expected to always read 0."

    Changes:
    - `CaptureEngine.ingest`: line 224 currently short-circuits with `if (stream.isTls && !stream.decryptedByMitm) return`, so no later byte of a TLS stream is examined. Before that return, run `TlsDecoder.findAlertInRecords` (task 7) over newly buffered bytes in both directions; on a fatal alert, upsert the stream's `TlsSessionEntity` with `handshakeState = FAILED.name`, `failureReason = "alert: ${alert.descriptionName}"`, `failedAt = now`. Keep `tlsSessionId = stream.key` so it updates the row written at line 186 rather than duplicating it.
    - `CaptureEngine`: in the same place, when an `isTls` stream reaches `handshakePhase == RESET` or `CLOSED` (task 1) without an ESTABLISHED TLS row, upsert FAILED with `failureReason = "connection reset during handshake"` / `"closed before handshake completed"`. A stream that never sent a ClientHello must stay untouched — that is the "never started" case the current schema cannot express.
    - `MitmProxyEngine.handleClient`: today `sslSocket.startHandshake()` and `upstreamSsl.startHandshake()` both sit under one `catch (e: Exception) { if (running.get()) Log.w(TAG, "Client handler: ${e.message}") }`. Wrap each individually, catch `javax.net.ssl.SSLHandshakeException` / `SSLException`, and call a new `persistFailedMitmTlsSession(db, host, port, stage, cause)` writing `tlsSessionId = "$host:$port"` (same convention as `persistMitmTlsSession`, lines 600–633), `handshakeState = FAILED.name`, `failureReason = "$stage: ${cause.javaClass.simpleName}: ${cause.message}"`, `wasDecrypted = false`. Then return as today so relay behaviour is unchanged. This path covers the TLS 1.3 encrypted-alert case task 7 cannot see on the wire.
    - `ConnectionLineage.tlsStep()`: delete the "can only ever be true" comment (lines 222–223) and keep `success = tls.handshakeState != "FAILED"`, now meaningful. Surface `failureReason` in the step's `detail` — `LineageStep(plane, label, detail, timestamp, success)` already has the field.
    - `VisibilityAnalyzer`: make the `limitingReasons` entry conditional and factual — report the real FAILED count when > 0, and when 0 state that no failed handshakes were observed rather than that the state is unreachable. Update the class doc (lines 14–19) for the same reason.

    Tests: new `app/src/test/java/com/example/interception/tls/TlsFailureStateTest.kt`. Extract an `internal fun classifyTlsOutcome(phase, alert, hasEstablishedRow): TlsOutcome?` so the mapping from (handshake phase, alert, existing row) to FAILED+reason is testable without Room or sockets. Cover: fatal alert → FAILED with alert reason; warning `close_notify` after establish → no FAILED; RST during handshake → FAILED with reset reason; RST after establish → no FAILED; no ClientHello → null. Add a Robolectric case asserting `VisibilityAnalyzer.limitingReasons` omits the FAILED sentence when a FAILED row exists.

12. **[after 10] Thread `connectionId` through every persist path**

    Files: `app/src/main/java/com/example/interception/CaptureEngine.kt`, `app/src/main/java/com/example/interception/MitmProxyEngine.kt`, `app/src/main/java/com/example/interception/gateway/GatewayEngine.kt`, `app/src/main/java/com/example/api/LocalApiServer.kt`, `app/src/main/java/com/example/correlation/graph/ConnectionLineage.kt`.

    Why: seven `CapturedTrafficEntity(...)` construction sites exist and none can be joined to a connection — `CaptureEngine.kt:195` (undecoded TLS), `:490` (`persistGrpcMessage`), `:594` (`persistTransaction`), `:667` (`persistWsFrame`), `MitmProxyEngine.emitTransaction` (line 431), `GatewayEngine`'s persist, and `LocalApiServer.receiveObserverEvents` (line 287). That absence is the entire reason `ConnectionLineage` needs a 30-second window.

    Changes:
    - `CaptureEngine`: pass `connectionId = stream.key` at all four sites. `stream.key` is `"$protocol|$srcIp:$srcPort>$dstIp:$dstPort"`, already used as `TcpConnectionEntity.connectionId` in `persistTcpConnection` — so the join is exact by construction.
    - `MitmProxyEngine.emitTransaction`: pass `connectionId = "$host:$port"`, matching `persistMitmTlsSession`'s convention. Document that MITM rows use host:port because the engine never sees the raw 5-tuple — the same asymmetry `ConnectionLineage.findTlsFor()` (lines 201–212) already handles with its two-step lookup. Keep both conventions rather than silently changing one, which would break existing rows.
    - `GatewayEngine`: build a per-connection id from the accepted socket's remote address plus the CONNECT/absolute-URI target, consistent with the `sourceIp` field it already sets.
    - `LocalApiServer.receiveObserverEvents`: `connectionId = null` — the SDK genuinely has no connection identity and null is the honest value. **This one line stays in this scope** (user decision: one owner for a one-liner); the SDK scope has been told not to touch it, even though it otherwise owns this file.
    - `ConnectionLineage.httpSteps()`: use `getByConnectionId(connectionId)` **only**. **Do NOT add a windowed fallback here, and do not re-add one later.**

    **Lineage contract — decided against this plan's earlier draft.** An earlier version of this task kept `getByHostInWindow` as a 30-second fallback inside `httpSteps()`, marking fallback-sourced steps as window-matched. The user resolved this in favour of the runtime scope's W6 position: a time-window heuristic can attach a plausible-but-wrong HTTP row to a connection, fabricating a causal chain the data does not support — precisely the failure mode this whole pass exists to eliminate. So:

    - `HTTP_WINDOW_MS = 30_000L` is **removed from the lineage path**. `getByHostInWindow` may remain as a DAO method if another caller legitimately needs it, but **no lineage step may be produced from it**.
    - **The lineage consumer is owned by the runtime scope's W6.** W6 replaces the window with exact `connectionId`-keyed joins plus an honest `unattachedHttpRows` count on `LineageChain` and a `MISSING`-kind step for rows carrying no transport identity. **Exact-key joins are the contract this task delivers to them** — this task's job is to make `connectionId` populated and reliable so W6's exact joins actually resolve. Do not implement W6's `unattachedHttpRows`/`MISSING` step here; that is theirs.
    - **Corollary to watch:** if `unattachedHttpRows` is routinely non-zero once this threading lands, that indicates a **gap in the threading**, not an acceptable steady state. Genuinely identity-less producers (SDK `/api/observe`, HAR imports) are the only legitimate contributors. Any other source appearing there is a bug in this task.

    Tests: new `app/src/test/java/com/example/correlation/graph/ConnectionLineageJoinTest.kt` (Robolectric + in-memory Room) — insert a TCP connection, a TLS session, and two `captured_traffic` rows (one with the matching `connectionId`, one same-host-and-inside-30s but a **different** connection) and assert the exact join returns only the first, i.e. the same-host-in-window row is **not** attached. Then insert a `connectionId`-less row and assert it produces **no** lineage step (rather than a window-matched one) — this is the regression test that stops the fallback being reintroduced. Add a case asserting every step returned has a non-null `connectionId` source.

13. **[after 4, 5, 11] QUIC handshake/1-RTT decryption → real HTTP/3 rows**

    Files: `app/src/main/java/com/example/interception/quic/QuicCrypto.kt` (200 lines), `app/src/main/java/com/example/interception/quic/QuicParser.kt` (319 lines), `app/src/main/java/com/example/interception/quic/QuicConnection.kt` (61 lines), new `app/src/main/java/com/example/interception/quic/Http3FrameParser.kt`, `app/src/main/java/com/example/interception/PacketPump.kt`, `app/src/main/java/com/example/interception/MitmProxyEngine.kt`.

    Why: `QpackDecoder.decode()` (`quic/QpackDecoder.kt`, 134 lines) has no production call site — confirmed by grep; only `QpackDecoderTest` exercises it, and that test's own doc comment says so. `QuicCrypto`'s class doc (lines 22–26) is correct that Handshake and 1-RTT keys need "the private key material of one endpoint": a passive observer cannot compute the ECDHE shared secret, and implementing the TLS 1.3 key schedule alone does not fix that because the schedule needs the secret as *input*. Also, `QuicParser.extractCryptoFrameData` stops its frame walk at any type other than `0x00` PADDING / `0x01` PING / `0x06` CRYPTO, so STREAM frames are never reached. And `PacketPump.persistQuicConnection` (lines 238–294) only ever sets `INITIALIZING` or `HANDSHAKE_IN_PROGRESS` — `HANDSHAKE_COMPLETE`, `DRAINING` and `CLOSED` are unreachable states in `QuicState`.

    The resolution is to stop being a passive observer on the planes requestr controls, consuming `TlsKeyLogRegistry` (task 5).

    Changes:
    - `MitmProxyEngine`: register the client random and derived secrets for connections it terminates into `TlsKeyLogRegistry`, making requestr a legitimate key source for its own MITM'd traffic. (The SDK scope adds the second producer against the same interface; the `SSLKEYLOGFILE` import is already in task 5.)
    - `QuicCrypto`: widen `hkdfExpandLabel` — currently `private` with signature `(secret, label, length)`, RFC 8446 §7.1, `"tls13 " + label`, empty context — to accept a context/transcript-hash argument and expose it `internal`. Add `deriveKeysFromTrafficSecret(version, secret): InitialKeys` reusing the existing `quic key` / `quic iv` / `quic hp` (and `quicv2 …`) labels. Add `decryptShortHeaderPacket(packet, dcidLength, keys)` for 1-RTT: the short header has no version field, the DCID length must come from connection state, and the header-protection mask uses `mask[0] and 0x1f` rather than Initial's `0x0f`.
    - `QuicParser`: add real frame-type dispatch so the walk no longer aborts on the first unknown type — `0x02/0x03` ACK, `0x08..0x0f` STREAM (honouring the OFF/LEN/FIN bits), `0x10` MAX_DATA, `0x18` NEW_TOKEN, `0x1c/0x1d` CONNECTION_CLOSE, and safe skips for the rest using the existing `readVarInt` (RFC 9000 §16).
    - `Http3FrameParser`: RFC 9114 frame layer over reassembled STREAM data — type+length varints, `0x01` HEADERS, `0x00` DATA, `0x04` SETTINGS — routing HEADERS payloads into `QpackDecoder.decode()`, which finally gets its production call site. Reuse `QuicStream`'s existing `requestBuffer`/`responseBuffer`/`headersEndOffset`/`decodedHeaders` fields, which exist for exactly this and are currently unused.
    - `QuicConnection`: drive the real state machine — `HANDSHAKE_COMPLETE` when handshake keys decrypt successfully, `DRAINING`/`CLOSED` on CONNECTION_CLOSE — making the three unreachable `QuicState` values real.
    - `PacketPump`: persist decoded HTTP/3 exchanges as `CapturedTrafficEntity` rows shaped exactly like the HTTP/2 path — `protocol = "HTTP/3"`, method/path/authority from the `:method`/`:path`/`:authority` pseudo-headers (already in `QpackDecoder`'s `STATIC` table), `connectionId` per task 12, `wasDecrypted = true`. Respect `SettingsRepository.settings.value.requestLoggingEnabled` / `responseLoggingEnabled` and the `captureEnabled` gate, matching `persistTransaction`/`persistGrpcMessage`.
    - **No-key case:** when no key material exists for a connection, persist the connection row with `wasDecrypted = false` and, when `printUndecoded` is on, an `isUndecoded = true` row carrying the real Initial-packet SNI/ALPN — the same honest shape the undecoded-TLS path already uses at `CaptureEngine.kt:195`. Never an invented HTTP/3 row. This is a real runtime state, surfaced in task 14's UI.

    Tests: extend `app/src/test/java/com/example/interception/quic/QuicCryptoTest.kt` (94 lines; already validates RFC 9001 A.1 keys — dcid `8394c8f03e515708` → key `1f369613dd76d5467730efcbe3b1a22d` — and A.2 decryption at `pnOffset = 18`) with a `deriveKeysFromTrafficSecret` vector and a short-header unprotect case from a known secret. New `QuicFrameParserTest.kt` — STREAM with and without OFF+LEN, ACK skipped, CONNECTION_CLOSE driving the state transition, unknown frame type no longer aborting the walk. New `Http3FrameParserTest.kt` — decode a HEADERS frame whose QPACK payload reuses `QpackDecoderTest`'s existing vectors (`0000` + `40` + `8c` + `f1e3c2e5f23a6ba0ab90f4ff` → `:authority` = `www.example.com`; static index 17 → `:method` = `GET`) and assert the resulting method/path/host; assert a DATA frame appends to the right buffer; assert a no-key connection yields an undecoded row and no HTTP/3 row.

14. **[after 4, 6, 9, 11, 13] UI surfaces + tracker updates**

    Files: `app/src/main/java/com/example/ui/screens/InterceptScreen.kt` (2968 lines), `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (1858 lines), `TODO_v5.md`, `AUDIT_REPORT.md`.

    **Visual spec: the design agent's mockups are the spec. Read them before implementing; do not invent layout.**

    Artifacts on disk in `/code/.plans/designs/`, each in matched `-light.html` / `-dark.html` pairs — support both themes:

    | Mockup | Covers |
    |---|---|
    | `relay-health-healthy-compact` | Relay health card, collapsed, all families relaying |
    | `relay-health-healthy-detail` | Relay health card, expanded per-family breakdown |
    | `relay-health-degraded-detail` | Partial relay — e.g. ICMP unavailable, `unrelayableCount` non-zero |
    | `relay-health-down-compact` | Relay stopped/failed, collapsed |
    | `relay-health-down-detail` | Relay stopped/failed, expanded with error text |
    | `tls-rows-mixed` | TLS tab list with DECRYPTED / SNI-only / FAILED rows side by side |
    | `tls-failed-detail-unknown-ca` | FAILED detail, `unknown_ca` reason |
    | `tls-failed-detail-handshake-failure` | FAILED detail, `handshake_failure` reason |
    | `http3-row-indicators` | QUIC/HTTP-3 row-level indicators |
    | `http3-detail-decoded` | HTTP/3 detail, frames decoded with key material |
    | `http3-detail-quic-only` | QUIC Initial metadata only, no HTTP/3 decode |
    | `http3-detail-undecrypted` | Connection present but no key material — the honest undecrypted state |

    `design-plan.json` in the same directory is the design agent's own index. All three surfaces are now covered, so **do not invent layout for any of them.** The two `tls-failed-detail-*` variants show the reason string rendered for two different alert codes — treat them as one layout parameterised by reason, not two layouts. Likewise the three `http3-detail-*` states are one layout across three data conditions, which map exactly to task 13's outcomes (frames decoded / Initial-only / no keys).

    Why: three fixes are otherwise invisible, and the user's bar is zero visible gaps. Patterns to extend, all observed in-file: `ConnectionsBottomSheet` (line 1523) with its 5-tab `TabRow` (`"TCP (${tcpConnections.size})"`, UDP, `"QUIC (${quicConnections.size})"`, `"TLS (${tlsSessions.size})"`, Provenance) backed by `ConnectionsRepository.observe*`; `GlassCard` + `BadgePill` per row (the TLS tab at index 3 already renders `BadgePill(text = if (tls.wasDecrypted) "DECRYPTED" else "SNI/JA3 ONLY", ...)`); `EmptyConnectionsState(message)` for honest empty states; `SettingsSectionHeader` + `produceState` for the existing OBSERVATION REPORT card.

    Changes:
    - **Relay health** (items 1, ICMP, IPv6): a card bound to `TunRelay`'s `StateFlow` showing active flows per family, bytes up/down, relay errors, `unrelayableCount`, and the ICMP path actually in use (ping socket vs owned probe vs unavailable). Bind with `collectAsStateWithLifecycle` — `androidx.lifecycle.runtime.compose` is already a dependency — never a plain `@Volatile var`, per the `LocalApiServer.isRunning` bug in `/memory/testing/requestr/known-issues.md`.
    - **TLS FAILED** (item 2): extend the TLS tab's existing badge with a `FAILED` state and render `failureReason` beneath the version/cipher line, styled like the existing `cert:` and `JA3:` rows.
    - **HTTP/3 + key material** (item 3): update the QUIC tab's empty state, which currently reads "No QUIC connections recorded yet. QUIC session identity is extracted from the Initial packet's SNI." — show the real `QuicState`, badge whether HTTP/3 rows were decoded or only Initial metadata was available, and surface `TlsKeyLogRegistry.entryCount` so a user can see whether key material is present. This makes the no-key case a visible runtime state rather than a silent absence.
    - `TODO_v5.md`: P2's row (line 40) currently ends by arguing that leaving `QpackDecoder` test-only "is the honest choice here"; the v5.8-backup DEEP AUDIT note (~line 155) says the VPN_TUN relay fix "is a substantial new relay/NAT implementation, not a wiring fix"; D2's row (line 106) records the FAILED "documented limitation". Rewrite all three to what actually shipped.
    - `AUDIT_REPORT.md`: flip the v5.8-backup addendum's CRITICAL finding from disclosed-not-fixed to fixed, naming the new `interception/tun/` files.
    - **Verification-honesty statement — must land in the trackers, not just this plan.** Both `TODO_v5.md` and `AUDIT_REPORT.md` must state explicitly which parts of this work were **device-verified end-to-end** and which are **static/unit-verified only**. Per `/memory/knowledge/setup-learnings/android.md` the Redroid target has no kernel TUN support, so the tun relay, IPv6 relay, and the ICMP ping-socket path **cannot** be exercised end-to-end here; per `/memory/testing/requestr/known-issues.md` VPN/root/Shizuku interception cannot be driven on non-rooted Redroid at all. Name those three as unverified-on-device with the reason, and name what *was* verified (unit tests, migration test, build gates, and any surface reachable without a tun). Do not let a reader infer end-to-end validation that did not happen — an undisclosed verification gap in the tracker is the same class of dishonesty this pass is removing from the code.

    Tests: no new Compose render tests — `roborazzi` is configured but there are no existing screenshot baselines for these screens, so adding them here expands scope. Verification is the manual pass below.

---

### Testing

**Per-task unit tests** are listed inline. All live under `app/src/test/java/` following existing conventions: `org.junit.Assert.*` + `org.junit.Test`, backtick-quoted test names, and a local `hex(s)` helper via `s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()` (from `QpackDecoderTest`/`QuicCryptoTest`). Robolectric 4.16.1 and `kotlinx-coroutines-test` are already test dependencies; `app/build.gradle.kts` already sets `testOptions { unitTests { isIncludeAndroidResources = true } }`.

**Build gates** — run after each task, and all three before declaring done:
```
./gradlew :app:compileDebugKotlin --no-configuration-cache
./gradlew :app:testDebugUnitTest  --no-configuration-cache
./gradlew :app:assembleDebug      --no-configuration-cache
```
`:app:assembleDebug` is BUILD SUCCESSFUL at HEAD `0db606c` and must stay that way. `debug.keystore` is not in git; if signing fails, generate it per `/memory/testing/requestr/known-issues.md`:
`keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`

**Integration verification — device caveat.** Per `/memory/knowledge/setup-learnings/android.md`, Redroid has no real kernel TUN support and `VpnService.Builder().establish()` fails there; `/dev/tun` is missing. The container-lifetime workaround (re-apply per container) is:
```
adb -s localhost:5555 root
adb -s localhost:5555 shell "ln -s /dev/net/tun /dev/tun"
```
Even with the symlink, `/memory/testing/requestr/known-issues.md` records that VPN/root/Shizuku interception cannot be genuinely exercised on non-rooted Redroid — Interceptr shows "NOT CONFIGURED". The ICMP ping socket in particular cannot be validated there (no ping-socket permission, no real kernel netstack). So end-to-end proof lives in the JVM tests of tasks 2, 3, 4, 6 and 9 (loopback servers plus `TcpReassembler` as the parsing oracle). Report honestly which parts were device-verified and which were not; never claim device verification that did not happen.

**Migration verification** (task 10) must not be skipped: install the pre-change APK, run a capture so `captured_traffic` and `tls_sessions` have rows, then install the post-change APK over it and confirm the rows survive. Before this change `.fallbackToDestructiveMigration(dropAllTables = true)` would wipe them — that difference is the whole point. `Migration8To9Test` covers the same ground in CI.

**Manual pass** (task 14), where the device permits: start VPN_TUN capture and confirm another app actually loads a page (the blackhole fix); confirm DNS resolves; confirm `ping` reaches a host (ICMP relay); confirm an IPv6-capable network still carries traffic; confirm the relay card shows non-zero flows/bytes per family; confirm a deliberately-failing TLS connection produces a FAILED badge with a reason; confirm the QUIC tab shows real state plus HTTP/3 rows when key material is present and an honest undecoded row when it is not.

---

### Risk / integration notes for the orchestrator

**This scope lands FIRST.** Tasks 4 and 9 substantially rewrite `PacketPump.kt`, and tasks 11–13 edit `CaptureEngine.kt` and `RequestrVpnService.kt`. The app scope threads live logging preferences through those same three files; per the user's decision that is a **small surgical diff applied on top of this scope's rewrite**, not a parallel edit. Do not merge the app scope's version of these three files.

| File | Other scope | Conflict |
|---|---|---|
| `data/local/RequestrDatabase.kt`, `Entities.kt` | app-level (`/code/.plans/plan-app.md`) | **This scope owns version 9 and the single migration**, which the app plan explicitly defers. **Resolved:** Section B's DDL is now written from that plan's **H1** column lists; the app scope owns the column definitions, this scope owns the version number and `Migration` objects. Cross-check the DDL against their `@Entity` declarations before landing. No second bump. |
| `data/local/Daos.kt` | app-level | New query methods on both sides; same file, low semantic conflict. |
| `interception/PacketPump.kt` | app-level (logging prefs) | Rewritten here by tasks 4, 9, 13. App scope diffs on top. |
| `interception/CaptureEngine.kt` | app-level, browser/runtime, SDK | Tasks 11 and 12 edit `ingest` and all four persist sites. |
| `interception/RequestrVpnService.kt` | app-level | Tasks 4, 8, 9 all edit `startCapture`. |
| `interception/MitmProxyEngine.kt` | browser/runtime, SDK | Tasks 11, 12, 13 edit it (failure rows, `connectionId`, keylog registration). |
| `interception/tls/TlsKeyLogRegistry.kt` | **SDK scope produces into it** | This scope owns the interface (task 5, deliberately early and dependency-free). SDK scope's decryption module exports key material into `record(...)`. **Resolved: the interface is now FROZEN in task 5** as an explicit signature block; the SDK scope binds to it verbatim. Notify that scope if any signature changes. |
| `api/LocalApiServer.kt` | SDK scope owns `/api/observe` | **Resolved: the one-line `connectionId = null` stays in THIS scope** (one owner for a one-liner). The SDK scope has been told not to edit that line. |
| `ui/screens/InterceptScreen.kt`, `SettingsScreen.kt` | app-level product-gap scope | Task 14 lands after that scope. **Resolved: mockups for all three surfaces are on disk** in `/code/.plans/designs/` (12 states x light/dark) — no longer a wait. |
| `correlation/graph/ConnectionLineage.kt` | **runtime scope owns the consumer (W6)** | **Resolved in W6's favour:** task 12 populates `connectionId` and uses exact-key joins only. The 30s `getByHostInWindow` fallback is **removed from the lineage path and must not be re-added**. W6 adds `unattachedHttpRows` + the `MISSING` step; this scope does not. |
| `TODO_v5.md`, `AUDIT_REPORT.md` | every scope | Append-only; land last to avoid churn. |

**Ordering:** tasks 1, 2, 3, 5, 7, 8 are independent and parallel. Task 4 needs 1+2+3. Task 6 needs 2+4. Task 9 needs 4. Task 10 needs 7. Task 11 needs 1+7+10. Task 12 needs 10. Task 13 needs 4+5+11. Task 14 needs 4+6+9+11+13 plus design artifacts.

**Blocking questions: none.** All five prior handoffs are resolved by user decision: (1) Section B DDL taken from `plan-app.md` H1; (2) `TlsKeyLogRegistry` frozen in task 5; (3) the `LocalApiServer` one-liner stays here; (4) mockups for all three UI surfaces are on disk; (5) land order confirmed — this scope first. The lineage-fallback disagreement with the runtime scope is resolved in W6's favour (exact-key joins, no time window). Two mechanism decisions are recorded for awareness, both resolved in-plan and neither blocking: the ICMP fallback uses an owned+protected probe rather than `InetAddress.isReachable()` (whose probe socket is platform-internal and therefore unprotectable, and which unprotected would loop back through our own relay); and fragmented IPv6 datagrams — the one named unrelayable-as-is sub-case — are handled by bounded reassembly (task 3) rather than dropped. Both can be overridden without restructuring the plan.
