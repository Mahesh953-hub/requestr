package com.example.interception.quic

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9001 §5.2 QUIC Initial packet protection — key derivation, header-protection removal,
 * and AEAD decryption.
 *
 * This is **not** a MITM or a secrecy break: QUIC Initial packets are, by design, protected
 * with keys derived *only* from public information (the QUIC version and the client's
 * Destination Connection ID, which is sent in the clear in every Initial packet). RFC 9001
 * §5.2 states this protection exists purely "to encumber decryption by observers", not to
 * provide confidentiality — decrypting an Initial packet is exactly as available to a passive
 * network observer as it is to either endpoint. We use this ability the same way tools like
 * Wireshark's QUIC dissector do: to recover the plaintext CRYPTO frame (the TLS ClientHello)
 * so its real fields (cipher list, extensions, ALPN, SNI) become visible.
 *
 * We deliberately do NOT attempt to decrypt anything beyond the Initial packet — Handshake
 * and 1-RTT keys are derived from the TLS key schedule (ECDHE shared secret), which requires
 * the private key material of one endpoint. Those packets are tracked at the framing level
 * only (packet number, length, type) without payload visibility, matching how [PacketPump]
 * already treats undecodable TLS bytes.
 */
object QuicCrypto {

    private const val TAG = "QuicCrypto"

    /** RFC 9001 §5.2, QUIC version 1. */
    private val SALT_V1 = hexToBytes("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

    /** RFC 9369 §3.3.1, QUIC version 2. */
    private val SALT_V2 = hexToBytes("0dede3def700a6db819381be6e269dcbf9bd2ed9")

    /** Known QUIC version magic numbers (mirrors [QuicParser]'s version whitelist). */
    const val VERSION_1 = 0x00000001
    const val VERSION_2 = 0x6b3343cf.toInt()

    data class InitialKeys(
        val key: ByteArray,
        val iv: ByteArray,
        val hp: ByteArray,
    )

    data class DecryptedInitial(
        /** Reassembled plaintext of all CRYPTO-frame payload bytes found in the packet. */
        val cryptoData: ByteArray,
        val packetNumber: Long,
    )

    /**
     * Derives the client's Initial {key, iv, hp} per RFC 9001 §5.2/RFC 9369 §3.3.2, keyed off
     * the client-chosen Destination Connection ID (public, sent in cleartext on the wire).
     * Returns null for unrecognized versions (Google QUIC / gQUIC has no equivalent TLS 1.3
     * Initial protection scheme, so those are left SNI-only via [QuicParser.extractSni]).
     */
    fun deriveClientInitialKeys(version: Int, dcid: ByteArray): InitialKeys? {
        val salt = when (version) {
            VERSION_1, 0 -> SALT_V1 // draft/negotiation packets commonly reuse the v1 salt
            VERSION_2 -> SALT_V2
            else -> return null
        }
        return try {
            val initialSecret = hkdfExtract(salt, dcid)
            val clientSecret = hkdfExpandLabel(initialSecret, "client in", 32)
            val keyLabel = if (version == VERSION_2) "quicv2 key" else "quic key"
            val ivLabel = if (version == VERSION_2) "quicv2 iv" else "quic iv"
            val hpLabel = if (version == VERSION_2) "quicv2 hp" else "quic hp"
            InitialKeys(
                key = hkdfExpandLabel(clientSecret, keyLabel, 16),
                iv = hkdfExpandLabel(clientSecret, ivLabel, 12),
                hp = hkdfExpandLabel(clientSecret, hpLabel, 16),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Initial key derivation failed: ${e.message}")
            null
        }
    }

    /**
     * Removes AES-based header protection (RFC 9001 §5.4.3) from a long-header Initial packet
     * and AES-128-GCM-decrypts its payload, returning the plaintext frame bytes (PADDING +
     * CRYPTO frames typically). [pnOffset] is the offset of the (still-protected) Packet Number
     * field; [payloadEnd] is the offset one past the end of the packet (end of UDP datagram
     * payload for a single-packet datagram, which is the common case for a ClientHello-sized
     * Initial).
     *
     * Returns null if header-protection removal or AEAD decryption fails (wrong keys, corrupt
     * packet, coalesced packet with an unexpected boundary, etc.) — callers fall back to the
     * existing SNI-scan heuristic in that case, so this is purely additive.
     */
    fun decryptInitialPacket(
        packet: ByteArray,
        pnOffset: Int,
        payloadEnd: Int,
        keys: InitialKeys,
    ): DecryptedInitial? {
        try {
            if (pnOffset + 4 + 16 > payloadEnd) return null // not even long enough for a sample
            val sampleOffset = pnOffset + 4
            val sample = packet.copyOfRange(sampleOffset, sampleOffset + 16)
            val mask = aesEcbEncryptBlock(keys.hp, sample)

            // Unprotect first byte + packet number length.
            val working = packet.copyOfRange(0, payloadEnd)
            working[0] = (working[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
            val pnLength = (working[0].toInt() and 0x03) + 1
            for (i in 0 until pnLength) {
                working[pnOffset + i] = (working[pnOffset + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
            }

            // Reconstruct the truncated packet number (RFC 9000 §17.1) — we don't track the
            // largest acked PN across packets (no state machine yet at this layer), so just
            // decode the raw truncated value; Initial packets are always early in the
            // connection so full decode-with-context isn't needed to get a sane packet number.
            var pn = 0L
            for (i in 0 until pnLength) {
                pn = (pn shl 8) or (working[pnOffset + i].toLong() and 0xFF)
            }

            val headerLen = pnOffset + pnLength
            val header = working.copyOfRange(0, headerLen)
            val ciphertext = working.copyOfRange(headerLen, payloadEnd)

            // Nonce = iv XOR packet number (right-aligned, RFC 9001 §5.3).
            val nonce = keys.iv.copyOf()
            for (i in 0 until 8) {
                val shift = i * 8
                val pnByte = ((pn ushr shift) and 0xFF).toByte()
                nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor pnByte.toInt()).toByte()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val plaintext = cipher.doFinal(ciphertext)
            return DecryptedInitial(cryptoData = plaintext, packetNumber = pn)
        } catch (e: Exception) {
            // Wrong keys, truncated sample, bad auth tag (tampered/garbage packet) — all
            // expected failure modes on real-world captures, not a bug. Fall back silently.
            Log.d(TAG, "Initial packet decrypt failed: ${e.message}")
            return null
        }
    }

    // ── HKDF (RFC 5869) + TLS 1.3 HKDF-Expand-Label (RFC 8446 §7.1) ────────────────────

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, copyLen)
            pos += copyLen
            counter++
        }
        return out
    }

    /**
     * TLS 1.3 HKDF-Expand-Label (RFC 8446 §7.1): HkdfLabel = length(2) || "tls13 " + label
     * (1-byte length prefix + bytes) || context (1-byte length prefix + bytes, empty here for
     * all QUIC labels).
     */
    private fun hkdfExpandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = ("tls13 " + label).toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + fullLabel.size + 1)
        info[0] = ((length shr 8) and 0xFF).toByte()
        info[1] = (length and 0xFF).toByte()
        info[2] = fullLabel.size.toByte()
        System.arraycopy(fullLabel, 0, info, 3, fullLabel.size)
        info[3 + fullLabel.size] = 0 // empty context
        return hkdfExpand(secret, info, length)
    }

    /** AES-ECB single-block encrypt, used for header-protection mask generation (RFC 9001 §5.4.3). */
    private fun aesEcbEncryptBlock(key: ByteArray, block16: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block16)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
}
