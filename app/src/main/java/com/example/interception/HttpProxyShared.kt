package com.example.interception

import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.net.SocketProtector
import com.example.interception.relay.ConnectionStateMachine
import com.example.interception.relay.RelayPool
import com.example.interception.relay.StructuredRelay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Shared, stateless HTTP/1.1 forward-proxy plumbing used by both [HttpProxyEngine] (on-device
 * traffic) and [com.example.interception.gateway.GatewayEngine] (T2 — traffic proxied on behalf
 * of remote LAN devices).
 *
 * Extracted rather than duplicated so the two engines can't silently drift apart on request
 * parsing / CONNECT tunneling / upstream forwarding behavior.
 */
internal object HttpProxyShared {
    const val BUFFER_SIZE = 8192
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_READ_TIMEOUT_MS = 20_000

    /**
     * Genuine hop-by-hop headers (RFC 7230 §6.1) plus `Proxy-Connection` -- these describe the
     * connection between one specific pair of endpoints and must never be blindly forwarded to
     * the *next* hop, in either direction. Everything else (including `Host`, `Content-Type`,
     * cookies, auth, etc.) is passed through byte-for-byte so the proxy stays transparent.
     */
    private val HOP_BY_HOP_HEADERS = setOf(
        "proxy-connection",
        "connection",
        "keep-alive",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "proxy-authenticate",
        "proxy-authorization",
    )

    /** Reads a single CRLF-terminated line from [input]. Returns null at EOF with no data read. */
    fun readLine(input: InputStream): String? {
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

    /**
     * Reads exactly [n] bytes from [input], looping on short reads. Returns fewer than [n] bytes
     * only if the stream hit EOF first. Deliberately hand-rolled instead of
     * `InputStream.readNBytes(int)`, which requires API 33 -- this app's `minSdk` is 24.
     */
    fun readExactBytes(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0) {
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        } else {
            target to defaultPort
        }
    }

    fun InputStream.copyToStream(out: OutputStream, bufSize: Int = BUFFER_SIZE) {
        val buf = ByteArray(bufSize)
        var n: Int
        while (read(buf).also { n = it } != -1) { out.write(buf, 0, n) }
    }

    /**
     * Handles a CONNECT request per the [ConnectControl.Disposition] the caller already
     * evaluated -- BLOCK answers the client directly, AllowOpaque/Redirect tunnel bidirectionally
     * (byte-counted, unlike the previous blind relay), and ForceMitm is answered with an explicit
     * error since a bare [Socket] relay can never terminate TLS -- only [MitmProxyEngine] can act
     * on that disposition.
     *
     * [onBytesTransferred] is invoked once, after the relay finishes, with
     * (bytes read from the client / written upstream, bytes read from upstream / written to the
     * client) so callers can persist a [com.example.data.local.ConnectAttemptEntity] byte count.
     *
     * §3 (C2) — [networkCondition] (disabled by default -- see
     * [NetworkConditionInjector.NetworkCondition.NONE]) is applied once, right before the
     * `AllowOpaque`/`Redirect` tunnel is established: adds the configured latency/jitter delay,
     * then (if it rolls a simulated loss) throws [NetworkConditionInjector.SimulatedPacketLossException]
     * *before* the client ever receives its "200 Connection Established" -- the connection simply
     * fails to establish, exactly like a real dial-time failure this function already propagates
     * (e.g. connection refused from [relay]'s own `Socket(host, port)`).
     */
    suspend fun handleConnectAttempt(
        client: Socket,
        target: String,
        disposition: ConnectControl.Disposition,
        output: OutputStream,
        networkCondition: NetworkConditionInjector.NetworkCondition = NetworkConditionInjector.NetworkCondition.NONE,
        random: Random = Random.Default,
        sleep: (Long) -> Unit = Thread::sleep,
        socketFactory: () -> Socket = { Socket() },
        socketConnector: (Socket, InetSocketAddress) -> Unit = { socket, address -> socket.connect(address) },
        replies: ConnectReplies = ConnectReplies.http(output),
        relayPool: RelayPool = RelayPool.shared(),
        onBytesTransferred: suspend (clientToUpstream: Long, upstreamToClient: Long) -> Unit = { _, _ -> },
    ) {
        when (disposition) {
            is ConnectControl.Disposition.Block -> replies.blocked()
            is ConnectControl.Disposition.AllowOpaque -> {
                val (host, port) = parseHostPort(target, 443)
                NetworkConditionInjector.apply(networkCondition, random, sleep)
                replies.established()
                relay(client, host, port, onBytesTransferred, socketFactory, socketConnector, relayPool)
            }
            is ConnectControl.Disposition.Redirect -> {
                val (host, port) = parseHostPort(disposition.target, 443)
                NetworkConditionInjector.apply(networkCondition, random, sleep)
                replies.established()
                relay(client, host, port, onBytesTransferred, socketFactory, socketConnector, relayPool)
            }
            is ConnectControl.Disposition.ForceMitm -> {
                // A plain Socket relay can never terminate TLS -- only MitmProxyEngine can
                // honor FORCE_MITM. Callers that can't MITM (HttpProxyEngine, GatewayEngine)
                // must report this honestly rather than silently tunneling opaquely.
                replies.mitmUnavailable()
            }
        }
    }

