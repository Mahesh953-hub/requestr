package com.example.interception.gateway

import android.util.Log
import com.example.interception.gateway.local.Destination
import com.example.interception.gateway.local.EntryKind
import com.example.interception.gateway.local.GatewayRejectReason
import com.example.interception.gateway.local.GatewaySession
import com.example.interception.gateway.local.HttpPrelude
import com.example.interception.gateway.local.ProtocolClassifier
import com.example.interception.gateway.local.ProtocolClassifier.Classification
import com.example.interception.gateway.local.Socks4Handler
import com.example.interception.gateway.local.Socks5Credentials
import com.example.interception.gateway.local.Socks5Handler
import com.example.interception.tls.ClientHelloCapture
import com.example.interception.tls.PeekedSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives sessions from a [LocalGateway] once their protocol prelude has been consumed and the
 * destination is known. Implemented by [com.example.interception.MitmProxyEngine] (full tier) and
 * [com.example.interception.HttpProxyEngine] (HTTP_PROXY tier -- opaque tunnels only).
 */
interface GatewaySessionHandler {
    /**
     * A tunnel-shaped session ([EntryKind.SOCKS5], [EntryKind.SOCKS4], [EntryKind.HTTP_CONNECT],
     * [EntryKind.TRANSPARENT_TLS]). The handler owns the connection from here on, must call one
     * of the `session.reply*()` methods, and must close [GatewaySession.socket] when done.
     */
    suspend fun handleTunnel(session: GatewaySession)

    /**
     * A plaintext HTTP session ([EntryKind.HTTP_ABSOLUTE], [EntryKind.TRANSPARENT_HTTP]) whose
     * request head is replayed on [GatewaySession.socket]'s input stream.
     */
    suspend fun handlePlaintext(session: GatewaySession)
}

/** Optional `SO_ORIGINAL_DST`-style lookup for transparent sessions that carry no SNI/Host. */
fun interface OriginalDstResolver {
    fun resolve(client: Socket): Destination?
}

/**
 * The single local listener every capture tier funnels into (master plan §1 D1 / task 6).
 *
 * Accepts TCP connections, classifies the first bytes with [ProtocolClassifier], runs the matching
 * prelude (SOCKS5 with RFC 1929 `+info`, SOCKS4/4a, HTTP `CONNECT`, absolute-form HTTP, or
 * transparent TLS/HTTP), and hands a fully-resolved [GatewaySession] to the [GatewaySessionHandler].
 * The engines therefore no longer own a `ServerSocket` or parse `CONNECT` themselves, and the
 * legacy "anything that is not CONNECT gets a 400" path is gone: plaintext forward-proxy requests
 * and transparent captures are first-class entries.
 *
 * Every connection the gateway closes itself is counted per [GatewayRejectReason] (see
 * [rejectCounts]) and reported through [onRejected], so diagnostics can explain drops.
 */
