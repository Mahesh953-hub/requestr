# HTTPS Interception Engine Report — requestr vs. chucker

**Type:** Read-only technical audit / judgement document. No application code was written or
changed to produce this report — it is the output of a source-code investigation of both
repositories, landed here as documentation only.

**Scope:** how each project actually intercepts HTTPS traffic, decrypts it, and prints
plaintext/TLS/HTTP-3 information — with exact file/class citations — followed by a neutral
verdict, a list of aspirational/incorrect claims found in this repo's own code comments, and
an explicit answer to "if the engineering is this solid, why does decryption still fail?"

---

## 0. Method

Two independent, read-only code investigations were performed:

- **chucker** (`Mahesh953-hub/chucker`) — an in-app OkHttp debug-inspector library.
- **requestr** (this repository) — a system-wide HTTPS interception app with VPN, root/Shizuku
  iptables, MITM proxy, QUIC, and browser-CDP surfaces.

Every claim below is traceable to a specific file and, where useful, a specific class/function
name in the respective repository at the time of the audit. Nothing here is speculative; where
something could not be confirmed from the code, it is stated as such rather than guessed.

---

## 1. Executive summary

chucker and requestr are not solving the same problem, so this is not a fair fight on paper —
but it is a fair fight on **delivered correctness**. chucker claims a small, easy-to-fully-solve
scope (inspect *your own app's* already-decrypted OkHttp traffic) and executes essentially all
of it correctly. requestr claims a much larger, genuinely harder scope (intercept *any app's*
HTTPS traffic system-wide, decrypt it, and parse it structurally) — and while several of its
lower-level engineering components (a hand-rolled userspace TCP/IP stack, a hand-rolled
QUIC/TLS-1.3 AEAD implementation, dynamic per-host certificate minting) are real and
technically impressive, the **integration between components is where this repository
currently falls short**: multiple code paths that this repo's own doc comments describe as
working are, on direct inspection, either unwired, disconnected, or permanently disabled by a
flag that is never flipped.

This document names those gaps explicitly, compares the two projects' engines stage by stage,
and gives a plain answer to "why does decryption still fail here, when the CA-minting and
TLS-termination code is genuinely solid?"

---

## 2. Reference: how chucker intercepts, decrypts, and displays HTTPS

chucker (`Mahesh953-hub/chucker`) performs **zero decryption of its own**. It is a plain
`okhttp3.Interceptor` (`library/src/main/kotlin/com/chuckerteam/chucker/api/ChuckerInterceptor.kt`)
that the host app manually registers on its own `OkHttpClient.Builder` — either as an
application interceptor (before OkHttp's internal `ConnectInterceptor`) or a network
interceptor (between `ConnectInterceptor` and `CallServerInterceptor`).

Key facts, confirmed by direct code read of the chucker repository:

- `ConnectInterceptor` is the OkHttp stage that opens the TCP socket and performs the *entire*
  TLS handshake, wrapping it in an `SSLSocket`. By the time `ChuckerInterceptor.intercept()`
  runs — in either registration position — OkHttp has already finished 100% of the TLS work.
  Chucker never sees a socket or a byte of ciphertext; it reads OkHttp's already-parsed
  `Request`/`Response` Kotlin objects.
- An exhaustive grep of chucker's library module for `peerCertificate`, `X509Certificate`,
  `TrustManager`, `SSLSocketFactory`, `SSLContext`, `VpnService`, and raw `Socket(` construction
  returns **zero results**. Its `AndroidManifest.xml` requests only `WAKE_LOCK` and
  `POST_NOTIFICATIONS` — no `INTERNET`, no `BIND_VPN_SERVICE`, no certificate/keystore
  permission of any kind.
- Request bodies are captured via a second, independent `RequestBody.writeTo()` call into
  chucker's own buffer (`RequestProcessor.kt`) — never consuming the stream OkHttp itself will
  write. Response bodies are captured via a passive `TeeSource` (`TeeSource.kt`) that copies
  bytes as the app itself reads them — never pre-buffering, never blocking real streaming.
- TLS metadata shown is exactly two scalar fields lifted from OkHttp's own already-computed
  `Response.handshake` object: `tlsVersion` and `cipherSuite` (`ResponseProcessor.kt:64-67`).
  There is **no certificate chain viewer, no subject/issuer/expiry, no pinning-mismatch
  detail** anywhere in chucker — `isSsl` itself is a derived boolean from the URL scheme
  (`scheme.equals("https")`), not a live socket check.
- Everything captured is persisted to a Room database (`chucker.db`) inside the host app's own
  storage and shown via an in-app notification + Activity UI — a UI that lives inside the host
  app's own process, not a separate device-wide app.

**What this architecture makes chucker structurally incapable of:** seeing traffic from any
other app; seeing traffic from an `OkHttpClient` instance that doesn't have the interceptor
added; seeing non-OkHttp stacks (`HttpURLConnection`, raw sockets, unwrapped Cronet, gRPC
channels); or decrypting anything, ever — because there is nothing left for it to decrypt by
the time its code runs.

**Why this matters for requestr:** chucker's entire "engine" is the *last step only* of the
pipeline this repository is trying to build end-to-end (capture → decrypt → forward → parse →
display). Chucker gets that last step for free because a mature, heavily-tested third-party
library (OkHttp) already did every step before it. requestr has no such shortcut — it has to
build and correctly wire *every* stage itself, for arbitrary third-party apps it does not
control the source of. That is the real reason requestr's problem is categorically harder, and
also the real reason the specific gaps below matter so much: there is no OkHttp underneath
this repository's own capture path to quietly absorb the mistakes.

---

## 3. How requestr actually intercepts, decrypts, and displays HTTPS (this repo)

requestr implements **four distinct interception surfaces**, gated by
`PreflightController.run` (`app/src/main/java/com/example/interception/PreflightController.kt`):

| Tier (`ActiveTier`) | Mechanism | Sees other apps' traffic? | Decrypts TLS? |
|---|---|---|---|
| `ROOT` (`ROOT_IPTABLES`) | `iptables` NAT `REDIRECT` of tcp 80/443 (+udp 443) to local `MitmProxyEngine` | Yes, system-wide, transparently | Yes |
| `SHIZUKU` (`SHIZUKU_TUN`) | Same iptables rules via Shizuku's elevated shell | Yes, when Shizuku is truly available | Yes |
| `VPN` (`VPN_TUN`) | Real `VpnService` TUN + hand-written userspace TCP/IP relay (`TunRelay`) | Yes, all routed apps' packets enter the tun | **No** — passive metadata only |
| `PROXY` (`MITM_PROXY`/`HTTP_PROXY`) | Local `ServerSocket` HTTP/CONNECT proxy | Only if manually configured as the device/app's HTTP proxy | Yes if CA installed; opaque tunnel otherwise |

Plus two narrower, non-system-wide surfaces relevant to the chucker comparison:
- `OkHttpProxyInterceptor` — an `okhttp3.Interceptor` **architecturally identical to chucker's
  entire approach**, but used only for requestr's *own* outbound calls (AI-chat, "ApiTestr",
  WebView/CDP fetch adapters) — not for intercepting other apps.
- A Chrome DevTools Protocol bridge (`BrowserBridgeManager`/`CdpClient`) that asks a Chromium
  browser to report its *own* already-observed network activity over its remote-debugging
  WebSocket — not wire-level interception at all.

### 3.1 The MITM engine (`MitmProxyEngine.kt`, ~900 lines)

Reached only when routed to it via `iptables REDIRECT` (root/Shizuku) or manual proxy
configuration — there is **no automatic wiring** from the VPN tier into the MITM proxy; grepping
`TunRelay.kt`/`PacketPump.kt`/`RequestrVpnService.kt` for any MITM/proxy-port reference returns
nothing.

Architecture, per the engine's own doc comment:
`Client → TLS(per-host-cert) → requestr inspects plaintext → TLS(real) → Server`.

- On a CONNECT it is permitted to decrypt (`ConnectControl.evaluate` → `ForceMitm`), it answers
  `HTTP/1.1 200 Connection Established`, mints/reuses a per-host leaf certificate
  (`generateHostCert`, RSA-2048 via BouncyCastle, `CN=$host`, SAN `dNSName=host`, signed
  `SHA256withRSA` by requestr's own Root CA), builds a server-mode `SSLSocket` via standard
  JSSE (`SSLContext.getInstance("TLS")` — Android's Conscrypt provider actually performs the
  cryptographic handshake; requestr supplies certs/trust managers into that API, it does not
  implement TLS itself), and calls `startHandshake()`.
- It then opens a **second, independent** TLS connection upstream to the real server, using a
  custom `TrustAllCerts` `X509TrustManager` (because requestr, not the original client, is now
  the one validating the real server's certificate on this leg).
- Decrypted bytes are handed to `HttpParser.tryParseRequest`/`tryParseResponse` — **HTTP/1.x
  only**.

### 3.2 The Root CA and per-host leaf certificates (`CertificateAuthority.kt`, 407 lines)

- Generates a real, on-device RSA-2048 self-signed Root CA (`CN=requestr Root CA`), signed
  `SHA256withRSA` via BouncyCastle (explicitly installed at provider position 1, because
  Android's stripped built-in BC copy "produces signatures that cannot be verified by standard
  TLS clients," per the code's own comment).
- Installation is via the standard `android.security.KeyChain.createInstallIntent()` flow (user
  must explicitly accept in system Settings), or — on rooted devices — a direct write into the
  system trust store (`installToSystem()`, via `su -c` remount + `<subjectHash>.0` file +
  SELinux context fix).
- The CA private key is stored on disk as a PKCS12 file protected by a per-install
  `SecureRandom` password held in `AndroidKeyStore`-backed AES-256-GCM — a **fix** for a
  previously shipped version that reused the same literal password (`"requestr-ca-local"`) as
  the public, documented export password, which meant possessing the `.p12` file alone was
  enough to extract the private key.

### 3.3 The VPN engine (`RequestrVpnService.kt` + `TunRelay.kt`)

- A genuine `VpnService` subclass establishes a TUN device (IPv4 + IPv6, MTU 1500) and pumps
  raw packets through `PacketPump`, which feeds them to `CaptureEngine.ingest()` for parsing
  *and* to `TunRelay.offer()` — a hand-written userspace TCP/UDP relay (`java.nio.channels`,
  no bundled library — no Netty, no gVisor, no tun2socks) that opens real upstream sockets so
  routed apps' traffic actually reaches the internet.
- This tier **never terminates TLS**. It can only extract what's visible without keys: SNI,
  offered cipher suites, ALPN, and a JA3 hash, via a manual byte-level ClientHello parser
  (`TlsDecoder`), whose own KDoc says plainly: *"What this does NOT do: decrypt application
  data. That requires a full MITM proxy... built separately."*
- Its own foreground-service notification text is explicit about this limit: **"VPN active —
  traffic is relayed; HTTPS bodies need proxy mode."**

### 3.4 HTTP/3 (QUIC)

- `QuicCrypto.kt` correctly, and by its own documentation, only decrypts the QUIC **Initial**
  packet without any key material — this is public-key-free by TLS 1.3/QUIC design (RFC 9001
  §5.2: Initial keys are derived from public information "to encumber decryption by observers,
  not to provide confidentiality," the same technique Wireshark's own QUIC dissector uses).
  This recovers SNI, ALPN, and the negotiated cipher suite.
- Actual HTTP/3 **request/response bodies** require Handshake/1-RTT keys, which by TLS 1.3
  design require one endpoint's private key material. The code's only source for that material
  is `TlsKeyLogRegistry.lookup(...)`, populated only by an in-process SDK hook or a
  manually-imported NSS keylog file — **not** by anything the MITM engine itself produces (see
  §5 below).

---

## 4. Engine-by-engine comparison: VPN → capture → decrypt → forward → print plaintext

chucker has no VPN stage at all — it skips capture/decrypt/forward entirely, because OkHttp
already performed all three stages before chucker's own code ever runs.

| Stage | chucker | requestr (MITM tier) | requestr (VPN tier) |
|---|---|---|---|
| Traffic acquisition | N/A — in-process, opt-in per `OkHttpClient` | Reached only via `iptables REDIRECT` (root/Shizuku) or manual proxy config | Real `VpnService` TUN, all routed apps' packets land here automatically |
| Data capture | Reads already-parsed `Request`/`Response` objects OkHttp handed it | `ServerSocket` accepts CONNECT, opens a per-connection socket | `PacketPump` reads raw IP packets off the tun fd |
| Decryption | None needed — OkHttp already did it | Real: dynamically-minted per-host leaf cert + JSSE handshake (client leg), separate `TrustAllCerts` JSSE handshake (server leg) | None — only passive ClientHello/record-header parsing, no key material |
| Send ahead | N/A — same OkHttp call proceeds normally | New upstream `Socket`/`SSLSocket` to the real host, plaintext relayed both ways after inspection | `TunRelay` relays raw packets via real `SocketChannel`/`DatagramChannel` — ciphertext forwarded untouched, before/without any decryption |
| Prints plaintext | Directly renders OkHttp's already-decoded `Request`/`Response` objects | Only for HTTP/1.x (`HttpParser`); HTTP/2 and WebSocket are decrypted at the socket level but never turned into a structured row (§5); QUIC bodies never plaintext without external keys | Only SNI/cipher-list/JA3/ALPN metadata; body is always an honestly-labeled "undecoded" hex-ciphertext preview |

---

## 5. Dishonest / aspirational promises found in this repo's own code comments

The following are places where **this repository's own doc comments and KDoc claim a working
behavior that the calling code does not actually implement.** These are not external
criticisms invented for this report — each one is a direct contradiction between a comment and
the code sitting next to it, confirmed by grep of the entire `app/src/main/java` tree.

1. **`CaptureEngine`'s "let MITM-decrypted streams through" comment is false for the entire
   life of this codebase.**
   ```kotlin
   if (stream.isTls && !stream.decryptedByMitm) {
       persistTlsFailureIfNeeded(stream)
       return // allow MITM-decrypted streams through
   }
   ```
   `TcpStream.decryptedByMitm` (`interception/tcp/TcpStream.kt`) is declared, checked here, and
   read once more for a `wasDecrypted` flag — and is **never set to `true` anywhere in the
   entire codebase.** `MitmProxyEngine.kt` has no reference to `CaptureEngine`, `TcpReassembler`,
   or `TcpStream` at all. The comment describes a bridge between the two engines that simply
   does not exist in code. Practical effect: the fully-built HTTP/2/HPACK/gRPC/WebSocket parser
   inside `CaptureEngine` can never fire on any TLS-wrapped traffic, ever — which is nearly all
   real HTTP/2 and WebSocket traffic today.

2. **`TlsKeyLogRegistry`'s KDoc claims a feature `MitmProxyEngine` does not implement.** Its
   own doc comment states: *"the MITM leg (`MitmProxyEngine`), which terminates TLS itself,
   registers secrets for the connections it decrypts."* Grepping every call site of
   `TlsKeyLogRegistry.record(` across `app/src/main/java` and `sdk/src/main/java` finds exactly
   two call sites: a manual NSS-keylog import function (`LocalApiServer.importNssKeyLog`) and
   an in-process-only SDK hook (`SslSecretExtractor.kt`). **Zero calls from `MitmProxyEngine` or
   anything it calls.** This directly undercuts the HTTP/3 story: the one engine that actually
   terminates TLS never feeds its secrets to the QUIC decoder, so QUIC Handshake/1-RTT decoding
   is effectively dead for any traffic the MITM engine decrypts.

3. **The UDP/443 `iptables` redirect rule for QUIC has no receiving service.**
   `RootIptablesEngine` installs `-p udp --dport 443 -j REDIRECT --to-port <mitmPort>`, but
   `MitmProxyEngine` only opens a **TCP** `ServerSocket`. No UDP listener exists anywhere in the
   codebase bound to that port. The rule is installed as if QUIC/UDP interception on the root
   tier works; nothing in the code actually consumes the redirected traffic.

4. **Shizuku's non-root fallback path silently cannot do what it's asked to do.**
   `ShizukuTunEngine.executeShizuku()` tries `Shizuku.newProcess(...)` first; if that reflective
   method is missing on the installed Shizuku version, it falls back to
   `Runtime.getRuntime().exec(...)` run as requestr's own **non-elevated** process — which
   cannot modify `iptables` without root. The fallback silently proceeds as if it might still
   work, with no code-level guarantee it actually will on the affected Shizuku versions.

5. **The Frida pinning-bypass path looks complete in its class list but is not wired for real
   use.** `FridaClient` is hardcoded to target `com.android.shell`, not an arbitrary app, and
   its script only hooks a URL-logging call (not a pinning bypass) while misusing the
   `--codeshare` CLI flag (which expects a published CodeShare project slug, not inline JS).
   `FridaCaptureEngine.builtinScript("trustmanager")` defines a `TrustAllCerts` class but the
   script never hooks `SSLContext.init` to actually install it into a live handshake — it
   registers a trust-all manager class without wiring it in. No UI call site could be found
   that connects this to a real target-PID selector. The feature that would let the MITM engine
   actually work against apps that pin certificates is, on direct inspection, a non-functional
   prototype, not a working bypass.

**What is *not* on this list, for fairness:** several "failure"/"undecoded" states in this
repository are explicitly, honestly derived from a real absence of signal rather than
fabricated — e.g. `RelayStats`' comment on a metric with "no drop site in this relay today, so
it is deliberately absent rather than shown as a fabricated zero," `TlsFailureClassifier`'s
refusal to invent a verdict for a stream that never sent a ClientHello, and
`QuicLiveSummary.keyMaterialMissing`'s doc comment stating it is "true only when protected
packets were genuinely observed with no secret available for them." Those are legitimate,
disclosed limitations, not dishonest claims, and are called out here specifically so the five
items above are not read as "the whole codebase lies" — they don't; these five specific claims
just don't match the code sitting next to them.

---

## 6. "If everything is this solid, why does decryption still fail?" — here is why

The cryptography and low-level networking in this repository are, on direct inspection, mostly
correct: RSA-2048 CA generation with sane extensions, correct SNI-based per-host leaf minting,
correct use of JSSE for the actual handshake, a real userspace TCP/IP relay, and a QUIC Initial
packet decryptor that correctly follows RFC 9001. None of that is where the failures come from.
The failures come from four specific integration gaps, all confirmed above:

1. **The two engines that would need to cooperate — the VPN capture/parse pipeline and the MITM
   decrypt pipeline — are not connected.** `decryptedByMitm` never becomes `true`. This alone
   means HTTP/2 and WebSocket traffic, once decrypted by the MITM engine, is never turned into a
   readable row by anything in this codebase — not because decryption failed, but because the
   parser that *would* read it was built for a handoff that doesn't exist.
2. **The MITM engine's own HTTP parser only understands HTTP/1.x.** Anything else — HTTP/2
   frames, WebSocket frames — fails `couldBecomeHttpRequest`/`couldBecomeHttpResponse` and falls
   back to a blind raw-byte relay for the rest of that connection. The bytes were genuinely
   decrypted; they are simply never parsed into anything the UI can show.
3. **Automatic, no-configuration decryption only exists on root/Shizuku.** The plain `PROXY`
   tier requires the user to manually point their device or a specific app's HTTP proxy setting
   at requestr — there is no automatic redirect into `MitmProxyEngine` on that tier. The `VPN`
   tier, the one that needs zero special device permissions, is architecturally incapable of
   decrypting anything at all, by design. So on the majority of real-world devices (non-rooted,
   no Shizuku), decryption either never triggers automatically, or requires the user to do OS-level
   proxy configuration most users don't know how to do — very different from chucker's "add one
   line to `OkHttpClient.Builder`."
4. **Pinning and custom trust managers in the *target* app defeat the MITM leaf outright, and
   the bypass feature meant to address that is not actually wired up** (§5, item 5). Any modern
   app using `CertificatePinner`, a Cronet/BoringSSL stack, or a Flutter `dart:io`
   `badCertificateCallback` will simply reject requestr's leaf certificate and record a
   handshake failure — a correct and expected outcome of MITM interception in general, but one
   this repository's Frida-based mitigation does not currently solve in practice.

None of these four gaps are cryptography bugs. They are wiring gaps: components that were each
built to a reasonable standard individually, but never actually connected to each other, with
doc comments in some cases still describing the connection as if it existed.

---

## 7. Neutral verdict

- **Correctness of stated scope:** chucker wins. Its scope is small and it delivers essentially
  all of it, because it needs zero decryption logic of its own — it borrows OkHttp's,
  unconditionally correct by construction.
- **Ambition and engineering depth:** requestr wins by a wide margin. VPN-based system-wide
  capture, a self-issued CA with dynamic per-host leaf minting, a hand-rolled QUIC/TLS-1.3
  crypto implementation, JA3/stack fingerprinting, and a rule engine are a categorically harder
  and larger undertaking than anything chucker attempts.
- **Delivered depth vs. claimed depth:** this is where requestr currently loses ground. Its
  actual working decode coverage on modern real-world traffic (HTTP/2, WebSocket, HTTP/3
  bodies, pinned apps) is materially smaller than the surface area of code written for it,
  because of the specific integration gaps in §5–§6.

**In one line:** chucker is a small, fully-finished tool that cannot fail at decryption because
it never attempts any; requestr is a much larger, more ambitious tool whose cryptography and
low-level networking are real and mostly correct, but whose own engines were never fully wired
together — so several of its most-advertised capabilities do not currently function end to end.

## 8. The roast

**chucker** is an OkHttp interceptor with a Room database and a notification bar. It is not
clever, and it doesn't need to be — it borrows OkHttp's TLS stack the way a good student copies
the answer key: instantly correct, zero risk of ever getting the cryptography wrong, because it
never touches any. Its worst sins are a copy-pasted method-name mismatch inherited from
upstream and two undertested internal classes. It ships exactly what it promises. Boring.
Correct. Done.

**requestr** built an honestly impressive amount of hard infrastructure — a hand-rolled
userspace TCP/IP relay over raw NIO selectors, a from-scratch QUIC/TLS-1.3 AEAD implementation
straight from RFC 9001 primitives, dynamic per-hostname certificate minting off a self-signed
CA — and then left the plumbing disconnected at the exact seams that matter most. It wrote a
full HTTP/2/HPACK/gRPC/WebSocket parser and then wired it to a boolean flag that is permanently
`false`, forever. It built a JA3 fingerprinting feature and then let its own MITM path
structurally destroy the one signal that feature needs (JSSE consumes the raw ClientHello
before requestr's code can read it — `ja3Hash` is `null` on every MITM-decrypted session). It
documented, in its own KDoc, a key-log auto-registration behavior that the code never actually
calls. It installed an `iptables` rule to redirect QUIC/UDP traffic to a proxy port that has no
UDP listener behind it. And its answer to certificate pinning — the single feature that would
make the MITM tier actually useful against any serious modern app — is a Frida script
hardcoded to hook the Android shell using a CLI flag that doesn't do what the surrounding code
seems to think it does. It's a beautifully built glass elevator with no motor connected:
genuinely credible cryptography and systems engineering, undermined by unfinished integration
between its own components.

---

## 9. Source citations

- chucker: `library/src/main/kotlin/com/chuckerteam/chucker/api/ChuckerInterceptor.kt`,
  `internal/support/RequestProcessor.kt`, `internal/support/ResponseProcessor.kt`,
  `internal/support/TeeSource.kt`, `api/ChuckerCollector.kt`, `internal/data/entity/HttpTransaction.kt`,
  `AndroidManifest.xml` (library module).
- requestr (this repo): `app/src/main/java/com/example/interception/PreflightController.kt`,
  `MitmProxyEngine.kt`, `CertificateAuthority.kt`, `RequestrVpnService.kt`,
  `tun/TunRelay.kt`, `CaptureEngine.kt`, `tcp/TcpStream.kt`, `tls/TlsDecoder.kt`,
  `tls/TlsKeyLogRegistry.kt`, `tls/TlsFailureClassifier.kt`, `RootIptablesEngine.kt`,
  `ShizukuTunEngine.kt`, `runtime/frida/FridaClient.kt`, `FridaCaptureEngine.kt`,
  `OkHttpProxyInterceptor.kt`, `browser/BrowserBridgeManager.kt`, `browser/CdpClient.kt`,
  `quic/QuicCrypto.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.
