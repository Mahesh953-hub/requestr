package com.example.interception.gateway.local

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/** Username/password the gateway requires from SOCKS5 clients (RFC 1929). */
data class Socks5Credentials(val username: String, val password: String) {
    companion object {
        /** Fresh per-session secret so no other local app can drive the gateway's SOCKS side. */
        fun random(random: java.security.SecureRandom = java.security.SecureRandom()): Socks5Credentials {
            val bytes = ByteArray(24).also(random::nextBytes)
            val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return Socks5Credentials(username = "requestr", password = secret)
        }
    }
}

/**
 * RFC 1928 SOCKS5 (+ RFC 1929 user/pass) negotiation over blocking streams. `CONNECT` yields
 * [Outcome.Connect]; `UDP ASSOCIATE` (S1 task 9) yields [Outcome.UdpAssociate] with the reply
 * deferred to the caller, which either serves the association through
 * [com.example.interception.quic.UdpPolicyRouter] or answers `0x07`. `BIND` is answered with
 * `0x07 Command not supported` and surfaced as [Outcome.Rejected] with
 * [GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED] so the caller records *why* rather than
 * silently dropping.
 *
 * The vendored tun2proxy encodes the originating flow into the username as
 * `"<base>|<tcp|udp>|<src_ip>|<src_port>"` when the configured username ends with `+info`; that
 * suffix is decoded here into [ClientOrigin] and the credential check runs against `<base>`.
 */