    /**
     * How [handleConnectAttempt] answers the client. The wire encoding depends on how the
     * connection entered the gateway -- an HTTP `CONNECT` wants status lines, a SOCKS5 tunnel
     * wants a 10-byte reply, a transparent capture wants nothing at all -- so the caller passes
     * the right encoder instead of this helper hardcoding HTTP. [http] is the legacy default.
     */
    class ConnectReplies(
        val established: () -> Unit,
        val blocked: () -> Unit,
        val mitmUnavailable: () -> Unit,
    ) {
        companion object {
            fun http(output: OutputStream): ConnectReplies = ConnectReplies(
                established = {
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                },
                blocked = {
                    output.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
                    output.flush()
                },
                mitmUnavailable = {
                    output.write("HTTP/1.1 403 MITM required but not available\r\n\r\n".toByteArray())
                    output.flush()
                },
            )

            fun forSession(session: com.example.interception.gateway.local.GatewaySession): ConnectReplies =
                ConnectReplies(
                    established = session::replyEstablished,
                    blocked = session::replyBlocked,
                    mitmUnavailable = session::replyMitmUnavailable,
                )
        }
    }

    /**
     * Byte-counted bidirectional relay between [client] and a freshly dialed upstream socket.
     *
     * S1 task 7: the two copy legs are structured coroutines on the [RelayPool] dispatcher
     * ([StructuredRelay.run]) driven by a [ConnectionStateMachine]; [onBytesTransferred] fires
     * only once the connection reached a terminal state, never on a `join` timeout while a leg is
     * still copying.
     */
    private suspend fun relay(
        client: Socket,
        host: String,
        port: Int,
        onBytesTransferred: suspend (clientToUpstream: Long, upstreamToClient: Long) -> Unit,
        socketFactory: () -> Socket,
        socketConnector: (Socket, InetSocketAddress) -> Unit,
        relayPool: RelayPool,
    ) {
        val machine = ConnectionStateMachine()
        machine.transition(ConnectionStateMachine.State.DETECTED)
        val upstream = socketFactory()
        try {
            val protectResult = SocketProtector.protectSocket(upstream)
            if (protectResult == SocketProtector.ProtectResult.Failed) {
                throw IOException("SocketProtector.protectSocket failed for relay upstream $host:$port")
            }
            socketConnector(upstream, InetSocketAddress(host, port))
        } catch (t: Throwable) {
            runCatching { upstream.close() }
            machine.fail("upstream dial failed: ${t.message}")
            throw t
        }
        upstream.use {
            val sent = AtomicLong(0)
            val received = AtomicLong(0)
            machine.transition(ConnectionStateMachine.State.RELAYING)
            val metrics = relayPool.metrics
            val outcome = StructuredRelay.run(
                machine = machine,
                dispatcher = relayPool.dispatcher,
                metrics = metrics,
                drainTimeoutMs = OPAQUE_DRAIN_TIMEOUT_MS,
                onDrainTimeout = {
                    runCatching { client.close() }
                    runCatching { upstream.close() }
                },
                clientToUpstream = {
                    StructuredRelay.copy(client.getInputStream(), upstream.getOutputStream(), sent, relayPool.buffers) { machine.touch() }
                },
                upstreamToClient = {
                    StructuredRelay.copy(upstream.getInputStream(), client.getOutputStream(), received, relayPool.buffers) { machine.touch() }
                },
            )
            metrics.addBytesToUpstream(sent.get())
            metrics.addBytesToClient(received.get())
            if (!outcome.anyLegEndedNormally && sent.get() == 0L && received.get() == 0L && outcome.errors.isNotEmpty()) {
                machine.fail(outcome.errors.first().message ?: outcome.errors.first().javaClass.simpleName)
                metrics.onFailed()
            } else {
                machine.close()
                metrics.onCompleted()
            }
            onBytesTransferred(sent.get(), received.get())
        }
    }

