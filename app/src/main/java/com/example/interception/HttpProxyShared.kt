package com.example.interception

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

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
     * Handles a CONNECT request per the [ConnectControl.Disposition] the caller already
     * evaluated -- BLOCK answers the client directly, AllowOpaque/Redirect tunnel bidirectionally
     * (byte-counted, unlike the previous blind relay), and ForceMitm is answered with an explicit
     * error since a bare [Socket] relay can never terminate TLS -- only [MitmProxyEngine] can act
     * on that disposition.
     *
     * [onBytesTransferred] is invoked once, after the relay finishes, with
     * (bytes read from the client / written upstream, bytes read from upstream / written to the
     * client) so callers can persist a [com.example.data.local.ConnectAttemptEntity] byte count.
     */
    fun handleConnectAttempt(
        client: Socket,
        target: String,
        disposition: ConnectControl.Disposition,
        output: OutputStream,
        onBytesTransferred: (clientToUpstream: Long, upstreamToClient: Long) -> Unit = { _, _ -> },
    ) {
        when (disposition) {
            is ConnectControl.Disposition.Block -> {
                output.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
                output.flush()
            }
            is ConnectControl.Disposition.AllowOpaque -> {
                val (host, port) = parseHostPort(target, 443)
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                relay(client, host, port, onBytesTransferred)
            }
            is ConnectControl.Disposition.Redirect -> {
                val (host, port) = parseHostPort(disposition.target, 443)
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                relay(client, host, port, onBytesTransferred)
            }
            is ConnectControl.Disposition.ForceMitm -> {
                // A plain Socket relay can never terminate TLS -- only MitmProxyEngine can
                // honor FORCE_MITM. Callers that can't MITM (HttpProxyEngine, GatewayEngine)
                // must report this honestly rather than silently tunneling opaquely.
                output.write("HTTP/1.1 403 MITM required but not available\r\n\r\n".toByteArray())
                output.flush()
            }
        }
    }

    /** Byte-counted bidirectional relay between [client] and a freshly dialed upstream socket. */
    private fun relay(
        client: Socket,
        host: String,
        port: Int,
        onBytesTransferred: (clientToUpstream: Long, upstreamToClient: Long) -> Unit,
    ) {
        Socket(host, port).use { upstream ->
            val sent = AtomicLong(0)
            val received = AtomicLong(0)
            val t1 = Thread {
                runCatching {
                    val input = client.getInputStream()
                    val out = upstream.getOutputStream()
                    val buf = ByteArray(BUFFER_SIZE)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent.addAndGet(n.toLong())
                    }
                }
            }
            val t2 = Thread {
                runCatching {
                    val input = upstream.getInputStream()
                    val out = client.getOutputStream()
                    val buf = ByteArray(BUFFER_SIZE)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        received.addAndGet(n.toLong())
                    }
                }
            }
            t1.start(); t2.start()
            t1.join(30_000); t2.join(30_000)
            onBytesTransferred(sent.get(), received.get())
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
