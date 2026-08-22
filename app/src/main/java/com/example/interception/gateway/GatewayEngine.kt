package com.example.interception.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CaptureTier
import com.example.data.local.CapturedTrafficEntity
import com.example.data.local.RequestrDatabase
import com.example.interception.HttpProxyShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T2 — Gateway mode: lets requestr act as an HTTP proxy for OTHER devices on the same LAN/Wi-Fi
 * hotspot, not just for traffic originating on this device.
 *
 * All existing tiers (VPN_TUN, MITM_PROXY, HTTP_PROXY, ROOT_IPTABLES, SHIZUKU_TUN, SDK) capture
 * traffic that starts on THIS device. GatewayEngine is the opposite: a second device (phone,
 * laptop) configures GatewayEngine's LAN IP:port as its HTTP proxy, and every request that
 * device makes is forwarded upstream — and logged into the same Room-backed traffic log the
 * Intercept screen already renders — exactly like [com.example.interception.HttpProxyEngine]
 * does for local traffic. Request parsing / CONNECT tunneling / upstream forwarding is shared
 * with that engine via [HttpProxyShared] rather than duplicated.
 *
 * Architecture, deliberately: this is NOT threaded through
 * `com.example.data.interception.PreflightController`'s `CaptureMethod`/`ActiveTier` state
 * machine — that machine only resolves which single tier captures THIS device's own traffic
 * (Root > Shizuku > VPN/MITM/HTTP proxy), and gates on CA-install / VPN-consent that are
 * irrelevant here. Gateway mode runs orthogonally, in parallel with whatever local tier (if any)
 * is active, so it is a small independent singleton — the same pattern
 * [com.example.api.LocalApiServer] uses for its own manually-toggled background server.
 *
 * Persistence follows [com.example.api.LocalApiServer]'s `/api/observe` → `sdkSessionId()`
 * pattern (direct `dao.insert()` from the engine's own coroutine, backed by one lazily-created,
 * always-reused [CaptureTier.GATEWAY] session row) rather than
 * [com.example.interception.MitmProxyEngine]'s callback-injection pattern, since it needs no
 * `PreflightController` wiring at all.
 *
 * Scope limits (documented rather than silently failing):
 *  - Only a manually-configured HTTP proxy is supported — there is no DHCP/NAT/transparent
 *    gateway. The remote device must explicitly set its HTTP proxy to this device's LAN IP:port.
 *  - HTTPS (`CONNECT`) traffic from remote clients is rule-driven via [com.example.interception.ConnectControl],
 *    same as every other CONNECT call site — but a `FORCE_MITM` rule always falls back to a Block
 *    for gateway clients specifically, because MITM would require the remote device to install
 *    requestr's CA the same way an on-device MITM flow does, which this engine has no channel to
 *    do. That is a deliberate, honest scope limit, not a silent gap.
 *  - The enabled/port *preference* is persisted (`SettingsDataStore.gatewayModeEnabled/gatewayPort`)
 *    but the engine does not auto-start on app relaunch — same behavior as other manually
 *    toggled settings (e.g. `LOGGING_ENABLED`) which don't auto-apply either.
 */
object GatewayEngine {
    private const val TAG = "GatewayEngine"

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    // Compose-observable, mirroring LocalApiServer.isRunningFlow: a raw @Volatile var never
    // triggers recomposition because Compose's snapshot system only tracks State/StateFlow reads.
    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    val isRunning: Boolean get() = _isRunning.value

    @Volatile var port: Int = 0
        private set

    // Live count of distinct remote (non-loopback) client IPs observed since the server started —
    // what the Settings screen's "connected/observed remote clients" readout binds to.
    private val _remoteClients = MutableStateFlow<Set<String>>(emptySet())
    val remoteClientsFlow: StateFlow<Set<String>> = _remoteClients.asStateFlow()

