package com.example.interception.gateway.local

import java.io.OutputStream
import java.net.Socket

/**
 * How a connection entered the [com.example.interception.gateway.LocalGateway]. Decides which
 * wire reply (if any) the gateway owes the client once the session's disposition is known.
 */
enum class EntryKind {
    /** RFC 1928 SOCKS5 `CONNECT`, optionally authenticated with RFC 1929 user/pass + `+info`. */
    SOCKS5,
    /** SOCKS4/4a `CONNECT`. */
    SOCKS4,
    /** HTTP `CONNECT host:port` tunnel (device-proxy / legacy bridge wiring). */
    HTTP_CONNECT,
    /** Plaintext HTTP request with an absolute-form URI (`GET http://host/path`). */
    HTTP_ABSOLUTE,
    /** Transparent TLS: the first bytes are a TLS record; destination from SNI / original dst. */
    TRANSPARENT_TLS,
    /** Transparent plaintext HTTP (origin-form request line + `Host` header). */
    TRANSPARENT_HTTP,
}

/** The client's real source tuple, as reported by the bridge's SOCKS5 `+info` username. */
data class ClientOrigin(
    val ip: String,
    val port: Int,
    /** `tcp` or `udp` as written by the bridge; anything else is preserved verbatim. */
    val transport: String,
) {
    companion object {
        /**
         * Parses the `+info` payload the vendored tun2proxy encodes into the SOCKS5 username:
         * `"<base>|<tcp|udp>|<src_ip>|<src_port>"`. Returns `null` for a plain username. A
         * username that *looks* like an `+info` payload but is malformed also yields `null` so
         * callers can decide whether to reject it (see [Socks5Handler]).
         */
        fun parseInfoUsername(username: String): Parsed? {
            val parts = username.split('|')
            if (parts.size != 4) return null
            val port = parts[3].toIntOrNull() ?: return null
            if (port !in 0..65535) return null
            if (parts[2].isBlank()) return null
            return Parsed(base = parts[0], origin = ClientOrigin(ip = parts[2], port = port, transport = parts[1]))
        }
    }

    /** Result of [parseInfoUsername]: the credential base plus the decoded origin. */
    data class Parsed(val base: String, val origin: ClientOrigin)
}

/** Where the client asked to go. Either a hostname or an IP literal; never both resolved here. */
data class Destination(val host: String, val port: Int) {
    val isIpLiteral: Boolean get() = looksLikeIpLiteral(host)

    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        fun looksLikeIpLiteral(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host.contains(':')) return true // IPv6 literal (no brackets at this layer)
            return host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3
        }
    }
}

/**
 * Why a connection was closed by the gateway without ever reaching a handler. Recorded (not
 * merely logged) so diagnostics can explain a dropped connection instead of a bare `400`.
 */
enum class GatewayRejectReason {
    /** The first bytes matched no supported protocol. */
    UNKNOWN_PROTOCOL,
    /** The client sent nothing (or too little) before the first-byte deadline. */
    FIRST_BYTE_TIMEOUT,
    /** SOCKS negotiation failed (bad version, no acceptable auth method, bad credentials, ...). */
    SOCKS_HANDSHAKE_FAILED,
    /** SOCKS5 `UDP ASSOCIATE` (or `BIND`) requested; UDP policy routing lands in S1 task 9. */
    SOCKS_COMMAND_UNSUPPORTED,
    /** HTTP request line / headers could not be parsed. */
    HTTP_MALFORMED,
    /** Transparent TLS without SNI and no original-destination lookup available. */
    TRANSPARENT_NO_DESTINATION,
    /** The gateway is shutting down / the client disconnected mid-handshake. */
    CLIENT_CLOSED,
}

/**
 * A resolved, ready-to-handle client session. [socket]'s input stream replays every byte the
 * gateway consumed while classifying that still belongs to the application protocol (e.g. the
 * full absolute-form request for [EntryKind.HTTP_ABSOLUTE], the TLS ClientHello for
 * [EntryKind.TRANSPARENT_TLS]); protocol-negotiation bytes (SOCKS handshake, `CONNECT` head) are
 * consumed and NOT replayed.
 *
 * Handlers must answer through [replyEstablished] / [replyBlocked] / [replyUnreachable] rather
 * than writing status lines themselves -- the correct wire reply depends on [entry].
 */
