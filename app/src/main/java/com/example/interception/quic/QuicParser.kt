package com.example.interception.quic

import android.util.Log
import com.example.interception.tls.TlsDecoder
import java.io.ByteArrayOutputStream

/**
 * QUIC/HTTP/3 Initial-packet parser.
 *
 * v5.6 (P2): [parseInitial] now does real work, not just an SNI heuristic. For QUIC v1/v2
 * Initial packets it derives the public Initial keys ([QuicCrypto.deriveClientInitialKeys]),
 * removes header protection and AEAD-decrypts the packet ([QuicCrypto.decryptInitialPacket]),
 * walks the resulting QUIC frames to pull out the CRYPTO frame's TLS ClientHello bytes, and
 * hands those to [TlsDecoder.parseClientHello] (via a synthesized 5-byte TLS record header —
 * the decrypted CRYPTO frame payload *is* a standard TLS Handshake body) so the real cipher
 * list, extensions, ALPN and SNI become visible, matching what a MITM proxy sees for TCP/TLS.
 *
 * [extractSni] is kept for backward compatibility (and is still what [parseInitial] falls back
 * to for unsupported versions — e.g. Google QUIC Q043/Q046/Q050 have no TLS 1.3 Initial
 * protection scheme — or when decryption fails for any reason: truncated capture, coalesced
 * packet boundary mismatch, tampered bytes). It is a plaintext byte-scan for the ClientHello
 * type byte and therefore only ever recovers the SNI, never the ALPN (which lives in a
 * different extension that the heuristic scan does not attempt to locate reliably without a
 * real extension walk).
 */
object QuicParser {

    private const val TAG = "QuicParser"

    data class QuicInitial(
        val version: Int,
        val dcid: ByteArray,
        val scid: ByteArray,
        val sniHostname: String?,
        val alpn: String?,
        /**
         * True when [sniHostname]/[alpn] came from a real AEAD-decrypted ClientHello (RFC 9001
         * Initial-key decryption via [QuicCrypto]). False when they came from the older
         * heuristic byte-scan fallback ([extractSni]) — unsupported/gQUIC version, or the
         * decrypt path failed for any reason.
         */
        val decrypted: Boolean,
        /**
         * The ClientHello's 32-byte `random` when (and only when) the Initial was really decrypted —
         * the TLS-key-log identity for this connection (see
         * [com.example.interception.tls.TlsClientHello.clientRandom]). Null for the heuristic
         * fallback, which never sees a structurally-located random.
         */
        val clientRandom: ByteArray? = null,
    )

    /** Long-header packet types (RFC 9000 §17.2, bits 4-5 of the first byte). */
    private const val PACKET_TYPE_INITIAL = 0

    /** Known QUIC version magic numbers (kept in sync with [QuicCrypto]'s v1/v2 constants). */
    private val KNOWN_VERSIONS = setOf(
        QuicCrypto.VERSION_1,
        QuicCrypto.VERSION_2,
        0x51303433, // Google QUIC Q043
        0x51303436, // Google QUIC Q046
        0x51303530, // Google QUIC Q050
    )

    private fun isQuicVersion(version: Int): Boolean = version in KNOWN_VERSIONS || version == 0

    /**
     * Extracts SNI from a QUIC Initial packet (UDP payload on port 443).
     * Returns null if the packet doesn't look like a QUIC Initial.
     *
     * Kept as the lightweight heuristic entry point used by call sites that only need the
     * hostname; [parseInitial] is the fuller decode (real decrypt + ALPN) and falls back to
     * the same scan internally when decryption isn't possible.
     */
    fun extractSni(packet: ByteArray): String? {
        if (packet.size < 7) return null
        val firstByte = packet[0].toInt() and 0xFF
        if ((firstByte and 0x80) == 0) return null

        val version = readVersion(packet)
        if (!isQuicVersion(version)) return null

        var offset = 5
        if (packet.size <= offset) return null
        val dcidLen = packet[offset].toInt() and 0xFF
        offset += 1 + dcidLen
        if (packet.size <= offset) return null

        val scidLen = packet[offset].toInt() and 0xFF
        offset += 1 + scidLen
        if (packet.size <= offset) return null

        return scanForClientHelloSni(packet, offset)
    }

