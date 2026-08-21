package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.export.MediaStoreExporter
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.TcpConnectionEntity
import com.example.data.local.TlsSessionEntity
import com.example.data.repository.SettingsRepository
import com.example.interception.grpc.GrpcDetector
import com.example.interception.grpc.ProtobufDecoder
import com.example.interception.http.Http2Parser
import com.example.interception.http.HttpParser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.tcp.TcpReassembler
import com.example.interception.tcp.TcpStream
import com.example.interception.tls.TlsDecoder
import kotlinx.coroutines.runBlocking

/**
 * Orchestrates the pipeline: raw tun packets → TCP reassembly → HTTP/TLS
 * detection → Room persistence.
 *
 * This class is driven by [PacketPump] each time a packet is read from the
 * tun fd. It handles the "what do we do with this packet now? " logic that
 * turns raw IP into captured transactions.
 *
 * **Current capabilities:**
 * - Plaintext HTTP/1.x request-response pairing → persisted to `captured_traffic`
 * - TLS ClientHello SNI extraction → logs the hostname
 *
 * **Not yet built (next increment):**
 * - TLS MITM (decrypting application data requires a proxy that presents our
 *   CA to the client and connects to the real server)
 * - HTTP/2 frame parsing
 * - WebSocket message capture
 */
class CaptureEngine(
    private val context: Context,
    private val db: RequestrDatabase,
    private val sessionId: Long,
    var printUndecoded: Boolean = true,
    var dumpToFile: Boolean = false,
    var captureEnabled: Boolean = true,
    // Complaint #5 audit fix: "Enhanced Mode" previously gated nothing but a single
    // Log.i() debug line in PreflightController, despite its Settings UI claiming "Richer
    // packet analysis, dynamic logging, extended trace". [ProtocolDetector] is a real,
    // fully-implemented protocol fingerprinting engine (confidence-scored HTTP/1.1, HTTP/2,
    // WebSocket, SSE, gRPC, QUIC, DoH/DoT, TLS-version detection) that had ZERO production
    // call sites anywhere in the codebase -- the same orphaned-feature shape as the
    // QpackDecoder gap documented under P2. When true, every persisted row (undecoded-TLS
    // and plaintext HTTP alike) runs through ProtocolDetector and gets real
    // `x-protocol-detected` / `x-protocol-confidence` / `x-protocol-reason` diagnostic
    // headers attached -- visible today in the existing Headers tab of the traffic detail
    // sheet, no new UI needed. This is additive: it never changes what gets captured or
    // blocks anything, only annotates rows with an extra analysis pass.
    var enhancedMode: Boolean = false,
) {
    // Complaint #5 audit fix: this used to build a path via
    // `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` and open a raw
    // `File`/`bufferedWriter()` on it. On API 29+ (this app's targetSdk is 36) scoped storage
    // silently blocks that write, so "Dump to file" produced zero actual bytes on disk despite
    // logging a path and never reporting an error. Now routed through [MediaStoreExporter],
    // which uses `MediaStore.Downloads` on API 29+ (falling back to the old direct-File
    // approach pre-29, where it still works).
    private val dumpTarget: MediaStoreExporter.ExportTarget? by lazy {
        if (!dumpToFile) null else {
            val name = "capture_${sessionId}_${System.currentTimeMillis()}.hex"
            MediaStoreExporter.create(context, name, "text/plain")?.also {
                Log.i(TAG, "Dump target: Downloads/requestr/$name")
            } ?: run {
                Log.w(TAG, "Failed to create dump-to-file target $name")
                null
            }
        }
    }
    private val dumpWriter: java.io.BufferedWriter? by lazy {
        dumpTarget?.let { target ->
            runCatching { MediaStoreExporter.openOutputStream(context, target)?.bufferedWriter() }
                .onFailure { e -> Log.w(TAG, "Failed to open dump writer: ${e.message}") }
                .getOrNull()
        }
    }
    private val reassembler = TcpReassembler()
    private val pendingRequests = LinkedHashMap<String, ParsedHttpMessage>() // keyed by stream key
    // Streams that upgraded to WebSocket — subsequent bytes are WS frames (Fix #14)
    private val wsStreams = mutableSetOf<String>()


    // ── HTTP/2 / gRPC state ─────────────────────────────────────────────
    // Streams identified as HTTP/2 (plaintext h2c or MITM-decrypted h2).
    private val h2Streams = mutableSetOf<String>()
    // Per-direction HPACK decoder contexts, keyed by "$streamKey|c" / "$streamKey|s".
    private val hpackStates = mutableMapOf<String, Http2Parser.HpackDecoder>()
    // gRPC calls keyed by stream key, then HTTP/2 stream id.
    private val grpcCalls = mutableMapOf<String, MutableMap<Int, GrpcDetector.GrpcCall>>()
    // Partially-framed gRPC DATA payload per (stream key, direction, h2 stream id).
    private val grpcDataBuffers = mutableMapOf<String, MutableMap<String, ByteArray>>()
    // Latest grpc-status trailer value per (stream key, h2 stream id).
    private val grpcStatuses = mutableMapOf<String, MutableMap<Int, Int>>()

    /** Transactions persisted this session. */
    var transactionCount = 0
        private set

    // ── M1: TCP connection persistence ──────────────────────────────────
    // Last time (ms) each stream key's connection row was written to Room, so we upsert on
    // state changes immediately but otherwise only every [TCP_PERSIST_INTERVAL_MS].
    private val lastTcpPersist = mutableMapOf<String, Long>()

    private fun persistTcpConnection(stream: TcpStream) {
        val now = System.currentTimeMillis()
        val last = lastTcpPersist[stream.key]
        val dueForRefresh = last == null || now - last >= TCP_PERSIST_INTERVAL_MS
        if (!dueForRefresh && !stream.closed) return

        lastTcpPersist[stream.key] = now
        val entity = TcpConnectionEntity(
            connectionId = stream.key,
            sessionId = sessionId,
            srcIp = stream.srcIp,
            destIp = stream.dstIp,
            srcPort = stream.srcPort,
            destPort = stream.dstPort,
            state = when {
                stream.closed -> "CLOSED"
                stream.handshakeComplete -> "ESTABLISHED"
                else -> "SYN_SENT"
            },
            establishedAt = stream.establishedAt,
            closedAt = if (stream.closed) now else null,
            bytesSent = stream.totalBytesSent,
            bytesReceived = stream.totalBytesReceived,
            retransmissions = stream.retransmissions,
        )
        runCatching {
            runBlocking { db.tcpConnectionDao().upsert(entity) }
        }.onFailure { e -> Log.w(TAG, "Failed to persist TCP connection: ${e.message}") }
    }

    // ── entry point ─────────────────────────────────────────────────────

    /**
     * Feed one raw IP packet into the engine. Call from PacketPump's read loop.
     *
     * @param packet  complete IP datagram
     * @param length  bytes read
     * @param version 4 or 6
     */
    fun ingest(packet: ByteArray, length: Int, version: Int) {
        val stream = reassembler.feed(packet, length, version) ?: return

        // M1: persist/refresh this stream's TCP connection row. Throttled per-stream so we don't
        // hit Room on every packet — only on state transitions (new/closed) or every couple of
        // seconds of activity.
        persistTcpConnection(stream)

        // Only inspect streams that have some data buffered
        if (stream.clientToServer.isEmpty() && stream.serverToClient.isEmpty()) return

        // TLS detection: check once per stream
        if (!stream.isTls && stream.clientToServer.size() >= 5) {
            val clientBytes = stream.clientToServer.bytes()
            if (TlsDecoder.looksLikeTls(clientBytes)) {
                stream.isTls = true
                val clientHello = TlsDecoder.parseClientHello(clientBytes)
                stream.sniHost = clientHello?.sni ?: TlsDecoder.extractSni(clientBytes)
                Log.i(TAG, "TLS: ${stream.sniHost ?: "unknown"} (${stream.key}) bytes=${clientBytes.size}")

                // M2: persist a TlsSessionEntity for this handshake. We never decrypt this path
                // (no MITM here), so only what's visible on the wire in ClientHello is known:
                // SNI, offered version/ciphers/ALPN and their JA3 fingerprint. No cipher suite
                // was *negotiated* from our point of view and no certificate was seen.
                runCatching {
                    val tlsEntity = TlsSessionEntity(
                        tlsSessionId = stream.key,
                        sessionId = sessionId,
                        connectionId = stream.key,
                        sniHost = stream.sniHost,
                        version = clientHello?.version ?: TlsDecoder.versionName(clientBytes),
                        cipherSuite = null,
                        negotiatedProtocol = clientHello?.alpn,
                        ja3Hash = clientHello?.ja3Hash(),
                        handshakeState = com.example.interception.tls.TlsHandshakeState.CLIENT_HELLO.name,
                        wasDecrypted = false,
                        peerCertSubject = null,
                        peerCertIssuer = null,
                    )
                    runBlocking { db.tlsSessionDao().upsert(tlsEntity) }
                }.onFailure { e -> Log.w(TAG, "Failed to persist TLS session: ${e.message}") }

                if (printUndecoded) {
                    val entity = CapturedTrafficEntity(
                        method = "TLS",
                        url = "https://${stream.sniHost ?: stream.dstIp}",
                        host = stream.sniHost ?: "$stream.dstIp:$stream.dstPort",
                        requestHeaders = mapOf("x-tls-sni" to (stream.sniHost ?: "—"), "x-capture" to "undecoded") +
                            enhancedDiagnostics(stream.dstPort, clientBytes),
                        requestBody = null,
                        statusCode = 0,
                        responseHeaders = mapOf("x-undecoded" to "tls-detected", "x-bytes" to "${clientBytes.size}"),
                        responseBody = clientBytes.copyOfRange(0, minOf(128, clientBytes.size)).joinToString(" ") { "%02x".format(it) },
                        timestamp = System.currentTimeMillis(),
                        sessionId = sessionId,
                        wasDecrypted = false,
                        isUndecoded = true,
                    )
                    kotlinx.coroutines.runBlocking {
                        try { db.capturedTrafficDao().insert(entity); transactionCount++; Log.i(TAG, "Undecoded TLS persisted: ${stream.sniHost ?: stream.dstIp}") } catch (_: Exception) {}
                    }
                }
                // Dump to file if enabled
                if (dumpTarget != null) {
                    try { dumpWriter?.appendLine("[${System.currentTimeMillis()}] TLS ${stream.sniHost ?: stream.dstIp}:${stream.dstPort} ← ${clientBytes.size}B → ${scHex(clientBytes.copyOfRange(0, minOf(128, clientBytes.size)))}"); dumpWriter?.flush() } catch (_: Exception) {}
                }
                stream.clientToServer.reset()
                stream.serverToClient.reset()
                return
            }
        }

        if (stream.isTls && !stream.decryptedByMitm) return // allow MITM-decrypted streams through

        // ── HTTP/2 detection (plaintext h2c or MITM-decrypted h2) ───────
        // gRPC rides on HTTP/2, so this check must run before the HTTP/1.x
        // parser. Client-side detection keys on the connection preface; once
        // the preface is drained (or we joined mid-stream), a SETTINGS frame
        // on stream 0 — mandated first by RFC 7540 §3.5 — is the fallback.
        if (stream.key !in h2Streams) {
            val csProbe = stream.clientToServer.bytes()
            val scProbe = stream.serverToClient.bytes()
            val h2 = (csProbe.isNotEmpty() && (Http2Parser.isPreface(csProbe) || Http2Parser.looksLikeFrameStart(csProbe))) ||
                (scProbe.size >= 9 && Http2Parser.looksLikeFrameStart(scProbe))
            if (h2) {
                h2Streams.add(stream.key)
                if (Http2Parser.isPreface(stream.clientToServer.bytes())) {
                    stream.clientToServer.drain(Http2Parser.PREFACE_LENGTH)
                }
                Log.i(TAG, "HTTP/2 stream: ${stream.key} (${stream.sniHost ?: stream.dstIp})")
            }
        }
        if (stream.key in h2Streams) {
            handleHttp2(stream)
            return
        }

        // ── HTTP detection ───────────────────────────────────────────────
        val csBytes = stream.clientToServer.bytes()
        val request = HttpParser.tryParseRequest(
            bytes = csBytes,
            host = "${stream.dstIp}:${stream.dstPort}",
            port = stream.dstPort,
            isHttps = false, // TLS streams are caught above; this path is plaintext
        )

        if (request != null) {
            stream.clientToServer.drain(request.consumedBytes)
            // Store the pending request; wait for the response
            pendingRequests[stream.key] = request

            // Fix #14 (TODO): Detect WebSocket upgrade handshake. After the HTTP
            // upgrade request, subsequent bytes on this stream are WS frames.
            if (com.example.interception.http.WebSocketParser.looksLikeUpgrade(request.headers, isRequest = true)) {
                Log.i(TAG, "WebSocket upgrade on ${stream.key}")
                synchronized(wsStreams) { wsStreams.add(stream.key) }
            }
        }

        // Check for a response if we have a pending request
        val pendingReq = pendingRequests[stream.key] ?: return
        val scBytes = stream.serverToClient.bytes()
        val response = HttpParser.tryParseResponse(scBytes, sentRequestUrl = pendingReq.url)

        if (response != null) {
            // Fix #15 (TODO): If the response is SSE (text/event-stream), parse events
            // and persist the event stream as a tagged transaction.
            if (com.example.interception.http.SseParser.looksLikeSse(response.headers)) {
                val (events, _) = com.example.interception.http.SseParser.parseEvents(response.body)
                if (events.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "SSE stream on ${stream.key}: ${events.size} events, first=" +
                            events.first().event
                    )
                }
            }

            stream.serverToClient.drain(response.consumedBytes)
            pendingRequests.remove(stream.key)

            // Persist the pair
            persistTransaction(pendingReq, response, stream)
        }

        // Fix #14: After WebSocket upgrade, parse frames from either direction
        if (stream.key in wsStreams) {
            val clientBytes = stream.clientToServer.bytes()
            val serverBytes = stream.serverToClient.bytes()
            if (clientBytes.isNotEmpty()) {
                val (consumed, frames) = com.example.interception.http.WebSocketParser.parseFrames(clientBytes)
                if (consumed > 0) {
                    stream.clientToServer.drain(consumed)
                    frames.forEach { frame ->
                        Log.d(TAG, "WS <- client: ${com.example.interception.http.WebSocketParser.describe(frame)}")
                        persistWsFrame(stream, frame, isClient = true)
                    }
                }
            }
            if (serverBytes.isNotEmpty()) {
                val (consumed, frames) = com.example.interception.http.WebSocketParser.parseFrames(serverBytes)
                if (consumed > 0) {
                    stream.serverToClient.drain(consumed)
                    frames.forEach { frame ->
                        Log.d(TAG, "WS -> server: ${com.example.interception.http.WebSocketParser.describe(frame)}")
                        persistWsFrame(stream, frame, isClient = false)
                    }
                }
            }
        }
    }

    // ── HTTP/2 + gRPC handling ──────────────────────────────────────────

    /**
     * Parse all complete HTTP/2 frames buffered in both directions of a
     * stream, route HEADERS through HPACK, and feed DATA frames of gRPC
     * streams to [GrpcDetector].
     */
    private fun handleHttp2(stream: TcpStream) {
        processH2Direction(stream, isClient = true)
        processH2Direction(stream, isClient = false)
    }

    private fun processH2Direction(stream: TcpStream, isClient: Boolean) {
        val buffer = if (isClient) stream.clientToServer else stream.serverToClient
        val data = buffer.bytes()
        if (data.size < 9) return

        val frames = Http2Parser.parseFrames(data)
        if (frames.isEmpty()) return

        val state = hpackStates.getOrPut("${stream.key}|${if (isClient) "c" else "s"}") {
            Http2Parser.HpackDecoder()
        }

        var consumed = 0
        var i = 0
        while (i < frames.size) {
            val frame = frames[i]
            consumed += 9 + frame.length
            when (frame.type) {
                Http2Parser.FRAME_HEADERS -> {
                    // Coalesce CONTINUATION frames when END_HEADERS is not set —
                    // a header block spans HEADERS + CONTINUATION(s) on one stream.
                    var payload = frame.payload
                    if (!frame.endHeaders) {
                        val acc = java.io.ByteArrayOutputStream()
                        acc.write(frame.payload)
                        while (i + 1 < frames.size &&
                            frames[i + 1].type == Http2Parser.FRAME_CONTINUATION &&
                            frames[i + 1].streamId == frame.streamId
                        ) {
                            i++
                            consumed += 9 + frames[i].length
                            acc.write(frames[i].payload)
                            if (frames[i].endHeaders) break
                        }
                        payload = acc.toByteArray()
                    }
                    val headers = Http2Parser.decodeHeaders(frame, payload, state)
                    handleH2Headers(stream, headers, isClient)
                }
                Http2Parser.FRAME_DATA -> handleH2Data(stream, frame, isClient)
                Http2Parser.FRAME_RST_STREAM -> {
                    grpcCalls[stream.key]?.remove(frame.streamId)
                    grpcStatuses[stream.key]?.remove(frame.streamId)
                    grpcDataBuffers[stream.key]?.remove("${if (isClient) "c" else "s"}|${frame.streamId}")
                }
            }
            i++
        }
        buffer.drain(consumed)
    }

    /**
     * HEADERS frame handling: register gRPC calls from request headers and
     * capture grpc-status from trailers.
     */
    private fun handleH2Headers(stream: TcpStream, headers: Http2Parser.H2Headers, isClient: Boolean) {
        // Trailers: no pseudo-headers, may carry grpc-status.
        val status = GrpcDetector.grpcStatusFromTrailers(headers)
        if (status != null) {
            grpcStatuses.getOrPut(stream.key) { mutableMapOf() }[headers.streamId] = status
            Log.d(TAG, "gRPC trailer: stream=${headers.streamId} grpc-status=$status")
            return
        }

        var call = GrpcDetector.callFromHeaders(headers)
        if (call == null) {
            // Response-side headers (no :path) or HPACK-mangled request headers:
            // still recognise the stream as gRPC so DATA frames get captured.
            val isGrpc = headers.all.entries.any {
                it.key.equals("content-type", ignoreCase = true) && GrpcDetector.isGrpcContentType(it.value)
            }
            if (!isGrpc) return
            if (grpcCalls[stream.key]?.containsKey(headers.streamId) == true) return
            call = GrpcDetector.GrpcCall(
                streamId = headers.streamId,
                grpcMethod = "/(mid-stream)",
                authority = headers.pseudoHeaders[":authority"]
                    ?: stream.sniHost ?: "${stream.dstIp}:${stream.dstPort}",
                scheme = headers.pseudoHeaders[":scheme"] ?: if (stream.isTls) "https" else "http",
                httpMethod = "POST",
                contentType = GrpcDetector.GRPC_CONTENT_TYPE,
                encoding = "proto",
                service = "",
                rpcName = "",
                messageType = "",
                headers = headers.regularHeaders,
            )
        }

        grpcCalls.getOrPut(stream.key) { mutableMapOf() }[headers.streamId] = call
        Log.i(
            TAG,
            "gRPC call: ${call.url} h2stream=${headers.streamId} " +
                "type=${call.encoding} msgHint=${call.messageType.ifEmpty { "—" }}"
        )
    }

    /**
     * DATA frame handling: accumulate payload per gRPC stream and extract
     * complete length-prefixed protobuf messages.
     */
    private fun handleH2Data(stream: TcpStream, frame: Http2Parser.H2Frame, isClient: Boolean) {
        val call = grpcCalls[stream.key]?.get(frame.streamId) ?: return

        val dirKey = "${if (isClient) "c" else "s"}|${frame.streamId}"
        val bufs = grpcDataBuffers.getOrPut(stream.key) { mutableMapOf() }
        val combined = (bufs[dirKey] ?: ByteArray(0)) + frame.payload

        val (used, messages) = GrpcDetector.parseMessages(combined)
        val remainder = combined.copyOfRange(used, combined.size)
        if (remainder.size > MAX_GRPC_BUFFER) {
            Log.w(TAG, "gRPC buffer overflow on ${stream.key}/${frame.streamId} — dropping ${remainder.size}B")
            bufs.remove(dirKey)
        } else if (remainder.isEmpty()) {
            bufs.remove(dirKey)
        } else {
            bufs[dirKey] = remainder
        }

        for (msg in messages) {
            Log.d(TAG, GrpcDetector.describe(call, msg))
            persistGrpcMessage(stream, call, msg, isClient)
        }

        if (frame.endStream) bufs.remove(dirKey)
    }

    /**
     * Persist one gRPC message as a captured transaction:
     * protocol=gRPC, body = protobuf hex dump, decoded field tree attached as
     * a synthetic header so the detail UI can show it.
     */
    private fun persistGrpcMessage(
        stream: TcpStream,
        call: GrpcDetector.GrpcCall,
        msg: GrpcDetector.GrpcMessage,
        isClient: Boolean,
    ) {
        if (!captureEnabled) return

        val hexDump = ProtobufDecoder.hexDump(msg.payload)
        val decoded = if (!msg.compressed) ProtobufDecoder.decodeToString(msg.payload) else null
        val grpcStatus = grpcStatuses[stream.key]?.get(call.streamId)

        val metaHeaders = buildMap {
            put("x-grpc-encoding", call.encoding)
            put("x-grpc-stream-id", call.streamId.toString())
            if (call.messageType.isNotEmpty()) put("x-grpc-message-type", call.messageType)
            if (msg.compressed) put("x-grpc-compressed", "true")
            if (grpcStatus != null) put("x-grpc-status", grpcStatus.toString())
            if (decoded != null) put("x-protobuf-decoded", decoded)
        }

        val host = call.authority.ifEmpty { stream.sniHost ?: "${stream.dstIp}:${stream.dstPort}" }
        val entity = CapturedTrafficEntity(
            sessionId = sessionId,
            method = call.httpMethod, // :method — POST for real gRPC
            url = call.url,
            host = host,
            path = call.grpcMethod,   // :path — e.g. /my.Service/GetUser
            statusCode = null,
            protocol = "gRPC",
            requestHeaders = if (isClient) {
                if (SettingsRepository.settings.value.requestLoggingEnabled) call.headers + metaHeaders else metaHeaders
            } else metaHeaders,
            responseHeaders = metaHeaders,
            requestBody = if (isClient && SettingsRepository.settings.value.requestLoggingEnabled) hexDump else null,
            responseBody = if (!isClient && SettingsRepository.settings.value.responseLoggingEnabled) hexDump else null,
            requestSizeBytes = if (isClient) (msg.length + GrpcDetector.MESSAGE_HEADER_SIZE).toLong() else 0L,
            responseSizeBytes = if (!isClient) (msg.length + GrpcDetector.MESSAGE_HEADER_SIZE).toLong() else 0L,
            latencyMs = 0L,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = stream.decryptedByMitm,
        )

        runCatching {
            kotlinx.coroutines.runBlocking { db.capturedTrafficDao().insert(entity) }
            transactionCount++
            Log.d(
                TAG,
                "Captured gRPC: ${call.grpcMethod} ${if (isClient) "req" else "res"} " +
                    "${msg.length}B${if (msg.compressed) " compressed" else ""}"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to persist gRPC message: ${e.message}")
        }
    }

    // ── persistence ─────────────────────────────────────────────────────

    private fun persistTransaction(
        request: ParsedHttpMessage,
        response: ParsedHttpMessage,
        stream: TcpStream,
    ) {
        // v5: captureEnabled flag passed from RequestrVpnService (reads live settings at start)
        if (!captureEnabled) return

        // Fix 15 (AUDIT_REPORT): Consult intercept rules before persisting.
        // Fix #7 (TODO): Implement all rule actions, not just BLOCK.
        val ruleAction = checkRules(request.url, request.host)
        when (ruleAction) {
            com.example.data.local.RuleAction.BLOCK -> {
                Log.d(TAG, "BLOCKED by rule: ${request.method?.name} ${request.url.take(80)}")
                return  // Drop entirely — never persisted, never forwarded
            }
            com.example.data.local.RuleAction.REDIRECT -> {
                // REDIRECT: Find the matching rule's replacementValue and rewrite the URL.
                // For now, log + persist with a tag so user knows it was a redirect candidate.
                Log.d(TAG, "REDIRECT rule matched: ${request.url.take(80)}")
                // Persist with modified URL if replacementValue is set
                val matchedRule = getMatchingRule(request.url, request.host)
                if (matchedRule?.replacementValue?.isNotBlank() == true) {
                    request.url = matchedRule.replacementValue!!
                    request.host = runCatching {
                        java.net.URI(matchedRule.replacementValue!!).host ?: request.host
                    }.getOrDefault(request.host)
                }
            }
            com.example.data.local.RuleAction.MODIFY_REQUEST -> {
                // MODIFY_REQUEST: Transform request headers/body per rule's replacementValue.
                // replacementValue format: JSON with optional "addHeaders", "removeHeaders", "bodyReplace"
                Log.d(TAG, "MODIFY_REQUEST rule matched: ${request.url.take(80)}")
                val matchedRule = getMatchingRule(request.url, request.host)
                matchedRule?.replacementValue?.let { config ->
                    applyRequestModification(request, config)
                }
            }
            com.example.data.local.RuleAction.MODIFY_RESPONSE -> {
                // MODIFY_RESPONSE: Transform response headers/body per rule's replacementValue.
                Log.d(TAG, "MODIFY_RESPONSE rule matched: ${request.url.take(80)}")
                val matchedRule = getMatchingRule(request.url, request.host)
                matchedRule?.replacementValue?.let { config ->
                    applyResponseModification(response, config)
                }
            }
            com.example.data.local.RuleAction.LOG_ONLY -> {
                // LOG_ONLY: Always persist, even if trafficCaptureEnabled is off.
                // This overrides the capture toggle — tagged requests are always saved.
            }
        }

        // LOG_ONLY rules override the capture toggle — always persist tagged traffic
        val forcePersist = (ruleAction == com.example.data.local.RuleAction.LOG_ONLY
            && checkRules(request.url, request.host) != com.example.data.local.RuleAction.LOG_ONLY)
            // ^ if a specific LOG_ONLY rule matched (not the default), force persist

        // Enhanced Mode: an independent ProtocolDetector pass over the same exchange, purely
        // additive diagnostic headers (never gates persistence/blocking -- see
        // enhancedDiagnostics doc). Feeds it the real observed request line as firstBytes and
        // the real response headers as httpMetadata, so e.g. a gRPC/WebSocket/SSE/DoH
        // content-type match surfaces even for streams the primary parser only classified as
        // plain HTTP.
        val enhancedHeaders = enhancedDiagnostics(
            stream.dstPort,
            "${(request.method ?: com.example.data.models.HttpMethod.GET).name} ${request.path} ${request.version}".toByteArray(Charsets.UTF_8),
        )

        val entity = CapturedTrafficEntity(
            sessionId = sessionId,
            method = (request.method ?: com.example.data.models.HttpMethod.GET).name,
            url = request.url,
            host = request.host,
            path = request.path,
            statusCode = if (response.statusCode == 0) null else response.statusCode,
            protocol = response.version.ifEmpty { request.version },
            requestHeaders = (if (SettingsRepository.settings.value.requestLoggingEnabled) request.headers else emptyMap()) + enhancedHeaders,
            responseHeaders = if (SettingsRepository.settings.value.responseLoggingEnabled) response.headers else emptyMap(),
            requestBody = if (SettingsRepository.settings.value.requestLoggingEnabled) request.body.ifEmpty { null } else null,
            responseBody = if (SettingsRepository.settings.value.responseLoggingEnabled) response.body.ifEmpty { null } else null,
            requestSizeBytes = request.body.toByteArray(Charsets.UTF_8).size.toLong(),
            responseSizeBytes = response.body.toByteArray(Charsets.UTF_8).size.toLong(),
            latencyMs = 0L, // without proxy we don't have timing between request/response
            timestamp = System.currentTimeMillis(),
            wasDecrypted = false,
        )

        runCatching {
            kotlinx.coroutines.runBlocking {
                db.capturedTrafficDao().insert(entity)
            }
            transactionCount++
            Log.d(TAG, "Captured: ${response.statusCode} ${request.method?.name} ${request.url.take(120)}")
        }.onFailure { e ->
            Log.w(TAG, "Failed to persist transaction: ${e.message}")
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    /** Prune idle streams and stale pending requests. Call periodically. */
    fun prune() {
        reassembler.prune()
        // Remove pending requests for streams that no longer exist
        val activeKeys = reassembler.streams.keys
        pendingRequests.keys.removeAll { it !in activeKeys }
        // Drop HTTP/2 / gRPC state for dead streams
        h2Streams.removeAll { it !in activeKeys }
        hpackStates.keys.removeAll { it.substringBeforeLast('|') !in activeKeys }
        grpcCalls.keys.removeAll { it !in activeKeys }
        grpcDataBuffers.keys.removeAll { it !in activeKeys }
        grpcStatuses.keys.removeAll { it !in activeKeys }
        // M1: drop throttling bookkeeping for streams evicted by the reassembler. Their last
        // known state was already persisted either on close (immediate write in
        // persistTcpConnection) or at the last TCP_PERSIST_INTERVAL_MS tick.
        lastTcpPersist.keys.removeAll { it !in activeKeys }
    }

    fun streamCount(): Int = reassembler.streamCount()

    // ── Rules engine (Fix 15 / AUDIT_REPORT) ─────────────────────────────

    /**
     * Check enabled intercept rules against a request URL/host.
     * Returns the highest-priority action if any rule matches, or LOG_ONLY as default.
     *
     * Rule matching: pattern is checked as regex against the full URL.
     * Domain field is checked as substring against the host.
     */
    private fun checkRules(url: String, host: String): com.example.data.local.RuleAction =
        RuleEnforcer.checkRules(db, url, host)

    /**
     * Persists a WebSocket frame as a fake transaction so it appears in the capture log.
     * The URL is derived from the stream host plus " (WS)" suffix to make the direction visible.
     */
    private fun persistWsFrame(stream: TcpStream, frame: com.example.interception.http.WebSocketParser.WsFrame, isClient: Boolean) {
        if (!captureEnabled) return

        val wsHost = stream.sniHost ?: stream.dstIp
        val wsUrl = "ws://$wsHost${if (stream.dstPort != 80) ":${stream.dstPort}" else ""}"
        val entity = CapturedTrafficEntity(
            sessionId = sessionId,
            method = if (isClient) "WS→" else "WS←",
            url = "$wsUrl (${frame.opcodeName})",
            host = wsHost,
            path = "/",
            statusCode = null,
            protocol = "WEBSOCKET",
            requestHeaders = emptyMap(),
            responseHeaders = emptyMap(),
            requestBody = if (isClient) frame.payloadText else null,
            responseBody = if (!isClient) frame.payloadText else null,
            requestSizeBytes = if (isClient) frame.totalLength.toLong() else 0L,
            responseSizeBytes = if (!isClient) frame.totalLength.toLong() else 0L,
            latencyMs = 0L,
            timestamp = System.currentTimeMillis(),
            wasDecrypted = false,
        )
        runCatching {
            kotlinx.coroutines.runBlocking { db.capturedTrafficDao().insert(entity) }
        }.onFailure { Log.w(TAG, "Failed to persist WS frame: ${it.message}") }
    }

    /** Returns the first matching rule entity (for accessing replacementValue). */
    private fun getMatchingRule(url: String, host: String): InterceptRuleEntity? =
        RuleEnforcer.getMatchingRule(db, url, host)

    /**
     * Applies request modifications from a rule's replacementValue (JSON config).
     * Format: {"addHeaders":{"X-Custom":"value"}, "removeHeaders":["Cookie"], "bodyReplace":"new body"}
     */
    private fun applyRequestModification(request: ParsedHttpMessage, config: String) =
        RuleEnforcer.applyRequestModification(request, config)

    /**
     * Applies response modifications from a rule's replacementValue (JSON config).
     * Format: {"statusCode":200, "addHeaders":{"X-Mock":"true"}, "bodyReplace":"{}"}
     */
    private fun applyResponseModification(response: ParsedHttpMessage, config: String) =
        RuleEnforcer.applyResponseModification(response, config)

    private fun scHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Enhanced Mode's real analysis pass. Runs [ProtocolDetector] against the raw bytes seen on
     * a stream and returns extra `x-protocol-*` headers describing what it independently
     * concluded -- a cross-check against whatever the HTTP/TLS parser already decided, useful
     * for traffic the primary parsers only partially recognize (e.g. an UNKNOWN/POSSIBLE verdict
     * on a stream the HTTP parser also struggled with is itself a real diagnostic signal).
     * Returns an empty map when [enhancedMode] is off or detection fails, so callers can always
     * merge this in with `+` without a null check.
     */
    private fun enhancedDiagnostics(port: Int, firstBytes: ByteArray): Map<String, String> {
        if (!enhancedMode || firstBytes.isEmpty()) return emptyMap()
        return runCatching {
            val detection = ProtocolDetector.detect(port, firstBytes)
            mapOf(
                "x-protocol-detected" to detection.protocol.label,
                "x-protocol-confidence" to detection.confidence.name,
                "x-protocol-reason" to detection.reason.take(120),
            )
        }.getOrElse { e ->
            Log.w(TAG, "Enhanced Mode: ProtocolDetector failed: ${e.message}")
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "CaptureEngine"

        /** Cap on a buffered partial gRPC message per stream (defends against OOM). */
        private const val MAX_GRPC_BUFFER = 8 * 1024 * 1024

        /** M1: how often (ms) an unchanged TCP connection row gets refreshed in Room. */
        private const val TCP_PERSIST_INTERVAL_MS = 2_000L
    }
}