    /** How long the second leg of an opaque tunnel may keep going after the first one ended. */
    private const val OPAQUE_DRAIN_TIMEOUT_MS = 30_000L

    /**
     * Forwards a plain (non-CONNECT) HTTP request to its destination over a **raw [Socket]**
     * (an [javax.net.ssl.SSLSocket] for `https://` request targets) and returns the response.
     *
     * Fix #31 follow-up: this used to dial upstream with `HttpURLConnection`, which is subject to
     * Android's Network Security Config -- `network_security_config.xml` intentionally permits
     * cleartext only to loopback/emulator-host addresses (the app's own client-side traffic, e.g.
     * API Testr, must never be allowed to speak cleartext HTTP to arbitrary domains), so every
     * upstream dial to a real cleartext-HTTP site was refused by the platform before a socket
     * even opened (`Cleartext HTTP traffic to <host> not permitted`, failing in ~1ms). A forward
     * *proxy*'s upstream leg has the opposite requirement: it must be able to relay cleartext HTTP
     * to arbitrary destinations on behalf of the client -- that's the whole point of the product.
     * A raw `Socket` is not governed by Network Security Config at all, so it resolves that
     * conflict without weakening the app's own client-side posture (NSC still applies to every
     * `HttpURLConnection`/`OkHttp` call the app itself makes, e.g. from API Testr). It also means
     * requests are forwarded faithfully byte-for-byte -- no header rewriting/normalizing, no
     * lossy `String` body coercion for binary payloads.
     *
     * [body] and the returned body are raw bytes (not `String`) so binary request/response
     * payloads survive the round trip unmangled.
     *
     * §3 (C2) — [networkCondition] (disabled by default) is applied first, inside the same
     * [runCatching] that already turns any dial/handshake failure into a `502` response below:
     * a simulated loss ([NetworkConditionInjector.SimulatedPacketLossException], an [IOException])
     * is caught by that existing `.getOrElse` exactly like a real connection failure would be,
     * so no separate error-handling path was needed for this feature.
     */
    fun forwardRequest(
        rawUrl: String,
        method: String,
        reqHeaders: Map<String, String>,
        body: ByteArray,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        networkCondition: NetworkConditionInjector.NetworkCondition = NetworkConditionInjector.NetworkCondition.NONE,
        random: Random = Random.Default,
        sleep: (Long) -> Unit = Thread::sleep,
        socketFactory: (secure: Boolean) -> Socket = { secure ->
            if (secure) SSLSocketFactory.getDefault().createSocket() else Socket()
        },
        socketConnector: (Socket, InetSocketAddress, Int) -> Unit = { socket, address, timeout ->
            socket.connect(address, timeout)
        },
    ): Triple<Int, Map<String, String>, ByteArray> {
        return runCatching {
            NetworkConditionInjector.apply(networkCondition, random, sleep)
            val uri = normalizeUri(rawUrl, reqHeaders)
            val host = uri.host ?: throw IOException("Missing host in request target: $rawUrl")
            val secure = uri.scheme.equals("https", ignoreCase = true)
            val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
            val requestTarget = requestTargetFor(uri)
            // Only include the port in a synthesized Host header when it's non-default -- an
            // explicit `Host: example.com:443` on an https request looks like a mismatch to some
            // servers, and it's simply redundant.
            val hostHeaderValue = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"

            val socket = socketFactory(secure)
            try {
                val protectResult = SocketProtector.protectSocket(socket)
                if (protectResult == SocketProtector.ProtectResult.Failed) {
                    throw IOException("SocketProtector.protectSocket failed for forwardRequest $host:$port")
                }
                socketConnector(socket, InetSocketAddress(host, port), connectTimeoutMs)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
            socket.use { sock ->
                sock.soTimeout = readTimeoutMs
                if (sock is SSLSocket) sock.startHandshake()

                val out = sock.getOutputStream()
                out.write(buildRequestBytes(method, requestTarget, hostHeaderValue, reqHeaders, body))
                out.flush()

                readUpstreamResponse(sock.getInputStream(), method)
            }
        }.getOrElse { e ->
            val message = e.message ?: e.javaClass.simpleName
            Triple(
                502,
                mapOf("X-Proxy-Error" to message),
                "{\"error\":\"${message.replace("\"", "'")}\"}".toByteArray(Charsets.UTF_8),
            )
        }
    }

    /** Resolves [rawUrl] to a [URI], filling in scheme/host from the `Host` header for origin-form targets. */
    private fun normalizeUri(rawUrl: String, reqHeaders: Map<String, String>): URI {
        return when {
            rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true) ->
                URI(rawUrl)
            rawUrl.startsWith("/") -> {
                val host = reqHeaders.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
                    ?: throw IOException("Origin-form request target with no Host header: $rawUrl")
                URI("http://$host$rawUrl")
            }
            else -> URI("http://$rawUrl")
        }
    }

    /** Origin-form request-target (path + query, never the authority) to send upstream. */
    private fun requestTargetFor(uri: URI): String {
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        return if (uri.rawQuery != null) "$path?${uri.rawQuery}" else path
    }

    /** Serializes the request line + headers + body exactly as they'll go out on the wire. */
    private fun buildRequestBytes(
        method: String,
        requestTarget: String,
        host: String,
        reqHeaders: Map<String, String>,
        body: ByteArray,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n")
        var hasHost = false
        reqHeaders.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower in HOP_BY_HOP_HEADERS || lower == "content-length") return@forEach
            if (lower == "host") hasHost = true
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (!hasHost) sb.append("Host: ").append(host).append("\r\n")
        // A fresh Socket is opened per call (no connection pooling across unrelated client
        // requests), so tell upstream not to keep it alive -- this also lets response reading
        // fall back to "read until EOF" when upstream sends neither Content-Length nor
        // Transfer-Encoding (see readUpstreamResponse below).
        sb.append("Connection: close\r\n")
        if (body.isNotEmpty()) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return if (body.isEmpty()) headerBytes else headerBytes + body
    }

    /**
     * Parses an upstream HTTP/1.1 response: status line, headers, and body -- honoring
     * `Content-Length`, `Transfer-Encoding: chunked`, and the no-body cases (HEAD requests,
     * 1xx/204/304 responses) per RFC 7230 §3.3.3, falling back to "read until EOF" when upstream
     * declares neither (relying on the `Connection: close` we always send).
     *
     * Hop-by-hop response headers (including `Transfer-Encoding` and `Content-Length`, since the
     * body has already been fully de-framed into [body] by the time this returns) are stripped
     * from the returned header map -- callers must compute their own `Content-Length` from the
     * actual returned [ByteArray] rather than trust whatever upstream declared, since a body read
     * that hit EOF early (upstream misbehavior) can legitimately be shorter than a stale header.
     */
    private fun readUpstreamResponse(input: InputStream, requestMethod: String): Triple<Int, Map<String, String>, ByteArray> {
        val statusLine = readLine(input) ?: throw IOException("Upstream closed the connection before sending a response")
        val statusParts = statusLine.trim().split(" ", limit = 3)
        val statusCode = statusParts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed upstream status line: $statusLine")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }

        val noBody = requestMethod.equals("HEAD", ignoreCase = true) ||
            statusCode == 204 || statusCode == 304 || statusCode in 100..199
        val transferEncoding = headers.entries.firstOrNull { it.key.equals("Transfer-Encoding", ignoreCase = true) }?.value
        val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.toLongOrNull()

        val body = when {
            noBody -> ByteArray(0)
            transferEncoding?.contains("chunked", ignoreCase = true) == true -> readChunkedBody(input)
            contentLength != null -> readExactBytes(input, contentLength.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            else -> input.readBytes() // Connection: close -- read until upstream closes the socket.
        }

        val respHeaders = headers.filterKeys { it.lowercase() !in HOP_BY_HOP_HEADERS && !it.equals("Content-Length", ignoreCase = true) }
        return Triple(statusCode, respHeaders, body)
    }

    /** Decodes an HTTP/1.1 chunked-transfer-encoded body (RFC 7230 §4.1), consuming trailers too. */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                // Final (zero-length) chunk -- drain any trailer headers, then done.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactBytes(input, size))
            readLine(input) // consume the CRLF that terminates this chunk's data
        }
        return out.toByteArray()
    }

    /** Standard HTTP/1.1 reason phrase for [statusCode], falling back to a generic label. */
    fun reasonPhraseFor(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        410 -> "Gone"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> if (statusCode in 100..599) "Unknown Status" else "Unknown"
    }

    /**
     * Writes a complete HTTP/1.1 response to [output]: status line (with a correct reason phrase
     * -- previously every response line was hardcoded `"$httpVersion $status OK"`, so e.g. a 502
     * was sent to the client as `HTTP/1.1 502 OK`), [headers], and [body], always framed with a
     * `Content-Length` computed from [body]'s *actual* size rather than any stale upstream header.
     */
    fun writeResponse(
        output: OutputStream,
        httpVersion: String,
        statusCode: Int,
        headers: Map<String, String>,
        body: ByteArray,
    ) {
        val sb = StringBuilder()
        sb.append(httpVersion).append(' ').append(statusCode).append(' ').append(reasonPhraseFor(statusCode)).append("\r\n")
        headers.forEach { (k, v) ->
            if (k.equals("Content-Length", ignoreCase = true) || k.lowercase() in HOP_BY_HOP_HEADERS) return@forEach
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    /**
     * Builds the [CapturedTrafficEntity] for one fully-relayed HTTP transaction.
     *
     * Shared by [HttpProxyEngine] (on-device HTTP_PROXY tier) and
     * [com.example.interception.gateway.GatewayEngine] (T2 LAN-relay tier): both hand raw byte
     * arrays straight out of [forwardRequest], never a `ParsedHttpMessage`, so both must classify
     * text-vs-binary themselves via [HttpBodyDecoder.forStorage]'s `ByteArray` overload rather
     * than force-decoding as UTF-8 (which silently mangles an uploaded image or a protobuf
     * response into lossy text). Same 2 MiB cap, same truncation flag, and the same three-state
     * contract (text/no-body, binary stored, binary over cap) as
     * `MitmProxyEngine.emitTransaction`/`CaptureEngine.persistTransaction`.
     *
     * `wasDecrypted`/`isUndecoded` are constants rather than parameters: both callers only reach
     * here from their fully-decrypted HTTP relay path (a CONNECT tunnel they relay opaquely is
     * recorded as a `ConnectAttemptEntity` instead), so threading them through would only add two
     * dead arguments. Each caller keeps its own insert and failure handling.
     */
    fun capturedEntityFor(
        sessionId: Long,
        rawUrl: String,
        method: String,
        statusCode: Int?,
        latencyMs: Long,
        reqHeaders: Map<String, String>,
        respHeaders: Map<String, String>,
        reqBodyBytes: ByteArray,
        respBodyBytes: ByteArray,
        protocol: String,
        captureMethod: CaptureRowMethod,
        sourceIp: String? = null,
        connectionId: String? = null,
    ): CapturedTrafficEntity {
        val absoluteUrl = if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl"
        val (host, path) = runCatching {
            val u = URI(absoluteUrl)
            (u.host ?: rawUrl) to (u.path ?: "")
        }.getOrDefault(rawUrl to "")

        val reqStorage = HttpBodyDecoder.forStorage(reqBodyBytes)
        val respStorage = HttpBodyDecoder.forStorage(respBodyBytes)

        return CapturedTrafficEntity(
            sessionId = sessionId,
            method = method,
            url = absoluteUrl,
            host = host,
            path = path,
            statusCode = statusCode,
            protocol = protocol,
            requestHeaders = reqHeaders,
            responseHeaders = respHeaders,
            requestBody = if (reqStorage.isBinary) null else String(reqBodyBytes, Charsets.UTF_8).ifEmpty { null },
            responseBody = if (respStorage.isBinary) null else String(respBodyBytes, Charsets.UTF_8).ifEmpty { null },
            requestSizeBytes = reqStorage.trueSizeBytes,
            responseSizeBytes = respStorage.trueSizeBytes,
            isRequestBodyBinary = reqStorage.isBinary,
            isResponseBodyBinary = respStorage.isBinary,
            requestBodyBytes = reqStorage.bytes,
            responseBodyBytes = respStorage.bytes,
            requestBodyBytesTruncated = reqStorage.truncated,
            responseBodyBytesTruncated = respStorage.truncated,
            latencyMs = latencyMs,
            wasDecrypted = true,
            isUndecoded = false,
            sourceIp = sourceIp,
            connectionId = connectionId,
            captureMethod = captureMethod,
        )
    }
}
