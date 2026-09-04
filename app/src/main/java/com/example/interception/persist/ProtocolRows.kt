package com.example.interception.persist

import com.example.data.local.CaptureRowMethod
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.GrpcCallEntity
import com.example.data.local.GrpcMessageEntity
import com.example.data.local.HeaderKind
import com.example.data.local.Http2StreamEventEntity
import com.example.data.local.MessageDirection
import com.example.data.local.SseEventEntity
import com.example.data.local.TransactionHeaderEntity
import com.example.data.local.WebSocketMessageEntity
import com.example.interception.grpc.GrpcDetector
import com.example.interception.grpc.ProtobufDecoder
import com.example.interception.http.H2FrameEvent
import com.example.interception.http.HttpBodyDecoder
import com.example.interception.http.Http2Parser
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.http.SseParser
import com.example.interception.http.WebSocketParser

/**
 * S3 task 20 -- the one place that turns protocol events into v20 rows.
 *
 * Shared by [com.example.interception.MitmProxyEngine] (MITM path) and
 * [com.example.interception.CaptureEngine] (tun path) so a WebSocket message, SSE event or gRPC
 * message renders identically whichever pipeline observed it. Pure functions: no I/O, no clock
 * other than the `now` the caller passes, so every shape is unit-testable without Room.
 *
 * Replaces the two pre-v20 conventions -- one `captured_traffic` row per WebSocket frame with
 * `method = "WS→"/"WS←"`, and one per gRPC message carrying `x-grpc-*` synthetic headers --
 * which v21 (task 21) folds into the same tables these rows land in.
 */
internal object ProtocolRows {

    // -- headers -------------------------------------------------------------------------------

    /**
     * Wire-ordered `transaction_headers` rows for an exchange. HTTP/2 pseudo-headers come from
     * the [h2Request]/[h2Response] blocks (REQUEST_PSEUDO/RESPONSE_PSEUDO); everything else from
     * the messages' `headerLines`/`trailers`. Header-logging toggles suppress client/server
     * content the same way the legacy map columns do, except requestr's own metadata keys.
     */
    fun headerRows(
        request: ParsedHttpMessage?,
        response: ParsedHttpMessage?,
        logRequests: Boolean,
        logResponses: Boolean,
        h2Request: Http2Parser.H2Headers? = null,
        h2Response: Http2Parser.H2Headers? = null,
        h2RequestTrailers: Http2Parser.H2Headers? = null,
        h2ResponseTrailers: Http2Parser.H2Headers? = null,
        metadataKeys: Set<String> = emptySet(),
    ): List<TransactionHeaderEntity> {
        val rows = ArrayList<TransactionHeaderEntity>()
        fun add(kind: HeaderKind, entries: List<Pair<String, String>>, allowed: Boolean) {
            var ordinal = 0
            for ((name, value) in entries) {
                if (!allowed && name.lowercase() !in metadataKeys) continue
                rows += TransactionHeaderEntity(trafficId = 0L, kind = kind, ordinal = ordinal++, name = name, value = value)
            }
        }
        h2Request?.let { add(HeaderKind.REQUEST_PSEUDO, it.entries.filter { e -> e.first.startsWith(":") }, logRequests) }
        h2Response?.let { add(HeaderKind.RESPONSE_PSEUDO, it.entries.filter { e -> e.first.startsWith(":") }, logResponses) }
        request?.let {
            add(HeaderKind.REQUEST, it.headerLines.ifEmpty { it.headers.toList() }, logRequests)
            add(HeaderKind.REQUEST_TRAILER, h2RequestTrailers?.regularEntries ?: it.trailers, logRequests)
        }
        response?.let {
            add(HeaderKind.RESPONSE, it.headerLines.ifEmpty { it.headers.toList() }, logResponses)
            add(HeaderKind.RESPONSE_TRAILER, h2ResponseTrailers?.regularEntries ?: it.trailers, logResponses)
        }
        return rows
    }

    // -- WebSocket -----------------------------------------------------------------------------

    fun webSocketMessage(
        frame: WebSocketParser.WsFrame,
        isClient: Boolean,
        connectionId: String,
        frameIndex: Int,
        logPayload: Boolean,
        now: Long,
    ): WebSocketMessageEntity {
        val isText = frame.opcode == WebSocketParser.OP_TEXT
        var text: String? = null
        var bytes: ByteArray? = null
        var truncated = false
        if (logPayload) {
            if (isText || frame.opcode == WebSocketParser.OP_CLOSE || frame.opcode == WebSocketParser.OP_PING ||
                frame.opcode == WebSocketParser.OP_PONG
            ) {
                val full = frame.payloadText
                if (full.length > WebSocketMessageEntity.MAX_TEXT_CHARS) {
                    text = full.substring(0, WebSocketMessageEntity.MAX_TEXT_CHARS)
                    truncated = true
                } else {
                    text = full
                }
            }
            if (!isText) {
                val cap = HttpBodyDecoder.MAX_STORED_BODY_BYTES
                if (frame.payload.size > cap) {
                    bytes = frame.payload.copyOf(cap)
                    truncated = true
                } else {
                    bytes = frame.payload
                }
            }
        }
        return WebSocketMessageEntity(
            trafficId = 0L,
            connectionId = connectionId,
            direction = if (isClient) MessageDirection.CLIENT else MessageDirection.SERVER,
            opcode = frame.opcode,
            isText = isText,
            payloadText = text,
            payloadBytes = bytes,
            payloadTruncated = truncated,
            sizeBytes = frame.payload.size.toLong(),
            timestampMs = now,
            frameIndex = frameIndex,
        )
    }