class Socks5Handler(
    private val credentials: Socks5Credentials? = null,
) {
    sealed class Outcome {
        data class Connect(val destination: Destination, val origin: ClientOrigin?) : Outcome()
        /**
         * `UDP ASSOCIATE` request (RFC 1928 §7). [requested] is the client's DST.ADDR/DST.PORT hint
         * (the vendored tun2proxy sends the unspecified address). No reply has been written yet:
         * the caller must answer with the relay's BND.ADDR/BND.PORT or a failure code.
         */
        data class UdpAssociate(val requested: Destination, val origin: ClientOrigin?) : Outcome()
        /** Negotiation failed; the wire reply (if any) has already been written. */
        data class Rejected(val reason: GatewayRejectReason, val detail: String) : Outcome()
    }

    fun negotiate(input: InputStream, output: OutputStream): Outcome {
        val din = DataInputStream(input)
        return try {
            negotiateInner(din, output)
        } catch (e: EOFException) {
            Outcome.Rejected(GatewayRejectReason.CLIENT_CLOSED, "client closed during SOCKS5 negotiation")
        }
    }

    private fun negotiateInner(din: DataInputStream, out: OutputStream): Outcome {
        // --- Greeting -----------------------------------------------------------------------
        val ver = din.readUnsignedByte()
        if (ver != VERSION) {
            return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "bad SOCKS version $ver")
        }
        val nMethods = din.readUnsignedByte()
        val methods = ByteArray(nMethods).also(din::readFully).map { it.toInt() and 0xFF }.toSet()
        val chosen = when {
            credentials != null && METHOD_USER_PASS in methods -> METHOD_USER_PASS
            credentials != null -> METHOD_NO_ACCEPTABLE
            METHOD_NO_AUTH in methods -> METHOD_NO_AUTH
            METHOD_USER_PASS in methods -> METHOD_USER_PASS // accept for +info even without creds
            else -> METHOD_NO_ACCEPTABLE
        }
        out.write(byteArrayOf(VERSION.toByte(), chosen.toByte()))
        out.flush()
        if (chosen == METHOD_NO_ACCEPTABLE) {
            return Outcome.Rejected(
                GatewayRejectReason.SOCKS_HANDSHAKE_FAILED,
                "no acceptable auth method (offered=${methods.joinToString()}, requireAuth=${credentials != null})",
            )
        }

        // --- RFC 1929 sub-negotiation ---------------------------------------------------------
        var origin: ClientOrigin? = null
        if (chosen == METHOD_USER_PASS) {
            val subVer = din.readUnsignedByte()
            if (subVer != USER_PASS_VERSION) {
                out.write(byteArrayOf(USER_PASS_VERSION.toByte(), AUTH_FAILURE.toByte())); out.flush()
                return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "bad user/pass sub-version $subVer")
            }
            val username = readLengthPrefixed(din)
            val password = readLengthPrefixed(din)
            val parsed = ClientOrigin.parseInfoUsername(username)
            val base = parsed?.base ?: username
            origin = parsed?.origin
            val ok = credentials == null || (credentials.username == base && credentials.password == password)
            out.write(byteArrayOf(USER_PASS_VERSION.toByte(), (if (ok) AUTH_SUCCESS else AUTH_FAILURE).toByte()))
            out.flush()
            if (!ok) {
                return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "SOCKS5 credentials rejected")
            }
        }

        // --- Request ------------------------------------------------------------------------
        val reqVer = din.readUnsignedByte()
        if (reqVer != VERSION) {
            out.write(reply(REP_GENERAL_FAILURE)); out.flush()
            return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "bad request version $reqVer")
        }
        val cmd = din.readUnsignedByte()
        din.readUnsignedByte() // RSV
        val atyp = din.readUnsignedByte()
        val host = when (atyp) {
            ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(din::readFully)).hostAddress
            ATYP_DOMAIN -> readLengthPrefixed(din)
            ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(din::readFully)).hostAddress
            else -> {
                out.write(reply(REP_ADDRESS_TYPE_NOT_SUPPORTED)); out.flush()
                return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "unsupported ATYP $atyp")
            }
        }
        val port = din.readUnsignedShort()
        if (cmd == CMD_UDP_ASSOCIATE) {
            return Outcome.UdpAssociate(Destination(host, port), origin)
        }
        if (cmd != CMD_CONNECT) {
            out.write(reply(REP_COMMAND_NOT_SUPPORTED)); out.flush()
            val name = when (cmd) { CMD_BIND -> "BIND"; else -> "0x%02x".format(cmd) }
            return Outcome.Rejected(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, "SOCKS5 $name not supported")
        }
        if (host.isBlank() || port == 0) {
            out.write(reply(REP_GENERAL_FAILURE)); out.flush()
            return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "empty destination")
        }
        // The success/failure reply is deferred to GatewaySession.reply*() so ConnectControl can
        // decide first (a blocked destination must be answered with 0x02, not 0x00).
        return Outcome.Connect(Destination(host, port), origin)
    }

    private fun readLengthPrefixed(din: DataInputStream): String {
        val len = din.readUnsignedByte()
        return String(ByteArray(len).also(din::readFully), Charsets.UTF_8)
    }

    companion object {
        const val VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val METHOD_USER_PASS = 0x02
        const val METHOD_NO_ACCEPTABLE = 0xFF
        const val USER_PASS_VERSION = 0x01
        const val AUTH_SUCCESS = 0x00
        const val AUTH_FAILURE = 0x01
        const val CMD_CONNECT = 0x01
        const val CMD_BIND = 0x02
        const val CMD_UDP_ASSOCIATE = 0x03
        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAIN = 0x03
        const val ATYP_IPV6 = 0x04
        const val REP_SUCCEEDED = 0x00
        const val REP_GENERAL_FAILURE = 0x01
        const val REP_NOT_ALLOWED_BY_RULESET = 0x02
        const val REP_HOST_UNREACHABLE = 0x04
        const val REP_COMMAND_NOT_SUPPORTED = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

        /** `VER REP RSV ATYP=IPv4 BND.ADDR=0.0.0.0 BND.PORT=0` (10 bytes). */
        fun reply(rep: Int): ByteArray =
            byteArrayOf(VERSION.toByte(), rep.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

        /** `VER REP=0 RSV ATYP BND.ADDR BND.PORT` announcing where a UDP relay listens. */
        fun replyBound(address: InetAddress, port: Int): ByteArray {
            val raw = address.address
            val atyp = if (raw.size == 16) ATYP_IPV6 else ATYP_IPV4
            return byteArrayOf(VERSION.toByte(), REP_SUCCEEDED.toByte(), 0x00, atyp.toByte()) + raw +
                byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
        }
    }
}
