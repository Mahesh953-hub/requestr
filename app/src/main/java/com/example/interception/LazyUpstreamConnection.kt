package com.example.interception

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

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
    private val connectionFactory: (String, Int) -> Socket = { h, p -> Socket(h, p) },
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
) {

    class Connected(
        val socket: Socket,
        val sslSocket: SSLSocket,
        val input: InputStream,
        val output: OutputStream,
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
     * [java.io.IOException] [connectionFactory] throws (e.g. unknown host, connection refused)
     * -- callers are responsible for translating an [javax.net.ssl.SSLException] into a
     * persisted failure record, matching the previous eager call site's behaviour of only
     * treating the handshake step (not the raw connect) as a first-class "upstream handshake"
     * failure. §3 (C2): also throws [NetworkConditionInjector.SimulatedPacketLossException] (an
     * [java.io.IOException]) before ever calling [connectionFactory], if [networkCondition] rolls
     * a simulated loss on this dial attempt -- handled identically to any other raw-connect
     * failure by every existing caller, since it's just another [java.io.IOException] subtype.
     */
    fun connect(): Connected {
        connected?.let { return it }
        synchronized(connectLock) {
            connected?.let { return it }
            try {
                NetworkConditionInjector.apply(networkCondition, random, sleep)
                val socket = connectionFactory(host, port)
                val sslSocket = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(AcceptAllTrustManager), SecureRandom())
                }.socketFactory.createSocket(socket, host, port, true) as SSLSocket
                // Offer the same ALPN protocol the client-facing leg negotiated (§4 ALPN
                // negotiation) -- see the KDoc on [preferredAlpnProtocols]. Best-effort: an
                // upstream server that doesn't understand ALPN, or a platform too old to expose
                // SSLParameters.applicationProtocols, just proceeds without it exactly as before
                // this parameter existed.
                if (preferredAlpnProtocols.isNotEmpty() &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                ) {
                    runCatching {
                        val params = sslSocket.sslParameters
                        params.applicationProtocols = preferredAlpnProtocols.toTypedArray()
                        sslSocket.sslParameters = params
                    }
                }
                sslSocket.startHandshake()
                val result = Connected(socket, sslSocket, sslSocket.getInputStream(), sslSocket.getOutputStream())
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

/**
 * Default trust manager for the upstream leg: the whole point of MITM is that this engine,
 * not the original client, is the one deciding whether to trust the real server's certificate
 * (the client only ever sees requestr's own per-host cert). Matches the trust posture the
 * previous eager call site used.
 */
private object AcceptAllTrustManager : X509TrustManager {
    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
