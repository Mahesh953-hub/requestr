package com.requestr.sdk

import android.util.Log
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status

/**
 * Phase 6: real gRPC capture via gRPC's own [ClientInterceptor] API -- entirely at the
 * already-deserialized-message layer, so this never has to parse HTTP/2+HPACK off the wire
 * (the hard problem `MitmProxyEngine` still has, see `docs/plans/MASTER-FEATURE-ADDITION-PLAN.md`
 * §1.2). Works for any gRPC stub the host app builds, unary or streaming, as long as the host
 * app wires [RequestrGrpcInterceptor] onto its own `ManagedChannelBuilder`/`Channel` -- there is
 * no process-global gRPC hook analogous to [HttpUrlConnectionWrapper], gRPC channels are always
 * built explicitly by the caller.
 *
 * `compileOnly("io.grpc:grpc-api:...")` in `sdk/build.gradle.kts` means this file's classes only
 * load if the host app itself depends on gRPC -- an app that never touches gRPC never pays for
 * (or crashes on) a missing class, exactly the same opt-in shape as [CronetInterceptor] and
 * [KtorInterceptor].
 */
fun <T : ManagedChannelBuilder<T>> ManagedChannelBuilder<T>.addRequestrInterceptor(observer: RequestrObserver): ManagedChannelBuilder<T> {
    return intercept(RequestrGrpcInterceptor(observer))
}

/**
 * Captures metadata (request headers, response headers, trailers), every sent/received message,
 * the terminal [Status], timing, and client-initiated cancellation for one gRPC call, then
 * reports it through the same [RequestrObserver.observeDetailed] pipeline every other stack
 * (`OkHttp`/`HttpURLConnection`/`Cronet`/`Ktor`) funnels into.
 *
 * Streaming calls (client-streaming, server-streaming, bidi) can send/receive more than one
 * message before the call closes -- unlike the single-shot `requestBody`/`responseBody` the
 * other interceptors capture, this renders every sent/received message into a JSON array (see
 * [GrpcMessageRenderer]) and reports the whole call exactly once, from [onClose] -- gRPC
 * guarantees `Listener.onClose` fires exactly once per call, including after a client-initiated
 * [ClientCall.cancel], so that's the one safe place to report from without ever double-reporting.
 *
 * A long-lived bidi/streaming call can send or receive an unbounded number of messages before it
 * ever closes -- retaining every one of them verbatim until `onClose` would let a single call's
 * memory footprint grow without limit. [GrpcCallCapture] bounds retention per direction (see
 * [BoundedMessageBuffer]): once either the message-count or total-byte budget is hit, further
 * messages are counted and their sizes tallied but not retained, and the reported event's headers
 * carry explicit `grpc-sent-*`/`grpc-received-*` count/drop/truncation metadata so a dropped
 * message is always visible in the captured event, never silently missing.
 */
class RequestrGrpcInterceptor(private val observer: RequestrObserver) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val capture = GrpcCallCapture(method.fullMethodName, method.type.name)
        val call = next.newCall(method, callOptions)

        return object : SimpleForwardingClientCall<ReqT, RespT>(call) {
            override fun start(responseListener: ClientCall.Listener<RespT>, headers: Metadata) {
                capture.requestHeaders = GrpcMessageRenderer.metadataToMap(headers)

                val listener = object : SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onHeaders(headers: Metadata) {
                        capture.responseHeaders = GrpcMessageRenderer.metadataToMap(headers)
                        super.onHeaders(headers)
                    }

                    override fun onMessage(message: RespT) {
                        capture.recordReceived(GrpcMessageRenderer.render(message))
                        super.onMessage(message)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        capture.trailers = GrpcMessageRenderer.metadataToMap(trailers)
                        capture.status = status
                        runCatching { capture.report(observer) }.onFailure {
                            Log.w(TAG, "failed to report gRPC call for ${method.fullMethodName}: ${it.message}")
                        }
                        super.onClose(status, trailers)
                    }
                }
                super.start(listener, headers)
            }

            override fun sendMessage(message: ReqT) {
                capture.recordSent(GrpcMessageRenderer.render(message))
                super.sendMessage(message)
            }

            override fun cancel(message: String?, cause: Throwable?) {
                // Doesn't report here -- gRPC still guarantees a matching onClose(Status.CANCELLED,
                // ...) after this, which is where the (now-final) capture actually gets reported.
                // See this class's kdoc for why onClose is the one safe reporting point.
                capture.cancelled = true
                capture.cancelReason = message ?: cause?.message
                super.cancel(message, cause)
            }
        }
    }

    private companion object {
        const val TAG = "RequestrGrpc"
    }
}

