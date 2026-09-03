### Summary

Rebuild requestr's capture transport as one coherent path: the Rust bridge switches from an HTTP-CONNECT proxy (which drops all UDP and hides hostnames) to SOCKS5 with `+info` session info and explicit DNS/IPv6/UDP configuration; the CONNECT-only MITM front door is replaced by a multi-protocol `LocalGateway` that also serves the device-proxy and transparent (root/Shizuku) tiers; the relay layer moves from unbounded coroutines plus raw threads to a bounded pool with a per-connection state machine and non-blocking persistence; and the certificate layer is rewritten to read the ClientHello before issuing a leaf, sniff upstream certificates for IP/no-SNI connects, and support per-domain server certificates and mTLS. QUIC gains an explicit block-or-tee policy with passive decode; root/Shizuku gain `SO_ORIGINAL_DST` and verified capability gating; trust becomes a diagnosable report instead of a boolean.

All file paths are relative to `/code/Mahesh953-hub/requestr`. Baseline: `vorflux/fix-audit-gaps` @ `b71d61f`.

---

### Tasks

1. **[parallel] Native bridge protocol upgrade — SOCKS5, `+info`, DNS, IPv6, UDP**

   *Rust — `native/tun2proxy-bridge/src/config.rs`:*
   - Add `session_info: bool` to `BridgeConfig` (default `false`). When `true`, `build_args()` must set credentials whose username ends in the literal `+info` — this is the only mechanism upstream `tun2proxy 0.8.3` offers (`src/socks.rs::send_auth_data`, `SESSION_INFO_MARKER`), and it works **only** for `WireProxyType::Socks5` with user/pass auth. Reject `session_info = true` combined with any other proxy type with `ERR_INVALID_CONFIG`.
   - Make `ipv6_enabled` mandatory-explicit in the Kotlin-facing contract and validate it: if the caller declares the tun carries `::/0`, `ipv6_enabled` must be `true`. Add a `tun_has_ipv6` field so the mismatch is a config error rather than a silent v6 blackhole.
   - Validate the DNS matrix: `WireDnsMode::Direct` with `WireProxyType::Socks5` and no `udpgw` means DNS travels as SOCKS5 UDP ASSOCIATE; `Direct` with `Http` must be rejected outright, because `HttpManager::new_proxy_handler` errors for every non-TCP session (verified in the vendored crate) and that is exactly today's silent DNS/QUIC drop.
   - Keep `close_fd_on_drop(false)` and `setup(false)` unchanged.

   *Rust — `native/tun2proxy-bridge/src/lib.rs` and `src/state.rs`:*
   - Preserve the existing readiness handshake (`START_READINESS_TIMEOUT`, `wait_for_running_or_failed`), the dedicated OS thread, `ThreadDoneGuard` drop order, and the `general_run_async` choice over `general_run_for_api` (whose watchdog calls `std::process::exit`). Do not regress these.
   - Extend `state` with a monotonic `session_counter` and `last_error_kind` enum so Kotlin can distinguish "config rejected", "runtime failed", "timed out" without string matching.
   - Replace the packed-`Long` traffic-stats encoding with two out-parameters (`nativeTrafficStats(long[] out)`), removing the documented `0xFFFFFFFF`-both-directions ambiguity in `NativeTun2Proxy`.

   *Kotlin — `app/src/main/java/com/example/interception/tun/Tun2ProxyConfig.kt`:*
   - Mirror the new fields (`sessionInfo`, `tunHasIpv6`, `proxyAuth`) in the hand-rolled JSON writer; keep avoiding `org.json.JSONObject` so plain JVM tests still work.
   - Replace `forMitmProxyEngine(mitmPort, mtu)` with `forLocalGateway(port, credentials, dnsMode, tunHasIpv6, udpPolicy)` producing `ProxyType.SOCKS5`, `sessionInfo = true`, `ipv6Enabled = tunHasIpv6`, `dnsMode` from settings (default per Q1 answer), `bypassCidrs` including `127.0.0.0/8` and the tun's own prefixes, and `maxSessions` raised to match the relay pool budget (see task 4).
   - Retire `forMitmProxyEngine` outright — it is the source of the `ProxyType.HTTP` defect and must not survive as a "legacy" overload.

   *Kotlin — `Tun2ProxyBridge.kt` / `NativeTun2Proxy.kt`:* surface `last_error_kind` on `StartResult.Failed`, keep `STOP_QUIESCENCE_TIMEOUT_MS` and `awaitStoppedOrTimeout` semantics.

   *Tests:* extend the 10 Rust config tests to cover `session_info` username suffixing, `session_info` rejected for HTTP/SOCKS4, `Direct` + HTTP rejected, `tun_has_ipv6` mismatch rejected, and the new error-kind mapping. Extend `app/src/test/java/com/example/interception/tun/Tun2ProxyConfigTest.kt` for the new JSON fields and `forLocalGateway` output; extend `NativeTun2ProxyTest` for the two-out-parameter traffic stats.

