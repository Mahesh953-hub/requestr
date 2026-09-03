package com.example.interception

import android.content.Context
import android.os.Build
import com.example.data.interception.CaState
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.interception.net.SocketProtector
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Re-issues a captured CONNECT (Task 5 "Iter" -- CONNECT replay) so a user inspecting the
 * Connections sheet can probe a target's *current* disposition/ALPN without re-running a whole
 * capture session.
 *
 * Writes the same `CONNECT host:port HTTP/1.1` request line the real proxy engines parse
 * ([HttpProxyEngine], [MitmProxyEngine], [com.example.interception.gateway.GatewayEngine]), reads
 * back the status line, and reports the outcome.
 *
 * When [forceMitm] is false this opens a direct [Socket] to `targetHost:targetPort` -- the same
 * shape of connection a raw CONNECT-tunneling proxy client would make. When [forceMitm] is true
 * the replay instead dials the locally running [MitmProxyEngine] (its port is published via
 * [InterceptionStateRepository] while a MITM_PROXY capture is active) so the CONNECT is evaluated
 * by the same rule-driven [ConnectControl] the real engine uses, and -- once tunneled -- completes
 * a genuine TLS handshake through it to report the negotiated ALPN.
 */
object ConnectReplay {
    data class ReplayResult(
        /** Same disposition vocabulary as [com.example.data.local.ConnectAttemptEntity.disposition]. */
        val disposition: String,
        val latencyMs: Long,
        val alpn: String?,
        val error: String?,
    )

    /**
     * Master plan §3 LOW-ADD — advanced repeat with count+jitter. Runs [replay] [count] times
     * sequentially (each call reuses the same [targetHost]/[targetPort]/[forceMitm] arguments),
     * sleeping a random `[0, jitterMs]` delay between attempts so a burst of replays doesn't look
     * like a synchronized retry storm to the target/proxy. `count <= 1` runs exactly once with no
     * delay. Returns every [ReplayResult] in call order (including failures — callers see the
     * full attempt history, not just the last one).
     */
    suspend fun replayRepeated(
        context: Context,
        targetHost: String,
        targetPort: Int,
        forceMitm: Boolean = false,
        count: Int = 1,
        jitterMs: Long = 0L,
        random: Random = Random.Default,
    ): List<ReplayResult> {
        val attempts = count.coerceAtLeast(1)
        val maxJitter = jitterMs.coerceAtLeast(0L)
        return buildList {
            repeat(attempts) { attemptIndex ->
                add(replay(context, targetHost, targetPort, forceMitm))
                if (attemptIndex < attempts - 1 && maxJitter > 0) {
                    delay(random.nextLong(0, maxJitter + 1))
                }
            }
        }
    }

    /**
     * Master plan §3 LOW-ADD — per-CONNECT CA trust recheck. A [forceMitm] replay's
     * `MITM_DECRYPTED` outcome only means the *proxy* tunneled the CONNECT through; it says
     * nothing about whether this device's trust store still trusts the requestr CA at the moment
     * of the check (the user may have removed it, or a work-profile policy may have dropped it,
     * since the last [CapabilityProbe.probeAll] refresh). Calling this immediately before/after a
     * [forceMitm] replay gives an up-to-the-second answer instead of relying on
     * [InterceptionStateRepository]'s possibly-stale cached capability snapshot.
     */
    suspend fun recheckCaTrust(): CaState = CapabilityProbe.probeCaInstalled()

    private const val SOCKET_TIMEOUT_MS = 10_000

    fun replay(
        context: Context,
        targetHost: String,
        targetPort: Int,
        forceMitm: Boolean = false,
        socketFactory: () -> Socket = { Socket() },
        socketConnector: (Socket, SocketAddress, Int) -> Unit = { socket, address, timeout ->
            socket.connect(address, timeout)
        },
    ): ReplayResult {
        val start = System.currentTimeMillis()

        val dialTarget = if (forceMitm) {
            val state = InterceptionStateRepository.state.value
            if (state.captureMethod != CaptureMethod.MITM_PROXY || state.port <= 0) {
                return ReplayResult(
                    disposition = "FAILED",
                    latencyMs = System.currentTimeMillis() - start,
                    alpn = null,
                    error = "MITM proxy is not running -- start a MITM_PROXY capture first",
                )
            }
            "127.0.0.1" to state.port
        } else {
            targetHost to targetPort
        }

        return try {
            val socket = socketFactory()
            try {
                val protectResult = SocketProtector.protectSocket(socket)
                if (protectResult == SocketProtector.ProtectResult.Failed) {
                    return ReplayResult(
                        "FAILED", System.currentTimeMillis() - start, null,
                        "SocketProtector.protectSocket failed for ${dialTarget.first}:${dialTarget.second}",
                    ).also { runCatching { socket.close() } }
                }
                socketConnector(socket, InetSocketAddress(dialTarget.first, dialTarget.second), SOCKET_TIMEOUT_MS)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
            socket.use {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(
                    "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n".toByteArray()
                )
                output.flush()

                val input = socket.getInputStream()
                val statusLine = HttpProxyShared.readLine(input)
                    ?: return ReplayResult(
                        "FAILED", System.currentTimeMillis() - start, null,
                        "no response from ${dialTarget.first}:${dialTarget.second}",
                    )
                // Drain the remaining response headers up to the blank line separator.
                while (true) {
                    val line = HttpProxyShared.readLine(input) ?: break
                    if (line.isEmpty()) break
                }

                val statusCode = statusLine.trim().split(" ").getOrNull(1)?.toIntOrNull()
                if (statusCode != 200) {
                    val disposition = if (statusCode == 403) "BLOCKED" else "FAILED"
                    return ReplayResult(disposition, System.currentTimeMillis() - start, null, statusLine)
                }

                if (!forceMitm) {
                    return ReplayResult("ALLOWED_OPAQUE", System.currentTimeMillis() - start, null, null)
                }

                // forceMitm + 200: the MITM engine tunneled us through to the real host. Complete
                // a genuine TLS handshake over it so we can report the negotiated ALPN, mirroring
                // what MitmProxyEngine records for a real client's connection.
                val alpn = negotiateAlpn(socket, targetHost, targetPort)
                ReplayResult("MITM_DECRYPTED", System.currentTimeMillis() - start, alpn, null)
            }
        } catch (e: Exception) {
            ReplayResult("FAILED", System.currentTimeMillis() - start, null, e.message ?: e.toString())
        }
    }

    /** Best-effort: failures here don't fail the replay, they just leave [alpn] null. */
    private fun negotiateAlpn(tunnel: Socket, targetHost: String, targetPort: Int): String? = try {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(TrustAllReplayCerts), SecureRandom())
        val sslSocket = sslContext.socketFactory.createSocket(tunnel, targetHost, targetPort, true) as SSLSocket
        sslSocket.useClientMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = sslSocket.sslParameters
            params.applicationProtocols = arrayOf("h2", "http/1.1")
            sslSocket.sslParameters = params
        }
        sslSocket.startHandshake()
        val alpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sslSocket.applicationProtocol?.ifEmpty { null }
        } else {
            null
        }
        runCatching { sslSocket.close() }
        alpn
    } catch (e: Exception) {
        null
    }

    private object TrustAllReplayCerts : X509TrustManager {
        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
