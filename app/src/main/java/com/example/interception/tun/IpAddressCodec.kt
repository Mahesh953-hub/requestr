package com.example.interception.tun

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Address-string <-> byte-array codec for both IP families.
 *
 * The existing parsers ([com.example.interception.PacketPump.parseIpv6],
 * [com.example.interception.tcp.TcpReassembler.feedIpv6]) build IPv6 addresses as a bare
 * 32-hex-char string (`packet.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }`) that
 * [InetAddress.getByName] cannot parse. [formatIpv6] reproduces that exact string byte-for-byte
 * so existing flow keys and [com.example.data.local.TcpConnectionEntity.connectionId] values
 * already in users' databases stay stable. [toInetAddress] sidesteps the unparseable-string
 * problem entirely by going through [InetAddress.getByAddress], which takes raw bytes.
 */
object IpAddressCodec {

    fun formatIpv4(bytes: ByteArray, offset: Int): String =
        "${bytes[offset].u()}.${bytes[offset + 1].u()}.${bytes[offset + 2].u()}.${bytes[offset + 3].u()}"

    /** Reproduces the existing bare 32-hex-char IPv6 address form, byte-for-byte. */
    fun formatIpv6(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder(32)
        for (i in 0 until 16) sb.append("%02x".format(bytes[offset + i]))
        return sb.toString()
    }

    fun parseIpv4(address: String): ByteArray {
        val parts = address.split(".")
        require(parts.size == 4) { "Not a dotted-quad IPv4 address: $address" }
        return ByteArray(4) { i -> parts[i].toInt().toByte() }
    }

    /** Accepts the existing bare 32-hex-char form. */
    fun parseIpv6(address: String): ByteArray {
        require(address.length == 32) { "Not a 32-hex-char IPv6 address: $address" }
        return ByteArray(16) { i ->
            val hi = Character.digit(address[i * 2], 16)
            val lo = Character.digit(address[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex in IPv6 address: $address" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun toInetAddress(address: String, family: Int): InetAddress = when (family) {
        4 -> InetAddress.getByAddress(parseIpv4(address))
        6 -> InetAddress.getByAddress(parseIpv6(address))
        else -> throw IllegalArgumentException("Unknown address family: $family")
    }

    fun bytesOf(inetAddress: InetAddress): ByteArray = inetAddress.address

    fun familyOf(inetAddress: InetAddress): Int = when (inetAddress) {
        is Inet4Address -> 4
        is Inet6Address -> 6
        else -> throw IllegalArgumentException("Unknown InetAddress subtype: ${inetAddress.javaClass}")
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF
}
