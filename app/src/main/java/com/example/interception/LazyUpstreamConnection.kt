package com.example.interception

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.KeyManager

import com.example.interception.net.SocketProtector
import com.example.interception.tls.UpstreamTrustPolicy

/**
 * §1.5 MUST-FIX: lazily dials + TLS-handshakes the real upstream server for a single MITM'd
 * client connection.
 *
 * [MitmProxyEngine] used to dial and TLS-handshake `host:port` unconditionally, *before* it
 * ever read the client's first decrypted request or evaluated any rule against it. That broke
 * MOCK_RESPONSE's core promise (never touch the real endpoint) -- a fully-mocked connection
 * still opened a real socket and completed a real TLS handshake to the server it was supposed
 * to be avoiding.
 *
 * [connect] defers both until something on the connection actually needs the network (a
 * REDIRECT / MODIFY_REQUEST / LOG_ONLY-forwarded request, or non-HTTP/1.x blind-relay bytes --
 * see [MitmProxyEngine.inspectClientToServer] / [MitmProxyEngine.handleParsedRequest]).
 * [connect] is safe to call repeatedly across multiple requests on the same client connection:
 * the first call actually connects; every later call (including from a later request) returns
 * the exact same cached [Connected] rather than reconnecting.
 *
 * [awaitConnectedOrGiveUp] lets the paired server→client reader
 * ([MitmProxyEngine.inspectServerToClient]) block until [connect] actually succeeds (or fails --
 * either way there is a definite outcome to react to) -- or, if the client→server side finishes
 * its entire loop without ever needing upstream at all (e.g. every request on this connection
 * was MOCK_RESPONSE), [giveUp] unblocks it with `null` instead. That is what lets a fully-mocked
 * connection finish without this engine ever attempting a TCP connection to the real host.
 */
