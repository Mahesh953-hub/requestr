package com.requestr.sdk

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Phase 6: WebSocket capture decorator for the SDK module.
 *
 * Unlike every other stack instrumented here ([OkHttpInterceptor], [HttpUrlConnectionWrapper],
 * [CronetInterceptor], [KtorInterceptor]), a WebSocket connection has no natural single
 * "request"/"response" pair to report once at the end -- it's long-lived and can carry an
 * unbounded number of messages in both directions over its lifetime. So rather than
 * accumulating everything until close (unbounded memory for a socket that might stay open for
 * the life of the process), each lifecycle event is reported to [RequestrObserver] as its own
 * event, the moment it happens: [WebSocketListener.onOpen]/[onMessage]/[onClosing]/[onClosed]/
 * [onFailure] on the receive side, plus [WebSocket.send] on the send side (OkHttp's
 * [WebSocketListener] is never told about messages the app itself sends -- only ones it
 * receives -- so capturing outgoing messages requires decorating the [WebSocket] handle itself,
 * not just the listener).
 *
 * `okhttp3.WebSocketListener`/`okhttp3.WebSocket` are already part of this module's regular
 * (non-`compileOnly`) `okhttp` dependency -- unlike gRPC/Cronet/Ktor, there's no extra opt-in
 * dependency needed here.
 *
 * Usage, in place of a host app's normal `client.newWebSocket(request, listener)`:
 * ```kotlin
 * val socket = client.newRequestrWebSocket(request, myListener, RequestrObserver)
 * socket.send("hello")   // captured as a "sent text" event
 * ```
 */
fun OkHttpClient.newRequestrWebSocket(
    request: Request,
    listener: WebSocketListener,
    observer: RequestrObserver,
): WebSocket {
    val url = request.url.toString()
    val capturingListener = RequestrWebSocketListener(url, listener, observer)
    val realSocket = newWebSocket(request, capturingListener)
    return RequestrWebSocket(url, realSocket, observer)
}

/**
 * Decorates a caller-supplied [WebSocketListener], reporting every received event to
 * [observer] and then forwarding unchanged to [delegate] so the host app's own callback logic
 * still runs exactly as if this decorator weren't there.
 */
class RequestrWebSocketListener(
    private val url: String,
    private val delegate: WebSocketListener,
    private val observer: RequestrObserver,
) : WebSocketListener() {
    @Volatile private var openedAt: Long = System.currentTimeMillis()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        openedAt = System.currentTimeMillis()
        report(
            event = "open",
            statusCode = response.code,
            headers = headerMap(response.headers),
        )
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        report(event = "message-received-text", responseBody = text.toByteArray(Charsets.UTF_8))
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        report(event = "message-received-binary", responseBody = bytes.toByteArray())
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        report(event = "closing", statusCode = code, extraHeaders = mapOf("ws-reason" to reason))
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        report(event = "closed", statusCode = code, extraHeaders = mapOf("ws-reason" to reason))
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        report(
            event = "failure",
            statusCode = response?.code,
            headers = response?.let { headerMap(it.headers) } ?: emptyMap(),
            extraHeaders = mapOf("ws-error" to (t.message ?: t.javaClass.name)),
        )
        delegate.onFailure(webSocket, t, response)
    }

    private fun report(
        event: String,
        statusCode: Int? = null,
        requestBody: ByteArray? = null,
        responseBody: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            observer.observeDetailed(
                url = url,
                method = "WEBSOCKET",
                headers = headers + extraHeaders + mapOf("ws-event" to event),
                statusCode = statusCode,
                requestBody = requestBody,
                responseBody = responseBody,
                durationMs = System.currentTimeMillis() - openedAt,
                httpStack = "OkHttp-WebSocket",
                isDecrypted = true,
            )
        }.onFailure { Log.w(TAG, "failed to report WebSocket event '$event' for $url: ${it.message}") }
    }

    private fun headerMap(headers: okhttp3.Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (name in headers.names()) headers[name]?.let { map[name] = it }
        return map
    }

    private companion object {
        const val TAG = "RequestrWebSocket"
    }
}

/**
 * Decorates a real [WebSocket] handle so outgoing `send`/`close` calls the host app makes are
 * captured too -- [RequestrWebSocketListener] alone only sees the receive side. Every method
 * delegates to [delegate] after capturing; [equals]/[hashCode] delegate as well so this can be
 * used as a drop-in [WebSocket] (e.g. stored in a `Set<WebSocket>` alongside undecorated ones).
 */
class RequestrWebSocket(
    private val url: String,
    private val delegate: WebSocket,
    private val observer: RequestrObserver,
) : WebSocket {
    override fun request(): Request = delegate.request()

    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean {
        report(event = "message-sent-text", requestBody = text.toByteArray(Charsets.UTF_8))
        return delegate.send(text)
    }

    override fun send(bytes: ByteString): Boolean {
        report(event = "message-sent-binary", requestBody = bytes.toByteArray())
        return delegate.send(bytes)
    }

    override fun close(code: Int, reason: String?): Boolean {
        report(event = "close-requested", statusCode = code, extraHeaders = reason?.let { mapOf("ws-reason" to it) } ?: emptyMap())
        return delegate.close(code, reason)
    }

    override fun cancel() {
        report(event = "cancel-requested")
        delegate.cancel()
    }

    private fun report(
        event: String,
        statusCode: Int? = null,
        requestBody: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            observer.observeDetailed(
                url = url,
                method = "WEBSOCKET",
                headers = extraHeaders + mapOf("ws-event" to event),
                statusCode = statusCode,
                requestBody = requestBody,
                responseBody = null,
                durationMs = 0,
                httpStack = "OkHttp-WebSocket",
                isDecrypted = true,
            )
        }.onFailure { Log.w(TAG, "failed to report WebSocket event '$event' for $url: ${it.message}") }
    }

    override fun equals(other: Any?): Boolean = delegate == (other as? RequestrWebSocket)?.delegate || delegate == other

    override fun hashCode(): Int = delegate.hashCode()

    private companion object {
        const val TAG = "RequestrWebSocket"
    }
}
