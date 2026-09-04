package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
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
import com.example.interception.http.Http2Transaction
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.http.WebSocketParser
import com.example.interception.http.WsMessageAssembler
import com.example.interception.gateway.GatewaySessionHandler
import com.example.interception.gateway.LocalGateway
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.TransactionTimingEntity
import com.example.data.persistence.CaptureSink
import com.example.data.persistence.InterceptorFlags
import com.example.data.persistence.PersistenceHealth
import com.example.data.persistence.RowHandle
import com.example.interception.http.GrpcCallEnd
import com.example.interception.http.GrpcCallStart
import com.example.interception.http.SseParser
import com.example.interception.persist.ProtocolRows
import com.example.interception.relay.ConnectionStateMachine
import com.example.interception.relay.ConnectionTimingTracker
import com.example.interception.relay.RelayPool
import com.example.interception.tls.CertificateEngine
import com.example.interception.tls.ClientCertException
import com.example.interception.tls.ClientCertStore
import com.example.interception.uid.UidAttributor
import com.example.interception.relay.StructuredRelay
import com.example.interception.gateway.OriginalDstResolver
import com.example.interception.gateway.local.ClientOrigin
import com.example.interception.gateway.local.EntryKind
import com.example.interception.gateway.local.GatewaySession
import com.example.interception.gateway.local.ProtocolClassifier
import com.example.interception.gateway.local.Socks5Credentials
import com.example.interception.tls.ClientHelloCapture
import com.example.interception.tls.TlsClientHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
     * S2 task 18: like [onDecryptedTransaction] but also receives the measured phase timings
     * for the transaction (null when nothing could be observed, e.g. a mocked or blocked
     * exchange). When set it takes precedence over [onDecryptedTransaction]; the plain callback
     * stays for callers and tests that do not care about timings.
     */
    private val onDecryptedTransactionTimed: ((CapturedTrafficEntity, TransactionTimingEntity?) -> Unit)? = null,
    /**
     * S3 task 20: the persistence sink for parent rows AND their protocol children (WebSocket
     * messages, SSE events, gRPC call/messages, HTTP/2 frame events). When set it takes
     * precedence over both callbacks above -- production wires [PersistenceQueue] here; the
     * callbacks stay as the flat-entity seam for the many existing tests. Every call is
     * fire-and-forget on the relay thread.
     */
    private val sink: CaptureSink? = null,
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
    /**
     * RNG every [RuleAction.CHAOS] roll ([RuleEnforcer.applyChaosToRequest] /
     * [RuleEnforcer.applyChaosToResponse]) draws from. Test seam only -- production uses the
     * default so a `failRate` of 0.1 really is a 10% chance per matched exchange.
     */
    private val chaosRandom: kotlin.random.Random = kotlin.random.Random.Default,
    /**
     * How a [RuleEnforcer.ChaosApplyResult.Delay] is served. Production sleeps the calling
     * server→client relay thread (only that one connection stalls; the sleep happens OUTSIDE the
     * per-connection `clientWriteLock`); tests inject a recorder so a `[5000, 5000]` delay rule
     * can be asserted without actually waiting five seconds.
     */
    private val chaosSleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /** S1 task 7: bounded relay pool whose dispatcher runs both copy legs of every tunnel. */
    private val relayPool: RelayPool = RelayPool.shared(),
    /**
     * S1 task 8: the single leaf issuer (SNI-first identity, upstream sniffing, hardened EC
     * leaves, per-domain overrides, mTLS key managers). Null means "build the default engine
     * backed by [ClientCertStore.get] on first use"; tests inject one with fake I/O.
     */
    certificateEngine: CertificateEngine? = null,
    /** S1 task 10: UID attribution; null means the process-wide [UidAttributor.shared]. */
    uidAttributor: UidAttributor? = null,
    /** S1 task 9: serves SOCKS5 UDP ASSOCIATE through the capture-scoped UDP policy; null refuses it. */
    private val udpAssociationHandler: com.example.interception.gateway.UdpAssociationHandler? = null,
) : GatewaySessionHandler {
    private var gateway: LocalGateway? = null
    private var appContext: Context? = null
    private val running = AtomicBoolean(false)
    /**
     * Plaintext HTTP inside this tier (absolute-form forward-proxy requests, transparent HTTP,
     * or plaintext spoken inside a CONNECT tunnel) is forwarded by the same code path the
     * HTTP_PROXY tier uses, rather than a second copy of the request loop.
     */
    private val plaintextDelegate: HttpProxyEngine by lazy { HttpProxyEngine(scope) }
    private val packetCount = AtomicLong(0)
    private val sessionId = AtomicLong(System.currentTimeMillis())
    /**
     * S3 task 20: `InterceptorFlags.protocolChildren`, sampled at [start]. `true` writes v20
     * child rows under one parent; `false` keeps the pre-v20 flattened `WS→`/`x-grpc-*` rows.
     * Only consulted when a [sink] is present -- the callback seam is always flat.
     */
    @Volatile internal var protocolChildren: Boolean = true
    private val injectedCertificateEngine = certificateEngine
    /**
     * S1 task 8: one issuer for every leaf (see [CertificateEngine]); its internal
     * [ExpiringLruCertCache] keeps the §3 bounded/expiring LRU so a long-running capture that
     * MITMs many distinct hosts never retains every minted leaf forever.
     */
    private val certificateEngine: CertificateEngine by lazy {
        injectedCertificateEngine ?: CertificateEngine(policyStore = appContext?.let { ClientCertStore.get(it) })
    }

    val port: Int get() = gateway?.port ?: 0

    /** Connections the underlying [LocalGateway] dropped itself, keyed by reason. */
    val gatewayRejectCounts: Map<com.example.interception.gateway.local.GatewayRejectReason, Long>
        get() = gateway?.rejectCounts ?: emptyMap()

    companion object {
        private const val TAG = "MitmProxy"
        /** How long a tunnel client may stay silent after the tunnel is established. */
        private const val TUNNEL_FIRST_BYTE_TIMEOUT_MS = 15_000
        /**
         * Bytes buffered without a successful parse before falling back to blind relay: now the
         * relay pool's per-flow per-direction cap ([com.example.interception.relay.BufferPool.flowCapBytes],
         * 512 KiB..2 MiB, default 1 MiB) -- S1 task 7 / gate G3.
         */
        private val HTTP_METHODS = listOf(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE",
        )
    }

    /**
     * Starts a [LocalGateway] routed to this engine and returns the bound port.
     *
     * [socks5Credentials] (per-session, see [Socks5Credentials.random]) makes the SOCKS5 entry
     * require RFC 1929 auth -- the tun2proxy bridge supplies them plus the `+info` origin suffix.
     * [originalDstResolver] optionally resolves transparent TLS sessions that carry no SNI.
     */
    fun start(
        context: Context,
        port: Int = 0,
        socks5Credentials: Socks5Credentials? = null,
        originalDstResolver: OriginalDstResolver? = null,
    ): Int {
        if (running.get()) return this.port
        appContext = context.applicationContext
        plaintextDelegate.bind(context)
        protocolChildren = InterceptorFlags.get(context).protocolChildren

        // Pre-warm the CA material off the caller's thread (S1 task 7: no runBlocking on the
        // start path); the first MITM'd connection awaits the same suspend getOrCreate.
        scope.launch(Dispatchers.IO) {
            runCatching { CertificateAuthority.getOrCreate(context) }
                .onFailure { Log.w(TAG, "CA prewarm failed: ${it.message}") }
        }

        val gw = LocalGateway(
            handler = this,
            scope = scope,
            socks5Credentials = socks5Credentials,
            originalDstResolver = originalDstResolver,
            logTag = TAG,
            relayPool = relayPool,
            udpAssociationHandler = udpAssociationHandler,
        )
        val boundPort = gw.start(port)
        gateway = gw
        running.set(true)

        InterceptionStateRepository.markActive(
            sessionId = sessionId.get(), tier = CaptureMethod.MITM_PROXY.engineTier(),
            port = boundPort, method = CaptureMethod.MITM_PROXY,
            lifecycleOwner = CaptureMethod.MITM_PROXY.engineLifecycleOwner(),
        )
        return boundPort
    }

    fun stop() {
        running.set(false)
        gateway?.stop()
        gateway = null
        Log.i(TAG, "MITM proxy stopped")
    }

    // ── GatewaySessionHandler ──────────────────────────────────────────────

    override suspend fun handlePlaintext(session: GatewaySession) {
        plaintextDelegate.handlePlaintext(session)
    }

    /**
     * Rule-evaluated tunnel. The gateway has already consumed the protocol prelude (SOCKS
     * handshake / `CONNECT` head), so what remains on [GatewaySession.socket] is exactly the
     * client's payload: for a FORCE_MITM destination we peek it to decide between TLS
     * termination, plaintext HTTP forwarding, or an opaque relay.
     */
    override suspend fun handleTunnel(session: GatewaySession) {
        val context = appContext
        if (context == null) {
            Log.w(TAG, "handleTunnel before start(): refusing ${session.destination}")
            session.replyUnreachable()
            runCatching { session.socket.close() }
            return
        }
        handleClient(context, session)
    }

    private suspend fun handleClient(context: Context, session: GatewaySession) {
        var client: Socket = session.socket
        // S1 task 7: one lifecycle record per tunnel. The gateway already classified the entry
        // protocol, so the session is DETECTED by the time this handler sees it.
        val machine = ConnectionStateMachine()
        machine.transition(ConnectionStateMachine.State.DETECTED)
        // S2 task 18: measured phases for every transaction relayed on this connection.
        val timing = ConnectionTimingTracker()
        try {
            runCatching { client.setTcpNoDelay(true) }
            val output = client.getOutputStream()

            // The gateway already consumed the CONNECT head / SOCKS handshake, so the bytes
            // remaining on the socket are exactly the client's payload (normally a TLS
            // ClientHello). `target` keeps the client's own spelling for the opaque relay.
            val target = session.rawTarget
            val host = session.destination.host
            val port = session.destination.port
            val replies = HttpProxyShared.ConnectReplies.forSession(session)

            val db = RequestrDatabase.get(context)
            val disposition = ConnectControl.evaluate(db, host, port)
            // §3 (C2) -- read fresh per CONNECT for the non-MITM (opaque tunnel) paths below.
            // The MITM path re-reads this itself just before dialing upstream (see
            // `networkCondition` a few lines further down, passed into LazyUpstreamConnection).
            val opaqueNetworkCondition = SettingsDataStore.get(context).networkCondition.first()

            when (disposition) {
                is ConnectControl.Disposition.Block -> {
                    Log.i(TAG, "BLOCKED by rule: $host")
                    session.replyBlocked()
                    persistConnectAttempt(context, client, db, host, port, "BLOCKED", failureReason = disposition.reason, origin = session.origin)
                    return
                }
                is ConnectControl.Disposition.Redirect -> {
                    // Rule redirects this CONNECT to a different upstream — tunnel opaquely to
                    // the redirect target instead of the original (no MITM either way here).
                    // HttpProxyShared.handleConnectAttempt answers the client via `replies`.
                    HttpProxyShared.handleConnectAttempt(
                        client, disposition.target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                        replies = replies,
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "REDIRECTED",
                            redirectTarget = disposition.target, bytesToUpstream = sent, bytesToClient = recv,
                            origin = session.origin,
                        )
                    }
                    return
                }
                is ConnectControl.Disposition.AllowOpaque -> {
                    // No FORCE_MITM rule matched — tunnel opaquely rather than terminate TLS,
                    // even though this engine is capable of MITM.
                    // HttpProxyShared.handleConnectAttempt answers the client via `replies`.
                    HttpProxyShared.handleConnectAttempt(
                        client, target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                        replies = replies,
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "ALLOWED_OPAQUE",
                            bytesToUpstream = sent, bytesToClient = recv,
                            origin = session.origin,
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
                            replies = replies,
                        ) { sent, recv ->
                            persistConnectAttempt(
                                context, client, db, host, port, "ALLOWED_OPAQUE",
                                failureReason = "FORCE_MITM rule matched, but requestr's CA is not trusted " +
                                    "on this device (install it in Settings to enable MITM decryption for " +
                                    "this host) -- degraded to raw relay",
                                bytesToUpstream = sent, bytesToClient = recv,
                                origin = session.origin,
                            )
                        }
                        return
                    }
                    // Fall through to the existing MITM path below.
                }
            }

            // Tell the client the tunnel is up (HTTP 200 / SOCKS success / nothing for
            // transparent entries) — we're about to MITM.
            session.replyEstablished()

            // The tunnel payload is not guaranteed to be TLS: a FORCE_MITM rule can match an
            // `http://` CONNECT, a SOCKS5 client dialing port 80, or a non-TLS binary protocol.
            // Peek the first bytes and route accordingly instead of feeding JSSE garbage.
            val firstBytes = LocalGateway.peekPrefix(client, TUNNEL_FIRST_BYTE_TIMEOUT_MS)
            if (firstBytes == null) {
                persistConnectAttempt(
                    context, client, db, host, port, "FAILED",
                    failureReason = "client sent no payload after tunnel establishment",
                    origin = session.origin,
                )
                return
            }
            val (prefix, replaying) = firstBytes
            client = replaying
            when (ProtocolClassifier.classify(prefix)) {
                ProtocolClassifier.Classification.Tls -> Unit // fall through to TLS termination below
                ProtocolClassifier.Classification.HttpAbsolute,
                ProtocolClassifier.Classification.HttpOrigin,
                ProtocolClassifier.Classification.HttpConnect -> {
                    Log.i(TAG, "Plaintext HTTP inside tunnel to $host:$port -- forwarding decoded")
                    plaintextDelegate.handlePlaintext(
                        GatewaySession(replaying, EntryKind.TRANSPARENT_HTTP, session.destination, session.origin, target),
                    )
                    persistConnectAttempt(context, replaying, db, host, port, "PLAINTEXT_FORWARDED", origin = session.origin)
                    return
                }
                else -> {
                    Log.i(TAG, "Non-TLS payload inside tunnel to $host:$port -- relaying opaquely")
                    HttpProxyShared.handleConnectAttempt(
                        replaying, target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                        replies = HttpProxyShared.ConnectReplies({}, {}, {}),
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, replaying, db, host, port, "ALLOWED_OPAQUE",
                            failureReason = "FORCE_MITM rule matched, but the tunnel payload is not TLS -- relayed opaquely",
                            bytesToUpstream = sent, bytesToClient = recv,
                            origin = session.origin,
                        )
                    }
                    return
                }
            }

            // S1 task 8: upstream-leaf sniffing is CertificateEngine's default for IP-literal
            // and SNI-less connects; this §1.10 setting additionally requests it for named hosts
            // (extra fidelity at the cost of one protected upstream probe per first-seen host).
            val mirrorEnabled = SettingsDataStore.get(context).remoteCertMirrorEnabled.first()
            // §3 (C2) -- read fresh per CONNECT, same convention as mirrorEnabled above. Disabled
            // (all-zero/no-op) by default, so a user who never touches this setting pays nothing.
            val networkCondition = SettingsDataStore.get(context).networkCondition.first()

            // Real JA3 (M6): peek the raw ClientHello record directly off the accepted socket
            // BEFORE handing it to JSSE -- by the time startHandshake() below returns, JSSE has
            // consumed those bytes and there is no supported way to read them back. peeked.socket
            // replays the exact same bytes to JSSE (see ClientHelloCapture's KDoc), so the
            // handshake itself is completely unaffected by this.
            //
            // S1 task 8: the peek now happens BEFORE leaf issuance, so the ClientHello's SNI is
            // the authoritative identity for the leaf (CONNECT 1.2.3.4:443 with SNI example.com
            // gets a CN=example.com leaf, not CN=1.2.3.4).
            val peeked = ClientHelloCapture.peekClientHello(client)
            val clientHello: TlsClientHello? = peeked.clientHello
            client = peeked.socket

            val issuance = try {
                certificateEngine.issue(
                    context,
                    CertificateEngine.Request(
                        connectAuthority = host, port = port, sni = clientHello?.sni,
                        preferUpstreamSniff = mirrorEnabled,
                    ),
                )
            } catch (e: ClientCertException) {
                persistConnectAttempt(
                    context, client, db, host, port, "FAILED",
                    failureReason = "certificate policy: ${e.message}",
                    origin = session.origin,
                )
                machine.fail("certificate policy: ${e.message}")
                return
            }
            val leafIdentity = issuance.identity.host
            val (leafKey, leafChain) = when (issuance) {
                is CertificateEngine.Outcome.Passthrough -> {
                    Log.i(TAG, "PASSTHROUGH policy for $leafIdentity ($host:$port) -- relaying TLS opaquely")
                    HttpProxyShared.handleConnectAttempt(
                        client, target, ConnectControl.Disposition.AllowOpaque, output,
                        networkCondition = opaqueNetworkCondition,
                        replies = HttpProxyShared.ConnectReplies({}, {}, {}),
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "ALLOWED_OPAQUE",
                            failureReason = "per-domain PASSTHROUGH policy (${issuance.policy.hostPattern}) -- not decrypted",
                            bytesToUpstream = sent, bytesToClient = recv,
                            origin = session.origin,
                        )
                    }
                    return
                }
                is CertificateEngine.Outcome.Custom -> issuance.privateKey to issuance.chain
                is CertificateEngine.Outcome.Issued -> issuance.keyPair.private to arrayOf(issuance.certificate)
            }

            // Upgrade client side to TLS with the leaf (or imported server cert) for this identity
            val sslContext = createSslContext(leafKey, leafChain)
            val sslSocket = sslContext.socketFactory
                .createSocket(client, host, port, true) as SSLSocket
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
            machine.transition(ConnectionStateMachine.State.TLS)
            try {
                sslSocket.startHandshake()
            } catch (e: javax.net.ssl.SSLException) {
                // S1 task 13: keep the classified reason so TrustDiagnostics can say *why*.
                ConnectControl.PinningFailureRegistry.recordFailure(host, reason = e.message?.take(160))
                persistFailedMitmTlsSession(db, host, port, "client-facing handshake", e)
                machine.fail("client-facing handshake: ${e.message}")
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
            // S1 task 8: mutual TLS -- a per-domain policy with client material makes the
            // upstream leg present that client certificate; a misconfigured one fails by name.
            val upstreamKeyManagers = try {
                certificateEngine.clientKeyManagersFor(leafIdentity)
            } catch (e: ClientCertException) {
                persistFailedMitmTlsSession(db, host, port, "client certificate", e)
                persistConnectAttempt(context, client, db, host, port, "FAILED", failureReason = "mTLS: ${e.message}", origin = session.origin)
                machine.fail("mTLS: ${e.message}")
                runCatching { sslSocket.close() }
                return
            }
            val upstream = LazyUpstreamConnection(
                host, port,
                preferredAlpnProtocols = negotiatedAlpn?.let { listOf(it) } ?: emptyList(),
                networkCondition = networkCondition,
                keyManagers = upstreamKeyManagers,
                onFirstConnect = { connected ->
                // S2 task 18: the upstream leg's DNS/connect/TLS phases belong to the first
                // transaction on this connection.
                timing.attachSetup(connected.setupTimings)
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
            },
            )

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

            // S1 task 7: both directions run as structured coroutines on the relay pool
            // dispatcher (StructuredRelay.run) instead of two raw Threads joined with a 60 s
            // timeout. Control only returns here once BOTH legs are done (or the drain deadline
            // force-closed the stalled one), so the MITM_DECRYPTED row below is written from the
            // connection's terminal state -- never while bytes are still moving.
            machine.transition(ConnectionStateMachine.State.RELAYING)
            val clientToUpstream: () -> Unit
            val upstreamToClient: () -> Unit
            if (negotiatedAlpn == "h2") {
                // §3/§5: HTTP/2 is decided once, upfront, from the ALPN result -- this
                // connection never falls into the HTTP/1.x buffering loop or its blind-relay
                // fallback at all. Every byte is forwarded immediately in both directions (h2
                // frames are never rule-evaluated -- that only applies to the HTTP/1.x path);
                // Http2Session is handed a copy of each parsed frame purely to explain what it
                // means (HPACK decode, per-stream pairing, gRPC message framing).
                val grpcCalls = GrpcCallTable()
                val h2Session = Http2Session(
                    onTransaction = { tx ->
                        val handle = emitTransaction(
                            tx.request, tx.response, tx.latencyMs, "$host:$port", logRequests, logResponses,
                            timings = h2Timings(tx, timing),
                            headers = ProtocolRows.headerRows(
                                tx.request, tx.response, logRequests, logResponses,
                                h2Request = tx.requestHeaders, h2Response = tx.responseHeaders,
                                h2RequestTrailers = tx.requestTrailers, h2ResponseTrailers = tx.responseTrailers,
                                metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
                            ),
                        )
                        if (handle != null && tx.frameEvents.isNotEmpty()) {
                            sink?.http2StreamEvents(handle, ProtocolRows.http2StreamEvents(tx.frameEvents, "$host:$port"))
                        }
                    },
                    onGrpcMessage = { event -> emitGrpcMessage(event, host, port, logRequests, logResponses, grpcCalls) },
                    onGrpcCallStarted = { start -> emitGrpcCallStart(start, host, port, logRequests, grpcCalls) },
                    onGrpcCallEnded = { end -> emitGrpcCallEnd(end, host, port, logRequests, logResponses, grpcCalls) },
                )
                clientToUpstream = { inspectH2ClientToServer(db, decryptedInput, upstream, host, port, h2Session, sent) }
                upstreamToClient = { inspectH2ServerToClient(upstream, decryptedOutput, host, port, h2Session, received) }
            } else {
                // HTTP/1.x, with an in-place switch to WebSocket framing the moment a 101
                // upgrade response is observed -- see handleParsedResponse and
                // ConnectionProtocolState. Genuinely unrecognized protocols still fall back to
                // the pre-existing blind relay inside inspectClientToServer/inspectServerToClient.
                val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
                val clientWriteLock = Any()
                val wsState = ConnectionProtocolState()
                clientToUpstream = {
                    inspectClientToServer(db, decryptedInput, upstream, decryptedOutput,
                        clientWriteLock, host, port, pending, sent, logRequests, logResponses, wsState, timing)
                }
                upstreamToClient = {
                    inspectServerToClient(
                        db, upstream, decryptedOutput, clientWriteLock, pending, host, port, received,
                        logRequests, logResponses, wsState, timing,
                    )
                }
            }
            val outcome = StructuredRelay.run(
                machine = machine,
                dispatcher = relayPool.dispatcher,
                metrics = relayPool.metrics,
                drainTimeoutMs = StructuredRelay.DEFAULT_DRAIN_TIMEOUT_MS,
                onDrainTimeout = {
                    runCatching { sslSocket.close() }
                    runCatching { upstream.giveUp() }
                    runCatching { upstream.closeQuietly() }
                },
                clientToUpstream = clientToUpstream,
                upstreamToClient = upstreamToClient,
            )
            relayPool.metrics.addBytesToUpstream(sent.get())
            relayPool.metrics.addBytesToClient(received.get())

            val relayFailed = !outcome.anyLegEndedNormally && sent.get() == 0L && received.get() == 0L &&
                outcome.errors.isNotEmpty()
            if (relayFailed) {
                val cause = outcome.errors.first()
                machine.fail(cause.message ?: cause.javaClass.simpleName)
                relayPool.metrics.onFailed()
                persistConnectAttempt(
                    context, client, db, host, port, "FAILED",
                    negotiatedAlpn = negotiatedAlpn,
                    failureReason = "relay failed before any byte was exchanged: ${cause.message ?: cause.javaClass.simpleName}",
                    origin = session.origin,
                )
            } else {
                machine.close()
                relayPool.metrics.onCompleted()
                persistConnectAttempt(
                    context, client, db, host, port, "MITM_DECRYPTED",
                    negotiatedAlpn = negotiatedAlpn,
                    bytesToUpstream = sent.get(), bytesToClient = received.get(),
                    failureReason = if (outcome.drainTimedOut) {
                        "one leg stalled after the other closed; force-closed at the ${StructuredRelay.DEFAULT_DRAIN_TIMEOUT_MS / 1000}s drain deadline"
                    } else null,
                    origin = session.origin,
                )
            }

            packetCount.addAndGet(1)
            InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler: ${e.message}")
            machine.fail(e.message ?: e.javaClass.simpleName)
        } finally {
            // Every exit that is not an explicit close() above is a failure or an early return
            // (blocked, redirected, opaque, plaintext...) whose row was already written by its
            // own branch; the machine just needs a terminal so nothing lingers non-terminal.
            if (!machine.isTerminal) machine.close()
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
    private suspend fun persistConnectAttempt(
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
        origin: ClientOrigin? = null,
    ) {
        runCatching {
            // S1 task 10: one attribution path (bridge +info tuple -> getConnectionOwnerUid ->
            // /proc/net/tcp -> single-app selection inference -> explicit UNATTRIBUTED). The
            // reasons for an unattributed flow are recorded on the row instead of a silent null.
            val attribution = uidAttributor(context).attribute(
                UidAttributor.Flow(origin = origin, clientSocket = client, destination = null),
            )
            val attributionNote = when (attribution) {
                is UidAttributor.Attribution.Resolved ->
                    if (attribution.isInferred) "uid inferred from single-app capture selection" else null
                is UidAttributor.Attribution.Unattributed -> "uid unattributed: ${attribution.reason}"
            }
            val entity = ConnectAttemptEntity(
                sessionId = sessionId.get(),
                targetHost = host,
                targetPort = port,
                clientUid = attribution.uidOrNull,
                negotiatedAlpn = negotiatedAlpn,
                disposition = disposition,
                redirectTarget = redirectTarget,
                bytesToClient = bytesToClient,
                bytesToUpstream = bytesToUpstream,
                failureReason = listOfNotNull(failureReason, attributionNote).joinToString(" | ").ifEmpty { null },
                finishedAt = System.currentTimeMillis(),
            )
            db.connectAttemptDao().insert(entity)
        }.onFailure { e -> Log.w(TAG, "Failed to persist CONNECT attempt for $host: ${e.message}") }
    }

    private val injectedUidAttributor = uidAttributor
    private fun uidAttributor(context: Context): UidAttributor = injectedUidAttributor ?: UidAttributor.shared(context)

    // ── WebSocket mode switch (§4/§5) ────────────────────────────────────

    /**
     * Per-connection state shared by [inspectClientToServer]/[inspectServerToClient] for the
     * HTTP/1.x relay path: once a 101 upgrade response is observed (see [handleParsedResponse]),
     * [webSocketMode] flips to `true` and every subsequent byte on both directions is parsed as
     * WebSocket frames instead of HTTP/1.x -- this is what removes the old blind-relay fallback
     * for WebSocket traffic (§5). [clientAssembler]/[serverAssembler] reassemble fragmented
     * messages (RFC 6455 §5.4) independently per direction, matching the per-direction
     * [Http2Parser.HpackDecoder] convention used for HTTP/2.
     *
     * `internal` (not `private`): [handleParsedResponse] takes this as a parameter and is
     * itself `internal` for testability (security audit fix, MUST-FIX 2) -- a `private` type
     * can't appear in an `internal` function's signature.
     */
    internal class ConnectionProtocolState {
        val webSocketMode = AtomicBoolean(false)
        @Volatile var wsUrl: String = ""
        val clientAssembler = WsMessageAssembler()
        val serverAssembler = WsMessageAssembler()
        /** S3 task 20: the 101 upgrade row every message on this connection hangs off. */
        @Volatile var wsParent: RowHandle? = null
        /** S3 task 20: next `frameIndex`, both directions interleaved. */
        val wsFrameIndex = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /**
     * S3 task 20: per-connection gRPC call table (h2 stream id -> parent row + running byte
     * totals), created by the call-start event and released by the call-end event.
     */
    internal class GrpcCallTable {
        class Entry(val parent: RowHandle, val entity: CapturedTrafficEntity, val startedAt: Long) {
            val requestBytes = AtomicLong(0)
            val responseBytes = AtomicLong(0)
        }
        val byStream = java.util.concurrent.ConcurrentHashMap<Int, Entry>()
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
        timing: ConnectionTimingTracker? = null,
    ) {
        val buffer = ByteArrayOutputStream()
        val wsBuffer = ByteArrayOutputStream()
        val chunk = relayPool.buffers.acquire()
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
                            emitWsFrame(complete, isClient = true, wsState, host, port, logRequests, logResponses)
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
                        if (!couldBecomeHttpRequest(bytes) || bytes.size > relayPool.buffers.flowCapBytes) {
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
                            host, port, logRequests, logResponses, timing,
                        )
                        buffer.reset()
                        buffer.write(bytes)
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */
        } finally {
            relayPool.buffers.release(chunk)
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
            // S1 task 13: a server that demands a client certificate is an mTLS host, not a
            // pinning or CA problem -- remember it so the trust report can say so.
            com.example.interception.tls.MutualTlsSignals.recordIfDemand(host, e)
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
        timing: ConnectionTimingTracker? = null,
    ) {
        // §1.5 MUST-FIX: mirrors inspectClientToServer's lazy connect -- this reader has
        // nothing to read until the upstream connection actually exists. If the whole
        // client→server side finishes without ever needing it (e.g. every request on this
        // connection was MOCK_RESPONSE), upstream.giveUp() unblocks this with null and this
        // direction never touches the network either.
        val input = upstream.awaitConnectedOrGiveUp()?.input ?: return

        val buffer = ByteArrayOutputStream()
        val wsBuffer = ByteArrayOutputStream()
        val chunk = relayPool.buffers.acquire()
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
                            emitWsFrame(complete, isClient = false, wsState, host, port, logRequests, logResponses)
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
                // S2 task 18: an empty parse buffer means these are the first bytes of a new
                // response -- the TTFB instant for the request at the head of `pending`.
                if (buffer.size() == 0) timing?.markFirstResponseByte()
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
                        if (!couldBecomeHttpResponse(bytes) || bytes.size > relayPool.buffers.flowCapBytes) {
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
                            logRequests, logResponses, wsState, timing,
                        )
                        buffer.reset()
                        buffer.write(bytes)
                        // Leftover bytes already belong to the next pipelined response.
                        if (bytes.isNotEmpty()) timing?.markFirstResponseByte()
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */
        } finally {
            relayPool.buffers.release(chunk)
        }
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
        val chunk = relayPool.buffers.acquire()
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
            relayPool.buffers.release(chunk)
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
        val chunk = relayPool.buffers.acquire()
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
            relayPool.buffers.release(chunk)
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
        timing: ConnectionTimingTracker? = null,
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
            RuleAction.CHAOS -> {
                // Master plan §3 LOW-ADD: the `failRate` die is rolled HERE, before upstream is
                // ever dialed, so an injected failure really behaves like an unreachable server
                // -- the request never reaches the backend (no real side effect), the client gets
                // the synthetic 503 (or the rule's failStatusCode), and the exchange is still
                // persisted (0ms, stamped x-requestr-chaos) so it is not invisible in the list.
                // Same short-circuit shape as BLOCK / MOCK_RESPONSE above. The corrupt/delay dice
                // are rolled on the real response in handleParsedResponse.
                val chaos = rule?.let { RuleEnforcer.applyChaosToRequest(it, chaosRandom) }
                if (chaos is RuleEnforcer.ChaosApplyResult.Fail) {
                    synchronized(clientWriteLock) {
                        clientOut.write(serializeResponse(chaos.failureResponse))
                        clientOut.flush()
                    }
                    Log.i(TAG, "CHAOS fail (decrypted): ${request.method?.name} ${request.url.take(80)} -> ${chaos.failureResponse.statusCode}")
                    emitTransaction(request, chaos.failureResponse, 0L, "$host:$port", logRequests, logResponses)
                    return
                }
                raw
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
                val scriptError = RuleEnforcer.applyScriptRewrite(request, rule?.replacementValue)
                val bytes = serializeRequest(request)
                if (scriptError != null && rule != null) {
                    // Added AFTER serialization on purpose: this diagnostic must reach the
                    // persisted row (emitTransaction keeps x-requestr-* request headers even with
                    // request logging off) but must never be sent to the origin server.
                    request.headers = request.headers +
                        (RuleEnforcer.SCRIPT_ERROR_HEADER to RuleEnforcer.scriptErrorHeaderValue(scriptError, rule.name))
                }
                bytes
            }
            RuleAction.BODY_ENCRYPT_AES, RuleAction.BODY_DECRYPT_AES -> {
                val cryptoResult = rule?.let {
                    RuleEnforcer.applyBodyCrypto(request, it, encrypt = it.action == RuleAction.BODY_ENCRYPT_AES)
                }
                if (cryptoResult is RuleEnforcer.CryptoApplyResult.Failed) {
                    // Security audit fix (fail-open AES crypto): never forward the
                    // untransformed request -- for BODY_ENCRYPT_AES that would be exactly the
                    // plaintext the rule was supposed to encrypt. Same short-circuit shape as
                    // BLOCK above: the real request never reaches upstream, and the client (and
                    // the captured-traffic UI) see a visibly-failed transaction rather than one
                    // that looks like it succeeded.
                    val failureResponse = RuleEnforcer.buildCryptoFailureResponse(
                        ruleName = rule!!.name,
                        encrypt = rule.action == RuleAction.BODY_ENCRYPT_AES,
                        reason = cryptoResult.reason,
                    )
                    synchronized(clientWriteLock) {
                        clientOut.write(serializeResponse(failureResponse))
                        clientOut.flush()
                    }
                    Log.w(TAG, "BLOCKED (crypto failure): ${request.method?.name} ${request.url.take(80)}")
                    emitTransaction(request, failureResponse, 0L, "$host:$port", logRequests, logResponses)
                    return
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
        val sendStartedAt = System.currentTimeMillis()
        pending.add(request to sendStartedAt)
        upstreamOut.write(bytesToForward)
        upstreamOut.flush()
        // S2 task 18: sendMs is exactly the write+flush above; the response leg measures the rest.
        timing?.onRequestSent(request, sendStartedAt)
    }

    /**
     * `internal` (not `private`), same rationale as [handleParsedRequest]/[emitTransaction]:
     * asserting the BODY_ENCRYPT_AES/BODY_DECRYPT_AES fail-closed contract (security audit fix)
     * needs direct access to drive a synthesized response through this function with fake
     * streams, without standing up a real TLS/socket pair end-to-end.
     */
    internal fun handleParsedResponse(
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
        timing: ConnectionTimingTracker? = null,
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
        // S2 task 18: the response is complete at this instant -- before any CHAOS delay below,
        // which is requestr's own doing and belongs in latencyMs, not in the measured phases.
        val measured = if (request != null) timing?.completeResponse(request) else null
        var out = raw
        // Security audit fix (fail-open AES crypto): what actually gets persisted/shown in the
        // UI for this transaction. Defaults to the real [response]; overridden below to a
        // synthetic failure response when a BODY_ENCRYPT_AES/BODY_DECRYPT_AES rule fails, so the
        // client and the captured-traffic list both see a visibly-failed transaction rather than
        // the untransformed body forwarded as if it had succeeded.
        var responseToEmit = response
        // Master plan §3 LOW-ADD (CHAOS delay): served AFTER the rule block and BEFORE taking
        // clientWriteLock, so only this connection's server→client relay thread stalls and the
        // client-observed latency persisted below honestly includes the injected delay.
        var chaosDelayMs = 0L
        if (request != null) {
            val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
            if (rule?.action == RuleAction.CHAOS) {
                when (val chaos = RuleEnforcer.applyChaosToResponse(response, rule, chaosRandom)) {
                    is RuleEnforcer.ChaosApplyResult.Applied -> {
                        Log.i(TAG, "CHAOS corrupt-body (decrypted): ${request.method?.name} ${request.url.take(80)}")
                        out = serializeResponse(response)
                    }
                    is RuleEnforcer.ChaosApplyResult.Delay -> {
                        Log.i(TAG, "CHAOS delay ${chaos.millis}ms (decrypted): ${request.method?.name} ${request.url.take(80)}")
                        chaosDelayMs = chaos.millis
                        out = serializeResponse(response) // carries the x-requestr-chaos header
                    }
                    // Fail is only ever produced on the request leg; PassThrough forwards raw.
                    is RuleEnforcer.ChaosApplyResult.Fail, RuleEnforcer.ChaosApplyResult.PassThrough -> Unit
                }
            } else if (rule?.action == RuleAction.MODIFY_RESPONSE) {
                rule.replacementValue?.let { RuleEnforcer.applyResponseModification(response, it) }
                out = serializeResponse(response)
            } else if (rule?.action == RuleAction.SCRIPT_REWRITE) {
                val scriptError = RuleEnforcer.applyScriptRewrite(response, rule.replacementValue)
                if (scriptError != null) {
                    // On the wire AND persisted: the client under test can see the rewrite did
                    // not happen, and the traffic list badges the row instead of logcat-only.
                    response.headers = response.headers +
                        (RuleEnforcer.SCRIPT_ERROR_HEADER to RuleEnforcer.scriptErrorHeaderValue(scriptError, rule.name))
                }
                out = serializeResponse(response)
            } else if (rule?.action == RuleAction.BODY_ENCRYPT_AES || rule?.action == RuleAction.BODY_DECRYPT_AES) {
                val cryptoResult = RuleEnforcer.applyBodyCrypto(response, rule, encrypt = rule.action == RuleAction.BODY_ENCRYPT_AES)
                if (cryptoResult is RuleEnforcer.CryptoApplyResult.Failed) {
                    // The real request already went out and a real response came back -- that
                    // round trip cannot be undone. What can still be stopped is forwarding the
                    // untransformed response body to the client: for BODY_DECRYPT_AES that would
                    // be the raw ciphertext the rule was supposed to decrypt, silently passed
                    // through looking like a normal successful response.
                    responseToEmit = RuleEnforcer.buildCryptoFailureResponse(
                        ruleName = rule.name,
                        encrypt = rule.action == RuleAction.BODY_ENCRYPT_AES,
                        reason = cryptoResult.reason,
                    )
                    out = serializeResponse(responseToEmit)
                } else {
                    out = serializeResponse(response)
                }
            }
        }
        if (chaosDelayMs > 0L) chaosSleep(chaosDelayMs)
        synchronized(clientWriteLock) {
            clientOut.write(out)
            clientOut.flush()
        }
        if (request != null) {
            val latencyMs = System.currentTimeMillis() - pair!!.second
            val handle = emitTransaction(
                request, responseToEmit, latencyMs, "$host:$port", logRequests, logResponses, timings = measured,
                headers = ProtocolRows.headerRows(
                    request, responseToEmit, logRequests, logResponses, metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
                ),
            )
            // S3 task 20: a text/event-stream response's events become `sse_events` children of
            // this row. The HTTP/1.x relay hands over the response once its framing completes,
            // so a finite stream is persisted whole here; ProtocolRows.sseEvents numbers events
            // from an offset so a streaming producer can append in later batches.
            if (handle != null && logResponses && SseParser.looksLikeSse(responseToEmit.headers) &&
                responseToEmit.body.isNotEmpty()
            ) {
                sink?.sseEvents(handle, ProtocolRows.sseEvents(responseToEmit.body, System.currentTimeMillis()))
            }
            // §4/§5: a successful WebSocket upgrade handshake is terminal for HTTP/1.x framing
            // on this connection -- everything from here on is WS frames, in both directions.
            // Switching modes here (rather than, say, only in inspectClientToServer) means both
            // relay threads see the flip at the same logical point: right after the 101 that
            // caused it, before either side's next read.
            if (responseToEmit.statusCode == 101 &&
                WebSocketParser.looksLikeUpgrade(request.headers, true) &&
                WebSocketParser.looksLikeUpgrade(responseToEmit.headers, false)
            ) {
                wsState.wsUrl = buildWsUrl(request.url)
                wsState.wsParent = handle
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
        /** S2 task 18: measured phases, or null when nothing was observed for this exchange. */
        timings: TransactionTimingEntity? = null,
        /** S3 task 20: wire-ordered `transaction_headers` rows; empty when the caller has none. */
        headers: List<TransactionHeaderEntity> = emptyList(),
    ): RowHandle? {
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
            // requestr's own metadata about what IT did to this exchange is kept even when header
            // logging is off, so the CHAOS / SCRIPT ERROR / crypto-failed badges never silently
            // disappear with that toggle. Matched against the closed
            // RuleEnforcer.METADATA_HEADER_KEYS allow-list rather than an `x-requestr-` prefix: a
            // prefix match would also retain a header the client app under test happened to send
            // with that prefix, and client content is precisely what the toggle suppresses.
            requestHeaders = if (logRequests) request.headers
            else request.headers.filterKeys { it in RuleEnforcer.METADATA_HEADER_KEYS },
            responseHeaders = if (logResponses) response.headers
            else response.headers.filterKeys { it in RuleEnforcer.METADATA_HEADER_KEYS },
            // storage.text (not message.body): for a bodyReplaced message the text column must
            // hold what was actually sent -- see HttpBodyDecoder.BinaryBodyStorage's contract.
            requestBody = if (logRequests) reqStorage.text.ifEmpty { null } else null,
            responseBody = if (logResponses) respStorage.text.ifEmpty { null } else null,
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
        return deliverEntity(entity, timings, headers)
    }

    /**
     * S2 task 18: HTTP/2 phases from the instants [Http2Session] stamped on the stream. `send`
     * is first request frame -> END_STREAM on the request; `wait` is request end -> first
     * response HEADERS; `receive` is response HEADERS -> END_STREAM on the response. The
     * upstream setup phases go to the first stream that completes on the connection.
     */
    private fun h2Timings(tx: Http2Transaction, timing: ConnectionTimingTracker): TransactionTimingEntity? {
        val setup = timing.takeSetup()
        val startedAt = tx.startedAt
        val phase = ConnectionTimingTracker::phaseOrNull
        if (startedAt == null && setup == null) return null
        return TransactionTimingEntity(
            trafficId = 0L,
            dnsMs = setup?.dnsMs,
            connectMs = setup?.connectMs,
            tlsMs = setup?.tlsMs,
            sendMs = phase(startedAt, tx.requestEndedAt, PersistenceHealth),
            waitMs = phase(tx.requestEndedAt, tx.responseHeadersAt, PersistenceHealth),
            receiveMs = phase(tx.responseHeadersAt, tx.responseEndedAt, PersistenceHealth),
            totalMs = phase(startedAt, tx.responseEndedAt, PersistenceHealth),
            queuedAt = startedAt,
            firstByteAt = tx.responseHeadersAt,
            lastByteAt = tx.responseEndedAt,
        )
    }

    /**
     * §4/§5 + S3 task 20: emit one reassembled WebSocket message (see [WsMessageAssembler]).
     *
     * With a [sink] and `protocolChildren` on, the message is a `websocket_messages` child of the
     * connection's 101 upgrade row ([ConnectionProtocolState.wsParent]); text is capped at
     * [com.example.data.local.WebSocketMessageEntity.MAX_TEXT_CHARS], binary is byte-exact up to
     * `MAX_STORED_BODY_BYTES`, and `payloadTruncated` says so honestly. Otherwise the pre-v20
     * flattened `WS→`/`WS←` [CapturedTrafficEntity] is produced, matching
     * [com.example.interception.CaptureEngine]'s legacy convention exactly.
     */
    internal fun emitWsFrame(
        frame: WebSocketParser.WsFrame,
        isClient: Boolean,
        wsState: ConnectionProtocolState,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
    ) {
        val parent = wsState.wsParent
        val target = sink
        if (target != null && protocolChildren && parent != null) {
            val row = ProtocolRows.webSocketMessage(
                frame, isClient, "$host:$port", wsState.wsFrameIndex.getAndIncrement(),
                logPayload = if (isClient) logRequests else logResponses,
                now = System.currentTimeMillis(),
            )
            runCatching { target.webSocketMessage(parent, row) }
                .onSuccess { bumpCounters() }
                .onFailure { Log.w(TAG, "websocket child failed: ${it.message}") }
            return
        }
        val entity = CapturedTrafficEntity(
            sessionId = sessionId.get(),
            method = if (isClient) "WS→" else "WS←",
            url = "${wsState.wsUrl} (${frame.opcodeName})",
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
     * S3 task 20: a gRPC call was identified from its request HEADERS. Creates the one
     * `captured_traffic` parent row for the whole call (`protocol = "gRPC"`, `statusCode` NULL
     * until the trailers arrive) plus its `grpc_calls` row, and remembers the handle so every
     * message on the stream can hang off it. No-op on the legacy (flat) path.
     */
    internal fun emitGrpcCallStart(
        start: GrpcCallStart,
        host: String,
        port: Int,
        logRequests: Boolean,
        table: GrpcCallTable,
    ) {
        val target = sink ?: return
        if (!protocolChildren) return
        val call = start.call
        val entity = ProtocolRows.grpcParent(
            call, sessionId.get(), host, "$host:$port", CaptureRowMethod.MITM_PROXY,
            wasDecrypted = true, logRequests = logRequests, now = start.startedAt,
            metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
        )
        check(!ProtocolRows.hasSyntheticGrpcHeader(entity)) { "gRPC parent must not carry synthetic x-grpc-* headers" }
        val headers = ProtocolRows.headerRows(
            request = null, response = null, logRequests = logRequests, logResponses = false,
            h2Request = start.requestHeaders, metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
        ) + ProtocolRows.headerRows(
            request = ParsedHttpMessage(isRequest = true, method = null, headerLines = start.requestHeaders.regularEntries),
            response = null, logRequests = logRequests, logResponses = false,
            metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
        )
        runCatching {
            val handle = target.transaction(entity, headers)
            target.grpcCall(handle, ProtocolRows.grpcCall(call, call.streamId, grpcStatus = null, grpcMessage = null))
            table.byStream[call.streamId] = GrpcCallTable.Entry(handle, entity, start.startedAt)
        }.onSuccess { bumpCounters() }
            .onFailure { Log.w(TAG, "gRPC call-start persist failed: ${it.message}") }
    }

    /**
     * §3/§5 + S3 task 20: emit one gRPC message (request or response direction).
     *
     * With a [sink] and `protocolChildren` on, the message is a `grpc_messages` child of the
     * call's parent row (byte-exact payload up to `MAX_STORED_BODY_BYTES`, decoded protobuf
     * field tree in `decodedText`) -- no synthetic `x-grpc-*` header anywhere. Otherwise the
     * pre-v20 flattened [CapturedTrafficEntity] is produced, matching
     * [com.example.interception.CaptureEngine]'s legacy convention exactly.
     */
    internal fun emitGrpcMessage(
        event: GrpcEvent,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
        table: GrpcCallTable? = null,
    ) {
        val call = event.call
        val msg = event.message
        val target = sink
        val entry = table?.byStream?.get(call.streamId)
        if (target != null && protocolChildren && entry != null) {
            val row = ProtocolRows.grpcMessage(
                msg, event.isClient, call.streamId, event.messageIndex,
                logPayload = if (event.isClient) logRequests else logResponses,
                now = System.currentTimeMillis(),
            )
            val wire = (msg.length + GrpcDetector.MESSAGE_HEADER_SIZE).toLong()
            if (event.isClient) entry.requestBytes.addAndGet(wire) else entry.responseBytes.addAndGet(wire)
            runCatching { target.grpcMessage(entry.parent, row) }
                .onSuccess { bumpCounters() }
                .onFailure { Log.w(TAG, "gRPC message child failed: ${it.message}") }
            return
        }
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
     * S3 task 20: the gRPC stream ended. Patches the parent with `:status`, the response headers
     * and byte totals, upserts `grpc_calls` with the trailer status, replaces the header rows
     * (pseudo + response + both trailer blocks) and appends the stream's frame timeline.
     */
    internal fun emitGrpcCallEnd(
        end: GrpcCallEnd,
        host: String,
        port: Int,
        logRequests: Boolean,
        logResponses: Boolean,
        table: GrpcCallTable,
    ) {
        val target = sink ?: return
        val entry = table.byStream.remove(end.streamId) ?: return
        val patch = ProtocolRows.grpcParentPatch(
            entry.entity, end.responseHeaders,
            requestBytes = entry.requestBytes.get(), responseBytes = entry.responseBytes.get(),
            latencyMs = (end.responseEndedAt - entry.startedAt).coerceAtLeast(0L),
            logResponses = logResponses,
        )
        val headers = ProtocolRows.headerRows(
            request = end.requestHeaders?.let { ParsedHttpMessage(isRequest = true, method = null, headerLines = it.regularEntries) },
            response = end.responseHeaders?.let { ParsedHttpMessage(isRequest = false, method = null, headerLines = it.regularEntries) },
            logRequests = logRequests, logResponses = logResponses,
            h2Request = end.requestHeaders, h2Response = end.responseHeaders,
            h2RequestTrailers = end.requestTrailers, h2ResponseTrailers = end.responseTrailers,
            metadataKeys = RuleEnforcer.METADATA_HEADER_KEYS,
        )
        runCatching {
            target.patchExchange(entry.parent, patch, headers)
            target.grpcCall(entry.parent, ProtocolRows.grpcCall(end.call, end.streamId, end.grpcStatus, end.grpcMessage))
            if (end.frameEvents.isNotEmpty()) {
                target.http2StreamEvents(entry.parent, ProtocolRows.http2StreamEvents(end.frameEvents, "$host:$port"))
            }
        }.onFailure { Log.w(TAG, "gRPC call-end persist failed: ${it.message}") }
    }

    private fun bumpCounters() {
        packetCount.incrementAndGet()
        InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
    }

    /**
     * Shared tail for [emitTransaction]/[emitWsFrame]/[emitGrpcMessage]: hands [entity] to
     * [onDecryptedTransaction], bumping [packetCount] on success. Never throws -- a failed
     * callback must not break the relay that already forwarded the underlying bytes.
     */
    private fun deliverEntity(
        entity: CapturedTrafficEntity,
        timings: TransactionTimingEntity? = null,
        headers: List<TransactionHeaderEntity> = emptyList(),
    ): RowHandle? {
        val target = sink
        if (target != null) {
            // S3 task 20: the queue path -- parent row + wire-ordered headers in one op; the
            // returned handle is what protocol children hang off.
            return runCatching { target.transaction(entity, headers, timings) }
                .onSuccess { bumpCounters() }
                .onFailure { Log.w(TAG, "sink.transaction failed: ${it.message}") }
                .getOrNull()
        }
        val timed = onDecryptedTransactionTimed
        val callback = onDecryptedTransaction
        if (timed == null && callback == null) return null
        runCatching { if (timed != null) timed(entity, timings) else callback!!(entity) }
            .onSuccess { bumpCounters() }
            .onFailure { Log.w(TAG, "onDecryptedTransaction failed: ${it.message}") }
        return null
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

    // Per-host leaf issuance lives in com.example.interception.tls.CertificateEngine (S1 task 8).

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
            // S1 task 7: never block a relay leg on Room -- hand the upsert to the engine scope.
            scope.launch(Dispatchers.IO) { runCatching { db.tlsSessionDao().upsert(entity) } }
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
            // S1 task 7: never block a relay leg on Room -- hand the upsert to the engine scope.
            scope.launch(Dispatchers.IO) { runCatching { db.tlsSessionDao().upsert(entity) } }
            Log.w(TAG, "MITM TLS handshake FAILED: $host stage=$stage cause=${cause.message}")
        }.onFailure { e -> Log.w(TAG, "Failed to persist FAILED MITM TLS session for $host: ${e.message}") }
    }

    // ── SSL context ─────────────────────────────────────────────────────

    private fun createSslContext(privateKey: java.security.PrivateKey, chain: Array<X509Certificate>): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            // For minted leaves the chain is just the leaf: the client already has the CA in
            // its trust store, and sending a duplicate/extra cert makes stricter TLS stacks
            // (Conscrypt, some browsers) reject the chain. An imported CUSTOM_SERVER_CERT is
            // presented with whatever chain it was imported with.
            setKeyEntry("mitm", privateKey, "".toCharArray(), chain)
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "".toCharArray())
        val ctx = sslContextProvider?.let { SSLContext.getInstance("TLS", it) }
            ?: SSLContext.getInstance("TLS")
        return ctx.apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }
}