    private var gatewaySessionId: Long = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts the proxy bound to all interfaces (0.0.0.0), not just localhost, so LAN clients can
     * actually reach it — this is the entire point of Gateway mode versus [com.example.interception.HttpProxyEngine],
     * which is only ever configured as the local device's own proxy.
     */
    fun start(context: Context, requestedPort: Int): Int {
        appContext = context.applicationContext
        if (running.get()) return port

        running.set(true)
        _remoteClients.value = emptySet()

        serverSocket = ServerSocket(requestedPort, 50, InetAddress.getByName("0.0.0.0")).also {
            port = it.localPort
            Log.i(TAG, "Gateway proxy listening on 0.0.0.0:${it.localPort}")
        }
        _isRunning.value = true

        acceptJob = scope.launch {
            val socket = serverSocket
            while (isActive && running.get() && socket != null) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }

        return port
    }

    fun stop() {
        running.set(false)
        _isRunning.value = false
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "Gateway proxy stopped")
    }

    private suspend fun handleClient(client: Socket) {
        val remoteAddr = client.inetAddress
        val isRemote = !remoteAddr.isLoopbackAddress
        val clientIp = remoteAddr?.hostAddress ?: "unknown"

        if (isRemote) {
            _remoteClients.value = _remoteClients.value + clientIp
        }

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

                if (isConnect) {
                    val ctx = appContext
                    if (ctx == null) {
                        output.write("HTTP/1.1 500 Proxy not initialized\r\n\r\n".toByteArray())
                        return
                    }
                    val (host, connectPort) = HttpProxyShared.parseHostPort(rawUrl, 443)
                    val db = RequestrDatabase.get(ctx)
                    val rawDisposition = com.example.interception.ConnectControl.evaluate(
                        db, host, connectPort, sourceIp = if (isRemote) clientIp else null,
                    )
                    // FORCE_MITM is not supported for gateway clients — no channel to install
                    // requestr's CA on the remote device — so it always falls back to Block.
                    val disposition = if (rawDisposition is com.example.interception.ConnectControl.Disposition.ForceMitm) {
                        com.example.interception.ConnectControl.Disposition.Block("MITM not available for gateway clients")
                    } else rawDisposition
                    val dispositionName = when (disposition) {
                        is com.example.interception.ConnectControl.Disposition.AllowOpaque -> "ALLOWED_OPAQUE"
                        is com.example.interception.ConnectControl.Disposition.Block -> "BLOCKED"
                        is com.example.interception.ConnectControl.Disposition.Redirect -> "REDIRECTED"
                        is com.example.interception.ConnectControl.Disposition.ForceMitm -> "BLOCKED" // unreachable, see above
                    }
                    val entity = com.example.data.local.ConnectAttemptEntity(
                        sessionId = gatewaySessionId(ctx),
                        targetHost = host,
                        targetPort = connectPort,
                        sourceIp = if (isRemote) clientIp else null,
                        disposition = dispositionName,
                        redirectTarget = (disposition as? com.example.interception.ConnectControl.Disposition.Redirect)?.target,
                        failureReason = (disposition as? com.example.interception.ConnectControl.Disposition.Block)?.reason,
                    )
                    HttpProxyShared.handleConnectAttempt(sock, rawUrl, disposition, output) { sentBytes, recvBytes ->
                        runCatching {
                            kotlinx.coroutines.runBlocking {
                                RequestrDatabase.get(ctx).connectAttemptDao().insert(
                                    entity.copy(
                                        bytesToUpstream = sentBytes,
                                        bytesToClient = recvBytes,
                                        finishedAt = System.currentTimeMillis(),
                                    )
                                )
                            }
                        }.onFailure { Log.w(TAG, "Failed to persist gateway CONNECT attempt: ${it.message}") }
                    }
                    if (disposition is com.example.interception.ConnectControl.Disposition.Block) {
                        // Block never invokes onBytesTransferred (no relay ran) — persist directly.
                        runCatching {
                            db.connectAttemptDao().insert(entity.copy(finishedAt = System.currentTimeMillis()))
                        }.onFailure { Log.w(TAG, "Failed to persist gateway CONNECT attempt: ${it.message}") }
                    }
                } else {
                    val bodyLen = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyBytes = if (bodyLen > 0) input.readNBytes(bodyLen) else ByteArray(0)
                    val bodyStr = String(bodyBytes, Charsets.UTF_8)

                    val t0 = System.currentTimeMillis()
                    val (status, respHeaders, respBody) = HttpProxyShared.forwardRequest(rawUrl, method, headers, bodyStr)
                    val latency = System.currentTimeMillis() - t0

                    val responseLine = "$httpVersion $status OK\r\n"
                    output.write(responseLine.toByteArray())
                    respHeaders.forEach { (k, v) -> output.write("$k: $v\r\n".toByteArray()) }
                    output.write("\r\n".toByteArray())
                    output.write(respBody.toByteArray())
                    output.flush()

                    Log.d(TAG, "[gateway${if (isRemote) " remote=$clientIp" else ""}] $method $rawUrl → $status (${latency}ms)")

                    persistTransaction(
                        method = method,
                        rawUrl = rawUrl,
                        statusCode = status,
                        latencyMs = latency,
                        reqHeaders = headers,
                        respHeaders = respHeaders,
                        reqBody = bodyStr,
                        respBody = respBody,
                        sourceIp = if (isRemote) clientIp else null,
                        wasDecrypted = true,
                        isUndecoded = false,
                    )
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler error: ${e.message}")
        }
    }

    private suspend fun persistTransaction(
        method: String,
        rawUrl: String,
        statusCode: Int?,
        latencyMs: Long,
        reqHeaders: Map<String, String>,
        respHeaders: Map<String, String>,
        reqBody: String,
        respBody: String,
        sourceIp: String?,
        wasDecrypted: Boolean,
        isUndecoded: Boolean,
    ) {
        val ctx = appContext ?: return
        val (host, path) = runCatching {
            val u = if (rawUrl.startsWith("http")) java.net.URI(rawUrl) else java.net.URI("http://$rawUrl")
            (u.host ?: rawUrl) to (u.path ?: "")
        }.getOrDefault(rawUrl to "")

        val entity = CapturedTrafficEntity(
            sessionId = gatewaySessionId(ctx),
            method = method,
            url = if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl",
            host = host,
            path = path,
            statusCode = statusCode,
            protocol = "gateway",
            requestHeaders = reqHeaders,
            responseHeaders = respHeaders,
            requestBody = reqBody.ifEmpty { null },
            responseBody = respBody.ifEmpty { null },
            requestSizeBytes = reqBody.length.toLong(),
            responseSizeBytes = respBody.length.toLong(),
            latencyMs = latencyMs,
            wasDecrypted = wasDecrypted,
            isUndecoded = isUndecoded,
            sourceIp = sourceIp,
        )
        runCatching { RequestrDatabase.get(ctx).capturedTrafficDao().insert(entity) }
            .onFailure { Log.w(TAG, "Failed to persist gateway transaction: ${it.message}") }
    }

    /**
     * Lazily creates (once) and reuses a single always-open [CaptureTier.GATEWAY] session row,
     * mirroring [com.example.api.LocalApiServer.sdkSessionId] — so gateway traffic groups under
     * its own session independent of whether an on-device VPN/root/Shizuku capture is running.
     */
    private suspend fun gatewaySessionId(context: Context): Long {
        if (gatewaySessionId != 0L) return gatewaySessionId
        val sessionDao = RequestrDatabase.get(context).captureSessionDao()
        val existing = sessionDao.observeAll().first().firstOrNull { it.tier == CaptureTier.GATEWAY && it.endedAt == null }
        gatewaySessionId = existing?.id ?: sessionDao.insert(
            CaptureSessionEntity(tier = CaptureTier.GATEWAY, port = port)
        )
        return gatewaySessionId
    }

    /**
     * Best-effort LAN IPv4 address a remote client should point its proxy at. Uses
     * [ConnectivityManager.getLinkProperties] over the active network (Wi-Fi or hotspot uplink)
     * rather than `WifiManager`, so it doesn't require the `ACCESS_WIFI_STATE` permission and
     * works over any active network transport.
     *
     * Returns null if no usable non-loopback IPv4 address can be found (e.g. no active network),
     * in which case the UI must show an explicit "unavailable" state rather than a stale/fake IP.
     */
    fun getLanIpAddress(context: Context): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val active = cm.activeNetwork ?: return null
            val props: LinkProperties = cm.getLinkProperties(active) ?: return null
            props.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
