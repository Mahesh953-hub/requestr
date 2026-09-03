package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import com.example.data.local.CaptureRowMethod
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import com.example.interception.gateway.GatewaySessionHandler
import com.example.interception.gateway.LocalGateway
import com.example.interception.gateway.local.EntryKind
import com.example.interception.gateway.local.GatewaySession
import com.example.interception.gateway.local.HttpPrelude
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP_PROXY-tier forward proxy: forwards plaintext HTTP requests to their destination and
 * relays `CONNECT` tunnels opaquely, logging every request/response pair.
 *
 * Since S1 task 6 this engine no longer owns a listener or parses request lines: it is a
 * [GatewaySessionHandler] behind a [LocalGateway], which classifies each accepted connection
 * (SOCKS5/4, HTTP `CONNECT`, absolute-form HTTP, transparent) and hands over a resolved
 * [GatewaySession]. [start] keeps its old shape (`start(context, port): Int`) by constructing a
 * gateway routed to `this`, so existing callers and tests are unaffected.
 *
 * CONNECT tunnels are rule-driven via [ConnectControl]. This engine cannot terminate TLS itself,
 * so a FORCE_MITM rule is honestly answered as unavailable rather than silently falling back to
 * plaintext passthrough; only [MitmProxyEngine] can act on FORCE_MITM.
 *
 * Upstream forwarding is shared with [com.example.interception.gateway.GatewayEngine] (T2) via
 * [HttpProxyShared] rather than duplicated -- that engine binds to 0.0.0.0 and additionally
 * accepts connections from OTHER LAN devices.
 */
class HttpProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : GatewaySessionHandler {
    private var gateway: LocalGateway? = null
    private val running = AtomicBoolean(false)
    private val boundAsDelegate = AtomicBoolean(false)
    private var appContext: Context? = null
    val port: Int get() = gateway?.port ?: 0

    private val sessionId = AtomicLong(System.currentTimeMillis())
    private var packetCount = AtomicLong(0)

    companion object {
        private const val TAG = "HttpProxy"
        private const val CLIENT_SO_TIMEOUT_MS = 30_000
    }

    fun start(context: Context, port: Int = 0): Int {
        if (running.get()) return this.port
        appContext = context.applicationContext

        val gw = LocalGateway(handler = this, scope = scope, logTag = TAG)
        val boundPort = gw.start(port)
        gateway = gw
        running.set(true)
        Log.i(TAG, "Proxy listening on port $boundPort")

        InterceptionStateRepository.markActive(
            sessionId = sessionId.get(),
            // Audit fix: this used to hardcode ActiveTier.VPN even though
            // PreflightController.run() resolves ActiveTier.PROXY for HTTP_PROXY -- see
            // CaptureMethod.engineTier()'s KDoc. Derived, not restated, so it cannot silently
            // diverge from what PreflightController resolved for this same method again.
            tier = CaptureMethod.HTTP_PROXY.engineTier(),
            port = boundPort,
            method = CaptureMethod.HTTP_PROXY,
            lifecycleOwner = CaptureMethod.HTTP_PROXY.engineLifecycleOwner(),
        )
        return boundPort
    }

    /**
     * Makes this engine usable as a plaintext handler without owning a listener -- used by
     * [MitmProxyEngine], which routes its own gateway's plaintext sessions here.
     */
    internal fun bind(context: Context) {
        appContext = context.applicationContext
        boundAsDelegate.set(true)
    }

    fun stop() {
        running.set(false)
        gateway?.stop()
        gateway = null
        Log.i(TAG, "Proxy stopped")
    }

    // ── GatewaySessionHandler ──────────────────────────────────────────────

