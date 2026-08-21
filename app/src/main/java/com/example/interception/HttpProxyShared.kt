package com.example.interception

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Shared, stateless HTTP/1.1 forward-proxy plumbing used by both [HttpProxyEngine] (on-device
 * traffic) and [com.example.interception.gateway.GatewayEngine] (T2 — traffic proxied on behalf
 * of remote LAN devices).
 *
 * Extracted rather than duplicated so the two engines can't silently drift apart on request
 * parsing / CONNECT tunneling / upstream forwarding behavior.
 */
internal object HttpProxyShared {
    const val BUFFER_SIZE = 8192

    /** Reads a single CRLF-terminated line from [input]. Returns null at EOF with no data read. */
    fun readLine(input: InputStream): String? {
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

    fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colon = target.lastIndexOf(':')
        return if (colon > 0) {
            target.substring(0, colon) to (target.substring(colon + 1).toIntOrNull() ?: defaultPort)
        } else {
            target to defaultPort
        }
    }

    fun InputStream.copyToStream(out: OutputStream, bufSize: Int = BUFFER_SIZE) {
        val buf = ByteArray(bufSize)
        var n: Int
        while (read(buf).also { n = it } != -1) { out.write(buf, 0, n) }
    }

    /**
     * Opaque CONNECT tunnel (no MITM/inspection) — same behavior for on-device and remote/gateway
     * clients: bidirectional byte relay between the client socket and the upstream target.
     */
    fun handleConnectTunnel(client: Socket, target: String, output: OutputStream) {
        val (host, port) = parseHostPort(target, 443)
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        Socket(host, port).use { upstream ->
            val t1 = Thread { client.getInputStream().copyToStream(upstream.getOutputStream(), BUFFER_SIZE) }
            val t2 = Thread { upstream.getInputStream().copyToStream(client.getOutputStream(), BUFFER_SIZE) }
            t1.start(); t2.start()
            t1.join(30_000); t2.join(30_000)
        }
    }

    /** Forwards a plain (non-CONNECT) HTTP request to its destination and returns the response. */
    fun forwardRequest(
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
}
