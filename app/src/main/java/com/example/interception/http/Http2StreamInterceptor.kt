package com.example.interception.http

import com.example.interception.http.Http2Messages.H2Message
import com.example.interception.http.Http2Parser.H2Headers

/**
 * S4 task 35: the seam through which [Http2ProxyConnection] hands complete request/response
 * messages of a terminated h2 stream to the rule engine and forwards what comes back.
 *
 * The decision to intercept is taken once per stream, at the request HEADERS ([plan]), from the
 * header block alone (the matcher never needs the body). A `null` plan means the stream is
 * relayed frame by frame exactly as before -- gRPC streaming, SSE and long downloads on streams
 * no mutating rule matches are never buffered. A non-null plan buffers the whole request
 * (HEADERS + DATA until END_STREAM + trailers) before [Plan.onRequest], and -- when
 * [Plan.interceptsResponse] -- the whole response before [Plan.onResponse]. Both callbacks may
 * block (breakpoints, chaos delays); the connection runs them off the client's reader thread so
 * flow-control and the other streams of the connection keep moving.
 */
internal interface Http2StreamInterceptor {

    /** Decide for one request stream. `null` = forward untouched. */
    fun plan(streamId: Int, headers: H2Headers): Plan?

    interface Plan {
        /** Whether the response must be buffered and offered to [onResponse]. */
        val interceptsResponse: Boolean

        /** The complete request. Blocks the connection's rule thread (not the client reader). */
        fun onRequest(request: H2Message): RequestVerdict

        /** The complete response of a forwarded request. Runs on the upstream reader thread. */
        fun onResponse(response: H2Message): H2Message

        /** The stream ended (reset, upstream gone, cap hit) before the exchange completed. */
        fun onAbandoned(reason: String) {}
    }

    sealed interface RequestVerdict {
        /** Send [request] (already re-encoded) to the origin. */
        class Forward(val request: H2Message) : RequestVerdict

        /** Answer [response] ourselves; the origin is never dialed for this stream. */
        class Respond(val response: H2Message) : RequestVerdict
    }
}