    // -- SSE -----------------------------------------------------------------------------------

    /** Events parsed from [body], numbered from [firstIndex] (streaming appends continue the count). */
    fun sseEvents(body: String, now: Long, firstIndex: Int = 0): List<SseEventEntity> {
        val (events, _) = SseParser.parseEvents(body)
        return events.mapIndexed { i, e ->
            SseEventEntity(
                trafficId = 0L,
                eventName = e.event.takeIf { it != "message" },
                data = e.data,
                eventId = e.id,
                retryMs = e.retryMs?.toLong(),
                timestampMs = now,
                eventIndex = firstIndex + i,
            )
        }
    }

    // -- gRPC ----------------------------------------------------------------------------------

    /**
     * The parent `captured_traffic` row for a gRPC call, created when the request HEADERS
     * identify it. `statusCode` is NULL until the trailers patch it; `protocol = "gRPC"` keeps
     * the existing list filter working. No synthetic `x-grpc-*` header is written anywhere.
     */
    fun grpcParent(
        call: GrpcDetector.GrpcCall,
        sessionId: Long,
        host: String,
        connectionId: String,
        captureMethod: CaptureRowMethod,
        wasDecrypted: Boolean,
        logRequests: Boolean,
        now: Long,
        metadataKeys: Set<String> = emptySet(),
    ): CapturedTrafficEntity = CapturedTrafficEntity(
        sessionId = sessionId,
        method = call.httpMethod.ifEmpty { "POST" },
        url = call.url,
        host = call.authority.ifEmpty { host },
        path = call.grpcMethod,
        statusCode = null,
        protocol = "gRPC",
        requestHeaders = if (logRequests) call.headers else call.headers.filterKeys { it.lowercase() in metadataKeys },
        responseHeaders = emptyMap(),
        requestBody = null,
        responseBody = null,
        requestSizeBytes = 0L,
        responseSizeBytes = 0L,
        latencyMs = 0L,
        timestamp = now,
        wasDecrypted = wasDecrypted,
        isUndecoded = false,
        connectionId = connectionId,
        captureMethod = captureMethod,
    )

    /** The trailer-time patch: `:status`, response headers, per-direction byte totals, latency. */
    fun grpcParentPatch(
        parent: CapturedTrafficEntity,
        responseHeaders: Http2Parser.H2Headers?,
        requestBytes: Long,
        responseBytes: Long,
        latencyMs: Long,
        logResponses: Boolean,
    ): CapturedTrafficEntity {
        val status = responseHeaders?.pseudoHeaders?.get(":status")?.toIntOrNull()
        val regular = responseHeaders?.regularHeaders ?: emptyMap()
        return parent.copy(
            method = "",
            url = parent.url,
            host = "",
            path = "",
            statusCode = status,
            responseHeaders = if (logResponses) regular else emptyMap(),
            requestSizeBytes = requestBytes,
            responseSizeBytes = responseBytes,
            latencyMs = latencyMs,
        )
    }

    fun grpcCall(call: GrpcDetector.GrpcCall, streamId: Int, grpcStatus: Int?, grpcMessage: String?): GrpcCallEntity =
        GrpcCallEntity(
            trafficId = 0L,
            service = call.service,
            method = call.rpcName,
            encoding = call.encoding,
            messageType = call.messageType,
            streamId = streamId,
            grpcStatus = grpcStatus,
            grpcMessage = grpcMessage,
        )

    fun grpcMessage(
        msg: GrpcDetector.GrpcMessage,
        isClient: Boolean,
        streamId: Int,
        messageIndex: Int,
        logPayload: Boolean,
        now: Long,
    ): GrpcMessageEntity {
        var payload: ByteArray? = null
        var truncated = false
        var decoded: String? = null
        if (logPayload) {
            val cap = HttpBodyDecoder.MAX_STORED_BODY_BYTES
            if (msg.payload.size > cap) {
                payload = msg.payload.copyOf(cap)
                truncated = true
            } else {
                payload = msg.payload
            }
            if (!msg.compressed) decoded = ProtobufDecoder.decodeToString(msg.payload)
        }
        return GrpcMessageEntity(
            trafficId = 0L,
            streamId = streamId,
            direction = if (isClient) MessageDirection.CLIENT else MessageDirection.SERVER,
            compressed = msg.compressed,
            messageIndex = messageIndex,
            payload = payload,
            payloadTruncated = truncated,
            decodedText = decoded,
            sizeBytes = msg.length.toLong(),
            timestampMs = now,
        )
    }

    // -- HTTP/2 frames -------------------------------------------------------------------------

    fun http2StreamEvents(events: List<H2FrameEvent>, connectionId: String): List<Http2StreamEventEntity> =
        events.map { e ->
            Http2StreamEventEntity(
                trafficId = 0L,
                connectionId = connectionId,
                streamId = e.streamId,
                direction = if (e.fromClient) MessageDirection.CLIENT else MessageDirection.SERVER,
                frameType = e.frameType,
                flags = e.flags,
                payloadLength = e.payloadLength,
                timestampMs = e.timestampMs,
                note = e.note,
            )
        }

    /** Synthetic-header guard used by tests and the writers' own assertions. */
    fun hasSyntheticGrpcHeader(entity: CapturedTrafficEntity): Boolean =
        (entity.requestHeaders.keys + entity.responseHeaders.keys).any {
            it.startsWith("x-grpc-", ignoreCase = true) || it.equals("x-protobuf-decoded", ignoreCase = true)
        }
}
