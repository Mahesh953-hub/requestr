package com.example.interception

import com.example.interception.http.Http2Messages
import com.example.interception.http.Http2Messages.H2Message
import com.example.interception.http.Http2Parser.H2Headers
import com.example.interception.http.Http2StreamInterceptor
import com.example.interception.http.Http2StreamInterceptor.RequestVerdict
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.FlowAuditTrail
import com.example.interception.rules.RuleDecision
import com.example.interception.rules.stages.BreakpointStage
import com.example.interception.rules.stages.MirrorStage
import com.example.data.models.HttpMethod

/**
 * S4 task 35: rules on translated HTTP/2 events.
 *
 * One instance per terminated h2 connection (the [MitmProxyEngine] h2 branch). It is the
 * [Http2StreamInterceptor] of that connection's `Http2ProxyConnection`:
 * - [plan] evaluates the snapshot + safety gate once per stream from the request HEADERS
 *   ([MitmProxyEngine.evaluateRequestDecision] -- the same call the HTTP/1.x relay makes) and
 *   claims the stream only when a stage will actually act on it (gateway block, mirror, rewrite,
 *   breakpoint, script, mock, body crypto, chaos). Observe-only / throttle-only / refused flows
 *   stay frame-relayed but still get their provenance;
 * - [StreamPlan.onRequest]/[StreamPlan.onResponse] turn the buffered message into the
 *   [ParsedHttpMessage] model ([Http2Messages]), run the shared stage sequence
 *   ([MitmProxyEngine.runRequestStages]/[MitmProxyEngine.runResponseStages]) and re-encode the
 *   result -- `content-length` and DATA framing recomputed, `:authority` following a mirror /
 *   redirect Host header;
 * - the per-stream [Record] carries the audit trail, the final (stamped) request and the
 *   "do not persist" bit to the connection's `Http2Session.onTransaction` callback, which the
 *   engine consults through [take] so `applied_rules` rows land on the h2 row exactly as on h1.
 */
internal class Http2RuleInterceptor(
    private val engine: MitmProxyEngine,
    private val host: String,
    private val port: Int,
    private val mirror: MirrorStage.Plan?,
    private val clientUid: Int?,
    private val connectionKey: String,
) : Http2StreamInterceptor {

    /** Provenance of one stream, handed to the persistence callback when the exchange completes. */
    class Record(val trail: FlowAuditTrail, val decision: RuleDecision) {
        /** The request as the rules left it (persist-only stamps included); null = as the client sent it. */
        @Volatile var request: ParsedHttpMessage? = null
        /** The header block actually forwarded upstream (null = the client's own). */
        @Volatile var forwardedHeaders: H2Headers? = null
        /** Legacy BLOCK parity: answered but never persisted. */
        @Volatile var suppressed: Boolean = false
    }

    private val records = object : LinkedHashMap<Int, Record>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Record>): Boolean = size > MAX_RECORDS
    }
    private val lock = Any()

    /** Removes and returns the provenance of [streamId] (null when the stream matched nothing). */
    fun take(streamId: Int?): Record? {
        if (streamId == null) return null
        return synchronized(lock) { records.remove(streamId) }
    }

    override fun plan(streamId: Int, headers: H2Headers): Http2StreamInterceptor.Plan? {
        // Extended CONNECT (WebSocket over h2, RFC 8441) is a tunnel, not a message exchange.
        if (headers.pseudoHeaders[":method"] == "CONNECT") return null
        val trail = FlowAuditTrail()
        val probe = Http2Messages.request(headers, EMPTY)
        val decision = engine.evaluateRequestDecision(probe, host, port, trail, clientUid)
        if (decision.isEmpty && mirror == null) return null
        val record = Record(trail, decision)
        synchronized(lock) { records[streamId] = record }
        if (!needsInterception(decision)) return null // provenance only; frames keep flowing
        return StreamPlan(streamId, headers, record)
    }

    /** Stages that alter what the client or origin sees; throttle is connection-level and gateway pass-through modes do not block. */
    private fun needsInterception(d: RuleDecision): Boolean =
        d.gateway.blocks || mirror != null || !d.rewrite.isEmpty || d.breakpoint != null ||
            d.scripts.isNotEmpty() || d.mock != null || d.bodyCrypto != null || d.chaos != null

    private inner class StreamPlan(
        private val streamId: Int,
        private val original: H2Headers,
        private val record: Record,
    ) : Http2StreamInterceptor.Plan {
        private val flow = MitmProxyEngine.FlowContext()
        private val method = original.pseudoHeaders[":method"]
        @Volatile private var sentAt = 0L

        override val interceptsResponse: Boolean
            get() {
                val d = record.decision
                return mirror != null || d.rewrite.hasResponseWork || d.scripts.isNotEmpty() || d.bodyCrypto != null ||
                    d.chaos != null || (d.breakpoint?.let { BreakpointStage.applies(it.config, FlowAuditTrail.Phase.RESPONSE) } == true)
            }

        override fun onRequest(request: H2Message): RequestVerdict {
            val trailers = request.trailers?.let { H2Headers(streamId, it, true) }
            val message = Http2Messages.request(H2Headers(streamId, request.headers, request.endsOnHeaders), request.body, trailers)
            record.request = message
            return when (val outcome = engine.runRequestStages(message, record.decision, record.trail, mirror, flow, connectionKey, host, port)) {
                is MitmProxyEngine.RequestStages.Respond -> {
                    record.suppressed = !outcome.persist
                    RequestVerdict.Respond(Http2Messages.encodeResponse(outcome.response, bodyless = method == "HEAD"))
                }
                is MitmProxyEngine.RequestStages.Forward -> {
                    val encoded = if (outcome.modified) Http2Messages.encodeRequest(message, original, request.trailers) else request
                    if (outcome.modified) record.forwardedHeaders = H2Headers(streamId, encoded.headers, encoded.endsOnHeaders)
                    // Persist-only badges go on the model AFTER encoding, never to the origin.
                    outcome.stampPersistOnly(message)
                    sentAt = System.currentTimeMillis()
                    RequestVerdict.Forward(encoded)
                }
            }
        }

        override fun onResponse(response: H2Message): H2Message {
            val request = record.request ?: return response
            val originTrailers = response.trailers?.let { H2Headers(streamId, it, true) }
            val message = Http2Messages.response(H2Headers(streamId, response.headers, response.endsOnHeaders), response.body, originTrailers)
            val responseTimeMs = if (sentAt > 0L) System.currentTimeMillis() - sentAt else 0L
            val stages = engine.runResponseStages(message, request, record.decision, record.trail, mirror, flow, responseTimeMs, connectionKey, host)
            // Served here, on the upstream reader thread of this connection -- the h1 counterpart
            // sleeps its server→client relay thread the same way, outside any write lock.
            engine.serveChaosDelay(stages.chaosDelayMs)
            if (!stages.modified) return response
            val bodyless = method == "HEAD" || request.method == HttpMethod.HEAD
            // A synthetic replacement (abort, crypto failure) has no business carrying the
            // origin's trailers; an edited origin response keeps them.
            val trailers = if (stages.responseToEmit === message) response.trailers else null
            return Http2Messages.encodeResponse(stages.responseToEmit, bodyless = bodyless, trailers = trailers)
        }

        override fun onAbandoned(reason: String) {
            synchronized(lock) { records.remove(streamId) }
        }
    }

    companion object {
        /** Same bound as `Http2Session.DEFAULT_MAX_STREAMS`: a long-lived connection never leaks records. */
        const val MAX_RECORDS = 500
        private val EMPTY = ByteArray(0)
    }
}
