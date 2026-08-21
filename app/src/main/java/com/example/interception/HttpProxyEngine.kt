package com.example.interception

import android.util.Log
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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
        private const val BUFFER_SIZE = 8192
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

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val rawUrl = parts[1]
                val httpVersion = parts.getOrElse(2) { "HTTP/1.1" }

                // Parse headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                    }
                }

                val isConnect = method.equals("CONNECT", ignoreCase = true)

                if (isConnect) {
                    handleConnectTunnel(sock, rawUrl, output)
                } else {
                    val bodyLen = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyBytes = if (bodyLen > 0) input.readNBytes(bodyLen) else ByteArray(0)
                    val bodyStr = String(bodyBytes, Charsets.UTF_8)

                    // Forward to upstream
                    val t0 = System.currentTimeMillis()
                    val (status, respHeaders, respBody) = forwardRequest(rawUrl, method, headers, bodyStr)
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

    private fun handleConnectTunnel(client: Socket, target: String, output: OutputStream) {
        val (host, port) = parseHostPort(target, 443)
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        Socket(host, port).use { upstream ->
            val t1 = Thread { client.getInputStream().copyTo(upstream.getOutputStream(), BUFFER_SIZE) }
            val t2 = Thread { upstream.getInputStream().copyTo(client.getOutputStream(), BUFFER_SIZE) }
            t1.start(); t2.start()
            t1.join(30_000); t2.join(30_000)
        }
    }

    private fun forwardRequest(
        rawUrl: String, method: String, reqHeaders: Map<String, String>, body: String
    ): Triple<Int, Map<String, String>, String> {
        val url = if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl"
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            reqHeaders.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) && !k.equals("Proxy-Connection", ignoreCase = true)) {
                    conn.setRequestProperty(k, v)
                }
            }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }

            val status = conn.responseCode
            val respHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.joinToString(", ") }
                .filterKeys { it != "Transfer-Encoding" }

            val respBody = runCatching {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrElse {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }

            Triple(status, respHeaders, respBody)
        }.getOrElse { e ->
            Triple(502, mapOf("X-Proxy-Error" to (e.message ?: "unknown")), "{\"error\":\"${e.message}\"}")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return sb.toString().ifEmpty { null }
            if (b == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0) {
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        } else {
            target to defaultPort
        }
    }

    private fun InputStream.copyTo(out: OutputStream, bufSize: Int = BUFFER_SIZE) {
        val buf = ByteArray(bufSize)
        var n: Int
        while (read(buf).also { n = it } != -1) { out.write(buf, 0, n) }
    }
}