    /** Opaque, rule-evaluated tunnel (HTTP CONNECT / SOCKS / transparent TLS). */
    override suspend fun handleTunnel(session: GatewaySession) {
        try {
            session.socket.use { sock ->
                sock.soTimeout = CLIENT_SO_TIMEOUT_MS
                val output = sock.getOutputStream()
                val host = session.destination.host
                val connectPort = session.destination.port
                val ctx = appContext
                if (ctx == null) {
                    // Should never happen in practice (start() always sets appContext before
                    // the accept loop begins) -- fail closed rather than tunnel un-evaluated.
                    session.replyUnreachable()
                    return
                }
                // §3 (C2) -- read fresh per connection. Disabled (NONE) by default.
                val networkCondition = SettingsDataStore.get(ctx).networkCondition.first()
                val db = RequestrDatabase.get(ctx)
                val disposition = ConnectControl.evaluate(db, host, connectPort)
                val dispositionName = when (disposition) {
                    is ConnectControl.Disposition.AllowOpaque -> "ALLOWED_OPAQUE"
                    is ConnectControl.Disposition.Block -> "BLOCKED"
                    is ConnectControl.Disposition.Redirect -> "REDIRECTED"
                    is ConnectControl.Disposition.ForceMitm -> "BLOCKED"
                }
                val entity = ConnectAttemptEntity(
                    sessionId = sessionId.get(),
                    targetHost = host,
                    targetPort = connectPort,
                    disposition = dispositionName,
                    redirectTarget = (disposition as? ConnectControl.Disposition.Redirect)?.target,
                    failureReason = if (disposition is ConnectControl.Disposition.ForceMitm) {
                        "MITM not available in HTTP_PROXY tier"
                    } else null,
                )
                HttpProxyShared.handleConnectAttempt(
                    sock, session.destination.toString(), disposition, output,
                    networkCondition = networkCondition,
                    onBytesTransferred = { sentBytes, recvBytes ->
                        runCatching {
                            db.connectAttemptDao().insert(
                                entity.copy(
                                    bytesToUpstream = sentBytes,
                                    bytesToClient = recvBytes,
                                    finishedAt = System.currentTimeMillis(),
                                )
                            )
                        }.onFailure { Log.w(TAG, "Failed to persist CONNECT attempt: ${it.message}") }
                    },
                    replies = HttpProxyShared.ConnectReplies.forSession(session),
                )
                if (disposition is ConnectControl.Disposition.Block || disposition is ConnectControl.Disposition.ForceMitm) {
                    // Blocked/unavailable dispositions never invoke onBytesTransferred (no
                    // relay ran) -- persist the record directly so it's not silently dropped.
                    runCatching {
                        db.connectAttemptDao().insert(entity.copy(finishedAt = System.currentTimeMillis()))
                    }.onFailure { Log.w(TAG, "Failed to persist CONNECT attempt: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            if (running.get() || boundAsDelegate.get()) Log.w(TAG, "Client handler error: ${e.message}")
        }
    }

    /**
     * Plaintext HTTP/1.x forward proxy. Serves requests until the client closes, sends
     * `Connection: close`, or speaks HTTP/1.0 without keep-alive -- the old engine served exactly
     * one request per connection, which broke every keep-alive client on the second request.
     *
     * Absolute-form requests ([EntryKind.HTTP_ABSOLUTE]) forward to the URL as written.
     * Origin-form requests (transparent capture, or plaintext inside a CONNECT tunnel) are turned
     * back into absolute URLs from the session's destination.
     */
    override suspend fun handlePlaintext(session: GatewaySession) {
        try {
            session.socket.use { sock ->
                sock.soTimeout = CLIENT_SO_TIMEOUT_MS
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val networkCondition = appContext?.let {
                    SettingsDataStore.get(it).networkCondition.first()
                } ?: NetworkConditionInjector.NetworkCondition.NONE

                while (true) {
                    val head = when (val r = HttpPrelude.read(input)) {
                        is HttpPrelude.Result.Parsed -> r.head
                        is HttpPrelude.Result.Malformed -> {
                            output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); output.flush()
                            return
                        }
                        HttpPrelude.Result.Eof -> return
                    }
                    val method = head.method
                    val httpVersion = head.version
                    val headers = head.headers
                    val rawUrl = if (head.target.startsWith("http://", true) || head.target.startsWith("https://", true)) {
                        head.target
                    } else {
                        val scheme = if (session.destination.port == 443) "https" else "http"
                        HttpPrelude.absoluteUrlFor(session.destination, head.target, scheme)
                    }

                    val bodyLen = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                    val bodyBytes = if (bodyLen > 0) HttpProxyShared.readExactBytes(input, bodyLen) else ByteArray(0)

                    val t0 = System.currentTimeMillis()
                    val (status, respHeaders, respBody) = HttpProxyShared.forwardRequest(
                        rawUrl, method, headers, bodyBytes,
                        networkCondition = networkCondition,
                    )
                    val latency = System.currentTimeMillis() - t0

                    HttpProxyShared.writeResponse(output, httpVersion, status, respHeaders, respBody)

                    packetCount.incrementAndGet()
                    InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
                    Log.d(TAG, "$method $rawUrl → $status (${latency}ms)")

                    // The HTTP_PROXY tier's plain-HTTP relay must persist a CapturedTrafficEntity
                    // or the Intercept traffic list stays permanently empty for this tier.
                    runCatching {
                        persistTransaction(rawUrl, method, status, latency, headers, respHeaders, bodyBytes, respBody)
                    }.onFailure { Log.w(TAG, "Failed to persist HTTP proxy transaction: ${it.message}") }

                    if (!keepAlive(httpVersion, headers)) return
                }
            }
        } catch (e: Exception) {
            if (running.get() || boundAsDelegate.get()) Log.w(TAG, "Client handler error: ${e.message}")
        }
    }

    private fun keepAlive(version: String, headers: Map<String, String>): Boolean {
        val connection = headers.entries.firstOrNull {
            it.key.equals("Connection", true) || it.key.equals("Proxy-Connection", true)
        }?.value?.lowercase()
        return when {
            connection?.contains("close") == true -> false
            version == "HTTP/1.0" -> connection?.contains("keep-alive") == true
            else -> true
        }
    }

    // internal (not private): gives HttpProxyEngine's tests a direct, no-socket-plumbing-required
    // entry point. Entity construction is shared with GatewayEngine.persistTransaction via
    // HttpProxyShared.capturedEntityFor so this on-device HTTP_PROXY tier and the T2 LAN-relay
    // tier cannot drift apart on how they classify/store binary bodies.
    internal suspend fun persistTransaction(
        rawUrl: String,
        method: String,
        statusCode: Int?,
        latencyMs: Long,
        reqHeaders: Map<String, String>,
        respHeaders: Map<String, String>,
        reqBodyBytes: ByteArray,
        respBodyBytes: ByteArray,
    ) {
        val ctx = appContext ?: return
        val entity = HttpProxyShared.capturedEntityFor(
            sessionId = sessionId.get(),
            rawUrl = rawUrl,
            method = method,
            statusCode = statusCode,
            latencyMs = latencyMs,
            reqHeaders = reqHeaders,
            respHeaders = respHeaders,
            reqBodyBytes = reqBodyBytes,
            respBodyBytes = respBodyBytes,
            protocol = "http-proxy",
            captureMethod = CaptureRowMethod.HTTP_PROXY,
        )
        RequestrDatabase.get(ctx).capturedTrafficDao().insert(entity)
    }
}