2. **[parallel] VPN service lifecycle hardening and universal `protect()`**

   *`app/src/main/java/com/example/interception/RequestrVpnService.kt`:*
   - Builder: add `setBlocking(true)` (the relay reads are already on a dedicated dispatcher, and non-blocking reads currently spin), add `setUnderlyingNetworks(arrayOf(activeNetwork))` on API 29+, keep `setMetered(false)`, keep the IPv4+IPv6 addresses/routes, and keep `resolveFilterPlan`'s strict allowlist-XOR-blocklist behaviour.
   - DNS servers: replace the hard-coded public resolvers with the resolvers the chosen DNS mode requires. If Q1 resolves to virtual DNS, advertise a single virtual resolver inside the tun pool (e.g. `198.18.0.1`) and make the pool a constant shared with `Tun2ProxyConfig.virtualDnsPool`. If Q1 resolves to `over_tcp`, advertise the system's active-network resolvers (read from `LinkProperties.dnsServers`) with the public resolvers only as a last-resort fallback, so DNS keeps working on captive/enterprise networks.
   - Network handoff: register a `ConnectivityManager.NetworkCallback` (`NetworkRequest` with `NET_CAPABILITY_INTERNET`). On `onAvailable`/`onCapabilitiesChanged`/`onLinkPropertiesChanged`, call `setUnderlyingNetworks` and, if the resolver set or IP family changed, run a debounced (≈750 ms) tear-down/re-establish through the same path `filterObserverJob` already uses. On `onLost` with no replacement, keep the tun up and mark the watcher `DEGRADED` rather than stopping capture.
   - Foreground start: change the companion `start()` to `startForegroundService(...)` on API 26+, and call `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` synchronously at the top of `onStartCommand` with a minimal notification, *before* any suspending `SettingsDataStore` read; update the notification content afterwards from `scope.launch`. Keep `specialUse` and the manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; **do not introduce `dataSync`**. Remove the now-unused `FOREGROUND_SERVICE_DATA_SYNC` permission from `app/src/main/AndroidManifest.xml`.
   - Persistence: replace the two `kotlinx.coroutines.runBlocking(Dispatchers.IO) { db... }` call sites (`onDecryptedTransaction`, `onDnsObserved`) with sends into the bounded `TransactionSink` from task 4.
   - Keep `onRevoke()`, the `cleanup()` ordering, the `stopCapture` → `cleanup()` → `markStopped` sequence, and the `stopCaptureStepRecorder` test seam intact.

   *New `app/src/main/java/com/example/interception/net/SocketProtector.kt`:* a process-wide seam holding the current `ProtectHook` (set by the service, cleared on teardown, `NoOpProtectHook` otherwise). Route every egress socket through it: `LazyUpstreamConnection.kt:41` (`connectionFactory`), `RemoteCertificateMirror.kt:59`, `HttpProxyShared.kt:162` and `:245`, `ConnectReplay.kt:109`, and the `IcmpRelay`/`TunRelay` call sites (which keep their existing explicit hook). Log and fail the connection when `protect()` returns `false` while a tun is active, instead of proceeding unprotected.

   *Tests:* extend `RequestrVpnServiceLifecycleTest` with fake `ConnectivityManager`/callback drivers — handoff triggers exactly one debounced re-establish, `onLost` without replacement does not stop capture, `setUnderlyingNetworks` is called on API 29+ and skipped below, `startForeground` is invoked before the first DataStore read, and revoke still runs the full cleanup order. Add `SocketProtectorTest` asserting every listed call site consults the hook and that an unprotected result aborts the dial.

