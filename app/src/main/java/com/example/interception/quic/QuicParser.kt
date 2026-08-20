package com.example.interception.quic

import android.util.Log

/**
 * QUIC/HTTP/3 SNI extractor.
 *
 * QUIC uses TLS 1.3 ClientHello embedded in the Initial packet.
 * The SNI extension is visible in plaintext (before encryption is established),
 * so we can extract the hostname without MITM.
 *
 * We don't attempt to decrypt the QUIC stream — just read the SNI from
 * the Initial packet and log it.
 */
object QuicParser {

    private const val TAG = "QuicParser"

    data class QuicInitial(
        val version: UInt,
        val dcid: ByteArray,
        val scid: ByteArray,
        val sniHostname: String?,
    )

    /**
     * Extracts SNI from a QUIC Initial packet (UDP payload on port 443).
     * Returns null if the packet doesn't look like a QUIC Initial.
     */
    fun extractSni(packet: ByteArray): String? {
        // QUIC Initial packets start with a 1 byte header form field
        // The packet is: [Header Form:1][Fixed Bit:1][Version:32][DCID Len:8][DCID][SCID Len:8][SCID]
        // For Initial: Type=00, Long Header, Version must be valid QUIC version

        if (packet.size < 7) return null

        // Check for QUIC long header: first byte should have high bits set
        val firstByte = packet[0].toInt() and 0xFF
        val isLongHeader = (firstByte and 0x80) != 0
        if (!isLongHeader) return null

        // Version must be non-zero for long header packets
        val version = ((packet[1].toInt() and 0xFF) shl 24) or
                ((packet[2].toInt() and 0xFF) shl 16) or
                ((packet[3].toInt() and 0xFF) shl 8) or
                (packet[4].toInt() and 0xFF)

        // Known QUIC versions: 0x00000001, 0x709a50c4 (v2), etc.
        // Also check for Google QUIC versions
        val isQuicVersion = version in listOf(
            0x00000001.toInt(),  // QUIC v1
            0x6b3343cf.toInt(),  // QUIC v2
            0x51303433.toInt(),  // Google QUIC Q043
            0x51303436.toInt(),  // Google QUIC Q046
            0x51303530.toInt(),  // Google QUIC Q050
        ) || (version == 0)

        if (!isQuicVersion) return null

        // Parse DCID length and skip
        var offset = 5
        if (packet.size <= offset) return null
        val dcidLen = packet[offset].toInt() and 0xFF
        offset += 1 + dcidLen
        if (packet.size <= offset) return null

        // Parse SCID length and skip
        val scidLen = packet[offset].toInt() and 0xFF
        offset += 1 + scidLen
        if (packet.size <= offset) return null

        // For Initial packets, the TLS ClientHello is inside the CRYPTO frame
        // The CRYPTO frame starts after the packet number. The Initial packet
        // contains a PADDING frame, then the CRYPTO frame with the ClientHello.
        // We look for the TLS handshake type byte (0x01 = ClientHello) in the payload.

        // Scan for TLS ClientHello within the packet
        for (i in offset until packet.size - 3) {
            if (packet[i].toInt() and 0xFF == 0x01) {
                // Possible ClientHello start: type(1) + length(3) + version(2) + random(32)
                if (i + 6 >= packet.size) break
                val helloLen = ((packet[i + 1].toInt() and 0xFF) shl 16) or
                        ((packet[i + 2].toInt() and 0xFF) shl 8) or
                        (packet[i + 3].toInt() and 0xFF)
                if (helloLen > 0 && i + 4 + helloLen <= packet.size) {
                    // Try to extract SNI from the TLS extension block
                    return extractSniFromTls(packet, i + 4)
                }
            }
        }
        return null
    }

    /**
     * Extracts the SNI extension value from a TLS ClientHello.
     */
    private fun extractSniFromTls(data: ByteArray, offset: Int): String? {
        // Skip: version(2) + random(32) = 34 bytes
        var pos = offset + 34
        if (pos >= data.size) return null

        // Session ID length
        val sessionIdLen = data[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos >= data.size) return null

        // Cipher suites length
        val csLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2 + csLen
        if (pos >= data.size) return null

        // Compression methods length
        val compLen = data[pos].toInt() and 0xFF
        pos += 1 + compLen
        if (pos >= data.size) return null

        // Extensions length
        if (pos + 2 > data.size) return null
        val extLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2

        // Iterate extensions looking for SNI (type 0)
        val extEnd = pos + extLen
        while (pos + 4 <= extEnd && pos + 4 <= data.size) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extDataLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4

            if (extType == 0 && extDataLen >= 5) {
                // SNI extension: list_length(2) + type(1) + name_length(2) + name
                if (pos + 3 + (data[pos + 3].toInt() and 0xFF) <= data.size) {
                    val nameLen = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                    if (pos + 5 + nameLen <= data.size) {
                        return String(data, pos + 5, nameLen, Charsets.UTF_8)
                    }
                }
            }
            pos += extDataLen
        }
        return null
    }
}
