package com.example.interception.grpc

import com.example.interception.http.Http2Parser

/**
 * gRPC-over-HTTP/2 detector and message framer.
 *
 * gRPC is carried inside HTTP/2 streams: a HEADERS frame whose
 * `content-type` is `application/grpc` (optionally with a `+proto` /
 * `+json` / custom suffix) opens an RPC call; the body is a sequence of
 * length-prefixed messages inside DATA frames, and a trailing HEADERS
 * frame carries `grpc-status`.
 *
 * Message framing (https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md):
 *
 * ```
 * +--------+-------------------+-------------------+
 * | flag   | message length    | message payload   |
 * | 1 byte | 4 bytes (big-end) | <length> bytes    |
 * +--------+-------------------+-------------------+
 * ```
 *
 * The 1-byte flag is 0x00 (uncompressed) or 0x01 (compressed); the
 * payload is usually one protobuf message, decodable with [ProtobufDecoder].
 *
 * About "messageType": the protobuf request/response type names are NOT
 * transmitted on the wire — recovering them exactly requires the service's
 * .proto descriptors. [GrpcCall.messageType] is therefore a documented
 * best-effort hint derived from the conventional protoc naming scheme
 * (`<Rpc>Request`), while [GrpcCall.encoding] (`proto`, `json`, …) comes
 * from the content-type suffix and is authoritative.
 */
object GrpcDetector {
    private const val TAG = "GrpcDetector"

    /** Prefix of every gRPC content-type (may carry a `+proto`/`+json`/custom suffix). */
    const val GRPC_CONTENT_TYPE = "application/grpc"

    /** gRPC message header: 1 compression-flag byte + 4 length bytes. */
    const val MESSAGE_HEADER_SIZE = 5

    /** Refuse framed messages larger than this — guards against framing mis-detection. */
    private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024

    /** One parsed gRPC call descriptor, recovered from a HEADERS frame. */
    data class GrpcCall(
        /** HTTP/2 stream id this RPC runs on. */
        val streamId: Int,
        /** `:path`, e.g. `/my.Service/GetUser` — the full gRPC method. */
        val grpcMethod: String,
        /** `:authority`. */
        val authority: String,
        /** `:scheme` (http/https). */
        val scheme: String,
        /** HTTP `:method` (POST for real gRPC). */
        val httpMethod: String,
        /** Full content-type, e.g. `application/grpc+proto`. */
        val contentType: String,
        /** Encoding suffix from the content-type (`proto`, `json`, …) or "proto" default. */
        val encoding: String,
        /** Service part of the path (`my.Service`). */
        val service: String,
        /** RPC name part of the path (`GetUser`). */
        val rpcName: String,
        /**
         * Best-effort protobuf message type hint. The wire does not carry type
         * names; this follows the protoc convention `<Rpc>Request`.
         */
        val messageType: String,
        /** All decoded regular headers (grpc-timeout, user-agent, …). */
        val headers: Map<String, String>,
    ) {
        /** Absolute URL used for persistence, e.g. `https://host/my.Service/GetUser`. */
        val url: String get() = "$scheme://$authority$grpcMethod"
    }