/** Mutable per-call accumulator for [RequestrGrpcInterceptor] -- one instance per
 * `interceptCall`, never shared or reused across calls.
 *
 * `internal` (not file-private) so [GrpcCallCaptureStressTest]-style tests in other files can
 * exercise the bounded-retention/cleanup contract directly against real [BoundedMessageBuffer]s,
 * without having to drive an entire fake [ClientCall] lifecycle just to observe internal
 * accounting. */
internal class GrpcCallCapture(private val fullMethodName: String, private val methodType: String) {
    private val startedAt = System.currentTimeMillis()
    var requestHeaders: Map<String, String> = emptyMap()
    var responseHeaders: Map<String, String> = emptyMap()
    var trailers: Map<String, String> = emptyMap()

    /** Bounded per-direction message retention -- see [BoundedMessageBuffer]'s kdoc. Exposed
     * `internal` (not `private`) purely so tests can assert on retained/dropped counts and on
     * post-[report] cleanup without needing reflection. */
    val sentBuffer = BoundedMessageBuffer(MAX_MESSAGES_PER_DIRECTION, MAX_BYTES_PER_DIRECTION)
    val receivedBuffer = BoundedMessageBuffer(MAX_MESSAGES_PER_DIRECTION, MAX_BYTES_PER_DIRECTION)
    var status: Status? = null
    var cancelled: Boolean = false
    var cancelReason: String? = null
    private var reported = false

    fun recordSent(rendered: String) = sentBuffer.add(rendered)
    fun recordReceived(rendered: String) = receivedBuffer.add(rendered)

    /** Reports the completed call -- see [RequestrGrpcInterceptor.RequestrGrpcInterceptor.interceptCall]'s
     * `onClose` override, the one place this is called from. Idempotent (a second call is a
     * no-op) purely as a defensive guard; gRPC's own contract already guarantees `onClose` fires
     * exactly once per call, so this should never actually be exercised in practice. */
    fun report(observer: RequestrObserver) {
        if (reported) return
        reported = true
        try {
            val headers = LinkedHashMap<String, String>()
            requestHeaders.forEach { (k, v) -> headers["request:$k"] = v }
            responseHeaders.forEach { (k, v) -> headers["response:$k"] = v }
            trailers.forEach { (k, v) -> headers["trailer:$k"] = v }
            headers["grpc-method-type"] = methodType
            val finalStatus = status
            if (finalStatus != null) {
                headers["grpc-status-code"] = finalStatus.code.name
                finalStatus.description?.let { headers["grpc-status-description"] = it }
                finalStatus.cause?.message?.let { headers["grpc-status-cause"] = it }
            }
            if (cancelled) {
                headers["grpc-cancelled"] = "true"
                cancelReason?.let { headers["grpc-cancel-reason"] = it }
            }
            addRetentionMetadata(headers, direction = "sent", buffer = sentBuffer)
            addRetentionMetadata(headers, direction = "received", buffer = receivedBuffer)

            observer.observeDetailed(
                url = "grpc://$fullMethodName",
                method = methodType,
                headers = headers,
                statusCode = finalStatus?.code?.value(),
                requestBody = GrpcMessageRenderer.renderMessageList(sentBuffer.messages).toByteArray(Charsets.UTF_8),
                responseBody = GrpcMessageRenderer.renderMessageList(receivedBuffer.messages).toByteArray(Charsets.UTF_8),
                durationMs = System.currentTimeMillis() - startedAt,
                httpStack = "gRPC",
                isDecrypted = true,
            )
        } finally {
            // Terminal/cancel cleanup: report() only ever runs once, from onClose (which also
            // fires after a client-initiated cancel() -- see this class's kdoc), and by the time
            // it returns the event above has already been handed to the observer. There is no
            // reason to keep retained message strings pinned in memory beyond that: a host app
            // that stores its ClientCall (or a listener closure that indirectly keeps this
            // capture alive) for logging/retry purposes would otherwise keep holding onto up to
            // MAX_BYTES_PER_DIRECTION * 2 of message text per completed call for as long as that
            // reference lives.
            sentBuffer.clear()
            receivedBuffer.clear()
        }
    }

