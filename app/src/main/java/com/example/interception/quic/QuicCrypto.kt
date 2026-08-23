package com.example.interception.quic

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
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

    /**
     * {key, iv, hp} derived from a TLS 1.3 traffic secret via [deriveKeysFromTrafficSecret]. The
     * [cipherSuite] is carried alongside the key material so the decrypt path ([decryptProtectedPayload]
     * / [decryptShortHeaderOrHandshakePayload]) can pick the right AEAD transformation (AES/GCM vs
     * ChaCha20-Poly1305) and header-protection construction (AES-ECB vs ChaCha20 keystream) without a
     * separate suite argument threaded through every call site. It defaults to AES-128-GCM — the
     * QUIC Initial-key AEAD — so call sites that construct keys for the Initial path keep compiling.
     */
    data class PacketProtectionKeys(
        val key: ByteArray,
        val iv: ByteArray,
        val hp: ByteArray,
        val cipherSuite: CipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
    )

    /**
     * The subset of TLS 1.3 AEAD cipher suites QUIC can negotiate (RFC 9001 §5.3) that this
     * object currently knows how to derive traffic keys for and decrypt. [hmacAlgorithm]/
     * [hashLength] pick the HKDF hash function (the suite's own hash, per RFC 9001 §5.1 — *not*
     * always SHA-256; only Initial secrets are pinned to SHA-256 regardless of suite). [aeadKeyLength]
     * is the AEAD key size in bytes (the `keyLength` of the suite); for the two AES-GCM suites it also
     * sizes the AES-ECB header-protection key (RFC 9001 §5.4.3). [aeadTransformation]/
     * [aeadKeyAlgorithm] select the JCE AEAD cipher and key algorithm, and
     * [headerProtectionIsChaCha20] switches header protection from AES-ECB (RFC 9001 §5.4.3) to the
     * ChaCha20 keystream construction (RFC 9001 §5.4.4).
     *
     * The QUIC AEAD nonce is always 12 bytes ([ivLength]) and the AEAD tag is always 16 bytes
     * ([tagLength] = 128 bits) for every suite QUIC can negotiate (RFC 9001 §5.1), independent of
     * the suite's key size — so those are constants rather than per-suite fields.
     */
    enum class CipherSuite(
        val hmacAlgorithm: String,
        val hashLength: Int,
        val aeadKeyLength: Int,
        /** JCE AEAD transformation for this suite's bulk cipher (RFC 9001 §5.3). */
        val aeadTransformation: String,
        /** JCE algorithm name for the AEAD key ([SecretKeySpec] algorithm). */
        val aeadKeyAlgorithm: String,
        /**
         * True when header protection uses the ChaCha20 keystream construction (RFC 9001 §5.4.4)
         * instead of AES-ECB (RFC 9001 §5.4.3).
         */
        val headerProtectionIsChaCha20: Boolean,
    ) {
        TLS_AES_128_GCM_SHA256(
            hmacAlgorithm = "HmacSHA256", hashLength = 32, aeadKeyLength = 16,
            aeadTransformation = "AES/GCM/NoPadding", aeadKeyAlgorithm = "AES",
            headerProtectionIsChaCha20 = false,
        ),
        TLS_AES_256_GCM_SHA384(
            hmacAlgorithm = "HmacSHA384", hashLength = 48, aeadKeyLength = 32,
            aeadTransformation = "AES/GCM/NoPadding", aeadKeyAlgorithm = "AES",
            headerProtectionIsChaCha20 = false,
        ),
        TLS_CHACHA20_POLY1305_SHA256(
            // RFC 9001 §5.3 / RFC 8446 §B.4: 32-byte (256-bit) ChaCha20 key, 12-byte AEAD nonce,
            // 16-byte (128-bit) Poly1305 tag, SHA-256 HKDF hash (hashLength = 32). Header protection
            // is the RFC 9001 §5.4.4 ChaCha20 keystream construction, not AES-ECB.
            hmacAlgorithm = "HmacSHA256", hashLength = 32, aeadKeyLength = 32,
            aeadTransformation = "ChaCha20-Poly1305", aeadKeyAlgorithm = "ChaCha20",
            headerProtectionIsChaCha20 = true,
        );

        /** AEAD nonce length in bytes — always 12 for every QUIC AEAD suite (RFC 9001 §5.1). */
        val ivLength: Int get() = 12

        /** AEAD authentication-tag length in bytes — always 16 (128 bits) for every QUIC AEAD suite. */
        val tagLength: Int get() = 16

        companion object {
            /** The IANA TLS cipher-suite identifier ([CipherSuite] was negotiated from), or null. */
            fun fromIanaCode(code: Int): CipherSuite? = when (code) {
                0x1301 -> TLS_AES_128_GCM_SHA256
                0x1302 -> TLS_AES_256_GCM_SHA384
                0x1303 -> TLS_CHACHA20_POLY1305_SHA256
                else -> null
            }
        }
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
     * (SHA-256 or SHA-384 for the two AES-GCM suites, SHA-256 for ChaCha20-Poly1305), not always
     * SHA-256 -- Initial secrets are the one exception pinned to SHA-256 regardless of suite (RFC
     * 9001 §5.2). The AEAD key length (`quic key`) and header-protection key length (`quic hp`) are
     * the negotiated suite's [CipherSuite.aeadKeyLength] (16 for AES-128-GCM, 32 for AES-256-GCM and
     * ChaCha20-Poly1305); the AEAD nonce (`quic iv`) is always 12 bytes ([CipherSuite.ivLength]) for
     * every AEAD QUIC can negotiate (RFC 9001 §5.1), independent of the suite's key size. The
     * negotiated [cipherSuite] is carried on the returned [PacketProtectionKeys] so the decrypt path
     * selects the matching AEAD/header-protection construction.
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
                iv = hkdfExpandLabel(secret, ivLabel, cipherSuite.ivLength, cipherSuite.hmacAlgorithm),
                hp = hkdfExpandLabel(secret, hpLabel, cipherSuite.aeadKeyLength, cipherSuite.hmacAlgorithm),
                cipherSuite = cipherSuite,
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

    // ── Handshake long-header + 1-RTT short-header payload decryption (key-log sourced) ──

    /**
     * Decrypts a QUIC Handshake-level long-header packet (RFC 9000 §17.2.2 / RFC 9001 §5.2) keyed
     * from a TLS 1.3 handshake traffic secret -- a `CLIENT_HANDSHAKE_TRAFFIC_SECRET` /
     * `SERVER_HANDSHAKE_TRAFFIC_SECRET` obtained from
     * [com.example.interception.tls.TlsKeyLogRegistry] (the same secret a key-log source such as
     * Cronet's `ssl_key_log_file` emits). Unlike [decryptInitialPacket] -- whose keys come only
     * from public DCID-derived material -- this keys the AEAD from the negotiated TLS handshake
     * secret, so the server's Certificate/EncryptedExtensions/Finished CRYPTO frames become visible,
     * exactly what a passive observer with a key log can recover (the model Wireshark's QUIC
     * dissector uses under `SSLKEYLOGFILE`).
     *
     * [payload] is the full on-wire Handshake long-header packet starting at its first (form) byte:
     * first byte(1) + version(4) + DCID-len(1) + DCID + SCID-len(1) + SCID + Length varint +
     * packet number + protected payload. [dcid] is the connection's Destination Connection ID
     * (carried forward from the Initial); it is validated against the packet's DCID field before
     * any crypto is touched -- a mismatch means this packet does not belong to this connection and
     * is rejected without attempting AEAD. [handshakeSecret] is the raw traffic secret;
     * [cipherSuite] selects the HKDF hash and AEAD key size via [deriveKeysFromTrafficSecret].
     *
     * Header protection (RFC 9001 §5.4) is removed, the packet number is extracted (full RFC 9000
     * §17.1 reconstruction across a packet-number space is the caller's per-space responsibility;
     * this reads the on-wire truncated value with the no-prior-packet sentinel), and the remaining
     * bytes are AEAD-decrypted with an **empty AAD** (this object's simplified payload contract --
     * see [decryptProtectedPayload]). A wrong secret, corrupt packet, or DCID mismatch returns null,
     * never throws. Returns the decrypted frame bytes (CRYPTO/ACK/etc.) or null on any failure.
     */
    fun decryptHandshakePacket(
        version: Int,
        cipherSuite: CipherSuite,
        handshakeSecret: ByteArray,
        dcid: ByteArray,
        payload: ByteArray,
    ): ByteArray? {
        val keys = deriveKeysFromTrafficSecret(version, cipherSuite, handshakeSecret) ?: return null

        // Locate the packet-number field in the Handshake long header (RFC 9000 §17.2.2):
        // first byte(1) + version(4) + DCID-len(1) + DCID + SCID-len(1) + SCID + Length varint.
        if (payload.size < 7) return null
        if ((payload[0].toInt() and 0x80) == 0) return null // not a long header

        var pos = 5 // skip first byte + 4-byte version
        val dcidLen = payload[pos].toInt() and 0xFF
        pos += 1
        if (pos + dcidLen > payload.size) return null
        if (dcid.isNotEmpty()) {
            val packetDcid = payload.copyOfRange(pos, pos + dcidLen)
            if (!packetDcid.contentEquals(dcid)) return null // wrong connection -- reject before AEAD
        }
        pos += dcidLen

        if (pos >= payload.size) return null
        val scidLen = payload[pos].toInt() and 0xFF
        pos += 1
        if (pos + scidLen > payload.size) return null
        pos += scidLen

        val (lengthValueLong, varintLen) = readVarint(payload, pos) ?: return null
        pos += varintLen
        val pnOffset = pos
        // The Length varint spans only this packet's PN + ciphertext; bound it against the remaining
        // bytes (also guards against a pathological >Int value before the toInt() below).
        if (lengthValueLong > payload.size - pnOffset) return null
        val lengthValue = lengthValueLong.toInt()
        val packetEnd = pnOffset + lengthValue
        if (packetEnd > payload.size || pnOffset >= packetEnd) return null

        // Strip the variable long-header middle, keeping the first byte (which carries the
        // packet-number-length bits) so the shared payload decryptor sees the uniform
        // [firstByte, packetNumber, ciphertext] layout that [decryptProtectedPayload] expects.
        val aligned = ByteArray(1 + (packetEnd - pnOffset))
        aligned[0] = payload[0]
        System.arraycopy(payload, pnOffset, aligned, 1, packetEnd - pnOffset)
        return decryptProtectedPayload(keys, aligned)
    }

    /**
     * AEAD-decrypts an already-header-stripped protected QUIC payload using already-derived [keys]
     * (from [deriveKeysFromTrafficSecret]). [payload] is the packet's first byte (carrying the
     * packet-number-length bits) immediately followed by the protected packet number and the AEAD
     * ciphertext -- callers strip any bytes between the first byte and the packet number (the
     * version, CIDs, and Length varint of a long header; the DCID of a short header) so this sees a
     * uniform layout. This is the shared decryption primitive both Handshake long-header packets
     * ([decryptHandshakePacket]) and 1-RTT short-header packets feed once their variable header has
     * been located; the two share the same header-protection-removal + AEAD machinery (RFC 9001
     * §5.4/§5.3), differing only in which bits of the first byte are protected. Returns the decrypted
     * frame bytes or null on any failure (never throws).
     */
    fun decryptProtectedPayload(keys: PacketProtectionKeys, payload: ByteArray): ByteArray? =
        decryptShortHeaderOrHandshakePayload(keys, payload)

    /**
     * Actual header-protection-removal + AEAD-decrypt implementation backing
     * [decryptProtectedPayload]. [payload] starts at the packet's first byte (the form/flags byte
     * whose low bits carry the packet-number length) followed **immediately** by the protected
     * packet number and the AEAD ciphertext. The header-protection sample (RFC 9001 §5.4) is taken
     * 4 bytes after the start of the packet-number field; the (unprotected) form bit selects whether
     * the low 4 bits (long/Handshake) or low 5 bits (short/1-RTT) of the first byte are protected.
     * The mask is generated with the construction selected by [PacketProtectionKeys.cipherSuite]:
     * AES-ECB (RFC 9001 §5.4.3) for the two AES-GCM suites, or the ChaCha20 keystream construction
     * (RFC 9001 §5.4.4) for TLS_CHACHA20_POLY1305_SHA256. The packet number is removed and the
     * remaining bytes are AEAD-decrypted with an empty AAD using the suite's AEAD (AES/GCM or
     * ChaCha20-Poly1305). Returns the plaintext frame bytes or null on any failure.
     */
    private fun decryptShortHeaderOrHandshakePayload(keys: PacketProtectionKeys, payload: ByteArray): ByteArray? {
        return try {
            if (payload.size < 1) return null
            val pnStart = 1 // packet-number field begins right after the first byte in this stripped layout
            val sampleOffset = pnStart + 4 // RFC 9001 §5.4: sample = 4 bytes after the PN field start
            if (sampleOffset + 16 > payload.size) return null
            val sample = payload.copyOfRange(sampleOffset, sampleOffset + 16)
            val mask = headerProtectionMask(keys, sample)

            val working = payload.copyOf()
            // The form bit (high bit of the first byte) is not header-protected, so read it before
            // unprotecting to pick the mask width: long header (Handshake) protects the low 4 bits
            // (0x0f), short header (1-RTT) the low 5 bits (0x1f) -- RFC 9001 §5.4.1.
            val firstMask = if ((working[0].toInt() and 0x80) != 0) 0x0f else 0x1f
            working[0] = (working[0].toInt() xor (mask[0].toInt() and firstMask)).toByte()
            val pnLength = (working[0].toInt() and 0x03) + 1

            for (i in 0 until pnLength) {
                working[pnStart + i] = (working[pnStart + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
            }

            // Reconstruct the full packet number from the truncated on-wire value. With no per-space
            // largest-packet-number context threaded through this primitive yet, use the -1 sentinel
            // (no prior packet), which returns the truncated value verbatim -- correct for a first
            // Handshake/1-RTT packet and the same default [decryptInitialPacket] uses.
            var truncatedPn = 0L
            for (i in 0 until pnLength) {
                truncatedPn = (truncatedPn shl 8) or (working[pnStart + i].toLong() and 0xFF)
            }
            val pn = decodePacketNumber(-1L, truncatedPn, pnLength)

            val ciphertextStart = pnStart + pnLength
            if (working.size - ciphertextStart < 16) return null // not even a full 16-byte AEAD tag
            val ciphertext = working.copyOfRange(ciphertextStart, working.size)

            // Nonce = iv XOR packet number (right-aligned, RFC 9001 §5.3).
            val nonce = keys.iv.copyOf()
            for (i in 0 until 8) {
                val shift = i * 8
                val pnByte = ((pn ushr shift) and 0xFF).toByte()
                nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor pnByte.toInt()).toByte()
            }

            // Empty AAD: this primitive authenticates only the ciphertext + packet number, not the
            // stripped header (callers that need full-header authentication keep using
            // [decryptInitialPacket], which AADs the whole long header). The suite's AEAD is selected
            // by [PacketProtectionKeys.cipherSuite] (AES/GCM or ChaCha20-Poly1305).
            aeadDecrypt(keys, nonce, ciphertext)
        } catch (e: Exception) {
            Log.d(TAG, "Protected payload decrypt failed: ${e.message}")
            null
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

    /**
     * Generates the header-protection mask (RFC 9001 §5.4) for [keys]' negotiated suite. The
     * returned array always has at least 5 bytes (the first-byte mask + up to 4 packet-number
     * bytes) so the caller indexes [0] and [1..4] uniformly regardless of suite:
     * - AES-GCM suites (RFC 9001 §5.4.3): `mask = AES-ECB(hp, sample)` (a 16-byte block; the
     *   caller uses the first 5 bytes).
     * - ChaCha20-Poly1305 (RFC 9001 §5.4.4): the 16-byte [sample] is split into a 4-byte ChaCha20
     *   block counter (`sample[0..3]`, little-endian) and a 12-byte nonce (`sample[4..15]`); the
     *   mask is the first 5 bytes of the ChaCha20 keystream produced by encrypting five zero bytes
     *   with the header-protection key (the 32-byte `quic hp` key) under that counter/nonce.
     */
    private fun headerProtectionMask(keys: PacketProtectionKeys, sample16: ByteArray): ByteArray =
        if (keys.cipherSuite.headerProtectionIsChaCha20) chacha20HeaderProtectionMask(keys.hp, sample16)
        else aesEcbEncryptBlock(keys.hp, sample16)

    /**
     * ChaCha20 header-protection mask (RFC 9001 §5.4.4): encrypt five zero bytes with ChaCha20
     * (raw stream cipher, not AEAD) keyed by the header-protection key, using the sample's first 4
     * bytes as the little-endian block counter and its remaining 12 bytes as the nonce. Returns the
     * 5-byte keystream — the first-byte mask plus up to 4 packet-number masks.
     */
    private fun chacha20HeaderProtectionMask(hpKey: ByteArray, sample16: ByteArray): ByteArray {
        val counter = (sample16[0].toInt() and 0xFF) or
            ((sample16[1].toInt() and 0xFF) shl 8) or
            ((sample16[2].toInt() and 0xFF) shl 16) or
            ((sample16[3].toInt() and 0xFF) shl 24)
        val nonce = sample16.copyOfRange(4, 16)
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hpKey, "ChaCha20"), ChaCha20ParameterSpec(nonce, counter))
        return cipher.doFinal(ByteArray(5)) // XOR of 5 zero bytes with keystream = the keystream itself
    }

    /**
     * AEAD-decrypts [ciphertext] (with its trailing 16-byte tag appended) under [keys]' negotiated
     * suite, using [nonce] (the `quic iv` XOR packet number) and an empty AAD. Branches on
     * [PacketProtectionKeys.cipherSuite]: AES/GCM via `GCMParameterSpec(128, nonce)` (the 128-bit
     * tag length), or ChaCha20-Poly1305 via `IvParameterSpec(nonce)` (its 16-byte tag is built into
     * the algorithm). Throws on a bad tag / wrong key / malformed input; callers wrap in try/catch.
     */
    private fun aeadDecrypt(keys: PacketProtectionKeys, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val suite = keys.cipherSuite
        val cipher = Cipher.getInstance(suite.aeadTransformation)
        return if (suite.headerProtectionIsChaCha20) {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        }
    }

    /**
     * Reads a QUIC variable-length integer (RFC 9000 §16) at [offset]: the top 2 bits of the first
     * byte encode the byte length (00=1, 01=2, 10=4, 11=8), the remaining bits + trailing bytes are
     * the big-endian value. Returns (value, byteCount) or null on truncation. Used by
     * [decryptHandshakePacket] to walk the Handshake long header's Length field.
     */
    private fun readVarint(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        val len = when (first shr 6) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; else -> return null
        }
        if (offset + len > buf.size) return null
        var value = (first and 0x3f).toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return value to len
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
}