class GatewaySession(
    val socket: Socket,
    val entry: EntryKind,
    val destination: Destination,
    /** Real source tuple when the bridge supplied `+info`; `null` for every other entry kind. */
    val origin: ClientOrigin?,
    /** The raw target token as the client wrote it (`host:port` for CONNECT, URL for absolute). */
    val rawTarget: String = destination.toString(),
    private val replyWriter: ReplyWriter = ReplyWriter.forEntry(entry),
) {
    private var replied = false

    val output: OutputStream get() = socket.getOutputStream()

    /** Tell the client the tunnel is open (HTTP `200`, SOCKS success, or nothing for transparent). */
    fun replyEstablished() = replyOnce { replyWriter.established(output, this) }

    /** Tell the client the connection was refused by policy. */
    fun replyBlocked() = replyOnce { replyWriter.blocked(output, this) }

    /** Tell the client the upstream could not be reached / the gateway failed. */
    fun replyUnreachable() = replyOnce { replyWriter.unreachable(output, this) }

    /** Tell the client that MITM was required by rule but this tier cannot terminate TLS. */
    fun replyMitmUnavailable() = replyOnce { replyWriter.mitmUnavailable(output, this) }

    private inline fun replyOnce(block: () -> Unit) {
        if (replied) return
        replied = true
        block()
        output.flush()
    }

    /** Per-[EntryKind] wire encoding of the four gateway replies. */
    interface ReplyWriter {
        fun established(out: OutputStream, session: GatewaySession)
        fun blocked(out: OutputStream, session: GatewaySession)
        fun unreachable(out: OutputStream, session: GatewaySession)
        fun mitmUnavailable(out: OutputStream, session: GatewaySession)

        companion object {
            fun forEntry(entry: EntryKind): ReplyWriter = when (entry) {
                EntryKind.HTTP_CONNECT -> HttpConnectReplyWriter
                EntryKind.SOCKS5 -> Socks5ReplyWriter
                EntryKind.SOCKS4 -> Socks4ReplyWriter
                EntryKind.HTTP_ABSOLUTE, EntryKind.TRANSPARENT_TLS, EntryKind.TRANSPARENT_HTTP -> SilentReplyWriter
            }
        }
    }

    /** Legacy `CONNECT` status lines -- byte-identical to what `MitmProxyEngine` used to write. */
    object HttpConnectReplyWriter : ReplyWriter {
        override fun established(out: OutputStream, session: GatewaySession) =
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        override fun blocked(out: OutputStream, session: GatewaySession) =
            out.write("HTTP/1.1 403 Blocked by requestr\r\n\r\n".toByteArray())
        override fun unreachable(out: OutputStream, session: GatewaySession) =
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
        override fun mitmUnavailable(out: OutputStream, session: GatewaySession) =
            out.write("HTTP/1.1 403 MITM required but not available\r\n\r\n".toByteArray())
    }

    /** RFC 1928 §6 replies. BND.ADDR/BND.PORT are zero: the gateway is a MITM, not a NAT. */
    object Socks5ReplyWriter : ReplyWriter {
        override fun established(out: OutputStream, session: GatewaySession) =
            out.write(Socks5Handler.reply(Socks5Handler.REP_SUCCEEDED))
        override fun blocked(out: OutputStream, session: GatewaySession) =
            out.write(Socks5Handler.reply(Socks5Handler.REP_NOT_ALLOWED_BY_RULESET))
        override fun unreachable(out: OutputStream, session: GatewaySession) =
            out.write(Socks5Handler.reply(Socks5Handler.REP_HOST_UNREACHABLE))
        override fun mitmUnavailable(out: OutputStream, session: GatewaySession) =
            out.write(Socks5Handler.reply(Socks5Handler.REP_NOT_ALLOWED_BY_RULESET))
    }

    /** SOCKS4 replies: `0x00 0x5A` granted, `0x00 0x5B` rejected. */
    object Socks4ReplyWriter : ReplyWriter {
        override fun established(out: OutputStream, session: GatewaySession) =
            out.write(Socks4Handler.reply(Socks4Handler.CD_GRANTED))
        override fun blocked(out: OutputStream, session: GatewaySession) =
            out.write(Socks4Handler.reply(Socks4Handler.CD_REJECTED))
        override fun unreachable(out: OutputStream, session: GatewaySession) =
            out.write(Socks4Handler.reply(Socks4Handler.CD_REJECTED))
        override fun mitmUnavailable(out: OutputStream, session: GatewaySession) =
            out.write(Socks4Handler.reply(Socks4Handler.CD_REJECTED))
    }

    /**
     * Transparent and absolute-form entries have no tunnel-establishment phase: the application
     * bytes are already flowing, so "established" is a no-op and refusals simply close.
     */
    object SilentReplyWriter : ReplyWriter {
        override fun established(out: OutputStream, session: GatewaySession) = Unit
        override fun blocked(out: OutputStream, session: GatewaySession) = Unit
        override fun unreachable(out: OutputStream, session: GatewaySession) = Unit
        override fun mitmUnavailable(out: OutputStream, session: GatewaySession) = Unit
    }
}
