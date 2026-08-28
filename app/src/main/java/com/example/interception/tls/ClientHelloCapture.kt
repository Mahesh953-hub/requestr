package com.example.interception.tls

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.SocketChannel
import java.net.Socket

/**
 * Real JA3 fingerprinting + TLS-key-log identity for the MITM proxy's client-facing leg.
 *
 * [MitmProxyEngine.handleClient] hands the accepted [Socket] straight to
 * `sslContext.socketFactory.createSocket(client, host, port, true)` (the "layered" SSLSocket
 * constructor), which drives the TLS handshake by reading directly off `client`'s own
 * [Socket.getInputStream]. By the time [javax.net.ssl.SSLSocket.startHandshake] returns, the
 * raw ClientHello bytes are gone -- consumed by JSSE with no supported way to read them back
 * out of the (by-then-encrypted-application-data) socket. That is why
 * `TlsSessionEntity.ja3Hash` was hardcoded to `null` in `persistMitmTlsSession`: JA3
 * ([TlsClientHello.ja3Hash]) needs the *offered* cipher suites/extensions/groups/sig-algs,
 * which only exist on the wire in that one ClientHello record.
 *
 * [peekClientHello] fixes this by reading the ClientHello record directly off the accepted
 * socket's input stream -- *before* it is ever handed to JSSE -- and returning both the parsed
 * [TlsClientHello] and a drop-in replacement [Socket] ([PeekedSocket]) whose
 * [Socket.getInputStream] replays the exact bytes peeked (via [SequenceInputStream]) followed by
 * whatever is still unread on the real stream. JSSE sees byte-for-byte the same input it would
 * have seen without this wrapper -- the handshake is unaffected -- while this engine also gets
 * to look at the ClientHello first.
 */
object ClientHelloCapture {
    private const val TAG = "ClientHelloCapture"

    /** TLS record header: 1 content-type + 2 version + 2 length. */
    private const val RECORD_HEADER_LEN = 5

    /**
     * Guard against a client that never sends a real ClientHello (garbage, or a non-TLS
     * CONNECT target): refuse to buffer more than this many bytes hunting for one.
     */
    private const val MAX_PEEK_BYTES = 32 * 1024

    /** Result of [peekClientHello]: the parsed ClientHello (if any) plus a replay-safe socket. */
    class Result(
        /** Null when the peeked bytes did not parse as a ClientHello (e.g. a non-TLS client). */
        val clientHello: TlsClientHello?,
        /**
         * Use this in place of the original [Socket] for every subsequent operation (in
         * particular, the `sslContext.socketFactory.createSocket(socket, ...)` call) --
         * its [Socket.getInputStream] replays the peeked bytes transparently.
         */
        val socket: Socket,
    )

    /**
     * Peeks the TLS ClientHello record off [client] without consuming it from the stream JSSE
     * will read next. Assumes (RFC 8446 §5.1 allows, and every real client does) the ClientHello
     * fits in a single TLS record -- true for every observed browser/OS TLS stack; a ClientHello
     * that legitimately spans multiple records is vanishingly rare and, worst case, this simply
     * fails to parse (returns `clientHello = null`) rather than corrupting the byte stream, since
     * the replay wrapper always forwards exactly what it read regardless of parse success.
     *
     * Never throws for a parse failure -- only for a genuine I/O error reading the socket (in
     * which case the caller's handshake attempt would have failed anyway).
     */
    fun peekClientHello(client: Socket): Result {
        val input = client.getInputStream()
        val peeked = java.io.ByteArrayOutputStream(1024)

        // Read the 5-byte record header first so we know exactly how many more bytes the
        // ClientHello handshake record needs -- never read more than that record declares.
        val header = readNBytes(input, RECORD_HEADER_LEN)
        if (header == null || header.size < RECORD_HEADER_LEN) {
            // Not enough bytes for even a record header (EOF/closed). Replay whatever we did read.
            peeked.write(header ?: ByteArray(0))
            return Result(null, PeekedSocket(client, peeked.toByteArray()))
        }
        peeked.write(header)

        val contentType = header[0].toInt() and 0xFF
        val recordLen = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        if (contentType != 22 /* handshake */ || recordLen <= 0 || recordLen > MAX_PEEK_BYTES) {
            // Not a plausible ClientHello record (or absurdly large) -- stop peeking, replay
            // just the header bytes read so far. Whatever this is, JSSE (or the blind-relay
            // fallback) will deal with it the same as before this feature existed.
            return Result(null, PeekedSocket(client, peeked.toByteArray()))
        }

        val body = readNBytes(input, recordLen)
        if (body != null) peeked.write(body)

        val fullRecord = peeked.toByteArray()
        val clientHello = runCatching { TlsDecoder.parseClientHello(fullRecord) }
            .onFailure { e -> Log.w(TAG, "ClientHello parse failed: ${e.message}") }
            .getOrNull()

        return Result(clientHello, PeekedSocket(client, fullRecord))
    }

