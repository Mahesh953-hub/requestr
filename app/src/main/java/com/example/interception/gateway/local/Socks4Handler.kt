package com.example.interception.gateway.local

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * SOCKS4 / SOCKS4a `CONNECT` (`VN=4 CD=1 DSTPORT DSTIP USERID NUL [HOSTNAME NUL]`). SOCKS4a is
 * detected by the `0.0.0.x` destination IP, in which case the trailing hostname is used.
 * `BIND` is refused with `0x5B`.
 */
class Socks4Handler {
    sealed class Outcome {
        data class Connect(val destination: Destination, val userId: String) : Outcome()
        data class Rejected(val reason: GatewayRejectReason, val detail: String) : Outcome()
    }

    fun negotiate(input: InputStream, output: OutputStream): Outcome {
        val din = DataInputStream(input)
        return try {
            negotiateInner(din, output)
        } catch (e: EOFException) {
            Outcome.Rejected(GatewayRejectReason.CLIENT_CLOSED, "client closed during SOCKS4 negotiation")
        }
    }

    private fun negotiateInner(din: DataInputStream, out: OutputStream): Outcome {
        val vn = din.readUnsignedByte()
        if (vn != VERSION) {
            return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "bad SOCKS4 version $vn")
        }
        val cd = din.readUnsignedByte()
        val port = din.readUnsignedShort()
        val ip = ByteArray(4).also(din::readFully)
        val userId = readNulTerminated(din)
        val isSocks4a = ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()
        val host = if (isSocks4a) readNulTerminated(din) else InetAddress.getByAddress(ip).hostAddress
        if (cd != CMD_CONNECT) {
            out.write(reply(CD_REJECTED)); out.flush()
            return Outcome.Rejected(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, "SOCKS4 command $cd not supported")
        }
        if (host.isBlank() || port == 0) {
            out.write(reply(CD_REJECTED)); out.flush()
            return Outcome.Rejected(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, "empty destination")
        }
        return Outcome.Connect(Destination(host, port), userId)
    }

    private fun readNulTerminated(din: DataInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = din.readUnsignedByte()
            if (b == 0) break
            if (buf.size() > MAX_FIELD) throw EOFException("SOCKS4 field too long")
            buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val VERSION = 0x04
        const val CMD_CONNECT = 0x01
        const val CD_GRANTED = 0x5A
        const val CD_REJECTED = 0x5B
        private const val MAX_FIELD = 512

        /** `VN=0 CD DSTPORT=0 DSTIP=0.0.0.0` (8 bytes). */
        fun reply(cd: Int): ByteArray = byteArrayOf(0x00, cd.toByte(), 0, 0, 0, 0, 0, 0)
    }
}
