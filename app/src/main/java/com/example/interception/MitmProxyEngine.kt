package com.example.interception

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.data.prefs.SettingsDataStore
import com.example.interception.grpc.GrpcDetector
import com.example.interception.grpc.ProtobufDecoder
import com.example.interception.http.GrpcEvent
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.Http2Parser
import com.example.interception.http.Http2Session
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.http.WebSocketParser
import com.example.interception.http.WsMessageAssembler
import com.example.interception.tls.ClientHelloCapture
import com.example.interception.tls.TlsClientHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * Real MITM proxy with per-host TLS certificate generation.
 *
 * Uses the existing [CertificateAuthority] root CA to dynamically issue
 * certificates for each host the client visits. TLS is terminated on the
 * client side (so requestr sees plaintext) and re-established upstream
 * (so the real server accepts the connection).
 *
 * Whether a given CONNECT actually gets MITM'd is decided by [ConnectControl] — see
 * [handleClient]. Only a `FORCE_MITM` disposition reaches the cert-generation / TLS-termination
 * code below; `AllowOpaque`/`Redirect` tunnel via [HttpProxyShared.handleConnectAttempt] just
 * like the non-MITM engines, and `Block` is answered directly. This engine being *capable* of
 * MITM does not mean every CONNECT it sees is decrypted.
 *
 * Architecture (for the FORCE_MITM case):
 *   Client → TLS(per-host-cert) → requestr inspects plaintext → TLS(real) → Server
 *
 * After the client-side TLS handshake completes, the decrypted stream is NOT
 * just relayed raw: each direction runs through an inspecting loop that parses
 * HTTP/1.x messages with [HttpParser], enforces intercept rules on the parsed
 * content (BLOCK / REDIRECT / MODIFY_REQUEST / MODIFY_RESPONSE), forwards the
 * (possibly modified) bytes, and emits a [CapturedTrafficEntity] for every
 * request/response pair via [onDecryptedTransaction]. Non-HTTP/1.x traffic
 * (HTTP/2 preface, WebSocket frames after upgrade, ...) falls back to a blind
 * relay for the remainder of that connection so it is never broken.
 *
 * Item 1: [emitTransaction]/[emitWsFrame]/[emitGrpcMessage] are only ever reached via the
 * `FORCE_MITM` disposition above (`AllowOpaque`/`Redirect`/`Block` never decrypt, so they never
 * emit a [CapturedTrafficEntity] at all) -- every row this class persists is genuinely
 * `captureMethod = CaptureRowMethod.MITM_PROXY`, unconditionally.
 */
class MitmProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /**
     * Invoked once per fully decrypted HTTP transaction (request + response
     * pair). Wired by the caller to persist into Room; invoked on the engine's
     * relay threads, so implementations must be thread-safe. Exceptions thrown
     * by the callback are caught and logged so they can never kill a relay.
     */
    private val onDecryptedTransaction: ((CapturedTrafficEntity) -> Unit)? = null,
    /**
     * Optional JSSE provider name for the client-facing TLS context (e.g. "SunJSSE").
     * Defaults to null so the platform's default provider (Conscrypt on Android) is used.
     * Exposed as a test seam so Robolectric/JVM tests can avoid Conscrypt's limitation
     * that prevents startHandshake() on a socket that already exchanged plain HTTP data.
     */
    private val sslContextProvider: String? = null,
    /**
     * C12: gates `FORCE_MITM` on requestr's CA actually being trusted client-side -- see
     * [CaTrustGate]'s KDoc and the `ForceMitm` branch of [handleClient]. Exposed as a
     * constructor param (rather than a hardcoded `CaTrustGate()`) purely as a test seam, so
     * tests can inject a fake/deterministic probe instead of touching the real KeyStore.
     */
    private val caTrustGate: CaTrustGate = CaTrustGate(),
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)
    private val sessionId = AtomicLong(System.currentTimeMillis())
    /**
     * §3: bounded/expiring LRU (see [ExpiringLruCertCache]) instead of an unbounded map -- a
     * long-running capture session that MITMs many distinct hosts must not retain every
     * generated (RSA-2048 keypair + cert) forever.
     */
    private val certCache = ExpiringLruCertCache()

    val port: Int get() = serverSocket?.localPort ?: 0

    companion object {
        private const val TAG = "MitmProxy"
        private const val BUFFER_SIZE = 8192
        /** Bytes buffered without a successful parse before falling back to blind relay. */
        private const val MAX_UNPARSED_BUFFER = 1024 * 1024
        private val HTTP_METHODS = listOf(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE",
        )
    }

    fun start(context: Context, port: Int = 0): Int {
        if (running.get()) return this.port

        // Pre-warm cert cache with CA material
        kotlinx.coroutines.runBlocking {
            CertificateAuthority.getOrCreate(context)
        }

        serverSocket = ServerSocket(port)
        running.set(true)

        InterceptionStateRepository.markActive(
            sessionId = sessionId.get(), tier = ActiveTier.PROXY,
            port = serverSocket!!.localPort, method = CaptureMethod.MITM_PROXY,
        )

        acceptJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(context, client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept: ${e.message}")
                }
            }
        }
        return serverSocket!!.localPort
    }

    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "MITM proxy stopped")
    }

    private suspend fun handleClient(context: Context, client: Socket) {
        try {
            runCatching { client.setTcpNoDelay(true) }
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                return
            }

            // Drain the rest of the CONNECT request (headers + terminating empty line) so
            // that the bytes remaining on the socket are exactly the client's TLS handshake.
            // Without this, leftover headers are interpreted as the TLS ClientHello and the
            // handshake fails with "wrong version number" / "Unable to parse TLS packet header".
            skipHeaders(input)

            val target = parts[1]
            val (host, port) = parseHostPort(target, 443)

            val db = RequestrDatabase.get(context)
            val disposition = ConnectControl.evaluate(db, host, port)
            // §3 (C2) -- read fresh per CONNECT for the non-MITM (opaque tunnel) paths below.
            // The MITM path re-reads this itself just before dialing upstream (see
            // `networkCondition` a few lines further down, passed into LazyUpstreamConnection).
            val opaqueNetworkCondition = SettingsDataStore.get(context).networkCondition.first()

            when (disposition) {
                is ConnectControl.Disposition.Block -> {
                    Log.i(TAG, "BLOCKED by rule: $host")
                    output.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
                    output.flush()
                    persistConnectAttempt(context, client, db, host, port, "BLOCKED", failureReason = disposition.reason)
                    return
                }
                is ConnectControl.Disposition.Redirect -> {
                    // Rule redirects this CONNECT to a different upstream — tunnel opaquely to
                    // the redirect target instead of the original (no MITM either way here).
                    // HttpProxyShared.handleConnectAttempt writes the 200 response itself.
                    HttpProxyShared.handleConnectAttempt(
                        client, disposition.target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "REDIRECTED",
                            redirectTarget = disposition.target, bytesToUpstream = sent, bytesToClient = recv,
                        )
                    }
                    return
                }
                is ConnectControl.Disposition.AllowOpaque -> {
                    // No FORCE_MITM rule matched — tunnel opaquely rather than terminate TLS,
                    // even though this engine is capable of MITM.
                    // HttpProxyShared.handleConnectAttempt writes the 200 response itself.
                    HttpProxyShared.handleConnectAttempt(
                        client, target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "ALLOWED_OPAQUE",
                            bytesToUpstream = sent, bytesToClient = recv,
                        )
                    }
                    return
                }
                is ConnectControl.Disposition.ForceMitm -> {
                    // C12: a FORCE_MITM rule matching is necessary but not sufficient -- if
                    // requestr's own CA isn't trusted client-side (never installed, or removed
                    // since), terminating TLS with our per-host leaf would just break the
                    // client-facing handshake outright. Degrade to the same opaque relay path
                    // AllowOpaque uses, with a descriptive failureReason, rather than failing
                    // silently or ignoring the rule without explanation.
                    if (shouldDegradeForUntrustedCa(disposition, caTrustGate.isTrusted())) {
                        Log.i(
                            TAG,
                            "FORCE_MITM rule matched for $host but requestr's CA is not trusted " +
                                "client-side -- degrading to raw relay",
                        )
                        HttpProxyShared.handleConnectAttempt(
                            client, target, ConnectControl.Disposition.AllowOpaque, output,
                            networkCondition = opaqueNetworkCondition,
                        ) { sent, recv ->
                            persistConnectAttempt(
                                context, client, db, host, port, "ALLOWED_OPAQUE",
                                failureReason = "FORCE_MITM rule matched, but requestr's CA is not trusted " +
                                    "on this device (install it in Settings to enable MITM decryption for " +
                                    "this host) -- degraded to raw relay",
                                bytesToUpstream = sent, bytesToClient = recv,
                            )
                        }
                        return
                    }
                    // Fall through to the existing MITM path below.
                }
            }

            // Send 200 to client — we're about to MITM
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            // Get or create per-host certificate. ExpiringLruCertCache.getOrCompute (§3) keeps
            // the same "generate at most once per host, even under concurrent callers" semantics
            // the old computeIfAbsent call site relied on, plus a 200-entry LRU cap + TTL.
            // §1.10: remote-cert mirroring is a discrete opt-in (default OFF) read fresh per
            // CONNECT -- see SettingsDataStore.remoteCertMirrorEnabled's KDoc for why this isn't
            // unconditional (it means an extra real upstream probe on every first-seen
            // FORCE_MITM host, which a user who leaves the toggle off never pays for).
            val mirrorEnabled = SettingsDataStore.get(context).remoteCertMirrorEnabled.first()
            // §3 (C2) -- read fresh per CONNECT, same convention as mirrorEnabled above. Disabled
            // (all-zero/no-op) by default, so a user who never touches this setting pays nothing.
            val networkCondition = SettingsDataStore.get(context).networkCondition.first()
            val (cert, keyPair) = certCache.getOrCompute(host) { h ->
                generateHostCertOrMirror(context, h, mirrorEnabled)
            }

            // Real JA3 (M6): peek the raw ClientHello record directly off the accepted socket
            // BEFORE handing it to JSSE -- by the time startHandshake() below returns, JSSE has
            // consumed those bytes and there is no supported way to read them back. peeked.socket
            // replays the exact same bytes to JSSE (see ClientHelloCapture's KDoc), so the
            // handshake itself is completely unaffected by this.
            val peeked = ClientHelloCapture.peekClientHello(client)
            val clientHello: TlsClientHello? = peeked.clientHello

            // Upgrade client side to TLS with our per-host cert
            val sslContext = createSslContext(keyPair, cert)
            val sslSocket = sslContext.socketFactory
                .createSocket(peeked.socket, host, port, true) as SSLSocket
            sslSocket.useClientMode = false
            // Client/server ALPN negotiation (§4): offer both h2 and http/1.1, exactly what a
            // real server behind this proxy would offer. The client's choice (read back below,
            // once the handshake completes) decides which relay mode this whole connection uses
            // -- see the h2-vs-HTTP/1.x dispatch further down.
            //
            // `SSLParameters.setApplicationProtocols` is API 24 (N), and the negotiated result is
            // read back via `SSLSocket.getHandshakeApplicationProtocol()` -- also API 24 -- rather
            // than the API 29 `getApplicationProtocol()`. This makes the h2 path reachable on
            // Android 7.0/8.x (API 24-28), where Android's Conscrypt JSSE provider already
            // supports ALPN even though the newer `applicationProtocol` getter does not exist yet.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                runCatching {
                    val params = sslSocket.sslParameters
                    params.applicationProtocols = arrayOf("h2", "http/1.1")
                    sslSocket.sslParameters = params
                }
            }
            try {
                sslSocket.startHandshake()
            } catch (e: javax.net.ssl.SSLException) {
                ConnectControl.PinningFailureRegistry.recordFailure(host)
                persistFailedMitmTlsSession(db, host, port, "client-facing handshake", e)
                return
            }

            val negotiatedAlpn = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    sslSocket.handshakeApplicationProtocol?.ifBlank { null }
                } else null
            }.getOrNull()

            val decryptedInput = sslSocket.getInputStream()
            val decryptedOutput = sslSocket.getOutputStream()

            // MUST-FIX (§1.5 correctness): the upstream TCP+TLS connection to the real server
            // used to be dialed and handshaked right here, unconditionally, *before* the first
            // decrypted request was even read or evaluated against a rule. That meant a
            // MOCK_RESPONSE-only connection -- the whole point of which is to avoid touching
            // the real endpoint (working offline, not calling a production side effect, ...) --
            // still opened a real socket and completed a real TLS handshake to it.
            // LazyUpstreamConnection defers both until something on this connection actually
            // needs the network: the first REDIRECT / MODIFY_REQUEST / LOG_ONLY-forwarded
            // request, or non-HTTP/1.x blind-relay bytes (see inspectClientToServer /
            // handleParsedRequest). It connects at most once and is reused for every later
            // request on this same client connection.
            //
            // W5 aliasing + M2 TLS-session persistence (previously done unconditionally right
            // after the eager connect, below) move into onFirstConnect: they still run --
            // exactly once, synchronously, before any caller's connect() call returns -- just
            // deferred until an upstream socket actually exists. Normal (non-mocked) traffic
            // still gets this on its very first request, so lineage-join behaviour for real
            // traffic is unchanged; a fully-mocked connection simply never triggers it.
            val upstream = LazyUpstreamConnection(
                host, port,
                preferredAlpnProtocols = negotiatedAlpn?.let { listOf(it) } ?: emptyList(),
                networkCondition = networkCondition,
            ) { connected ->
                // W5: the MITM proxy terminates TLS itself, so tlsSessionId/connectionId have
                // historically been keyed as "$host:$port" -- a real key, but a different
                // convention than the 5-tuple ConnectionLineage joins on for the VPN/tun path.
                // client (the accepted Socket) and connected.socket (the Socket this engine
                // itself opened) together give the real 5-tuple this MITM'd connection
                // corresponds to, so register the host:port alias against the canonical
                // ConnectionKey.forTcp key instead of inventing a third convention.
                val canonicalConnectionId = runCatching {
                    com.example.correlation.ConnectionKey.forTcp(
                        srcIp = client.inetAddress?.hostAddress ?: return@runCatching null,
                        srcPort = client.port,
                        dstIp = connected.socket.inetAddress?.hostAddress ?: return@runCatching null,
                        dstPort = port,
                    )
                }.getOrNull()
                if (canonicalConnectionId != null) {
                    com.example.correlation.ConnectionAliasRegistry.register(
                        alias = com.example.correlation.ConnectionKey.forHostPort(host, port),
                        canonical = canonicalConnectionId,
                    )
                }

                // M2: persist a TlsSessionEntity for this MITM'd handshake. Unlike the
                // undecoded/VPN path (CaptureEngine, ClientHello-only), we actually completed
                // both TLS legs here, so we know the *negotiated* cipher/protocol/ALPN from the
                // client-facing session and the *real* peer certificate from the upstream leg
                // (the client-facing cert is ours, not the server's — no signal there).
                persistMitmTlsSession(
                    db, host, port, sslSocket, connected.sslSocket, canonicalConnectionId,
                    ja3Hash = clientHello?.ja3Hash(),
                )
            }

            // Inspecting bidirectional relay: parse decrypted HTTP/1.x with
            // HttpParser, enforce rules on the parsed messages, persist each
            // transaction, and forward the (possibly modified) bytes.
            // Fix C: read the logging toggles once per connection via SettingsDataStore --
            // SettingsRepository.settings.value silently returns hardcoded `true` defaults
            // when nothing has ever called SettingsRepository.init() in this process (same
            // root cause documented at RequestrVpnService's `prefs.requestLogging.first()`
            // read), so a user with response logging OFF could still get bodies persisted
            // through this MITM path even though every other producer honoured the toggle.
            val settingsPrefs = SettingsDataStore.get(context)
            val logRequests = settingsPrefs.requestLogging.first()
            val logResponses = settingsPrefs.responseLogging.first()

            val sent = AtomicLong(0)
            val received = AtomicLong(0)

            if (negotiatedAlpn == "h2") {
                // §3/§5: HTTP/2 is decided once, upfront, from the ALPN result -- this
                // connection never falls into the HTTP/1.x buffering loop or its blind-relay
                // fallback at all. Every byte is forwarded immediately in both directions (h2
                // frames are never rule-evaluated -- that only applies to the HTTP/1.x path);
                // Http2Session is handed a copy of each parsed frame purely to explain what it
                // means (HPACK decode, per-stream pairing, gRPC message framing).
                val h2Session = Http2Session(
                    onTransaction = { tx ->
                        emitTransaction(tx.request, tx.response, tx.latencyMs, "$host:$port", logRequests, logResponses)
                    },
                    onGrpcMessage = { event -> emitGrpcMessage(event, host, port, logRequests, logResponses) },
                )
                val t1 = Thread {
                    inspectH2ClientToServer(db, decryptedInput, upstream, host, port, h2Session, sent)
                }
                val t2 = Thread {
                    inspectH2ServerToClient(upstream, decryptedOutput, host, port, h2Session, received)
                }
                t1.start(); t2.start()
                t1.join(60_000); t2.join(60_000)
            } else {
                // HTTP/1.x, with an in-place switch to WebSocket framing the moment a 101
                // upgrade response is observed -- see handleParsedResponse and
                // ConnectionProtocolState. Genuinely unrecognized protocols still fall back to
                // the pre-existing blind relay inside inspectClientToServer/inspectServerToClient.
                val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
                val clientWriteLock = Any()
                val wsState = ConnectionProtocolState()
                val t1 = Thread {
                    inspectClientToServer(db, decryptedInput, upstream, decryptedOutput,
                        clientWriteLock, host, port, pending, sent, logRequests, logResponses, wsState)
                }
                val t2 = Thread {
                    inspectServerToClient(
                        db, upstream, decryptedOutput, clientWriteLock, pending, host, port, received,
                        logRequests, logResponses, wsState,
                    )
                }
                t1.start(); t2.start()
                t1.join(60_000); t2.join(60_000)
            }

            persistConnectAttempt(
                context, client, db, host, port, "MITM_DECRYPTED",
                negotiatedAlpn = negotiatedAlpn,
                bytesToUpstream = sent.get(), bytesToClient = received.get(),
            )

            packetCount.addAndGet(1)
            InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler: ${e.message}")
        }
    }

    /**
     * Persists a [ConnectAttemptEntity] for a CONNECT this engine just handled. Captures
     * [ConnectAttemptEntity.clientUid] via [ConnectivityManager.getConnectionOwnerUid], keyed off
     * [client]'s actual kernel-level local/remote socket addresses (the real TCP 4-tuple this
     * accepted connection represents) -- this only resolves when requestr itself is the active
     * VPN (true for the VPN_TUN/MITM_PROXY tiers this engine runs under), so the lookup is
     * wrapped in [runCatching] and left null on any failure or when unsupported (< API 29). Never
     * throws overall -- a failed persist must not break the CONNECT it's describing, matching
     * [persistMitmTlsSession]'s existing never-throw contract.
     */
    private fun persistConnectAttempt(
        context: Context,
        client: Socket,
        db: RequestrDatabase,
        host: String,
        port: Int,
        disposition: String,
        redirectTarget: String? = null,
        failureReason: String? = null,
        negotiatedAlpn: String? = null,
        bytesToUpstream: Long = 0,
        bytesToClient: Long = 0,
    ) {
        runCatching {
            // UID attribution has two independent, never-throw paths: the API 29+
            // ConnectivityManager owner-UID lookup (works only while requestr is itself the
            // active VPN), and ConnectControl.resolveClientUid's /proc/net/tcp fallback (covers
            // pre-29 or non-VPN contexts, subject to Android's per-app socket visibility on
            // stock N+ devices where it legitimately returns null). Try the former, fall back
            // to the latter rather than silently recording null when a fallback was available.
            val clientUid = runCatching {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@runCatching null
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@runCatching null
                val local = client.localSocketAddress as? java.net.InetSocketAddress
                    ?: return@runCatching null
                val remote = client.remoteSocketAddress as? java.net.InetSocketAddress
                    ?: return@runCatching null
                cm.getConnectionOwnerUid(android.system.OsConstants.IPPROTO_TCP, local, remote).takeIf { it != -1 }
            }.getOrNull() ?: ConnectControl.resolveClientUid(client)
            val entity = ConnectAttemptEntity(
                sessionId = sessionId.get(),
                targetHost = host,
                targetPort = port,
                clientUid = clientUid,
                negotiatedAlpn = negotiatedAlpn,
                disposition = disposition,
                redirectTarget = redirectTarget,
                bytesToClient = bytesToClient,
                bytesToUpstream = bytesToUpstream,
                failureReason = failureReason,
                finishedAt = System.currentTimeMillis(),
            )
            kotlinx.coroutines.runBlocking { db.connectAttemptDao().insert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist CONNECT attempt for $host: ${e.message}") }
    }

    // ── WebSocket mode switch (§4/§5) ────────────────────────────────────

    /**
     * Per-connection state shared by [inspectClientToServer]/[inspectServerToClient] for the
     * HTTP/1.x relay path: once a 101 upgrade response is observed (see [handleParsedResponse]),
     * [webSocketMode] flips to `true` and every subsequent byte on both directions is parsed as
     * WebSocket frames instead of HTTP/1.x -- this is what removes the old blind-relay fallback
     * for WebSocket traffic (§5). [clientAssembler]/[serverAssembler] reassemble fragmented
     * messages (RFC 6455 §5.4) independently per direction, matching the per-direction
     * [Http2Parser.HpackDecoder] convention used for HTTP/2.
     */
    private class ConnectionProtocolState {
        val webSocketMode = AtomicBoolean(false)
        @Volatile var wsUrl: String = ""
        val clientAssembler = WsMessageAssembler()
        val serverAssembler = WsMessageAssembler()
    }

    /** `"https://host/path"` → `"wss://host/path"`, `"http://"` → `"ws://"`. */
    private fun buildWsUrl(url: String): String = when {
        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
        else -> url
    }

    // ── Inspecting relay ────────────────────────────────────────────────

    /**
     * Client→server direction. Buffers decrypted bytes until [HttpParser] can
     * parse a complete request, applies request-side rules AFTER parsing
     * (BLOCK answers the client directly; REDIRECT / MODIFY_REQUEST rewrite
     * the forwarded bytes; MOCK_RESPONSE short-circuits with a synthesized
     * response and never reaches upstream at all -- see [handleParsedRequest]),
     * and enqueues each forwarded request for pairing with its response.
     * Non-HTTP/1.x content flushes raw and switches this direction to blind relay.
     */
    private fun inspectClientToServer(
        db: RequestrDatabase,
        input: InputStream,
        upstream: LazyUpstreamConnection,
        clientOut: OutputStream,
        clientWriteLock: Any,
        host: String,
        port: Int,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        bytesSentToUpstream: AtomicLong,
        logRequests: Boolean,
        logResponses: Boolean,
        wsState: ConnectionProtocolState,
    ) {
        val buffer = ByteArrayOutputStream()
        val wsBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var blind = false
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesSentToUpstream.addAndGet(n.toLong())
                if (wsState.webSocketMode.get()) {
                    // §4/§5: WebSocket frame parsing after upgrade — forward raw immediately
                    // (WS frames are never rule-evaluated, same posture as h2), then feed a
                    // side-buffer through the frame parser purely to explain/emit what was sent.
                    val upstreamOut = connectOrThrow(db, upstream, host, port)
                    upstreamOut.write(chunk, 0, n)
                    upstreamOut.flush()
                    wsBuffer.write(chunk, 0, n)
                    val wsBytes = wsBuffer.toByteArray()
                    val (consumed, frames) = WebSocketParser.parseFrames(wsBytes)
                    if (consumed > 0) {
                        wsBuffer.reset()
                        wsBuffer.write(wsBytes, consumed, wsBytes.size - consumed)
                    }
                    for (frame in frames) {
                        wsState.clientAssembler.offer(frame)?.let { complete ->
                            emitWsFrame(complete, isClient = true, wsState.wsUrl, host, port, logRequests, logResponses)
                        }
                    }
                    continue
                }
                if (blind) {
                    val upstreamOut = connectOrThrow(db, upstream, host, port)
                    upstreamOut.write(chunk, 0, n)
                    upstreamOut.flush()
                    continue
                }
                buffer.write(chunk, 0, n)
                var bytes = buffer.toByteArray()
                var waitingForMore = false
                while (!waitingForMore && bytes.isNotEmpty()) {
                    val parsed = HttpParser.tryParseRequest(bytes, host, port, true)
                    if (parsed == null) {
                        if (!couldBecomeHttpRequest(bytes) || bytes.size > MAX_UNPARSED_BUFFER) {
                            // Not HTTP/1.x (HTTP/2 preface, WS frames, ...) — relay raw from now
                            // on. Blind-relay bytes are, by definition, never rule-evaluated
                            // (there is no parsed request to check), so they always need
                            // upstream -- this is a genuine "actually needs the network" case.
                            val upstreamOut = connectOrThrow(db, upstream, host, port)
                            upstreamOut.write(bytes)
                            upstreamOut.flush()
                            buffer.reset()
                            blind = true
                        }
                        waitingForMore = true
                    } else {
                        val raw = bytes.copyOfRange(0, parsed.consumedBytes)
                        bytes = bytes.copyOfRange(parsed.consumedBytes, bytes.size)
                        handleParsedRequest(
                            db, parsed, raw, upstream, clientOut, clientWriteLock, pending,
                            host, port, logRequests, logResponses,
                        )
                        buffer.reset()
                        buffer.write(bytes)
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */
        } finally {
            // §1.5 MUST-FIX: guarantees inspectServerToClient's awaitConnectedOrGiveUp always
            // unblocks, however this loop ends -- normal EOF, an exception (including a
            // failed connectOrThrow above), or falling into blind relay. A no-op if connect()
            // already ran (successfully or not): CountDownLatch.countDown() on an
            // already-released latch does nothing.
            upstream.giveUp()
        }
    }

    /**
     * Connects (or reuses the existing connection) on demand for whichever
     * [inspectClientToServer] direction actually needs to write to the real server. Only
     * translates [javax.net.ssl.SSLException] into a persisted failure record -- matching the
     * previous eager call site, which only guarded [SSLSocket.startHandshake] this way and let
     * a raw connect failure (unknown host, connection refused, ...) propagate untouched.
     */
    private fun connectOrThrow(
        db: RequestrDatabase,
        upstream: LazyUpstreamConnection,
        host: String,
        port: Int,
    ): OutputStream {
        return try {
            upstream.connect().output
        } catch (e: javax.net.ssl.SSLException) {
            persistFailedMitmTlsSession(db, host, port, "upstream handshake", e)
            throw e
        }
    }

    /**
     * Server→client direction. Parses responses, pairs each with the oldest
     * forwarded request (FIFO — HTTP/1.1 responses arrive in request order),
     * applies MODIFY_RESPONSE rules AFTER parsing so decrypted content is
     * actually rewritten, and emits one [CapturedTrafficEntity] per pair.
     */
    private fun inspectServerToClient(
        db: RequestrDatabase,
        upstream: LazyUpstreamConnection,
        clientOut: OutputStream,
        clientWriteLock: Any,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        host: String,
        port: Int,
        bytesReceivedFromUpstream: AtomicLong,
        logRequests: Boolean,
        logResponses: Boolean,
        wsState: ConnectionProtocolState,
    ) {
        // §1.5 MUST-FIX: mirrors inspectClientToServer's lazy connect -- this reader has
        // nothing to read until the upstream connection actually exists. If the whole
        // client→server side finishes without ever needing it (e.g. every request on this
        // connection was MOCK_RESPONSE), upstream.giveUp() unblocks this with null and this
        // direction never touches the network either.
        val input = upstream.awaitConnectedOrGiveUp()?.input ?: return

        val buffer = ByteArrayOutputStream()
        val wsBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var blind = false
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesReceivedFromUpstream.addAndGet(n.toLong())
                if (wsState.webSocketMode.get()) {
                    synchronized(clientWriteLock) {
                        clientOut.write(chunk, 0, n)
                        clientOut.flush()
                    }
                    wsBuffer.write(chunk, 0, n)
                    val wsBytes = wsBuffer.toByteArray()
                    val (consumed, frames) = WebSocketParser.parseFrames(wsBytes)
                    if (consumed > 0) {
                        wsBuffer.reset()
                        wsBuffer.write(wsBytes, consumed, wsBytes.size - consumed)
                    }
                    for (frame in frames) {
                        wsState.serverAssembler.offer(frame)?.let { complete ->
                            emitWsFrame(complete, isClient = false, wsState.wsUrl, host, port, logRequests, logResponses)
                        }
                    }
                    continue
                }
                if (blind) {
                    synchronized(clientWriteLock) {
                        clientOut.write(chunk, 0, n)
                        clientOut.flush()
                    }
                    continue
                }
                buffer.write(chunk, 0, n)
                var bytes = buffer.toByteArray()
                var waitingForMore = false
                while (!waitingForMore && bytes.isNotEmpty()) {
                    val response = HttpParser.tryParseResponse(
                        bytes,
                        pending.peek()?.first?.url,
                        pending.peek()?.first?.method,
                    )
                    if (response == null) {
                        if (!couldBecomeHttpResponse(bytes) || bytes.size > MAX_UNPARSED_BUFFER) {
                            synchronized(clientWriteLock) {
                                clientOut.write(bytes)
                                clientOut.flush()
                            }
                            buffer.reset()
                            blind = true
                        }
                        waitingForMore = true
                    } else {
                        val raw = bytes.copyOfRange(0, response.consumedBytes)
                        bytes = bytes.copyOfRange(response.consumedBytes, bytes.size)
                        handleParsedResponse(
                            db, response, raw, clientOut, clientWriteLock, pending, host, port,
                            logRequests, logResponses, wsState,
                        )
                        buffer.reset()
                        buffer.write(bytes)
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */ }
    }

    // ── HTTP/2 relay (§3/§5) ──────────────────────────────────────────────

    /**
     * Client→server HTTP/2 relay. Unlike [inspectClientToServer]'s HTTP/1.x path, every byte is
     * forwarded upstream immediately, unmodified -- h2 frames are never rule-evaluated here (a
     * deliberate scope decision: BLOCK/REDIRECT/MODIFY_REQUEST/MODIFY_RESPONSE/MOCK_RESPONSE for
     * h2 traffic is not part of this task). [session] is handed a *copy* of each parsed frame
     * purely so it can emit [Http2Transaction]/[GrpcEvent]s for display -- it never influences
     * what is sent on the wire.
     *
     * The client connection preface (RFC 7540 §3.5, `Http2Parser.PREFACE_LENGTH` bytes) is
     * stripped from the frame-parsing side-buffer exactly once before the first
     * [Http2Parser.parseFrames] call; buffering (rather than checking as soon as any bytes
     * arrive) avoids misinterpreting a preface that arrived split across TCP segments as a
     * bogus frame header.
     */
    private fun inspectH2ClientToServer(
        db: RequestrDatabase,
        input: InputStream,
        upstream: LazyUpstreamConnection,
        host: String,
        port: Int,
        session: Http2Session,
        bytesSentToUpstream: AtomicLong,
    ) {
        val frameBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var prefaceStripped = false
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesSentToUpstream.addAndGet(n.toLong())
                val upstreamOut = connectOrThrow(db, upstream, host, port)
                upstreamOut.write(chunk, 0, n)
                upstreamOut.flush()

                frameBuffer.write(chunk, 0, n)
                var bytes = frameBuffer.toByteArray()
                if (!prefaceStripped) {
                    if (bytes.size < Http2Parser.PREFACE_LENGTH) continue // wait for the full preface
                    if (Http2Parser.isPreface(bytes)) {
                        bytes = bytes.copyOfRange(Http2Parser.PREFACE_LENGTH, bytes.size)
                    }
                    prefaceStripped = true
                }
                val frames = Http2Parser.parseFrames(bytes)
                session.onClientFrames(frames)
                val consumed = frames.sumOf { 9 + it.length }
                frameBuffer.reset()
                frameBuffer.write(bytes, consumed, bytes.size - consumed)
            }
        } catch (_: Exception) { /* connection closed */
        } finally {
            // Mirrors inspectClientToServer's finally -- guarantees inspectH2ServerToClient's
            // awaitConnectedOrGiveUp always unblocks, however this loop ends.
            upstream.giveUp()
            // P1 fix: whichever h2 relay direction ends first, this connection is over --
            // discard every still-tracked stream (session.close() is idempotent/thread-safe,
            // see its KDoc) instead of relying solely on GOAWAY, which a client/server that
            // simply drops the TCP connection may never send.
            session.close()
        }
    }

    /**
     * Server→client HTTP/2 relay. No preface handling (a server never sends the client
     * connection preface) and no `clientWriteLock` (unlike the HTTP/1.x path, h2 has no
     * BLOCK/MOCK_RESPONSE short-circuit that writes to the client from the other thread).
     */
    private fun inspectH2ServerToClient(
        upstream: LazyUpstreamConnection,
        clientOut: OutputStream,
        host: String,
        port: Int,
        session: Http2Session,
        bytesReceivedFromUpstream: AtomicLong,
    ) {
        val input = upstream.awaitConnectedOrGiveUp()?.input ?: return
        val frameBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesReceivedFromUpstream.addAndGet(n.toLong())
                clientOut.write(chunk, 0, n)
                clientOut.flush()

                frameBuffer.write(chunk, 0, n)
                val bytes = frameBuffer.toByteArray()
                val frames = Http2Parser.parseFrames(bytes)
                session.onServerFrames(frames)
                val consumed = frames.sumOf { 9 + it.length }
                frameBuffer.reset()
                frameBuffer.write(bytes, consumed, bytes.size - consumed)
            }
        } catch (_: Exception) { /* connection closed */ } finally {
            // P1 fix: same cleanup trigger as inspectH2ClientToServer's finally -- whichever
            // direction's loop ends first tears down the shared session's stream state.
            session.close()
        }
    }

    // ── Rule enforcement (post-parse) ───────────────────────────────────

    /**
     * `internal` (not `private`), same rationale as [emitTransaction]: this is where
     * MOCK_RESPONSE's short-circuit (§1.5) actually happens, and asserting "never dials
     * upstream / never enqueues into `pending`" needs direct access to a fake/instrumented
     * [LazyUpstreamConnection] and `pending` without standing up a real TLS/socket pair
     * end-to-end.
     */
    internal fun handleParsedRequest(
        db: RequestrDatabase,
        request: ParsedHttpMessage,
        raw: ByteArray,
        upstream: LazyUpstreamConnection,
        clientOut: OutputStream,
        clientWriteLock: Any,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
    ) {
        val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
        val bytesToForward: ByteArray = when (rule?.action ?: RuleAction.LOG_ONLY) {
            RuleAction.BLOCK -> {
                Log.i(TAG, "BLOCKED (decrypted): ${request.method?.name} ${request.url.take(80)}")
                synchronized(clientWriteLock) {
                    clientOut.write(
                        "HTTP/1.1 403 Blocked by requestr\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                    clientOut.flush()
                }
                // Dropped: never forwarded upstream, never enqueued for pairing. Returns here
                // rather than falling through to the shared forward tail below -- this is the
                // real exception to that otherwise-identical connect/enqueue/write/flush path.
                return
            }
            RuleAction.MOCK_RESPONSE -> {
                // §1.5: short-circuit here, same shape as BLOCK above -- never dials upstream,
                // never enqueues into `pending` (there is no real response coming to pair with
                // it), and writes a synthesized response straight back to the client instead.
                // RuleEnforcer.buildMockResponse is shared with the CDP fetch-interception path
                // (CdpFetchAdapter) -- same replacementValue schema, one config format either way.
                val mockResponse = RuleEnforcer.buildMockResponse(rule?.replacementValue, request.url)
                synchronized(clientWriteLock) {
                    clientOut.write(serializeResponse(mockResponse))
                    clientOut.flush()
                }
                Log.i(TAG, "MOCKED (decrypted): ${request.method?.name} ${request.url.take(80)} -> ${mockResponse.statusCode}")
                // Still surfaced as a captured transaction (0ms latency -- no real round trip
                // happened) so a mocked request is not invisible in the traffic list.
                emitTransaction(request, mockResponse, 0L, "$host:$port", logRequests, logResponses)
                // Same reasoning as BLOCK above: return here rather than reaching the shared
                // forward tail, since this path never forwards anything upstream.
                return
            }
            RuleAction.REDIRECT -> {
                if (rule?.replacementValue?.isNotBlank() == true) {
                    request.url = rule.replacementValue!!
                    runCatching { java.net.URI(request.url).host?.let { request.host = it } }
                    val headers = request.headers.toMutableMap()
                    headers["host"] = request.host
                    request.headers = headers
                }
                serializeRequest(request)
            }
            RuleAction.MODIFY_REQUEST -> {
                rule?.replacementValue?.let { RuleEnforcer.applyRequestModification(request, it) }
                serializeRequest(request)
            }
            RuleAction.SCRIPT_REWRITE -> {
                RuleEnforcer.applyScriptRewrite(request, rule?.replacementValue)
                serializeRequest(request)
            }
            RuleAction.BODY_ENCRYPT_AES, RuleAction.BODY_DECRYPT_AES -> {
                rule?.let {
                    RuleEnforcer.applyBodyCrypto(request, it, encrypt = it.action == RuleAction.BODY_ENCRYPT_AES)
                }
                serializeRequest(request)
            }
            else -> raw // LOG_ONLY / response-only actions — forward the exact original bytes
        }

        // §1.5 MUST-FIX: connect (or reuse) lazily -- this is the first point in the whole
        // client→server pipeline where a REDIRECT/MODIFY_REQUEST/LOG_ONLY request actually
        // needs the network. BLOCK and MOCK_RESPONSE both `return` above without ever reaching
        // this shared tail -- they are the real exceptions to it, not just two more branches
        // that happen to look similar.
        val upstreamOut = connectOrThrow(db, upstream, host, port)
        pending.add(request to System.currentTimeMillis())
        upstreamOut.write(bytesToForward)
        upstreamOut.flush()
    }

    private fun handleParsedResponse(
        db: RequestrDatabase,
        response: ParsedHttpMessage,
        raw: ByteArray,
        clientOut: OutputStream,
        clientWriteLock: Any,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
        wsState: ConnectionProtocolState,
    ) {
        // Interim 1xx responses (e.g. 100 Continue) precede the final response:
        // forward them without consuming the request pairing. 101 is terminal
        // for the HTTP exchange (protocol upgrade), so it is handled normally.
        if (response.statusCode in 100..199 && response.statusCode != 101) {
            synchronized(clientWriteLock) {
                clientOut.write(raw)
                clientOut.flush()
            }
            return
        }
        val pair = pending.poll()
        val request = pair?.first
        var out = raw
        if (request != null) {
            val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
            if (rule?.action == RuleAction.MODIFY_RESPONSE) {
                rule.replacementValue?.let { RuleEnforcer.applyResponseModification(response, it) }
                out = serializeResponse(response)
            } else if (rule?.action == RuleAction.SCRIPT_REWRITE) {
                RuleEnforcer.applyScriptRewrite(response, rule.replacementValue)
                out = serializeResponse(response)
            } else if (rule?.action == RuleAction.BODY_ENCRYPT_AES || rule?.action == RuleAction.BODY_DECRYPT_AES) {
                RuleEnforcer.applyBodyCrypto(response, rule, encrypt = rule.action == RuleAction.BODY_ENCRYPT_AES)
                out = serializeResponse(response)
            }
        }
        synchronized(clientWriteLock) {
            clientOut.write(out)
            clientOut.flush()
        }
        if (request != null) {
            val latencyMs = System.currentTimeMillis() - pair!!.second
            emitTransaction(request, response, latencyMs, "$host:$port", logRequests, logResponses)
            // §4/§5: a successful WebSocket upgrade handshake is terminal for HTTP/1.x framing
            // on this connection -- everything from here on is WS frames, in both directions.
            // Switching modes here (rather than, say, only in inspectClientToServer) means both
            // relay threads see the flip at the same logical point: right after the 101 that
            // caused it, before either side's next read.
            if (response.statusCode == 101 &&
                WebSocketParser.looksLikeUpgrade(request.headers, true) &&
                WebSocketParser.looksLikeUpgrade(response.headers, false)
            ) {
                wsState.wsUrl = buildWsUrl(request.url)
                wsState.webSocketMode.set(true)
            }
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * Build a [CapturedTrafficEntity] from the decrypted pair and hand it to the callback.
     *
     * `internal` (not `private`) so [emitTransaction]'s logging-toggle gating -- the part FIX C
     * changed, from `SettingsRepository.settings.value` to booleans threaded in from
     * [SettingsDataStore] -- is directly unit-testable without standing up a real TLS/socket
     * pair end-to-end.
     */
    internal fun emitTransaction(
        request: ParsedHttpMessage,
        response: ParsedHttpMessage,
        latencyMs: Long,
        connectionId: String,
        logRequests: Boolean,
        logResponses: Boolean,
    ) {
        // Binary-body persistence (image gallery / hex viewer support): [HttpBodyDecoder.forStorage]
        // is the single source of truth for what to write to the *BodyBytes/isXBodyBinary/
        // XBodyBytesTruncated columns -- see that function's doc for the three-state contract.
        val reqStorage = HttpBodyDecoder.forStorage(request)
        val respStorage = HttpBodyDecoder.forStorage(response)
        val entity = CapturedTrafficEntity(
            sessionId = sessionId.get(),
            method = (request.method ?: HttpMethod.GET).name,
            url = request.url,
            host = request.host,
            path = request.path,
            statusCode = if (response.statusCode == 0) null else response.statusCode,
            protocol = response.version.ifEmpty { request.version },
            requestHeaders = if (logRequests) request.headers else emptyMap(),
            responseHeaders = if (logResponses) response.headers else emptyMap(),
            requestBody = if (logRequests) request.body.ifEmpty { null } else null,
            responseBody = if (logResponses) response.body.ifEmpty { null } else null,
            requestSizeBytes = reqStorage.trueSizeBytes,
            responseSizeBytes = respStorage.trueSizeBytes,
            isRequestBodyBinary = logRequests && reqStorage.isBinary,
            isResponseBodyBinary = logResponses && respStorage.isBinary,
            requestBodyBytes = if (logRequests) reqStorage.bytes else null,
            responseBodyBytes = if (logResponses) respStorage.bytes else null,
            requestBodyBytesTruncated = logRequests && reqStorage.truncated,
            responseBodyBytesTruncated = logResponses && respStorage.truncated,
            latencyMs = latencyMs,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = true,
            isUndecoded = false,
            // Task 12: MITM rows use "$host:$port" (the CONNECT target), matching
            // persistMitmTlsSession's convention -- the engine never sees the raw 5-tuple, only
            // the CONNECT target, so this is the exact identity ConnectionLineage.findTlsFor's
            // two-step lookup already handles.
            connectionId = connectionId,
            // Item 1: handleClient only ever reaches this call after terminating TLS itself via
            // MITM (see the class-level MITM_PROXY note below) -- every row here is genuinely
            // MITM_PROXY-tier.
            captureMethod = CaptureRowMethod.MITM_PROXY,
        )
        deliverEntity(entity)
    }

    /**
     * §4/§5: emit one reassembled WebSocket message (see [WsMessageAssembler]) as a
     * [CapturedTrafficEntity], matching [com.example.interception.CaptureEngine.persistWsFrame]'s
     * field convention exactly (`method`/`protocol`/`url` shape) so the traffic list renders
     * MITM'd and VPN-captured WebSocket frames identically.
     */
    internal fun emitWsFrame(
        frame: WebSocketParser.WsFrame,
        isClient: Boolean,
        wsUrl: String,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
    ) {
        val entity = CapturedTrafficEntity(
            sessionId = sessionId.get(),
            method = if (isClient) "WS→" else "WS←",
            url = "$wsUrl (${frame.opcodeName})",
            host = host,
            path = "/",
            statusCode = null,
            protocol = "WEBSOCKET",
            requestHeaders = emptyMap(),
            responseHeaders = emptyMap(),
            requestBody = if (isClient && logRequests) frame.payloadText else null,
            responseBody = if (!isClient && logResponses) frame.payloadText else null,
            requestSizeBytes = if (isClient) frame.totalLength.toLong() else 0L,
            responseSizeBytes = if (!isClient) frame.totalLength.toLong() else 0L,
            latencyMs = 0L,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = true,
            connectionId = "$host:$port",
            // Item 1: see the class-level MITM_PROXY note below.
            captureMethod = CaptureRowMethod.MITM_PROXY,
        )
        deliverEntity(entity)
    }

    /**
     * §3/§5: emit one gRPC message (request or response direction) as a [CapturedTrafficEntity],
     * matching [com.example.interception.CaptureEngine.persistGrpcMessage]'s field convention
     * exactly (`metaHeaders`, hex/decoded body rendering, per-direction size accounting) so a
     * gRPC call MITM'd here renders identically to one observed on the VPN-capture path.
     */
    internal fun emitGrpcMessage(
        event: GrpcEvent,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
    ) {
        val call = event.call
        val msg = event.message
        val hexDump = ProtobufDecoder.hexDump(msg.payload)
        val decoded = if (!msg.compressed) ProtobufDecoder.decodeToString(msg.payload) else null
        val metaHeaders = buildMap {
            put("x-grpc-encoding", call.encoding)
            put("x-grpc-stream-id", call.streamId.toString())
            if (call.messageType.isNotEmpty()) put("x-grpc-message-type", call.messageType)
            if (msg.compressed) put("x-grpc-compressed", "true")
            if (event.grpcStatus != null) put("x-grpc-status", event.grpcStatus.toString())
            if (decoded != null) put("x-protobuf-decoded", decoded)
        }
        val entity = CapturedTrafficEntity(
            sessionId = sessionId.get(),
            method = call.httpMethod, // :method — POST for real gRPC
            url = call.url,
            host = call.authority.ifEmpty { host },
            path = call.grpcMethod, // :path — e.g. /my.Service/GetUser
            statusCode = null,
            protocol = "gRPC",
            requestHeaders = if (event.isClient) {
                if (logRequests) call.headers + metaHeaders else metaHeaders
            } else metaHeaders,
            responseHeaders = metaHeaders,
            requestBody = if (event.isClient && logRequests) hexDump else null,
            responseBody = if (!event.isClient && logResponses) hexDump else null,
            requestSizeBytes = if (event.isClient) (msg.length + GrpcDetector.MESSAGE_HEADER_SIZE).toLong() else 0L,
            responseSizeBytes = if (!event.isClient) (msg.length + GrpcDetector.MESSAGE_HEADER_SIZE).toLong() else 0L,
            latencyMs = 0L,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = true,
            connectionId = "$host:$port",
            // Item 1: see the class-level MITM_PROXY note below.
            captureMethod = CaptureRowMethod.MITM_PROXY,
        )
        deliverEntity(entity)
    }

    /**
     * Shared tail for [emitTransaction]/[emitWsFrame]/[emitGrpcMessage]: hands [entity] to
     * [onDecryptedTransaction], bumping [packetCount] on success. Never throws -- a failed
     * callback must not break the relay that already forwarded the underlying bytes.
     */
    private fun deliverEntity(entity: CapturedTrafficEntity) {
        val callback = onDecryptedTransaction ?: return
        runCatching { callback(entity) }
            .onSuccess {
                packetCount.incrementAndGet()
                InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
            }
            .onFailure { Log.w(TAG, "onDecryptedTransaction failed: ${it.message}") }
    }

    // ── Re-serialization (only used when a rule modified the message) ───

    /**
     * `internal` (not `private`), matching [emitTransaction]'s precedent, purely so the
     * §1.4 Content-Encoding/Content-Length self-consistency behavior below is directly
     * unit-testable without standing up a real TLS/socket relay end-to-end.
     *
     * MUST-FIX 1 (severe): this used to always write `request.body.toByteArray(UTF_8)` --
     * [HttpBodyDecoder]'s decoded/decompressed *text rendering* of the body, which is the
     * empty [HttpBodyDecoder.BINARY_BODY_PLACEHOLDER] for any binary, undecodable
     * (br/zstd/compress), or failed-decompression body. Since serializeRequest/serializeResponse
     * run for every REDIRECT/MODIFY_REQUEST/MODIFY_RESPONSE/MOCK_RESPONSE-matched message --
     * not only ones a rule actually rewrote the body of -- that meant every image, protobuf,
     * or Brotli request/response whose connection matched *any* rule (even a header-only
     * MODIFY_* edit) was forwarded with a zero-byte body, while a stale nonzero Content-Length
     * from the original headers survived untouched (the old `if (bodyBytes.isNotEmpty())`
     * guard skipped fixing it up), hanging the upstream/client waiting for a body that would
     * never arrive.
     *
     * Fixed by keying entirely off [ParsedHttpMessage.bodyReplaced]: only a rule's explicit
     * `bodyReplace` sets that flag (see RuleEnforcer.applyRequestModification), and only then
     * is [ParsedHttpMessage.body] the real intended content. Every other case -- no rule
     * matched, or the rule only touched headers/status -- writes [ParsedHttpMessage.rawBodyBytes]
     * back out verbatim (the exact bytes [HttpParser] captured off the wire, before any
     * decompression/binary-detection lossiness), and leaves Content-Encoding untouched since
     * nothing about the body actually changed.
     */
    internal fun serializeRequest(request: ParsedHttpMessage): ByteArray {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            putAll(request.headers)
        }
        headers.remove("Transfer-Encoding") // re-serialized with an explicit length
        val bodyBytes = bodyBytesFor(request, headers)
        // Always set Content-Length to match exactly what is written, including the
        // zero-length case -- never leave a stale value (see MUST-FIX 1 above).
        headers["Content-Length"] = bodyBytes.size.toString()
        val sb = StringBuilder()
        sb.append(request.method?.name ?: "GET").append(' ')
            .append(request.path.ifEmpty { "/" }).append(' ')
            .append(request.version.ifEmpty { "HTTP/1.1" }).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    /** See [serializeRequest]'s KDoc for why this is `internal` and how body bytes are chosen. */
    internal fun serializeResponse(response: ParsedHttpMessage): ByteArray {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            putAll(response.headers)
        }
        headers.remove("Transfer-Encoding") // parser de-chunked the body
        val bodyBytes = bodyBytesFor(response, headers)
        headers["Content-Length"] = bodyBytes.size.toString()
        val sb = StringBuilder()
        sb.append(response.version.ifEmpty { "HTTP/1.1" }).append(' ')
            .append(response.statusCode).append(' ')
            .append(response.statusMessage.ifEmpty { "OK" }).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    /**
     * Shared by [serializeRequest] and [serializeResponse]: decides the body bytes to write
     * onto the wire for [msg], mutating [headers] to match (see [serializeRequest]'s KDoc for
     * the full MUST-FIX 1 rationale this preserves). A rule's explicit `bodyReplace` writes the
     * replacement plaintext and drops Content-Encoding -- any inherited value would now be a
     * lie about bytes that are not actually gzip/br/whatever-compressed anymore. Every other
     * message forwards [ParsedHttpMessage.rawBodyBytes] verbatim -- never
     * [HttpBodyDecoder]'s (possibly lossy/empty) text rendering of them -- falling back to
     * [ParsedHttpMessage.body] only for messages that were never parsed off a real wire (e.g.
     * hand-built test fixtures), where `body` *is* the real content. Content-Encoding is left
     * untouched in that non-replaced case: nothing about the body actually changed, so whatever
     * the client already believed about the encoding is still true.
     */
    private fun bodyBytesFor(msg: ParsedHttpMessage, headers: MutableMap<String, String>): ByteArray {
        return if (msg.bodyReplaced) {
            headers.remove("Content-Encoding")
            // §2.14(b)/§6: prefer the byte-exact replacement when one was set (binary fixture
            // files, RequestCryptoManager ciphertext, ...) -- re-encoding msg.body as UTF-8
            // would corrupt anything that isn't representable as text.
            msg.replacedBodyBytes ?: msg.body.toByteArray(Charsets.UTF_8)
        } else {
            msg.rawBodyBytes ?: msg.body.toByteArray(Charsets.UTF_8)
        }
    }

    // ── Parse-support helpers ───────────────────────────────────────────

    /** True while the buffered bytes could still be the prefix of an HTTP/1.x request. */
    private fun couldBecomeHttpRequest(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, bytes.size.coerceAtMost(8), Charsets.ISO_8859_1)
        return HTTP_METHODS.any { it.startsWith(head) || head.startsWith("$it ") }
    }

    /** True while the buffered bytes could still be the prefix of an HTTP/1.x response. */
    private fun couldBecomeHttpResponse(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, bytes.size.coerceAtMost(5), Charsets.ISO_8859_1)
        return "HTTP/".startsWith(head)
    }

    // ── Per-host cert generation ────────────────────────────────────────

    /**
     * §1.10: when remote-cert mirroring is enabled, tries [CertificateAuthority.issueMirroredLeaf]
     * first (an independent direct probe of the real host, subject/SANs/validity copied into our
     * self-signed leaf) and falls back to the plain [generateHostCert] leaf whenever mirroring
     * fails for any reason (unreachable host, handshake failure, timeout -- issueMirroredLeaf
     * never throws, only returns null). When mirroring is disabled (the default), this is
     * exactly the pre-existing [generateHostCert] behaviour with zero extra I/O.
     */
    internal fun generateHostCertOrMirror(
        context: Context,
        host: String,
        mirrorEnabled: Boolean,
    ): Pair<X509Certificate, KeyPair> {
        if (mirrorEnabled) {
            val mirrored = kotlinx.coroutines.runBlocking {
                CertificateAuthority.issueMirroredLeaf(context, host)
            }
            if (mirrored != null) return mirrored
        }
        return generateHostCert(context, host)
    }

    private fun generateHostCert(context: Context, host: String): Pair<X509Certificate, KeyPair> {
        val ca = kotlinx.coroutines.runBlocking { CertificateAuthority.getOrCreate(context) }
        val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", bcProvider)
            .apply { initialize(2048, SecureRandom()) }
            .genKeyPair()
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val now = Date()
        val expiry = Date(now.time + 365L * 24 * 60 * 60 * 1000)

        // Use the real CA certificate's subject DN as the issuer so the generated
        // per-host cert chains correctly. Hardcoding "CN=requestr Root CA" breaks
        // verification when the CA subject includes O/OU components.
        val issuer = X500Name.getInstance(ca.certificate.subjectX500Principal.encoded)
        val subject = X500Name("CN=$host")

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, now, expiry, subject, keyPair.public
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            addExtension(Extension.subjectAlternativeName, false, HostSanGenerator.build(host))
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(ca.keyPair.private)

        val cert = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        return cert to keyPair
    }

    // ── TLS session persistence (M2) ─────────────────────────────────────

    /**
     * Reads the negotiated cipher suite / protocol / ALPN off the client-facing
     * [SSLSession] and the peer certificate chain off the upstream leg, then upserts a
     * [TlsSessionEntity]. Keyed by `host:port` (one row per MITM'd host per proxy lifetime;
     * re-negotiations on the same host just refresh the row, matching how [certCache]
     * already treats a host as a single logical connection target) -- that primary key is
     * left untouched so no existing row is orphaned, but [connectionId] carries the real
     * canonical 5-tuple key (see [ConnectionAliasRegistry] registration in [handleClient]),
     * which is the field [com.example.correlation.graph.ConnectionLineage] joins on (W5).
     *
     * Never throws — a failure here must not break the relay that already started.
     */
    private fun persistMitmTlsSession(
        db: RequestrDatabase,
        host: String,
        port: Int,
        clientFacingSocket: SSLSocket,
        upstreamSocket: SSLSocket,
        canonicalConnectionId: String?,
        ja3Hash: String? = null,
    ) {
        runCatching {
            val clientSession: SSLSession = clientFacingSocket.session
            val upstreamSession: SSLSession = upstreamSocket.session
            val peerCert = upstreamSession.peerCertificates?.firstOrNull() as? X509Certificate
            val aliasConnectionId = com.example.correlation.ConnectionKey.forHostPort(host, port)
            val entity = com.example.data.local.TlsSessionEntity(
                tlsSessionId = aliasConnectionId,
                sessionId = sessionId.get(),
                connectionId = canonicalConnectionId ?: aliasConnectionId,
                sniHost = host,
                version = clientSession.protocol,
                cipherSuite = clientSession.cipherSuite,
                negotiatedProtocol = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        clientFacingSocket.applicationProtocol?.ifBlank { null }
                    } else null
                }.getOrNull(),
                ja3Hash = ja3Hash, // real fingerprint (§1/M6) — see ClientHelloCapture
                handshakeState = com.example.interception.tls.TlsHandshakeState.ESTABLISHED.name,
                wasDecrypted = true,
                peerCertSubject = peerCert?.subjectX500Principal?.name,
                peerCertIssuer = peerCert?.issuerX500Principal?.name,
            )
            kotlinx.coroutines.runBlocking { db.tlsSessionDao().upsert(entity) }
            Log.i(TAG, "TLS session persisted: $host cipher=${entity.cipherSuite} alpn=${entity.negotiatedProtocol}")
        }.onFailure { e -> Log.w(TAG, "Failed to persist MITM TLS session for $host: ${e.message}") }
    }

    /**
     * Task 11: persist a FAILED [com.example.data.local.TlsSessionEntity] when either leg of
     * the MITM handshake throws. This covers the TLS 1.3 encrypted-alert case
     * [com.example.interception.tls.TlsDecoder.parseAlert] cannot see on the wire — the MITM
     * proxy is a real TLS endpoint, so [javax.net.ssl.SSLHandshakeException]/[SSLException] is
     * an unambiguous, first-class failure signal, not a heuristic.
     *
     * Uses the same `"$host:$port"` [connectionId] convention as [persistMitmTlsSession] so a
     * later successful re-handshake on the same host overwrites this row rather than
     * duplicating it. Never throws — a failure here must not break the caller's early return.
     */
    private fun persistFailedMitmTlsSession(
        db: RequestrDatabase,
        host: String,
        port: Int,
        stage: String,
        cause: Exception,
    ) {
        runCatching {
            val connectionId = "$host:$port"
            val entity = com.example.data.local.TlsSessionEntity(
                tlsSessionId = connectionId,
                sessionId = sessionId.get(),
                connectionId = connectionId,
                sniHost = host,
                version = null,
                cipherSuite = null,
                negotiatedProtocol = null,
                ja3Hash = null,
                handshakeState = com.example.interception.tls.TlsHandshakeState.FAILED.name,
                wasDecrypted = false,
                peerCertSubject = null,
                peerCertIssuer = null,
                failureReason = "$stage: ${cause.javaClass.simpleName}: ${cause.message}",
                failedAt = System.currentTimeMillis(),
            )
            kotlinx.coroutines.runBlocking { db.tlsSessionDao().upsert(entity) }
            Log.w(TAG, "MITM TLS handshake FAILED: $host stage=$stage cause=${cause.message}")
        }.onFailure { e -> Log.w(TAG, "Failed to persist FAILED MITM TLS session for $host: ${e.message}") }
    }

    // ── SSL context ─────────────────────────────────────────────────────

    private fun createSslContext(keyPair: KeyPair, cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            // The client already has the CA in its trust store; sending just the leaf
            // is enough and avoids an invalid chain (e.g. a duplicate leaf cert) that
            // stricter TLS implementations (Conscrypt, some browsers) reject.
            setKeyEntry("mitm", keyPair.private, "".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "".toCharArray())
        val ctx = sslContextProvider?.let { SSLContext.getInstance("TLS", it) }
            ?: SSLContext.getInstance("TLS")
        return ctx.apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    // ── I/O helpers ─────────────────────────────────────────────────────

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return sb.toString().ifEmpty { null }
            if (b == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    /** Drains the header block of an HTTP request up to and including the terminating empty line. */
    private fun skipHeaders(input: InputStream) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0)
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        else target to defaultPort
    }

}
