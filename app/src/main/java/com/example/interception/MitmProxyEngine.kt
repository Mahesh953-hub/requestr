package com.example.interception

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import com.example.data.models.HttpMethod
import com.example.data.repository.SettingsRepository
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val packetCount = AtomicLong(0)
    private val sessionId = AtomicLong(System.currentTimeMillis())
    private val certCache = ConcurrentHashMap<String, Pair<X509Certificate, KeyPair>>()

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
            sessionId = sessionId.get(), tier = ActiveTier.VPN,
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
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                return
            }

            val target = parts[1]
            val (host, port) = parseHostPort(target, 443)

            val db = RequestrDatabase.get(context)
            val disposition = ConnectControl.evaluate(db, host, port)

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
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                    HttpProxyShared.handleConnectAttempt(
                        client, disposition.target, ConnectControl.Disposition.AllowOpaque, output,
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
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                    HttpProxyShared.handleConnectAttempt(
                        client, target, ConnectControl.Disposition.AllowOpaque, output,
                    ) { sent, recv ->
                        persistConnectAttempt(
                            context, client, db, host, port, "ALLOWED_OPAQUE",
                            bytesToUpstream = sent, bytesToClient = recv,
                        )
                    }
                    return
                }
                is ConnectControl.Disposition.ForceMitm -> {
                    // Fall through to the existing MITM path below.
                }
            }

            // Send 200 to client — we're about to MITM
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            // Get or create per-host certificate
            val (cert, keyPair) = certCache.computeIfAbsent(host) {
                generateHostCert(context, host)
            }

            // Upgrade client side to TLS with our per-host cert
            val sslContext = createSslContext(keyPair, cert)
            val sslSocket = sslContext.socketFactory
                .createSocket(client, host, port, true) as SSLSocket
            sslSocket.useClientMode = false
            try {
                sslSocket.startHandshake()
            } catch (e: javax.net.ssl.SSLException) {
                persistFailedMitmTlsSession(db, host, port, "client-facing handshake", e)
                return
            }

            val decryptedInput = sslSocket.getInputStream()
            val decryptedOutput = sslSocket.getOutputStream()

            // Connect to real server
            val upstream = Socket(host, port)
            val upstreamSsl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TrustAllCerts), SecureRandom())
            }.socketFactory.createSocket(upstream, host, port, true) as SSLSocket
            try {
                upstreamSsl.startHandshake()
            } catch (e: javax.net.ssl.SSLException) {
                persistFailedMitmTlsSession(db, host, port, "upstream handshake", e)
                return
            }

            val upstreamInput = upstreamSsl.getInputStream()
            val upstreamOutput = upstreamSsl.getOutputStream()

            // W5: the MITM proxy terminates TLS itself, so tlsSessionId/connectionId have
            // historically been keyed as "$host:$port" -- a real key, but a different
            // convention than the 5-tuple ConnectionLineage joins on for the VPN/tun path.
            // client (the accepted Socket) and upstream (the Socket this engine itself opened)
            // together give the real 5-tuple this MITM'd connection corresponds to, so register
            // the host:port alias against the canonical ConnectionKey.forTcp key instead of
            // inventing a third convention.
            val canonicalConnectionId = runCatching {
                com.example.correlation.ConnectionKey.forTcp(
                    srcIp = client.inetAddress?.hostAddress ?: return@runCatching null,
                    srcPort = client.port,
                    dstIp = upstream.inetAddress?.hostAddress ?: return@runCatching null,
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
            persistMitmTlsSession(db, host, port, sslSocket, upstreamSsl, canonicalConnectionId)

            // Inspecting bidirectional relay: parse decrypted HTTP/1.x with
            // HttpParser, enforce rules on the parsed messages, persist each
            // transaction, and forward the (possibly modified) bytes.
            val pending = ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>()
            val clientWriteLock = Any()
            val sent = AtomicLong(0)
            val received = AtomicLong(0)
            val t1 = Thread {
                inspectClientToServer(db, decryptedInput, upstreamOutput, decryptedOutput,
                    clientWriteLock, host, port, pending, sent)
            }
            val t2 = Thread {
                inspectServerToClient(db, upstreamInput, decryptedOutput, clientWriteLock, pending, host, port, received)
            }
            t1.start(); t2.start()
            t1.join(60_000); t2.join(60_000)

            persistConnectAttempt(
                context, client, db, host, port, "MITM_DECRYPTED",
                negotiatedAlpn = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        sslSocket.applicationProtocol?.ifBlank { null }
                    } else null
                }.getOrNull(),
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
            val clientUid = runCatching {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@runCatching null
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@runCatching null
                val local = client.localSocketAddress as? java.net.InetSocketAddress
                    ?: return@runCatching null
                val remote = client.remoteSocketAddress as? java.net.InetSocketAddress
                    ?: return@runCatching null
                cm.getConnectionOwnerUid(android.system.OsConstants.IPPROTO_TCP, local, remote).takeIf { it != -1 }
            }.getOrNull()
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

    // ── Inspecting relay ────────────────────────────────────────────────

    /**
     * Client→server direction. Buffers decrypted bytes until [HttpParser] can
     * parse a complete request, applies request-side rules AFTER parsing
     * (BLOCK answers the client directly; REDIRECT / MODIFY_REQUEST rewrite
     * the forwarded bytes), and enqueues each forwarded request for pairing
     * with its response. Non-HTTP/1.x content flushes raw and switches this
     * direction to blind relay.
     */
    private fun inspectClientToServer(
        db: RequestrDatabase,
        input: InputStream,
        upstreamOut: OutputStream,
        clientOut: OutputStream,
        clientWriteLock: Any,
        host: String,
        port: Int,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        bytesSentToUpstream: AtomicLong,
    ) {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var blind = false
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesSentToUpstream.addAndGet(n.toLong())
                if (blind) {
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
                            // Not HTTP/1.x (HTTP/2 preface, WS frames, ...) — relay raw from now on.
                            upstreamOut.write(bytes)
                            upstreamOut.flush()
                            buffer.reset()
                            blind = true
                        }
                        waitingForMore = true
                    } else {
                        val raw = bytes.copyOfRange(0, parsed.consumedBytes)
                        bytes = bytes.copyOfRange(parsed.consumedBytes, bytes.size)
                        handleParsedRequest(db, parsed, raw, upstreamOut, clientOut, clientWriteLock, pending)
                        buffer.reset()
                        buffer.write(bytes)
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */ }
    }

    /**
     * Server→client direction. Parses responses, pairs each with the oldest
     * forwarded request (FIFO — HTTP/1.1 responses arrive in request order),
     * applies MODIFY_RESPONSE rules AFTER parsing so decrypted content is
     * actually rewritten, and emits one [CapturedTrafficEntity] per pair.
     */
    private fun inspectServerToClient(
        db: RequestrDatabase,
        input: InputStream,
        clientOut: OutputStream,
        clientWriteLock: Any,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
        host: String,
        port: Int,
        bytesReceivedFromUpstream: AtomicLong,
    ) {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var blind = false
        try {
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                bytesReceivedFromUpstream.addAndGet(n.toLong())
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
                    if (response == null || !chunkedBodyComplete(bytes, response)) {
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
                        handleParsedResponse(db, response, raw, clientOut, clientWriteLock, pending, host, port)
                        buffer.reset()
                        buffer.write(bytes)
                    }
                }
            }
        } catch (_: Exception) { /* connection closed */ }
    }

    // ── Rule enforcement (post-parse) ───────────────────────────────────

    private fun handleParsedRequest(
        db: RequestrDatabase,
        request: ParsedHttpMessage,
        raw: ByteArray,
        upstreamOut: OutputStream,
        clientOut: OutputStream,
        clientWriteLock: Any,
        pending: ConcurrentLinkedQueue<Pair<ParsedHttpMessage, Long>>,
    ) {
        when (RuleEnforcer.checkRules(db, request.url, request.host)) {
            RuleAction.BLOCK -> {
                Log.i(TAG, "BLOCKED (decrypted): ${request.method?.name} ${request.url.take(80)}")
                synchronized(clientWriteLock) {
                    clientOut.write(
                        "HTTP/1.1 403 Blocked by requestr\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                    clientOut.flush()
                }
                // Dropped: never forwarded upstream, never enqueued for pairing.
            }
            RuleAction.REDIRECT -> {
                val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
                if (rule?.replacementValue?.isNotBlank() == true) {
                    request.url = rule.replacementValue!!
                    runCatching { java.net.URI(request.url).host?.let { request.host = it } }
                    val headers = request.headers.toMutableMap()
                    headers["host"] = request.host
                    request.headers = headers
                }
                pending.add(request to System.currentTimeMillis())
                upstreamOut.write(serializeRequest(request))
                upstreamOut.flush()
            }
            RuleAction.MODIFY_REQUEST -> {
                val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
                rule?.replacementValue?.let { RuleEnforcer.applyRequestModification(request, it) }
                pending.add(request to System.currentTimeMillis())
                upstreamOut.write(serializeRequest(request))
                upstreamOut.flush()
            }
            else -> { // LOG_ONLY — forward the exact original bytes
                pending.add(request to System.currentTimeMillis())
                upstreamOut.write(raw)
                upstreamOut.flush()
            }
        }
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
            if (RuleEnforcer.checkRules(db, request.url, request.host) == RuleAction.MODIFY_RESPONSE) {
                val rule = RuleEnforcer.getMatchingRule(db, request.url, request.host)
                rule?.replacementValue?.let { RuleEnforcer.applyResponseModification(response, it) }
                out = serializeResponse(response)
            }
        }
        synchronized(clientWriteLock) {
            clientOut.write(out)
            clientOut.flush()
        }
        if (request != null) {
            val latencyMs = System.currentTimeMillis() - pair!!.second
            emitTransaction(request, response, latencyMs, "$host:$port")
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Build a [CapturedTrafficEntity] from the decrypted pair and hand it to the callback. */
    private fun emitTransaction(
        request: ParsedHttpMessage,
        response: ParsedHttpMessage,
        latencyMs: Long,
        connectionId: String,
    ) {
        val callback = onDecryptedTransaction ?: return
        val logRequests = SettingsRepository.settings.value.requestLoggingEnabled
        val logResponses = SettingsRepository.settings.value.responseLoggingEnabled
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
            requestSizeBytes = request.body.toByteArray(Charsets.UTF_8).size.toLong(),
            responseSizeBytes = response.body.toByteArray(Charsets.UTF_8).size.toLong(),
            latencyMs = latencyMs,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = true,
            isUndecoded = false,
            // Task 12: MITM rows use "$host:$port" (the CONNECT target), matching
            // persistMitmTlsSession's convention -- the engine never sees the raw 5-tuple, only
            // the CONNECT target, so this is the exact identity ConnectionLineage.findTlsFor's
            // two-step lookup already handles.
            connectionId = connectionId,
        )
        runCatching { callback(entity) }
            .onSuccess {
                packetCount.incrementAndGet()
                InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
                Log.d(TAG, "Decrypted: ${response.statusCode} ${request.method?.name} ${request.url.take(120)}")
            }
            .onFailure { Log.w(TAG, "onDecryptedTransaction failed: ${it.message}") }
    }

    // ── Re-serialization (only used when a rule modified the message) ───

    private fun serializeRequest(request: ParsedHttpMessage): ByteArray {
        val bodyBytes = request.body.toByteArray(Charsets.UTF_8)
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            putAll(request.headers)
        }
        headers.remove("Transfer-Encoding") // re-serialized with an explicit length
        if (bodyBytes.isNotEmpty()) headers["Content-Length"] = bodyBytes.size.toString()
        val sb = StringBuilder()
        sb.append(request.method?.name ?: "GET").append(' ')
            .append(request.path.ifEmpty { "/" }).append(' ')
            .append(request.version.ifEmpty { "HTTP/1.1" }).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    private fun serializeResponse(response: ParsedHttpMessage): ByteArray {
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            putAll(response.headers)
        }
        headers.remove("Transfer-Encoding") // parser de-chunked the body
        headers["Content-Length"] = bodyBytes.size.toString()
        val sb = StringBuilder()
        sb.append(response.version.ifEmpty { "HTTP/1.1" }).append(' ')
            .append(response.statusCode).append(' ')
            .append(response.statusMessage.ifEmpty { "OK" }).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
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

    /**
     * [HttpParser.tryParseResponse] emits a (possibly truncated) body for chunked
     * responses as soon as some chunks are buffered. Walk the raw chunk structure
     * and only accept the parse once the terminal zero-size chunk has arrived.
     */
    private fun chunkedBodyComplete(bytes: ByteArray, response: ParsedHttpMessage): Boolean {
        val te = response.headers["transfer-encoding"] ?: return true
        if (!te.contains("chunked", ignoreCase = true)) return true
        val bodyStart = (findHeaderEnd(bytes) ?: return true) + 4
        var pos = bodyStart
        while (true) {
            val crlf = findCrlf(bytes, pos) ?: return false
            val sizeHex = String(bytes, pos, crlf - pos, Charsets.ISO_8859_1).trim()
            val size = sizeHex.split(";")[0].toIntOrNull(16) ?: return true // unparseable → accept as-is
            if (size == 0) {
                val trailerEnd = findCrlf(bytes, crlf + 2) ?: return false
                return trailerEnd + 2 <= bytes.size
            }
            pos = crlf + 2 + size + 2
            if (pos > bytes.size) return false
        }
    }

    private fun findHeaderEnd(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun findCrlf(bytes: ByteArray, start: Int): Int? {
        for (i in start until bytes.size - 1) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i
        }
        return null
    }

    // ── Per-host cert generation ────────────────────────────────────────

    private fun generateHostCert(context: Context, host: String): Pair<X509Certificate, KeyPair> {
        val ca = kotlinx.coroutines.runBlocking { CertificateAuthority.getOrCreate(context) }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            .genKeyPair()
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val now = Date()
        val expiry = Date(now.time + 365L * 24 * 60 * 60 * 1000)

        val issuer = X500Name("CN=requestr Root CA")
        val subject = X500Name("CN=$host")

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, now, expiry, subject, keyPair.public
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, host))))
        }

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(ca.keyPair.private)

        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
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
                ja3Hash = null, // JA3 needs the raw ClientHello, which SSLSocket already consumed
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
            setKeyEntry("mitm", keyPair.private, "".toCharArray(),
                arrayOf(cert, /* chain root not needed for server */ cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "".toCharArray())
        return SSLContext.getInstance("TLS").apply {
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

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0)
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        else target to defaultPort
    }

    private object TrustAllCerts : X509TrustManager {
        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