    /**
     * Full Initial-packet parse (v5.6 / P2): decrypts real QUIC v1/v2 Initial packets and
     * returns the connection identity plus a genuinely decoded SNI + ALPN, falling back to the
     * plaintext heuristic scan (SNI only) when decryption isn't applicable or fails.
     */
    fun parseInitial(packet: ByteArray): QuicInitial? {
        if (packet.size < 7) return null
        val firstByte = packet[0].toInt() and 0xFF
        if ((firstByte and 0x80) == 0) return null // not a long-header packet

        val packetType = (firstByte shr 4) and 0x3
        val version = readVersion(packet)
        if (!isQuicVersion(version)) return null

        var offset = 5
        if (packet.size <= offset) return null
        val dcidLen = packet[offset].toInt() and 0xFF
        offset += 1
        if (offset + dcidLen > packet.size) return null
        val dcid = packet.copyOfRange(offset, offset + dcidLen)
        offset += dcidLen
        if (packet.size <= offset) return null

        val scidLen = packet[offset].toInt() and 0xFF
        offset += 1
        if (offset + scidLen > packet.size) return null
        val scid = packet.copyOfRange(offset, offset + scidLen)
        offset += scidLen

        var alpn: String? = null
        var sni: String? = null
        var decrypted = false
        var clientRandom: ByteArray? = null

        // Only real Initial packets (type 0b00) of a version we know an Initial-key salt for
        // carry a Token field + Length field we can parse to locate the AEAD ciphertext.
        if (packetType == PACKET_TYPE_INITIAL && version != 0) {
            try {
                val tokenLenVarint = readVarInt(packet, offset)
                if (tokenLenVarint != null) {
                    val (tokenLen, tokenLenSize) = tokenLenVarint
                    var pos = offset + tokenLenSize + tokenLen.toInt()
                    if (pos in 0..packet.size) {
                        val lengthVarint = readVarInt(packet, pos)
                        if (lengthVarint != null) {
                            val (payloadLen, lengthSize) = lengthVarint
                            val pnOffset = pos + lengthSize
                            val payloadEnd = pnOffset + payloadLen.toInt()
                            if (payloadEnd in (pnOffset + 1)..packet.size) {
                                val keys = QuicCrypto.deriveClientInitialKeys(version, dcid)
                                val result = keys?.let { QuicCrypto.decryptInitialPacket(packet, pnOffset, payloadEnd, it) }
                                val cryptoFrameData = result?.let { extractCryptoFrameData(it.cryptoData) }
                                val chello = cryptoFrameData?.let { parseDecryptedClientHello(it) }
                                if (chello != null) {
                                    decrypted = true
                                    sni = chello.sni
                                    alpn = chello.alpn
                                    clientRandom = chello.clientRandom
                                    Log.d(
                                        TAG,
                                        "Decrypted QUIC Initial pn=${result?.packetNumber} " +
                                            "cipherSuites=${chello.cipherSuites.size} alpn=$alpn",
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Malformed/truncated header fields -- fall back to the heuristic scan below,
                // exactly like a decrypt failure. Never crash the packet pump on bad input.
                Log.d(TAG, "Initial header parse failed, falling back to heuristic scan: ${e.message}")
            }
        }

        if (sni == null) {
            sni = scanForClientHelloSni(packet, offset)
        }

        if (sni == null && alpn == null && !decrypted) return null

        return QuicInitial(
            version = version,
            dcid = dcid,
            scid = scid,
            sniHostname = sni,
            alpn = alpn,
            decrypted = decrypted,
            clientRandom = clientRandom,
        )
    }

    private fun readVersion(packet: ByteArray): Int =
        ((packet[1].toInt() and 0xFF) shl 24) or
            ((packet[2].toInt() and 0xFF) shl 16) or
            ((packet[3].toInt() and 0xFF) shl 8) or
            (packet[4].toInt() and 0xFF)

    /**
     * Walks the decrypted Initial payload's QUIC frames (RFC 9000 §12.4) looking for CRYPTO
     * frames (type 0x06) and concatenates their data. PADDING (0x00) and PING (0x01) frames are
     * skipped. Any other frame type (ACK, etc. -- which a client's very first Initial will not
     * legitimately contain, since it has nothing to acknowledge yet) stops the walk and returns
     * whatever CRYPTO data was already collected, rather than attempting a full parse of frame
     * types this layer doesn't need.
     */
    private fun extractCryptoFrameData(frames: ByteArray): ByteArray? {
        var pos = 0
        var found = false
        val out = ByteArrayOutputStream()
        while (pos < frames.size) {
            val type = frames[pos].toInt() and 0xFF
            when (type) {
                0x00, 0x01 -> pos += 1 // PADDING, PING
                0x06 -> {
                    val offsetVarint = readVarInt(frames, pos + 1) ?: return if (found) out.toByteArray() else null
                    val lengthVarint = readVarInt(frames, pos + 1 + offsetVarint.second)
                        ?: return if (found) out.toByteArray() else null
                    val dataStart = pos + 1 + offsetVarint.second + lengthVarint.second
                    val dataLen = lengthVarint.first.toInt()
                    if (dataLen < 0 || dataStart + dataLen > frames.size) return if (found) out.toByteArray() else null
                    out.write(frames, dataStart, dataLen)
                    found = true
                    pos = dataStart + dataLen
                }
                else -> return if (found) out.toByteArray() else null
            }
        }
        return if (found) out.toByteArray() else null
    }

    /**
     * QUIC variable-length integer (RFC 9000 §16). Returns (value, bytesConsumed) or null if
     * out of bounds.
     */
    private fun readVarInt(data: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset < 0 || offset >= data.size) return null
        val firstByte = data[offset].toInt() and 0xFF
        val length = 1 shl ((firstByte and 0xC0) shr 6)
        if (offset + length > data.size) return null
        var value = (firstByte and 0x3F).toLong()
        for (i in 1 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value to length
    }

    /**
     * The decrypted CRYPTO frame data is a raw TLS Handshake message (type + 3-byte length +
     * body) with no TLS record header. [TlsDecoder.parseClientHello] expects a full 5-byte
     * record header in front of that, so we synthesize one (content-type=Handshake, a record
     * version inside the valid range -- its value doesn't affect ClientHello parsing, only the
     * ClientHello's own internal `client_version` field, read separately, does) rather than
     * duplicating [TlsDecoder]'s ~150 lines of extension-walking logic here.
     */
    private fun parseDecryptedClientHello(handshakeBody: ByteArray): com.example.interception.tls.TlsClientHello? {
        val synthetic = ByteArray(5 + handshakeBody.size)
        synthetic[0] = 22 // TLS record content-type: Handshake
        synthetic[1] = 0x03
        synthetic[2] = 0x03 // record version 0x0303 (TLS 1.2) -- placeholder, see doc above
        synthetic[3] = ((handshakeBody.size shr 8) and 0xFF).toByte()
        synthetic[4] = (handshakeBody.size and 0xFF).toByte()
        System.arraycopy(handshakeBody, 0, synthetic, 5, handshakeBody.size)
        return TlsDecoder.parseClientHello(synthetic)
    }

    /**
     * Heuristic fallback: scans forward from [offset] for the TLS ClientHello handshake-type
     * byte and, if found, extracts just the SNI extension. Used when we can't or didn't decrypt
     * (unsupported version, decrypt failure) -- this is the original P1-era approach, unchanged.
     */
    private fun scanForClientHelloSni(packet: ByteArray, offset: Int): String? {
        for (i in offset until packet.size - 3) {
            if (packet[i].toInt() and 0xFF == 0x01) {
                if (i + 6 >= packet.size) break
                val helloLen = ((packet[i + 1].toInt() and 0xFF) shl 16) or
                    ((packet[i + 2].toInt() and 0xFF) shl 8) or
                    (packet[i + 3].toInt() and 0xFF)
                if (helloLen > 0 && i + 4 + helloLen <= packet.size) {
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