3. **[after 1] `LocalGateway` — one multi-protocol listener replacing the CONNECT-only front door**

   *New package `app/src/main/java/com/example/interception/gateway/local/`:*
   - `LocalGateway.kt` — owns the `ServerSocket`, the accept loop, and dispatch. It peeks the first bytes of every accepted connection (without consuming them, reusing the `PeekedSocket` replay technique from `tls/ClientHelloCapture.kt`) and classifies:
     - `0x05` → `Socks5Handler`
     - `0x04` → `Socks4Handler`
     - `CONNECT ` → HTTP CONNECT tunnel (current behaviour)
     - other HTTP method + absolute-form URI → plaintext forward proxy (fixes device-proxy mode, which today gets `400`)
     - `0x16 0x03` → transparent TLS: destination from `SO_ORIGINAL_DST` when available (task 8), else SNI-only
     - otherwise, printable HTTP with a `Host` header → transparent plaintext
     - unclassifiable → close with a recorded `UNKNOWN_PROTOCOL` reason, never a bare `400`.
   - `Socks5Handler.kt` — full RFC 1928/1929 server: method negotiation (`NO_AUTH` and `USERNAME/PASSWORD`), `CONNECT`, and `UDP ASSOCIATE`. On user/pass auth it parses the `+info` payload `"<base>|tcp|<src_ip>|<src_port>"` produced by the bridge and attaches the resulting `ClientOrigin` to the session. Reject credentials whose base username does not match the per-session secret generated at bridge start.
   - `GatewaySession.kt` — the resolved session: `ClientOrigin?`, requested destination (host **or** IP, port), transport, protocol hint, and the peeked bytes to replay.
   - `SessionRouter.kt` — applies `ConnectControl.evaluate` once per session, then hands off to the MITM handler, the opaque tunnel, the plaintext forward path, or the UDP policy router (task 6).

   *Retire / rewire:* `MitmProxyEngine.handleClient`'s request-line parsing, `skipHeaders`, `parseHostPort`, and its own `ServerSocket`/accept loop are deleted; `MitmProxyEngine` becomes `MitmSessionHandler` taking a `GatewaySession`. `HttpProxyEngine`/`HttpProxyShared` likewise lose their listener and keep only their forwarding/tunnelling logic. `PreflightController` starts `LocalGateway` for `MITM_PROXY`, `HTTP_PROXY`, `ROOT_IPTABLES` and `SHIZUKU_TUN`, and `RequestrVpnService` points the bridge at it.

   *Tests:* new `gateway/local/LocalGatewayDispatchTest` (one case per classification branch, including a truncated first read and a slow-loris first byte), `Socks5HandlerTest` (method negotiation, bad credentials, `+info` parse including malformed/absent payloads, IPv4/IPv6/domain address types, `UDP ASSOCIATE` reply address), `Socks4HandlerTest`, and an absolute-form plaintext proxy test proving the old `400 Bad Request` path is gone. Keep the existing `HttpProxyEngineConnectTest` and `HttpProxySharedForwardRequestTest` green against the extracted handlers.