    /** Adds explicit count/drop/truncation metadata for one direction so a dropped message is
     * always visible on the reported event -- never silently missing from the JSON body without
     * a trace of why. */
    private fun addRetentionMetadata(headers: MutableMap<String, String>, direction: String, buffer: BoundedMessageBuffer) {
        headers["grpc-$direction-message-count"] = buffer.totalCount.toString()
        headers["grpc-$direction-messages-retained"] = buffer.messages.size.toString()
        if (buffer.isTruncated) {
            headers["grpc-$direction-truncated"] = "true"
            headers["grpc-$direction-messages-dropped"] = buffer.droppedCount.toString()
            headers["grpc-$direction-bytes-dropped"] = buffer.droppedBytes.toString()
        }
    }

    private companion object {
        /** Caps retained message *count* per direction -- independent of the byte budget below,
         * since a stream of many tiny messages could otherwise stay within
         * [MAX_BYTES_PER_DIRECTION] while still holding an unbounded number of small [String]
         * objects (and [MutableList] entries) alive. */
        const val MAX_MESSAGES_PER_DIRECTION = 50

        /** Caps retained message *bytes* per direction -- independent of the count budget above,
         * since a stream of relatively few large messages (each already capped at 256 KiB by
         * [GrpcMessageRenderer.MAX_RENDERED_MESSAGE_CHARS]) could otherwise stay within
         * [MAX_MESSAGES_PER_DIRECTION] while still retaining megabytes of text. 1 MiB retained per
         * direction (2 MiB per call) is generous enough to keep the vast majority of real calls
         * fully captured while still bounding a pathological long-lived bidi stream. */
        const val MAX_BYTES_PER_DIRECTION = 1 * 1024 * 1024
    }
}

/**
 * Bounded accumulator for one direction (sent or received) of one gRPC call's messages. Once
 * either [maxMessages] or [maxTotalBytes] is exceeded, further [add] calls are still counted
 * (via [totalCount]/[droppedCount]/[droppedBytes]) but the message text itself is dropped rather
 * than retained -- this is what actually bounds a long-lived streaming call's memory footprint,
 * as opposed to [GrpcMessageRenderer]'s per-message truncation, which only bounds a single
 * message's own size.
 */
internal class BoundedMessageBuffer(private val maxMessages: Int, private val maxTotalBytes: Int) {
    /** Rendered message text actually retained, in arrival order -- never larger than
     * [maxMessages] entries or [maxTotalBytes] total UTF-8 bytes. */
    val messages = mutableListOf<String>()

    /** Every message [add]-ed so far, retained or not -- lets a reported event always state how
     * many messages a stream actually carried, even once retention stops. */
    var totalCount: Int = 0
        private set

    /** Sum of the UTF-8 byte length of every entry currently in [messages]. */
    var retainedBytes: Int = 0
        private set

    /** How many messages were counted but not retained once a budget was hit. */
    var droppedCount: Int = 0
        private set

    /** Sum of the UTF-8 byte length of every dropped (not retained) message -- `Long` because a
     * sufficiently long-lived pathological stream could otherwise overflow an `Int` byte total
     * even though nothing that large is ever actually held in memory at once. */
    var droppedBytes: Long = 0
        private set

    /** True once at least one message has been dropped -- i.e. this buffer no longer holds the
     * complete picture of what the call sent/received. */
    val isTruncated: Boolean get() = droppedCount > 0

    fun add(rendered: String) {
        totalCount++
        val byteSize = rendered.toByteArray(Charsets.UTF_8).size
        val fitsBudget = messages.size < maxMessages && retainedBytes + byteSize <= maxTotalBytes
        if (fitsBudget) {
            messages.add(rendered)
            retainedBytes += byteSize
        } else {
            droppedCount++
            droppedBytes += byteSize
        }
    }

