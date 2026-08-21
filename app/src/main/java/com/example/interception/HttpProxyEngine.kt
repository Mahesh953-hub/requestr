package com.example.interception

import android.util.Log
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.interception.HttpProxyShared.copyToStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * This is NOT a MITM — TLS traffic passes through as CONNECT tunnels.
 * MITM decryption requires a separate MitmProxyEngine with per-host cert generation.
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
    val port: Int get() = serverSocket?.localPort ?: 0

    private val sessionId = AtomicLong(System.currentTimeMillis())
    private var packetCount = AtomicLong(0)

    companion object {
        private const val TAG = "HttpProxy"
    }

    fun start(port: Int = 0): Int {
        if (running.get()) return this.port

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

                if (isConnect) {
                    HttpProxyShared.handleConnectTunnel(sock, rawUrl, output)
                } else {
                    val bodyLen = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyBytes = if (bodyLen > 0) input.readNBytes(bodyLen) else ByteArray(0)
                    val bodyStr = String(bodyBytes, Charsets.UTF_8)

                    // Forward to upstream
                    val t0 = System.currentTimeMillis()
                    val (status, respHeaders, respBody) = HttpProxyShared.forwardRequest(rawUrl, method, headers, bodyStr)
                    val latency = System.currentTimeMillis() - t0

                    // Send response back
                    val responseLine = "$httpVersion $status OK\r\n"
                    output.write(responseLine.toByteArray())
                    respHeaders.forEach { (k, v) ->
                        output.write("$k: $v\r\n".toByteArray())
                    }
                    output.write("\r\n".toByteArray())
                    output.write(respBody.toByteArray())
                    output.flush()

                    packetCount.incrementAndGet()
                    InterceptionStateRepository.updateCounters(packetCount = packetCount.get().toInt())
                    Log.d(TAG, "$method $rawUrl → $status (${latency}ms)")
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Client handler error: ${e.message}")
        }
    }
}
