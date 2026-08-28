package com.requestr.sdk

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * No-op counterpart to the real `:sdk` module's `WebSocketInterceptor.kt` -- see there for the
 * full contract this mirrors. Opens a plain, uncaptured `client.newWebSocket(request, listener)`:
 * neither [listener] nor the returned [WebSocket] handle are wrapped, so nothing is reported for
 * open/sent/received/closing/closed/failure events.
 */
fun OkHttpClient.newRequestrWebSocket(
    request: Request,
    listener: WebSocketListener,
    observer: RequestrObserver,
): WebSocket = newWebSocket(request, listener)

/** No-op counterpart to the real `:sdk` module's `RequestrWebSocketListener` -- every callback
 * is forwarded straight to [delegate], unobserved. Not constructed by [newRequestrWebSocket]
 * above; kept only so the type itself remains source-compatible for any host-app code that
 * references it directly. */
class RequestrWebSocketListener(
    private val url: String,
    private val delegate: WebSocketListener,
    private val observer: RequestrObserver,
) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) = delegate.onOpen(webSocket, response)
    override fun onMessage(webSocket: WebSocket, text: String) = delegate.onMessage(webSocket, text)
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = delegate.onMessage(webSocket, bytes)
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = delegate.onClosing(webSocket, code, reason)
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = delegate.onClosed(webSocket, code, reason)
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = delegate.onFailure(webSocket, t, response)
}

/** No-op counterpart to the real `:sdk` module's `RequestrWebSocket` -- every method delegates
 * straight to [delegate], unobserved. Not constructed by [newRequestrWebSocket] above; kept only
 * so the type itself remains source-compatible for any host-app code that references it
 * directly. */
class RequestrWebSocket(
    private val url: String,
    private val delegate: WebSocket,
    private val observer: RequestrObserver,
) : WebSocket {
    override fun request(): Request = delegate.request()
    override fun queueSize(): Long = delegate.queueSize()
    override fun send(text: String): Boolean = delegate.send(text)
    override fun send(bytes: ByteString): Boolean = delegate.send(bytes)
    override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)
    override fun cancel() = delegate.cancel()

    override fun equals(other: Any?): Boolean = delegate == (other as? RequestrWebSocket)?.delegate || delegate == other
    override fun hashCode(): Int = delegate.hashCode()
}
