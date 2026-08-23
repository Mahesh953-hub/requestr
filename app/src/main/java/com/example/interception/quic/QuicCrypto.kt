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

    /**
     * RFC 9001 §5.8, QUIC version 1 Retry Integrity Tag key/nonce. The RFC publishes these as
     * fixed hex constants (themselves an HKDF-Expand-Label output of a fixed secret with labels
     * "quic key"/"quic iv") rather than something derived per-connection, so — matching how
     * [SALT_V1]/[SALT_V2] are stored as already-computed constants — these are hardcoded rather
     * than re-derived at runtime.
     */
    private val RETRY_INTEGRITY_KEY_V1 = hexToBytes("be0c690b9f66575a1d766b54e368c84e")
    private val RETRY_INTEGRITY_NONCE_V1 = hexToBytes("461599d35d632bf2239825bb")

    /** RFC 9369 §3.3.3, QUIC version 2 Retry Integrity Tag key/nonce (same construction as v1). */
    private val RETRY_INTEGRITY_KEY_V2 = hexToBytes("8fb4b01b56ac48e260fbcbcead7ccc92")
    private val RETRY_INTEGRITY_NONCE_V2 = hexToBytes("d86969bc2d7c6d9990efb04a")

    /** Known QUIC version magic numbers (mirrors [QuicParser]'s version whitelist). */
    const val VERSION_1 = 0x00000001
    const val VERSION_2 = 0x6b3343cf.toInt()

    /** RFC 9000 §17.2 Table 5 (v1) / RFC 9369 §3.2 (v2) long-header packet types. */
    enum class LongHeaderPacketType { INITIAL, ZERO_RTT, HANDSHAKE, RETRY }

    /**
     * Maps a long header's 2-bit Long Packet Type field (RFC 9000 §17.2, mask `0x30` of byte 0,
     * i.e. bits 4-5) to its meaning -- **the same 2 bits mean a different packet type depending
     * on the QUIC version**, so this must never be read through v1-only constants. RFC 9000
     * §17.2 Table 5 (v1): Initial=0b00, 0-RTT=0b01, Handshake=0b10, Retry=0b11. RFC 9369 §3.2
     * (v2) shifts every value by one (mod 4): Initial=0b01, 0-RTT=0b10, Handshake=0b11,
     * Retry=0b00 -- explicitly to combat wire-format ossification (RFC 9369 §1). [firstByte] is
     * the packet's raw, still-protected first byte (header protection covers only the low 4
     * bits for a long header, RFC 9001 §5.4, so the type bits at `0x30` are always readable in
     * the clear); this function extracts bits 4-5 itself. Returns null for an unrecognized
     * version (matches [deriveClientInitialKeys]'s "no known mapping" behavior) -- version `0`
     * (Version Negotiation / draft packets) uses the v1 mapping, the same fallback
     * [deriveInitialKeys] uses for its salt.
     */
    fun classifyLongHeaderPacketType(version: Int, firstByte: Int): LongHeaderPacketType? {
        val bits = (firstByte shr 4) and 0x3
        return when (version) {
            VERSION_1, 0 -> when (bits) {
                0 -> LongHeaderPacketType.INITIAL
                1 -> LongHeaderPacketType.ZERO_RTT
                2 -> LongHeaderPacketType.HANDSHAKE
                3 -> LongHeaderPacketType.RETRY
                else -> null // unreachable: `bits` is masked to 2 bits
            }
            VERSION_2 -> when (bits) {
                1 -> LongHeaderPacketType.INITIAL
                2 -> LongHeaderPacketType.ZERO_RTT
                3 -> LongHeaderPacketType.HANDSHAKE
                0 -> LongHeaderPacketType.RETRY
                else -> null
            }
            else -> null
        }
    }

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

    /** {key, iv, hp} derived from a TLS 1.3 traffic secret via [deriveKeysFromTrafficSecret]. */
    data class PacketProtectionKeys(
        val key: ByteArray,
        val iv: ByteArray,
        val hp: ByteArray,
    )

    /**
     * The subset of TLS 1.3 AEAD cipher suites QUIC can negotiate (RFC 9001 §5.3) that this
     * object currently knows how to derive traffic keys for. [hmacAlgorithm]/[hashLength] pick
     * the HKDF hash function (the suite's own hash, per RFC 9001 §5.1 — *not* always SHA-256;
     * only Initial secrets are pinned to SHA-256 regardless of suite). [aeadKeyLength] is the
     * AEAD key size in bytes, which also sizes the AES-ECB header-protection key for the two
     * AES-GCM suites below (RFC 9001 §5.4.3). TLS_CHACHA20_POLY1305_SHA256 is intentionally not
     * listed yet -- its header-protection scheme (RFC 9001 §5.4.4) is a separate ChaCha20
     * keystream construction, not AES-ECB, and lands in a follow-up change.
     */
    enum class CipherSuite(val hmacAlgorithm: String, val hashLength: Int, val aeadKeyLength: Int) {
        TLS_AES_128_GCM_SHA256(hmacAlgorithm = "HmacSHA256", hashLength = 32, aeadKeyLength = 16),
        TLS_AES_256_GCM_SHA384(hmacAlgorithm = "HmacSHA384", hashLength = 48, aeadKeyLength = 32),
    }

    /**
     * Derives the client's Initial {key, iv, hp} per RFC 9001 §5.2/RFC 9369 §3.3.2, keyed off
     * the client-chosen Destination Connection ID (public, sent in cleartext on the wire).
     * Returns null for unrecognized versions (Google QUIC / gQUIC has no equivalent TLS 1.3
     * Initial protection scheme, so those are left SNI-only via [QuicParser.extractSni]).
     */
    fun deriveClientInitialKeys(version: Int, dcid: ByteArray): InitialKeys? =
        deriveInitialKeys(version, dcid, "client in")

    /**
     * Derives the server's Initial {key, iv, hp} per RFC 9001 §5.2/RFC 9369 §3.3.2 -- same
     * public `initial_secret` as [deriveClientInitialKeys] (still keyed only off the client's
     * Destination Connection ID), just expanded with the "server in" label instead of
     * "client in". This is what lets a server-sourced Initial packet's ServerHello CRYPTO
     * frame be recovered, exactly as available to a passive observer as the client's own
     * ClientHello is (RFC 9001 §5.2's "no assurance" note about Initial protection applies
     * symmetrically to both directions).
     */
    fun deriveServerInitialKeys(version: Int, dcid: ByteArray): InitialKeys? =
        deriveInitialKeys(version, dcid, "server in")

    private fun deriveInitialKeys(version: Int, dcid: ByteArray, secretLabel: String): InitialKeys? {
        val salt = when (version) {
            VERSION_1, 0 -> SALT_V1 // draft/negotiation packets commonly reuse the v1 salt
            VERSION_2 -> SALT_V2
            else -> return null
        }
        return try {
            val initialSecret = hkdfExtract(salt, dcid)
            val directionSecret = hkdfExpandLabel(initialSecret, secretLabel, 32)
            val keyLabel = if (version == VERSION_2) "quicv2 key" else "quic key"
            val ivLabel = if (version == VERSION_2) "quicv2 iv" else "quic iv"
            val hpLabel = if (version == VERSION_2) "quicv2 hp" else "quic hp"
            InitialKeys(
                key = hkdfExpandLabel(directionSecret, keyLabel, 16),
                iv = hkdfExpandLabel(directionSecret, ivLabel, 12),
                hp = hkdfExpandLabel(directionSecret, hpLabel, 16),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Initial key derivation ($secretLabel) failed: ${e.message}")
            null
        }
    }

    /**
     * Derives {key, iv, hp} from an already-known TLS 1.3 traffic secret (RFC 9001 §5.1), e.g.
     * a `CLIENT_HANDSHAKE_TRAFFIC_SECRET`/`SERVER_HANDSHAKE_TRAFFIC_SECRET`/
     * `CLIENT_TRAFFIC_SECRET_0`/`SERVER_TRAFFIC_SECRET_0` obtained from
     * [com.example.interception.tls.TlsKeyLogRegistry]. Unlike [deriveClientInitialKeys]/
     * [deriveServerInitialKeys], the HKDF hash function here is the *negotiated* suite's hash
     * (SHA-256 or SHA-384 for the two [CipherSuite]s currently supported), not always SHA-256 --
     * Initial secrets are the one exception pinned to SHA-256 regardless of suite (RFC 9001
     * §5.2). The AEAD nonce (`quic iv`) is always 12 bytes for every AEAD QUIC can negotiate
     * (RFC 9001 §5.1), independent of the suite's key size.
     *
     * This is a pure key-derivation primitive -- no decrypt path in this file drives it yet.
     * It exists so Handshake-level and 1-RTT decryption (a separate change) has a single,
     * already-tested place to get correctly-sized keys from a traffic secret.
     */
    fun deriveKeysFromTrafficSecret(version: Int, cipherSuite: CipherSuite, secret: ByteArray): PacketProtectionKeys? {
        return try {
            val keyLabel = if (version == VERSION_2) "quicv2 key" else "quic key"
            val ivLabel = if (version == VERSION_2) "quicv2 iv" else "quic iv"
            val hpLabel = if (version == VERSION_2) "quicv2 hp" else "quic hp"
            PacketProtectionKeys(
                key = hkdfExpandLabel(secret, keyLabel, cipherSuite.aeadKeyLength, cipherSuite.hmacAlgorithm),
                iv = hkdfExpandLabel(secret, ivLabel, 12, cipherSuite.hmacAlgorithm),
                hp = hkdfExpandLabel(secret, hpLabel, cipherSuite.aeadKeyLength, cipherSuite.hmacAlgorithm),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Traffic secret key derivation failed: ${e.message}")
            null
        }
    }

    /**
     * Reconstructs a full QUIC packet number from its truncated on-wire encoding, per RFC 9000
     * §17.1 / Appendix A.3's `DecodePacketNumber`. [largestPn] is the largest packet number
     * already successfully processed in the *same* packet-number space (Initial/Handshake/
     * 1-RTT each have their own, independent space -- RFC 9000 §12.3), or `-1L` if none has
     * been processed yet in that space (the sentinel that makes the very first packet's own
     * truncated value the answer, e.g. RFC 9001 Appendix A.2/A.3's first Initial packets with
     * packet numbers 2 and 1 respectively). [truncatedPn] is the raw value read from the
     * (already header-protection-removed) Packet Number field, and [pnLenBytes] is that
     * field's on-wire length in bytes (1-4, i.e. an 8/16/24/32-bit field).
     */
    fun decodePacketNumber(largestPn: Long, truncatedPn: Long, pnLenBytes: Int): Long {
        val pnBits = pnLenBytes * 8
        val expectedPn = largestPn + 1
        val pnWin = 1L shl pnBits
        val pnHwin = pnWin / 2
        val pnMask = pnWin - 1
        val candidatePn = (expectedPn and pnMask.inv()) or truncatedPn
        return when {
            candidatePn <= expectedPn - pnHwin && candidatePn < (1L shl 62) - pnWin -> candidatePn + pnWin
            candidatePn > expectedPn + pnHwin && candidatePn >= pnWin -> candidatePn - pnWin
            else -> candidatePn
        }
    }

    /**
     * Validates a Retry packet's 128-bit Retry Integrity Tag (RFC 9001 §5.8 for v1, RFC 9369
     * §3.3.3 for v2) and returns true only if it authenticates. **Callers MUST call this before
     * accepting the Retry's new Source Connection ID or its token, and before re-keying the
     * Initial secrets from that new CID** — an invalid tag means the packet is either corrupted
     * in transit or an attacker's spoofed Retry (the tag's whole purpose per RFC 9001 §5.8 is
     * that "only an entity that observes an Initial packet can send a valid Retry packet"); a
     * spoofed Retry that were acted upon could redirect the connection or poison the CID/token
     * used for the next Initial.
     *
     * [retryPacketWithoutTag] is the on-wire Retry packet (long header + token), **not**
     * including the trailing 16-byte tag itself; [tag] is those trailing 16 bytes; [odcid] is
     * the Original Destination Connection ID — the DCID from the *client's* Initial packet that
     * this Retry is responding to (not any field inside the Retry packet itself; RFC 9001 §5.8's
     * Retry Pseudo-Packet prepends it precisely because a Retry does not otherwise prove it was
     * sent in response to that specific Initial). The key/nonce used are the version-specific
     * fixed constants the RFC publishes ([RETRY_INTEGRITY_KEY_V1]/[RETRY_INTEGRITY_NONCE_V1] for
     * v1, the "quicv2"-labelled equivalents for v2) — never anything derived per-connection.
     *
     * Returns false (never throws) for an unrecognized [version] or on any AEAD failure
     * (mismatched tag, malformed input) — matching this object's existing "never propagate a
     * crypto exception to the caller" contract.
     */
    fun validateRetryIntegrityTag(
        version: Int,
        odcid: ByteArray,
        retryPacketWithoutTag: ByteArray,
        tag: ByteArray,
    ): Boolean {
        if (tag.size != 16 || odcid.size > 255) return false
        val (key, nonce) = when (version) {
            VERSION_1 -> RETRY_INTEGRITY_KEY_V1 to RETRY_INTEGRITY_NONCE_V1
            VERSION_2 -> RETRY_INTEGRITY_KEY_V2 to RETRY_INTEGRITY_NONCE_V2
            else -> return false
        }
        return try {
            // Retry Pseudo-Packet (RFC 9001 §5.8 Figure 8): ODCID Length(1) || ODCID || the
            // transmitted Retry packet with its Retry Integrity Tag removed.
            val pseudoPacket = ByteArray(1 + odcid.size + retryPacketWithoutTag.size)
            pseudoPacket[0] = odcid.size.toByte()
            System.arraycopy(odcid, 0, pseudoPacket, 1, odcid.size)
            System.arraycopy(retryPacketWithoutTag, 0, pseudoPacket, 1 + odcid.size, retryPacketWithoutTag.size)

            // AEAD_AES_128_GCM with an empty plaintext (RFC 9001 §5.8): the "ciphertext" is
            // just the tag, and GCM decryption with empty ciphertext input succeeds iff the tag
            // authenticates the associated data -- exactly the check we need, with no plaintext
            // to discard.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(pseudoPacket)
            cipher.doFinal(tag) // throws AEADBadTagException on mismatch; returns empty array on success
            true
        } catch (e: Exception) {
            Log.d(TAG, "Retry Integrity Tag validation failed: ${e.message}")
            false
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
     *
     * [largestPn] is the largest packet number already seen in this packet's space, passed to
     * [decodePacketNumber] for full RFC 9000 §17.1 reconstruction; it defaults to `-1L` (no
     * prior packet), which is correct for a connection's first Initial packet in either
     * direction and is also what every existing call site relies on today.
     */
    fun decryptInitialPacket(
        packet: ByteArray,
        pnOffset: Int,
        payloadEnd: Int,
        keys: InitialKeys,
        largestPn: Long = -1L,
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

            // Read the truncated on-wire value, then reconstruct the full packet number (RFC
            // 9000 §17.1) via [decodePacketNumber]. [largestPn] defaults to -1 (no packet
            // processed yet in this space), which callers use until real per-space
            // largest-packet-number tracking is threaded through (a separate, connection-level
            // change) -- the RFC algorithm with that sentinel returns exactly the truncated
            // value for a first packet, matching this function's previous raw-read behavior.
            var truncatedPn = 0L
            for (i in 0 until pnLength) {
                truncatedPn = (truncatedPn shl 8) or (working[pnOffset + i].toLong() and 0xFF)
            }
            val pn = decodePacketNumber(largestPn, truncatedPn, pnLength)

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
    //
    // Both helpers take an explicit [hmacAlgorithm] (default "HmacSHA256") because HKDF's hash
    // function must match the negotiated cipher suite's hash (RFC 9001 §5.1) once we're keying
    // from a real traffic secret ([deriveKeysFromTrafficSecret]) rather than the Initial
    // encryption level, which is always SHA-256 regardless of suite (RFC 9001 §5.2) -- e.g.
    // TLS_AES_256_GCM_SHA384 needs HKDF over SHA-384, not SHA-256.

    private fun hmac(hmacAlgorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(hmacAlgorithm)
        val hashLen = mac.macLength
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(hashLen) else key, hmacAlgorithm))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray, hmacAlgorithm: String = "HmacSHA256"): ByteArray =
        hmac(hmacAlgorithm, salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int, hmacAlgorithm: String = "HmacSHA256"): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmac(hmacAlgorithm, prk, input)
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
    private fun hkdfExpandLabel(secret: ByteArray, label: String, length: Int, hmacAlgorithm: String = "HmacSHA256"): ByteArray {
        val fullLabel = ("tls13 " + label).toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + fullLabel.size + 1)
        info[0] = ((length shr 8) and 0xFF).toByte()
        info[1] = (length and 0xFF).toByte()
        info[2] = fullLabel.size.toByte()
        System.arraycopy(fullLabel, 0, info, 3, fullLabel.size)
        info[3 + fullLabel.size] = 0 // empty context
        return hkdfExpand(secret, info, length, hmacAlgorithm)
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