internal class LazyUpstreamConnection(
    private val host: String,
    private val port: Int,
    /** Creates an unconnected socket. [connect] always owns protect-before-connect ordering. */
    private val socketFactory: () -> Socket = { Socket() },
    /** Package-private connector seam for deterministic tests; production uses [Socket.connect]. */
    private val socketConnector: (Socket, SocketAddress) -> Unit = { socket, address -> socket.connect(address) },
    /**
     * ALPN protocols to offer the upstream server, in preference order. Mirrors whatever the
     * client-facing leg actually negotiated (see [MitmProxyEngine.handleClient]) rather than
     * offering something independently -- if the client asked for `h2` and got it, this engine
     * must speak `h2` upstream too, or the two legs of the MITM'd connection would disagree
     * about framing. Empty (the default) means "no ALPN offer", matching every call site before
     * this parameter existed.
     */
    private val preferredAlpnProtocols: List<String> = emptyList(),
    /** §3 (C2) — weak-network simulation applied once, right before dialing [host]:[port]. */
    private val networkCondition: NetworkConditionInjector.NetworkCondition = NetworkConditionInjector.NetworkCondition.NONE,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val onFirstConnect: (Connected) -> Unit = {},
    /**
     * S1 task 8: client-certificate [KeyManager]s for mutual TLS towards this upstream (from
     * [com.example.interception.tls.ClientCertStore] via a matching per-domain policy). Null --
     * the default -- offers no client certificate, exactly as before this parameter existed.
     */
    private val keyManagers: Array<KeyManager>? = null,
    /** S1 task 8: how the upstream chain is judged; default accepts and records the outcome. */
    private val trustPolicy: UpstreamTrustPolicy = UpstreamTrustPolicy.AcceptAllRecording(),
) {

    class Connected(
        val socket: Socket,
        val sslSocket: SSLSocket,
        val input: InputStream,
        val output: OutputStream,
        /**
         * S2 task 18: what this leg's DNS lookup, TCP connect and TLS handshake actually took.
         * Measured around the blocking calls in [connect]; never derived.
         */
        val setupTimings: com.example.interception.relay.UpstreamSetupTimings =
            com.example.interception.relay.UpstreamSetupTimings(null, null, null),
    )

    @Volatile private var connected: Connected? = null
    private val readyLatch = CountDownLatch(1)
    private val connectLock = Object()

    /** True once [connect] has succeeded at least once. Never blocks. */
    val isConnected: Boolean get() = connected != null

    /**
     * Dials + TLS-handshakes on the first call; every later call returns the same cached
     * [Connected] without reconnecting. [onFirstConnect] runs exactly once, synchronously,
     * right after a successful handshake and before this (or any concurrent) call returns.
     *
     * Throws [javax.net.ssl.SSLException] if the handshake itself fails, and whatever
     * [java.io.IOException] the socket connector throws (e.g. unknown host, connection refused)
     * -- callers are responsible for translating an [javax.net.ssl.SSLException] into a
     * persisted failure record, matching the previous eager call site's behaviour of only
     * treating the handshake step (not the raw connect) as a first-class "upstream handshake"
     * failure. §3 (C2): also throws [NetworkConditionInjector.SimulatedPacketLossException] (an
     * [java.io.IOException]) before ever creating the socket, if [networkCondition] rolls
     * a simulated loss on this dial attempt -- handled identically to any other raw-connect
     * failure by every existing caller, since it's just another [java.io.IOException] subtype.
     */
    fun connect(): Connected {
        connected?.let { return it }
        synchronized(connectLock) {
            connected?.let { return it }
            try {
                NetworkConditionInjector.apply(networkCondition, random, sleep)
                val socket = socketFactory()
                // S2 task 18: phase clocks. InetSocketAddress(host, port) is where the blocking
                // name lookup happens (an IP literal resolves instantly, and ~0 ms is the honest
                // answer for it); the connector is the TCP connect; startHandshake() is TLS.
                val dnsMs: Long
                val connectMs: Long
                try {
                    val protectResult = SocketProtector.protectSocket(socket)
                    if (protectResult == SocketProtector.ProtectResult.Failed) {
                        throw java.io.IOException(
                            "SocketProtector.protectSocket failed for upstream $host:$port; refusing to connect"
                        )
                    }
                    val dnsStart = System.nanoTime()
                    val address = InetSocketAddress(host, port)
                    val connectStart = System.nanoTime()
                    dnsMs = (connectStart - dnsStart) / 1_000_000
                    socketConnector(socket, address)
                    connectMs = (System.nanoTime() - connectStart) / 1_000_000
                } catch (t: Throwable) {
                    runCatching { socket.close() }
                    throw t
                }
                val sslSocket = SSLContext.getInstance("TLS").apply {
                    init(keyManagers, arrayOf(trustPolicy.trustManager(host)), SecureRandom())
                }.socketFactory.createSocket(socket, host, port, true) as SSLSocket
                // Offer the same ALPN protocol the client-facing leg negotiated (§4 ALPN
                // negotiation) -- see the KDoc on [preferredAlpnProtocols]. Best-effort: an
                // upstream server that doesn't understand ALPN, or a platform too old to expose
                // SSLParameters.applicationProtocols, just proceeds without it exactly as before
                // this parameter existed. Gated at API 24 (N) to match the client-facing leg:
                // `SSLParameters.setApplicationProtocols` exists from N, and Android's Conscrypt
                // negotiates ALPN there even though `getApplicationProtocol()` is API 29.
                if (preferredAlpnProtocols.isNotEmpty() &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                ) {
                    runCatching {
                        val params = sslSocket.sslParameters
                        params.applicationProtocols = preferredAlpnProtocols.toTypedArray()
                        sslSocket.sslParameters = params
                    }
                }
                val tlsStart = System.nanoTime()
                sslSocket.startHandshake()
                val tlsMs = (System.nanoTime() - tlsStart) / 1_000_000
                val result = Connected(
                    socket, sslSocket, sslSocket.getInputStream(), sslSocket.getOutputStream(),
                    setupTimings = com.example.interception.relay.UpstreamSetupTimings(dnsMs, connectMs, tlsMs),
                )
                connected = result
                onFirstConnect(result)
                return result
            } finally {
                // Always releases -- on success AND on failure -- so awaitConnectedOrGiveUp
                // never blocks forever waiting for an attempt that already happened and failed.
                readyLatch.countDown()
            }
        }
    }

    /**
     * Always safe to call, any number of times, from any thread -- unblocks
     * [awaitConnectedOrGiveUp] with whatever [connect] last produced (`null` if [connect] was
     * never called at all). Idempotent: counting down an already-released latch is a no-op.
     */
    fun giveUp() {
        readyLatch.countDown()
    }

    /**
     * Closes the upstream sockets if a connection was ever established (S1 task 7 drain
     * deadline: unblocks a relay leg stuck in a read). Never throws; safe when never connected.
     */
    fun closeQuietly() {
        val c = connected ?: return
        runCatching { c.sslSocket.close() }
        runCatching { c.socket.close() }
    }

    /**
     * Blocks (bounded by [timeoutMillis] so a hung client→server side can never wedge this
     * reader forever) until either [connect] has run (successfully or not) or [giveUp] was
     * called first. Returns the connection if [connect] succeeded, `null` otherwise --
     * including on timeout.
     */
    fun awaitConnectedOrGiveUp(timeoutMillis: Long = 60_000): Connected? {
        readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return connected
    }
}