class LocalGateway(
    private val handler: GatewaySessionHandler,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /** When non-null, SOCKS5 clients must authenticate with exactly these credentials. */
    private val socks5Credentials: Socks5Credentials? = null,
    private val originalDstResolver: OriginalDstResolver? = null,
    /** How long a freshly accepted client may stay silent before it is dropped (slow-loris guard). */
    private val firstByteTimeoutMs: Int = DEFAULT_FIRST_BYTE_TIMEOUT_MS,
    private val onRejected: (GatewayRejectReason, String) -> Unit = { _, _ -> },
    private val serverSocketFactory: (Int) -> ServerSocket = { ServerSocket(it, 128, InetAddress.getByName("127.0.0.1")) },
    private val logTag: String = TAG,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val rejects = ConcurrentHashMap<GatewayRejectReason, AtomicLong>()

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running.get()

    /** Snapshot of connections the gateway dropped itself, keyed by reason. */
    val rejectCounts: Map<GatewayRejectReason, Long>
        get() = rejects.mapValues { it.value.get() }

    /** Binds (loopback only) and starts accepting. Returns the bound port. Idempotent. */
    fun start(port: Int = 0): Int {
        if (running.get()) return this.port
        val server = serverSocketFactory(port)
        serverSocket = server
        running.set(true)
        Log.i(logTag, "LocalGateway listening on ${server.localPort} (socks5Auth=${socks5Credentials != null})")
        acceptJob = scope.launch {
            while (isActive && running.get()) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(logTag, "Accept error: ${e.message}")
                    break
                }
                launch { dispatch(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(logTag, "LocalGateway stopped")
    }

    /**
     * Classifies one accepted connection and routes it. Internal (not private) so tests can drive
     * it with a socket pair without spinning up the accept loop.
     */
    internal suspend fun dispatch(client: Socket) {
        val prefix = try {
            readClassifiablePrefix(client)
        } catch (e: SocketTimeoutException) {
            reject(client, GatewayRejectReason.FIRST_BYTE_TIMEOUT, "no bytes within ${firstByteTimeoutMs}ms")
            return
        } catch (e: IOException) {
            reject(client, GatewayRejectReason.CLIENT_CLOSED, e.message ?: "read failed")
            return
        }
        if (prefix == null) {
            reject(client, GatewayRejectReason.CLIENT_CLOSED, "EOF before any protocol byte")
            return
        }
        // Everything read so far is replayed; the preludes consume from `replay`.
        val replay = PeekedSocket(client, prefix)
        runCatching { client.soTimeout = 0 }

        when (ProtocolClassifier.classify(prefix)) {
            Classification.Socks5 -> handleSocks5(client, replay)
            Classification.Socks4 -> handleSocks4(client, replay)
            Classification.HttpConnect -> handleHttpConnect(client, replay)
            Classification.HttpAbsolute -> handleHttpPlaintext(client, replay, EntryKind.HTTP_ABSOLUTE)
            Classification.HttpOrigin -> handleHttpPlaintext(client, replay, EntryKind.TRANSPARENT_HTTP)
            Classification.Tls -> handleTransparentTls(client, replay)
            Classification.NeedMore, Classification.Unknown ->
                reject(client, GatewayRejectReason.UNKNOWN_PROTOCOL, describePrefix(prefix))
        }
    }

    /**
     * Reads the minimum prefix [ProtocolClassifier] needs, bounded by [MAX_PREFIX_BYTES] and the
     * first-byte timeout. Returns `null` on EOF before any byte.
     */
    private fun readClassifiablePrefix(client: Socket): ByteArray? = readPrefix(client, firstByteTimeoutMs)

    private suspend fun handleSocks5(client: Socket, replay: Socket) {
        val outcome = Socks5Handler(socks5Credentials).negotiate(replay.getInputStream(), replay.getOutputStream())
        when (outcome) {
            is Socks5Handler.Outcome.Rejected -> reject(client, outcome.reason, outcome.detail)
            is Socks5Handler.Outcome.Connect -> handler.handleTunnel(
                GatewaySession(replay, EntryKind.SOCKS5, outcome.destination, outcome.origin),
            )
        }
    }

    private suspend fun handleSocks4(client: Socket, replay: Socket) {
        val outcome = Socks4Handler().negotiate(replay.getInputStream(), replay.getOutputStream())
        when (outcome) {
            is Socks4Handler.Outcome.Rejected -> reject(client, outcome.reason, outcome.detail)
            is Socks4Handler.Outcome.Connect -> handler.handleTunnel(
                GatewaySession(replay, EntryKind.SOCKS4, outcome.destination, origin = null),
            )
        }
    }

    private suspend fun handleHttpConnect(client: Socket, replay: Socket) {
        val head = readHead(client, replay) ?: return
        val destination = HttpPrelude.connectDestination(head)
        if (destination == null) {
            runCatching { replay.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
            reject(client, GatewayRejectReason.HTTP_MALFORMED, "CONNECT target '${head.target}' unparseable")
            return
        }
        // The CONNECT head is consumed and NOT replayed: what follows on the wire is the
        // tunneled payload (a TLS ClientHello, or plaintext HTTP for http:// tunnels).
        handler.handleTunnel(
            GatewaySession(replay, EntryKind.HTTP_CONNECT, destination, origin = null, rawTarget = head.target),
        )
    }

    private suspend fun handleHttpPlaintext(client: Socket, replay: Socket, entry: EntryKind) {
        val head = readHead(client, replay) ?: return
        val destination = if (entry == EntryKind.HTTP_ABSOLUTE) {
            HttpPrelude.absoluteDestination(head)
        } else {
            HttpPrelude.hostHeaderDestination(head)
        }
        if (destination == null) {
            runCatching { replay.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
            reject(client, GatewayRejectReason.HTTP_MALFORMED, "${head.method} ${head.target}: no destination")
            return
        }
        // Replay the whole head so the plaintext handler sees an untouched request stream.
        val session = GatewaySession(
            PeekedSocket(replay, head.raw), entry, destination, origin = null, rawTarget = head.target,
        )
        handler.handlePlaintext(session)
    }

    private suspend fun handleTransparentTls(client: Socket, replay: Socket) {
        val peeked = ClientHelloCapture.peekClientHello(replay)
        val sni = peeked.clientHello?.sni?.takeIf { it.isNotBlank() }
        val destination = when {
            sni != null -> Destination(sni, 443)
            else -> originalDstResolver?.resolve(client)
        }
        if (destination == null) {
            reject(client, GatewayRejectReason.TRANSPARENT_NO_DESTINATION, "TLS without SNI and no original-dst resolver")
            return
        }
        handler.handleTunnel(GatewaySession(peeked.socket, EntryKind.TRANSPARENT_TLS, destination, origin = null))
    }

    private fun readHead(client: Socket, replay: Socket): HttpPrelude.Head? {
        return when (val result = HttpPrelude.read(replay.getInputStream())) {
            is HttpPrelude.Result.Parsed -> result.head
            is HttpPrelude.Result.Malformed -> {
                runCatching { replay.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
                reject(client, GatewayRejectReason.HTTP_MALFORMED, result.detail)
                null
            }
            HttpPrelude.Result.Eof -> {
                reject(client, GatewayRejectReason.CLIENT_CLOSED, "EOF before request head")
                null
            }
        }
    }

    private fun reject(client: Socket, reason: GatewayRejectReason, detail: String) {
        rejects.getOrPut(reason) { AtomicLong() }.incrementAndGet()
        Log.d(logTag, "Dropping connection: $reason -- $detail")
        runCatching { onRejected(reason, detail) }
        runCatching { client.close() }
    }

    private fun describePrefix(prefix: ByteArray): String =
        prefix.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "LocalGateway"
        const val DEFAULT_FIRST_BYTE_TIMEOUT_MS = 10_000
        /** Enough for the longest method token + scheme; the HTTP head itself is read afterwards. */
        internal const val MAX_PREFIX_BYTES = 32

        /**
         * Reads the shortest prefix [ProtocolClassifier] can decide on (at most [MAX_PREFIX_BYTES]),
         * waiting up to [timeoutMs] for the first byte. Returns `null` on EOF before any byte.
         * Throws [SocketTimeoutException] if the client stays silent.
         */
        @Throws(IOException::class)
        internal fun readPrefix(client: Socket, timeoutMs: Int): ByteArray? {
            val previousTimeout = runCatching { client.soTimeout }.getOrDefault(0)
            client.soTimeout = timeoutMs
            try {
                val input = client.getInputStream()
                val buf = ByteArray(MAX_PREFIX_BYTES)
                var len = 0
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n == -1) break
                    len += n
                    if (ProtocolClassifier.classify(buf, len) != Classification.NeedMore) break
                }
                return if (len == 0) null else buf.copyOf(len)
            } finally {
                runCatching { client.soTimeout = previousTimeout }
            }
        }

        /**
         * Peeks the first payload bytes of an already-established tunnel without consuming them:
         * returns the prefix plus a socket whose input stream replays it. `null` if the client
         * closed or stayed silent for [timeoutMs] -- the caller decides how to record that.
         */
        internal fun peekPrefix(client: Socket, timeoutMs: Int): Pair<ByteArray, Socket>? {
            val prefix = try {
                readPrefix(client, timeoutMs)
            } catch (e: IOException) {
                null
            } ?: return null
            return prefix to PeekedSocket(client, prefix)
        }
    }
}

/** Convenience for tests and diagnostics: a session's one-line description. */
internal fun GatewaySession.describe(): String =
    "$entry -> $destination" + (origin?.let { " from ${it.ip}:${it.port}/${it.transport}" } ?: "")