    /** Reads up to [n] bytes, looping on short reads; returns fewer than [n] only at EOF. */
    private fun readNBytes(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) break
            read += r
        }
        if (read == 0) return null
        return if (read == n) buf else buf.copyOf(read)
    }
}

/**
 * A [Socket] that delegates every operation to [real] except [getInputStream], which replays
 * [peekedBytes] followed by whatever [real] still has unread. This is what lets
 * [ClientHelloCapture.peekClientHello] hand JSSE (or anything else downstream) a stream that
 * looks byte-for-byte identical to [real]'s, despite this engine having already read the first
 * few bytes off it directly.
 *
 * Only the members [MitmProxyEngine] and the layered [javax.net.ssl.SSLSocketFactory] actually
 * exercise are overridden with real delegation; anything unused simply inherits [Socket]'s
 * default (unconnected, no-op) behaviour, which is intentionally never reached in practice.
 */
internal class PeekedSocket(
    private val real: Socket,
    peekedBytes: ByteArray,
) : Socket() {
    private val replayStream: InputStream by lazy {
        SequenceInputStream(java.io.ByteArrayInputStream(peekedBytes), real.getInputStream())
    }

    override fun getInputStream(): InputStream = replayStream
    override fun getOutputStream(): OutputStream = real.getOutputStream()

    override fun isConnected(): Boolean = real.isConnected
    override fun isBound(): Boolean = real.isBound
    override fun isClosed(): Boolean = real.isClosed
    override fun isInputShutdown(): Boolean = real.isInputShutdown
    override fun isOutputShutdown(): Boolean = real.isOutputShutdown

    override fun close() {
        real.close()
    }

    override fun shutdownInput() {
        real.shutdownInput()
    }

    override fun shutdownOutput() {
        real.shutdownOutput()
    }

    override fun getInetAddress(): InetAddress? = real.inetAddress
    override fun getLocalAddress(): InetAddress? = real.localAddress
    override fun getPort(): Int = real.port
    override fun getLocalPort(): Int = real.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = real.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = real.localSocketAddress
    override fun getChannel(): SocketChannel? = real.channel

    @Throws(SocketException::class)
    override fun setSoTimeout(timeout: Int) {
        real.soTimeout = timeout
    }

    @Throws(SocketException::class)
    override fun getSoTimeout(): Int = real.soTimeout

    @Throws(SocketException::class)
    override fun setTcpNoDelay(on: Boolean) {
        real.tcpNoDelay = on
    }

    @Throws(SocketException::class)
    override fun getTcpNoDelay(): Boolean = real.tcpNoDelay

    @Throws(SocketException::class)
    override fun setKeepAlive(on: Boolean) {
        real.keepAlive = on
    }

    @Throws(SocketException::class)
    override fun getKeepAlive(): Boolean = real.keepAlive

    @Throws(SocketException::class)
    override fun setSoLinger(on: Boolean, linger: Int) {
        real.setSoLinger(on, linger)
    }

    @Throws(SocketException::class)
    override fun setReceiveBufferSize(size: Int) {
        real.receiveBufferSize = size
    }

    @Throws(SocketException::class)
    override fun getReceiveBufferSize(): Int = real.receiveBufferSize

    @Throws(SocketException::class)
    override fun setSendBufferSize(size: Int) {
        real.sendBufferSize = size
    }

    @Throws(SocketException::class)
    override fun getSendBufferSize(): Int = real.sendBufferSize
}
