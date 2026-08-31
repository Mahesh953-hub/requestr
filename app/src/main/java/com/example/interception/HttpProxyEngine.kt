package com.example.interception

import android.content.Context
import android.util.Log
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.CaptureRowMethod
import com.example.data.local.ConnectAttemptEntity
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.SettingsDataStore
import com.example.interception.HttpProxyShared.copyToStream
import com.example.interception.http.HttpBodyDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real HTTP forward proxy engine.
 *
 * Listens on a local port and transparently forwards HTTP requests to their
 * destination, logging every request/response pair to CaptureLogRepository.
 *
 * CONNECT tunnels are rule-driven via [ConnectControl] -- see [handleClient] -- rather than an
 * unconditional opaque relay. This engine cannot terminate TLS itself, so a FORCE_MITM rule is
 * honestly answered as unavailable rather than silently falling back to plaintext passthrough;
 * only [MitmProxyEngine] can act on FORCE_MITM.
 *
 * Request parsing / CONNECT tunneling / upstream forwarding is shared with
 * [com.example.interception.gateway.GatewayEngine] (T2) via [HttpProxyShared] rather than
 * duplicated — that engine reuses this same plumbing but binds to 0.0.0.0 and additionally
 * accepts connections from OTHER LAN devices.
 */
class HttpProxyEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private var appContext: Context? = null
    val port: Int get() = serverSocket?.localPort ?: 0

    private val sessionId = AtomicLong(System.currentTimeMillis())
    private var packetCount = AtomicLong(0)

    companion object {
        private const val TAG = "HttpProxy"
    }

    fun start(context: Context, port: Int = 0): Int {
        if (running.get()) return this.port
        appContext = context.applicationContext

        serverSocket = ServerSocket(port).also {
            Log.i(TAG, "Proxy listening on port ${it.localPort}")
        }
        running.set(true)

        InterceptionStateRepository.markActive(
            sessionId = sessionId.get(),
            tier = com.example.data.interception.ActiveTier.VPN,
            port = serverSocket!!.localPort,
            method = CaptureMethod.HTTP_PROXY,
        )

        acceptJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }

        return serverSocket!!.localPort
    }

    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "Proxy stopped")
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val requestLine = HttpProxyShared.readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val rawUrl = parts[1]
                val httpVersion = parts.getOrElse(2) { "HTTP/1.1" }

                // Parse headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = HttpProxyShared.readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                    }
                }

                val isConnect = method.equals("CONNECT", ignoreCase = true)

                // §3 (C2) -- read fresh per connection. Disabled (NONE) by default, and also if
                // appContext isn't set yet (shouldn't happen -- see the CONNECT branch's own
                // null-check below), so this never blocks a connection on a missing context.
                val networkCondition = appContext?.let {
                    SettingsDataStore.get(it).networkCondition.first()
                } ?: NetworkConditionInjector.NetworkCondition.NONE

                if (isConnect) {
                    val (host, connectPort) = HttpProxyShared.parseHostPort(rawUrl, 443)
                    val ctx = appContext
                    if (ctx == null) {
                        // Should never happen in practice (start() always sets appContext before
                        // the accept loop begins) -- fail closed rather than tunnel un-evaluated.
                        output.write("HTTP/1.1 500 Proxy not initialized\r\n\r\n".toByteArray())
                        return
                    }
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
                        sock, rawUrl, disposition, output,
                        networkCondition = networkCondition,
                    ) { sentBytes, recvBytes ->
                        runCatching {
                            runBlocking {
                                db.connectAttemptDao().insert(
                                    entity.copy(
                                        bytesToUpstream = sentBytes,
                                        bytesToClient = recvBytes,
                                        finishedAt = System.currentTimeMillis(),
                                    )
                                )
                            }
                        }.onFailure { Log.w(TAG, "Failed to persist CONNECT attempt: ${it.message}") }
                    }
                    if (disposition is ConnectControl.Disposition.Block || disposition is ConnectControl.Disposition.ForceMitm) {
                        // Blocked/unavailable dispositions never invoke onBytesTransferred (no
                        // relay ran) -- persist the record directly so it's not silently dropped.
                        runCatching {
                            db.connectAttemptDao().insert(entity.copy(finishedAt = System.currentTimeMillis()))
                        }.onFailure { Log.w(TAG, "Failed to persist CONNECT attempt: ${it.message}") }
                    }
                } else {
                    val bodyLen = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyBytes = if (bodyLen > 0) HttpProxyShared.readExactBytes(input, bodyLen) else ByteArray(0)

                    // Forward to upstream
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

                    // Item: the HTTP_PROXY tier's plain-HTTP relay forwarded and logged every
                    // request (see the Log.d above and the packetCount increment) but never
                    // persisted a CapturedTrafficEntity, so the Intercept traffic list stayed
                    // permanently empty for this tier ("No HTTP transaction decoded yet.") even
                    // though the relay itself worked -- GatewayEngine.persistTransaction is the
                    // proven pattern for this exact same HttpProxyShared.forwardRequest shape;
                    // mirrored here rather than duplicated ad hoc.
                    runCatching {
                        persistTransaction(rawUrl, method, status, latency, headers, respHeaders, bodyBytes, respBody)
                    }.onFailure { Log.w(TAG, "Failed to persist HTTP proxy transaction: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler error: ${e.message}")
        }
    }

    // internal (not private): mirrors GatewayEngine.persistTransaction's shape/reasoning exactly
    // (same HttpProxyShared.forwardRequest byte-array contract), so this on-device HTTP_PROXY
    // tier and the T2 LAN-relay tier classify/store binary bodies identically.
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
        val (host, path) = runCatching {
            val u = if (rawUrl.startsWith("http")) java.net.URI(rawUrl) else java.net.URI("http://$rawUrl")
            (u.host ?: rawUrl) to (u.path ?: "")
        }.getOrDefault(rawUrl to "")

        val reqStorage = HttpBodyDecoder.forStorage(reqBodyBytes)
        val respStorage = HttpBodyDecoder.forStorage(respBodyBytes)
        val reqBodyText = if (reqStorage.isBinary) null else String(reqBodyBytes, Charsets.UTF_8).ifEmpty { null }
        val respBodyText = if (respStorage.isBinary) null else String(respBodyBytes, Charsets.UTF_8).ifEmpty { null }

        val entity = CapturedTrafficEntity(
            sessionId = sessionId.get(),
            method = method,
            url = if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl",
            host = host,
            path = path,
            statusCode = statusCode,
            protocol = "http-proxy",
            requestHeaders = reqHeaders,
            responseHeaders = respHeaders,
            requestBody = reqBodyText,
            responseBody = respBodyText,
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
            captureMethod = CaptureRowMethod.HTTP_PROXY,
        )
        RequestrDatabase.get(ctx).capturedTrafficDao().insert(entity)
    }
}