4. **[after 3] Bounded relay pool, per-connection state machine, and non-blocking persistence**

   *New `app/src/main/java/com/example/interception/gateway/local/RelayPool.kt`:* a `Semaphore`-bounded (configurable 32–64, default 48) dispatcher pair — one for client→server, one for server→client — backed by a named thread factory, not `Dispatchers.IO`. Accepted sessions above the cap are queued (bounded queue, then oldest-idle eviction with a recorded reason), mirroring the reference-product behaviour of queueing rather than dropping.

   *`MitmSessionHandler` (ex-`MitmProxyEngine`):*
   - Delete both raw-`Thread` relay blocks and both `join(60_000)` calls. Relay legs become coroutines on the relay dispatchers, joined structurally; the connection's terminal state is written **once**, from the state machine, so a stalled leg can no longer emit a premature `MITM_DECRYPTED` `ConnectAttemptEntity`.
   - Introduce `ConnectionState` (`ACCEPTED → CLASSIFIED → UPSTREAM_CONNECTING → HANDSHAKING → RELAYING → HALF_CLOSED → CLOSED | FAILED(reason)`), with idle and total deadlines as configuration rather than a hard-coded 60 s, and a two-phase write discipline (never write to the peer before the corresponding upstream write is acknowledged as flushed).
   - Remove every `runBlocking` from the connection path: the CA prewarm moves to a `suspend` warm-up awaited once during `LocalGateway.start`, and `persistConnectAttempt`/leaf generation become `suspend`.
   - Large bodies spill to a temp file above a 1–2 MB threshold (configurable) instead of accumulating in `MAX_UNPARSED_BUFFER`-bounded memory.

   *New `app/src/main/java/com/example/interception/sink/TransactionSink.kt`:* a bounded `Channel` (capacity ≈ 256) consumed by a single writer coroutine that batches Room inserts. `SUSPEND` on full, so backpressure reaches the relay rather than the heap. All of `onDecryptedTransaction`, `onDnsObserved`, `persistConnectAttempt`, `persistMitmTlsSession` and `persistFailedMitmTlsSession` route through it. Expose a dropped/queued counter for diagnostics.

   *Tests:* `RelayPoolTest` (cap enforced, queueing beyond cap, eviction reason recorded, no leaked threads after teardown via a thread-count assertion), `ConnectionStateMachineTest` (every legal transition, terminal state written exactly once, stalled leg yields `FAILED` not `MITM_DECRYPTED`), `TransactionSinkTest` (ordering, batching, suspension on a full channel, drain on stop). Update `MitmProxyEngineEmitTransactionTest`, `MitmProxyEngineMitmHandshakeTest`, `MitmProxyEngineLazyUpstreamTest` and `MitmProxyEngineSerializeConsistencyTest` to the new handler shape.