    /** Releases every retained message string -- see [GrpcCallCapture.report]'s `finally` block,
     * the one place this is called from (terminal/cancel cleanup, always run exactly once per
     * call right after reporting). */
    fun clear() {
        messages.clear()
        retainedBytes = 0
    }
}

/**
 * Renders gRPC messages and [Metadata] into plain strings/maps without ever requiring a
 * compile-time dependency on protobuf -- a gRPC [MethodDescriptor] can be backed by any
 * [MethodDescriptor.Marshaller] (protobuf is merely the overwhelmingly common one), so this
 * module has no business assuming every captured message is a protobuf `Message`.
 *
 * When the message *does* turn out to be a protobuf message, rendering it via
 * `com.google.protobuf.util.JsonFormat` (looked up entirely by reflection -- this module has no
 * `protobuf-java`/`protobuf-java-util` dependency at all, not even `compileOnly`) produces
 * readable JSON instead of protobuf's opaque default `toString()`. Every reflective step
 * degrades to the plain `toString()` fallback if the protobuf classes simply aren't present on
 * the host app's classpath, or if formatting throws for any reason -- this must never be able to
 * crash the call it's observing.
 */
internal object GrpcMessageRenderer {
    private const val MAX_RENDERED_MESSAGE_CHARS = 256 * 1024

    private val messageOrBuilderClass: Class<*>? = runCatching {
        Class.forName("com.google.protobuf.MessageOrBuilder")
    }.getOrNull()

    /** Renders a single sent/received gRPC message body. */
    fun render(message: Any?): String {
        if (message == null) return "null"
        val asProtobufJson = renderAsProtobufJson(message)
        val rendered = asProtobufJson ?: runCatching { message.toString() }
            .getOrElse { "<unrenderable ${message.javaClass.name}>" }
        return if (rendered.length > MAX_RENDERED_MESSAGE_CHARS) {
            rendered.substring(0, MAX_RENDERED_MESSAGE_CHARS) + "...<truncated>"
        } else {
            rendered
        }
    }

    /** Renders the ordered list of messages observed over the lifetime of one call (more than
     * one for client-streaming/server-streaming/bidi calls) as a JSON array literal. */
    fun renderMessageList(messages: List<String>): String {
        val builder = StringBuilder("[")
        messages.forEachIndexed { index, rendered ->
            if (index > 0) builder.append(',')
            builder.append(org.json.JSONObject.quote(rendered))
        }
        builder.append(']')
        return builder.toString()
    }

    /** Best-effort JSON rendering via `com.google.protobuf.util.JsonFormat`, reached only by
     * reflection (see this object's kdoc) -- returns null (falls back to `toString()`) if the
     * message isn't a protobuf `MessageOrBuilder`, if `protobuf-java-util` isn't on the host
     * app's runtime classpath, or if formatting fails for any other reason. */
    private fun renderAsProtobufJson(message: Any): String? {
        val moClass = messageOrBuilderClass ?: return null
        if (!moClass.isInstance(message)) return null
        return runCatching {
            val jsonFormatClass = Class.forName("com.google.protobuf.util.JsonFormat")
            val printer = jsonFormatClass.getMethod("printer").invoke(null)
            val printMethod = printer.javaClass.getMethod("print", moClass)
            printMethod.invoke(printer, message) as String
        }.getOrNull()
    }

    /** Flattens gRPC [Metadata] (both ASCII and `-bin` binary entries) into a plain
     * `Map<String, String>` -- binary values are base64-encoded so they survive the same JSON
     * transport [RequestrObserver.flush] already uses for every other header map. */
    fun metadataToMap(metadata: Metadata): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val keys = metadata.keys()
        for (key in keys) {
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                val binaryKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER)
                val bytes = runCatching { metadata.get(binaryKey) }.getOrNull()
                if (bytes != null) {
                    map[key] = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            } else {
                val asciiKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                runCatching { metadata.get(asciiKey) }.getOrNull()?.let { map[key] = it }
            }
        }
        return map
    }
}