    /** One length-prefixed gRPC message extracted from DATA frames. */
    data class GrpcMessage(
        /** True when the sender set the compressed flag (payload then needs inflation). */
        val compressed: Boolean,
        /** Declared payload length in bytes. */
        val length: Int,
        /** The protobuf payload (or compressed blob when [compressed]). */
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is GrpcMessage && compressed == other.compressed &&
                length == other.length && payload.contentEquals(other.payload))
        override fun hashCode(): Int = 31 * (31 * compressed.hashCode() + length) + payload.contentHashCode()
    }

    /**
     * gRPC content-type check: matches `application/grpc`,
     * `application/grpc+proto`, `application/grpc+json`, and case variants.
     */
    fun isGrpcContentType(contentType: String?): Boolean {
        if (contentType == null) return false
        val ct = contentType.trim().lowercase()
        return ct == GRPC_CONTENT_TYPE ||
            (ct.startsWith("$GRPC_CONTENT_TYPE+") && ct.length > GRPC_CONTENT_TYPE.length + 1) ||
            ct.startsWith("$GRPC_CONTENT_TYPE;")
    }

    /**
     * Build a [GrpcCall] from a decoded HEADERS frame when it opens a gRPC
     * stream, else null. Request headers carry `:path`/`:method`; trailer-only
     * HEADERS (no pseudo-headers) return null and are handled separately.
     */
    fun callFromHeaders(headers: Http2Parser.H2Headers): GrpcCall? {
        val all = headers.pseudoHeaders + headers.regularHeaders
        val contentType = all.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?: return null
        if (!isGrpcContentType(contentType)) return null

        val path = headers.pseudoHeaders[":path"] ?: return null
        val (service, rpcName) = splitPath(path)
        return GrpcCall(
            streamId = headers.streamId,
            grpcMethod = path,
            authority = headers.pseudoHeaders[":authority"] ?: "",
            scheme = headers.pseudoHeaders[":scheme"] ?: "https",
            httpMethod = headers.pseudoHeaders[":method"] ?: "POST",
            contentType = contentType,
            encoding = contentType.substringAfter("$GRPC_CONTENT_TYPE+", "proto")
                .substringBefore(';').trim().ifEmpty { "proto" },
            service = service,
            rpcName = rpcName,
            messageType = if (rpcName.isNotEmpty()) "${rpcName}Request" else "",
            headers = headers.regularHeaders,
        )
    }

    /** Split `/package.Service/Rpc` into ("package.Service", "Rpc"). */
    fun splitPath(path: String): Pair<String, String> {
        val trimmed = path.removePrefix("/")
        val slash = trimmed.lastIndexOf('/')
        return if (slash > 0) trimmed.substring(0, slash) to trimmed.substring(slash + 1)
        else "" to trimmed
    }

    /**
     * Parse length-prefixed gRPC messages out of a byte buffer (typically the
     * concatenation of one or more DATA frame payloads).
     *
     * @return consumed bytes to remaining messages. A trailing partial
     *         message (header or payload incomplete) is left unconsumed so the
     *         caller can retry once more DATA arrives. On malformed framing
     *         (bad flag / absurd length) consumption stops before the bad
     *         message so we never loop on garbage.
     */
    fun parseMessages(data: ByteArray, offset: Int = 0): Pair<Int, List<GrpcMessage>> {
        val messages = mutableListOf<GrpcMessage>()
        var pos = offset
        while (pos + MESSAGE_HEADER_SIZE <= data.size) {
            val flag = data[pos].toInt() and 0xFF
            if (flag and 0xFE != 0) break // only bit 0 (compressed) is defined
            val len = ((data[pos + 1].toInt() and 0xFF) shl 24) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 8) or
                (data[pos + 4].toInt() and 0xFF)
            if (len < 0 || len > MAX_MESSAGE_SIZE) break // framing mis-detection guard
            if (pos + MESSAGE_HEADER_SIZE + len > data.size) break // wait for more DATA
            messages += GrpcMessage(
                compressed = flag == 0x01,
                length = len,
                payload = data.copyOfRange(pos + MESSAGE_HEADER_SIZE, pos + MESSAGE_HEADER_SIZE + len),
            )
            pos += MESSAGE_HEADER_SIZE + len
        }
        return (pos - offset) to messages
    }

    /**
     * Framing-only heuristic: does [data] look like gRPC DATA payload(s)?
     * Used as a fallback when HPACK decoding could not recover the
     * content-type. Requires at least one full message that frames cleanly.
     */
    fun looksLikeGrpcFraming(data: ByteArray): Boolean {
        if (data.size < MESSAGE_HEADER_SIZE) return false
        val (consumed, messages) = parseMessages(data)
        return consumed > 0 && messages.isNotEmpty()
    }

    /** Extract `grpc-status` from a trailer HEADERS block, when present. */
    fun grpcStatusFromTrailers(headers: Http2Parser.H2Headers): Int? {
        val all = headers.pseudoHeaders + headers.regularHeaders
        return all.entries.firstOrNull { it.key.equals("grpc-status", ignoreCase = true) }
            ?.value?.trim()?.toIntOrNull()
    }

    /** Short one-line description for logs. */
    fun describe(call: GrpcCall, message: GrpcMessage): String =
        "gRPC ${call.grpcMethod} msg#${message.length}B${if (message.compressed) " (compressed)" else ""}"
}