5. **[after 3] Certificate engine rewrite — SNI-first, upstream sniffing, per-domain certs and mTLS**

   *New `app/src/main/java/com/example/interception/tls/CertificateEngine.kt`* replacing `MitmProxyEngine.generateHostCert`, `generateHostCertOrMirror` and `CertificateAuthority.issueLeafFromProfile` with a single issuer:
   - **Order fixed:** the ClientHello is peeked first; the leaf identity is chosen as SNI → `SO_ORIGINAL_DST` reverse lookup → CONNECT authority, in that order. The current code mints the leaf before peeking, so `CONNECT 1.2.3.4:443` always yields a CN=IP leaf.
   - **Upstream certificate sniffing** for IP-literal and no-SNI connects: dial upstream (through `SocketProtector`), read the peer leaf, and clone its subject plus *all* SANs. This absorbs `RemoteCertificateMirror` — which keeps `profileFrom`'s validity clamping and raw-DER SAN extraction but loses its all-trusting ad-hoc socket in favour of the protected upstream connection already being opened.
   - **Leaf hardening (one issuer, one policy):** EC P-256 keys with a per-session reused keypair (removes per-host RSA-2048 keygen from the hot path), random positive serial with a reserved high bit, `basicConstraints(false)` critical, `keyUsage(digitalSignature | keyEncipherment)` critical, **`extendedKeyUsage(serverAuth)`**, SKI, AKI derived from the CA, SANs from `HostSanGenerator` (keep its PSL-backed wildcard logic verbatim) or the sniffed profile, and validity clamped to **≤90 days**. `CertificateAuthority.generate` gains AKI for symmetry; its CA extensions, per-install keystore password, atomic persist and `regenerate` epoch invalidation stay as they are.
   - **Per-domain overrides:** a new `data/local` table plus DAO for `DomainCertPolicy(hostPattern, mode, serverCertRef?, clientP12Ref?, clientP12Alias?)` where `mode ∈ {AUTO, CUSTOM_SERVER_CERT, PASSTHROUGH}`. `CUSTOM_SERVER_CERT` presents an imported certificate/key instead of a minted leaf. Reuse `CaP12Exchange`'s import path for both server and client material.
   - **mTLS:** `LazyUpstreamConnection` gains a `KeyManager` built from the matching `clientP12Ref`, so pinned/mTLS-required upstreams complete. Also fix its ALPN gate to API 24 to match the client leg, and replace `AcceptAllTrustManager` with a configurable trust policy (default: accept-all with the outcome recorded, opt-in: validate and surface a real upstream trust failure).

   *`ExpiringLruCertCache.kt`:* replace the instance-wide `@Synchronized` with per-key locks (a `ConcurrentHashMap<String, Lock>` or a `ConcurrentHashMap` of `Deferred` values), key entries by `(identity, policyMode, profileFingerprint)` so a policy change invalidates correctly, keep TTL/LRU and `maxSize = 200`.

   *Tests:* `CertificateEnginePolicyTest` (SNI beats CONNECT authority; IP CONNECT with SNI yields a DNS-name leaf; IP CONNECT without SNI yields a sniffed-profile leaf; no-upstream-available falls back to an IP-SAN leaf), `LeafHardeningTest` (asserts EKU serverAuth, SKI, AKI, critical flags, serial randomness and sign, ≤90-day validity, and that two leaves in the same millisecond have distinct serials — the current `System.currentTimeMillis()` serial fails this), `DomainCertPolicyTest` (pattern matching precedence, `CUSTOM_SERVER_CERT` presented verbatim, `PASSTHROUGH` skips MITM), `MutualTlsTest` (client cert presented when configured, absent otherwise, wrong alias reported as a named failure), `ExpiringLruCertCacheTest` extended for concurrent distinct-host issuance proving no serialization, and a regression test that the ClientHello peek precedes issuance.

6. **[after 3] QUIC policy — block by default, tee on request, passive decode, honest reporting**

   *New `app/src/main/java/com/example/interception/quic/QuicPolicy.kt`* with `Mode { BLOCK, TEE, PASSTHROUGH }`, default `BLOCK`, persisted in `SettingsDataStore`.

   *`Socks5Handler` UDP ASSOCIATE path (`gateway/local/UdpPolicyRouter.kt`, new):*
   - `BLOCK` + destination port 443 → refuse the association with SOCKS5 reply `0x07` (command not supported) and record a `QuicBlocked` diagnostic naming the destination and the app. Clients then fall back to TCP/HTTP-2, which is the whole point.
   - `TEE` → accept the association, forward datagrams over a `SocketProtector`-protected `DatagramChannel`, and copy each datagram into the existing passive pipeline (`QuicDatagramRouter` → `QuicCidTable` → `QuicConnectionDecoder`), which decodes Initial/Handshake and decrypts 1-RTT only when keys exist in `TlsKeyLogRegistry`.
   - `PASSTHROUGH` → forward without teeing (lowest overhead, no visibility).
   - DNS on UDP/53 is never subject to QUIC policy; it is always forwarded (or handled by virtual DNS, per Q1).

   *Legacy `TunRelay` path parity:* add the same policy check where `PacketPump` currently detects `protocol == 17 && (sPort == 443 || dPort == 443)` — today it relays UDP/443 unconditionally with no block and no badge. `BLOCK` drops the datagram and emits an ICMP port-unreachable so the client fails fast rather than timing out.

   *Reporting:* extend `QuicLiveSummary`/`QuicDecodeStatus` with `BLOCKED_BY_POLICY` and `TEED_UNDECRYPTABLE`, and record one diagnostic row per connection ID explaining the outcome. State explicitly, in the diagnostic text and in `QuicPolicy`'s KDoc, that **full QUIC MITM is not implemented** and that Chromium never trusts user-added CAs for QUIC regardless of store state — so block-or-tee plus passive decode is the honest ceiling for this task.

   *Tests:* `QuicPolicyTest` (mode matrix × port × transport), `UdpPolicyRouterTest` (SOCKS5 refusal bytes for `BLOCK`, association accepted and datagrams teed for `TEE`, DNS exempt in all modes, protected socket used for forwarding), `PacketPumpQuicPolicyTest` (legacy path drop + ICMP unreachable, tee reaching the decoder), and a decoder-integration test reusing `QuicWireFixtures` proving a teed Initial produces the same parse as the legacy path. Existing `quic/` suites must stay green untouched.

7. **[after 1, 3] Unified UID attribution**

   *New `app/src/main/java/com/example/interception/attribution/UidAttributor.kt`* with a single ordered strategy and an explicit result type `Attribution { Resolved(uid, source), Unattributed(reason) }`:
   1. `ClientOrigin` from `+info` (source IP/port) combined with the gateway's resolved destination → `ConnectivityManager.getConnectionOwnerUid(IPPROTO_TCP, local, remote)` on API 29+.
   2. `ProcNetUidResolver` (`/proc/net/tcp`, `/proc/net/tcp6`) — keep its existing parsing and its honest KDoc about SELinux restrictions.
   3. Transport allowlist inference: when the filter plan is a single-app allowlist, attribute to that app and mark the source as inferred.
   4. Otherwise `Unattributed` with a named reason — never a silent `-1` or a guessed package.

   Replace the inline attribution block in `persistConnectAttempt` (currently the only attribution site, and one that cannot work in native-bridge mode because the socket peer is tun2proxy's own loopback endpoint) with a call into `UidAttributor`. Persist the attribution source alongside the UID so the UI can distinguish measured from inferred.

   *Tests:* `UidAttributorTest` covering each strategy in order, fallthrough on `-1`, fake `/proc` fixtures (reusing `ProcNetUidResolverTest`'s style), single-app inference, and `Unattributed` reasons. Add a native-bridge-shaped test where the socket peer is loopback and only `+info` can resolve the owner.

8. **[after 3] Root and Shizuku tiers — transparent proxy done properly, or honestly unavailable**

   *Native — `native/tun2proxy-bridge/src/`* (new module, exported through the existing JNI surface): `getsockopt(SOL_IP, SO_ORIGINAL_DST)` and the IPv6 equivalent, returning the pre-NAT destination for a redirected socket file descriptor. This is the missing piece that makes `REDIRECT`-based capture possible at all; without it every redirected connection reaches the gateway with no destination information.

   *`app/src/main/java/com/example/interception/RootIptablesEngine.kt` — rewrite:*
   - Create and use a dedicated chain (`iptables -t nat -N REQUESTR_NAT`, jumped from `OUTPUT`), so install/verify/flush are idempotent and teardown cannot leave orphan rules. The current code appends directly to `OUTPUT` and its `stop()` omits the UDP/443 rule it installed, leaking a rule per session.
   - Own-UID `RETURN` first, then TCP 80/443 (and configurable extra ports) `REDIRECT`. **Remove the UDP/443 `REDIRECT`** — redirecting UDP to a TCP listener cannot work; QUIC policy is enforced with an explicit `REJECT --reject-with icmp-port-unreachable` (or `DROP` in passthrough) driven by `QuicPolicy`.
   - Mirror everything for `ip6tables`, tolerate its absence, and report partial coverage instead of claiming success.
   - `verify()` checks the chain contents and the jump, and is called after install; a failed verify tears down and reports failure.

   *`ShizukuTunEngine.kt` — rewrite:* drop the misleading name and the `Runtime.exec` fallback (which runs as the app UID and cannot touch `iptables`, yet is treated as a possible success). Use a real Shizuku `IUserService` (`bindUserService`) with an AIDL command interface, add the own-UID exclusion the current code omits entirely, and share the chain-management code with the root engine.

   *`PreflightController.kt`:* the `capabilities.isRootUsable` / `isShizukuUsable` branches must **verify before selecting** — chain install and `SO_ORIGINAL_DST` availability are probed, and on failure the resolver falls through to `VPN_TUN` with a recorded reason. Today any device with `su` (including most emulators) is steered into a tier that answers `400 Bad Request` to everything.

   *Frida consolidation:* `FridaCaptureEngine.kt` is retired. It is wired into nothing, hard-codes `/data/data/com.kailashhh.requestr/files/frida_scripts` instead of `context.filesDir`, and shells out to a `frida` CLI that does not exist on-device. `com.example.runtime.frida.FridaClient` (which already has an honest state machine and an armed-gate latch) becomes the single Frida entry point, and `CaptureMethod.ROOT_FRIDA` is documented as "unpinning assist for another tier", not a transport of its own.

   *Tests:* `RootIptablesEngineTest` with an injected command runner — asserts exact chain create/jump/insert/flush command sequences, idempotent re-install, symmetric teardown (every installed rule removed), `ip6tables` absence tolerated, QUIC reject rule follows `QuicPolicy`, and `verify()` failure triggers rollback. `ShizukuUserServiceTest` with a fake binder. `PreflightControllerTierSelectionTest` proving `su`-present-but-unverifiable falls back to `VPN_TUN`. `SoOriginalDstTest` as a Rust unit test over a fabricated socket where the platform permits, plus a Kotlin test that the JNI absence path degrades to SNI-only.

9. **[parallel] Trust diagnostics**

   *New `app/src/main/java/com/example/interception/trust/TrustDiagnostics.kt`* producing a structured report, with `CaTrustGate` kept as the fast boolean gate in front of it (its 30 s TTL cache, process-wide epoch and `invalidateAll()` wiring into `CertificateAuthority.regenerate` / `CaP12Exchange.importP12` / `CaWhitelistService.promoteToSystem` all stay):
   - CA presence per store (user, system, and the API 34+ Conscrypt APEX location) with the actual matching alias and fingerprint, so a stale or duplicate install is visible.
   - Per-target-app `networkSecurityConfig` inspection (does the app trust user CAs at all) using the existing `QUERY_ALL_PACKAGES` capability — this is the single most common cause of "MITM silently fails for one app".
   - Pinning-failure history from `ConnectControl.PinningFailureRegistry`, surfaced per host with the last failure reason from `TlsFailureClassifier`.
   - mTLS-required detection: an upstream `certificate_required` alert maps to a named "this host wants a client certificate" state pointing at the task 5 per-domain configuration.
   - Chromium/Chrome trust polarity and the QUIC caveat stated as facts, not warnings buried in logs.
   - A cost note honoured in code: the probe reads every file under the system CA directory and loads `AndroidCAStore`, so the full report is on-demand and cached; the boolean gate stays on the hot path.

   *`CaptureMethodWatcher`:* extend the health check so `DEGRADED` carries a reason string sourced from `TrustDiagnostics` or the bridge's `last_error_kind`, instead of an unexplained status.

   *Tests:* `TrustDiagnosticsTest` with fake stores and package fixtures (per-store presence, alias mismatch, app-opts-out-of-user-CAs, pinning history aggregation, mTLS-required mapping, cache behaviour), plus `CaTrustGateTest` extended to prove the fast gate and the full report never disagree on the boolean.

10. **[after 1-9] Test harness, validation matrix and acceptance gates**

    *Instrumentation harness (new, `app/src/androidTest/java/com/example/interception/`):* today `androidTest` contains only `ExampleInstrumentedTest.kt`, so there is no on-device coverage of any of this. Add: a tun-establish test (skipped with a recorded reason when `VpnService.prepare` cannot be granted), a bridge start/stop/readiness test, an end-to-end capture test driving a known plaintext and TLS endpoint through the gateway and asserting persisted rows, a QUIC `BLOCK` test asserting HTTP/2 fallback, a network-handoff test toggling Wi-Fi/cellular, and a root-chain test guarded on `su`.

    *Validation matrix.* Run on: a physical device (primary, API 33+ and one API 24–28 device), an AVD in proxy mode (API 30 and 34), and Redroid. Per `/memory/testing/requestr/test-plan-detailed.md`, Redroid has no kernel VPN support and reporting Root/Shizuku/VPN as unavailable there is *correct behaviour*, so: **a release is never blocked on emulator or Redroid VPN capture.** Emulator gates cover only what the emulator can honestly exercise (gateway classification, certificate issuance, policy decisions, proxy-mode capture).

    *Acceptance gates.*
    - `./gradlew :app:testDebugUnitTest` and `cargo test` in `native/tun2proxy-bridge` fully green, with the 204 existing JVM tests and 34 Rust tests still passing.
    - Native-bridge mode: DNS resolves, plaintext HTTP is captured, TLS is decrypted for a CA-trusting app, and each captured row carries a UID with its attribution source.
    - No `runBlocking` remains on any accept/relay/persist path (enforced by a source-level check in CI, since this class of defect keeps returning).
    - No raw `Thread` construction in the relay path; thread count returns to baseline within 5 s of `stopCapture`.
    - Concurrent-host certificate issuance shows no serialization; every leaf carries EKU serverAuth, SKI, AKI and ≤90-day validity; no two leaves share a serial.
    - QUIC UDP/443 in `BLOCK` mode produces a recorded diagnostic and an observed client fallback to HTTP/2; `TEE` mode produces a passive decode row.
    - Wi-Fi↔LTE handoff keeps capture alive with exactly one re-establish and no dropped session state.
    - `specialUse` remains the VpnService foreground-service type; `startForeground` happens before any suspending read; no `ForegroundServiceDidNotStartInTimeException` across 20 start/stop cycles.
    - Root/Shizuku either pass their verified-capability probe and capture real traffic, or are reported unavailable with a reason — never selected while broken.

---

### Testing

**Focused.** Each task above lists its own unit suites. The highest-value new suites are: `Tun2ProxyConfigTest` plus the Rust config tests (bridge contract), `LocalGatewayDispatchTest` and `Socks5HandlerTest` (the new front door, including `+info` parsing), `ConnectionStateMachineTest` and `RelayPoolTest` (concurrency correctness and the terminal-state-once guarantee), `CertificateEnginePolicyTest` and `LeafHardeningTest` (the SNI-before-issuance fix and one hardened issuer), `QuicPolicyTest` and `UdpPolicyRouterTest` (block-or-tee), `UidAttributorTest` (native-bridge attribution), `RootIptablesEngineTest` (exact command sequences and symmetric teardown), and `TrustDiagnosticsTest`.

**Integration.** Three JVM-level integration tests wire the gateway to a loopback origin server: (1) SOCKS5 + `+info` → gateway → MITM → `TransactionSink` → in-memory Room, asserting the row's host, UID and attribution source; (2) transparent TLS with no SNI, asserting the sniffed-profile leaf and a successful client handshake; (3) UDP ASSOCIATE for port 443 under each `QuicPolicy` mode, asserting the SOCKS5 reply bytes and the decoder side effects. Then the instrumentation suite and the device matrix from task 10.

**Regression watch.** The following existing behaviours have documented bug-fix history and must be re-asserted, not re-broken: the bridge readiness handshake, `ThreadDoneGuard` drop order, `RelayFlow.mapKey`, the `stopCapture` → `cleanup()` → `markStopped` ordering, `CaptureMethod.engineTier()`/`engineLifecycleOwner()` single-source derivation, `PreflightController.stopCapture` routing by lifecycle owner, `HostSanGenerator`'s PSL wildcard rules, and `DeleteStrayCargoNdkArtifacts`.
