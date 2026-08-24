package com.example.interception.quic

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.Mac
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
     * Result of removing header protection (RFC 9001 §5.4) from a real on-wire QUIC packet, before
     * the AEAD step. Unlike the simplified [decryptProtectedPayload] contract, this keeps the
     * packet's own header bytes so the AEAD can authenticate them as Associated Data exactly as RFC
     * 9001 §5.3 requires (`AAD = the contents of the QUIC header, starting from the first byte …
     * through the end of the packet number field`).
     *
     * @param firstByte the *unprotected* first byte (its low 4/5 bits have been de-masked).
     * @param packetNumber the full packet number reconstructed via [decodePacketNumber] against the
     *   caller-supplied largest packet number for this packet's own packet-number space.
     * @param pnLength the on-wire Packet Number field length in bytes (1..4).
     * @param keyPhaseBit the short-header KEY_PHASE bit (RFC 9001 §6); always 0 for a long header,
     *   which has no key phase.
     * @param aad the packet header bytes (with the first byte and packet number unprotected) that
     *   the AEAD must authenticate.
     * @param ciphertext the protected payload including its trailing 16-byte AEAD tag.
     */
    data class UnprotectedPacket(
        val firstByte: Int,
        val packetNumber: Long,
        val pnLength: Int,
        val keyPhaseBit: Int,
        val aad: ByteArray,
        val ciphertext: ByteArray,
    )

    /** A decrypted QUIC packet payload: the plaintext frame bytes plus the packet's own metadata. */
    data class DecryptedPacket(
        val plaintext: ByteArray,
        val packetNumber: Long,
        val keyPhaseBit: Int,
        /** The 1-RTT key generation that authenticated (RFC 9001 §6); 0 for non-1-RTT levels. */
        val keyGeneration: Int = 0,
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

    // ── Key-phase rotation (RFC 9001 §6): prev / current / next with auth-before-promotion ──

    /**
     * One generation of QUIC 1-RTT traffic keys under the key-update rotation (RFC 9001 §6). [key]
     * is the generation counter (0 for the initial `CLIENT/SERVER_TRAFFIC_SECRET_0`-derived
     * application-data keys, incremented on each key update). [protectKeys] are the keys used to
     * protect (encrypt) outgoing packets at this generation; [unprotectKeys] are used to unprotect
     * (decrypt) incoming packets at this generation. A retired previous generation keeps only
     * [unprotectKeys] (we no longer send with it but still accept delayed/reordered packets
     * encrypted under it for a bounded window) -- see [KeyPhaseManager].
     */
    data class KeyPhase(
        val key: Int,
        val protectKeys: PacketProtectionKeys,
        val unprotectKeys: PacketProtectionKeys?,
    )

    /**
     * Manages the prev/current/next key-phase rotation for one direction of a QUIC connection's
     * 1-RTT traffic (RFC 9001 §6), with auth-before-promotion and bounded retirement of previous
     * generations.
     *
     * A key update derives the next-generation traffic secret as
     * `HKDF-Expand-Label(secret_N, "quic ku", "", Hash.length)` (RFC 9001 §6.1) and then traffic
     * keys via [deriveKeysFromTrafficSecret]. The KEY_PHASE bit carried in each short header equals
     * `generation % 2`, so after two updates the bit wraps back to 0 -- a packet's bit alone is
     * ambiguous about which generation it used, which is why decryption tries candidates in priority
     * order rather than indexing a single key set.
     *
     * **Auth-before-promotion** (RFC 9001 §6): when a short header's KEY_PHASE bit signals a
     * peer-initiated update (it differs from the current generation's bit), the next-generation
     * keys are derived SPECULATIVELY and tried with AEAD -- they are promoted (current→previous, new
     * current) ONLY if that AEAD authenticates. A spoofed key-phase flip that fails AEAD is
     * discarded and never promoted, and the current generation's keys are left untouched. After
     * promotion, the previous generation is retained (unprotect-only) for [maxRetainedPrevious]
     * generations so delayed/reordered packets from the old phase still decrypt (the configurable
     * byte/packet window modeled here as a retained-generation count).
     *
     * @param version QUIC version (v1/v2) -- selects the `quic`/`quicv2` key labels.
     * @param cipherSuite the negotiated AEAD suite -- selects HKDF hash + AEAD key/header-protection.
     * @param initialSecret the generation-0 traffic secret (e.g. `CLIENT/SERVER_TRAFFIC_SECRET_0`).
     * @param maxRetainedPrevious how many previous generations to keep for decrypting delayed packets.
     */
    class KeyPhaseManager(
        private val version: Int,
        private val cipherSuite: CipherSuite,
        initialSecret: ByteArray,
        private val maxRetainedPrevious: Int = 1,
    ) {
        // phases: index 0 = oldest retained previous generation; last = current generation.
        private val phases: MutableList<KeyPhase> = mutableListOf()
        private var currentGeneration: Int = 0
        private var currentSecret: ByteArray = initialSecret

        /**
         * The connection's header-protection keys. RFC 9001 §5.4 fixes header protection for the whole
         * connection -- "the same header protection key is used for the duration of the connection, with
         * the value not changing after a key update" -- so this is generation 0's `hp` material and stays
         * that way no matter how many times the AEAD keys rotate. Later generations' derived `hp` bytes
         * exist as a side effect of [deriveKeysFromTrafficSecret] and are deliberately never used: a peer
         * that rotated its AEAD keys is still masking headers with this key, so unprotecting with a
         * rotated `hp` would corrupt the packet number and fail every AEAD candidate.
         */
        private val fixedHeaderProtectionKeys: PacketProtectionKeys

        init {
            val keys = deriveKeysFromTrafficSecret(version, cipherSuite, initialSecret)
                ?: throw IllegalArgumentException("failed to derive initial key-phase traffic keys")
            phases.add(KeyPhase(key = 0, protectKeys = keys, unprotectKeys = keys))
            fixedHeaderProtectionKeys = keys
        }

        /**
         * The keys whose `hp` value must be used to remove header protection for **every** generation
         * (RFC 9001 §5.4). Callers pass this to [unprotectShortHeaderPacket]; only the AEAD step, via
         * [attemptUnprotectPacket], varies by key phase.
         */
        fun headerProtectionKeys(): PacketProtectionKeys = fixedHeaderProtectionKeys

        /** The current generation number (0 for the initial application-data keys, +1 per key update). */
        val generation: Int get() = currentGeneration

        /** The current generation's KEY_PHASE bit value (`generation % 2`, RFC 9001 §6). */
        val currentKeyPhaseBit: Int get() = currentGeneration % 2

        /** The keys used to protect (encrypt) outgoing packets at the current generation. */
        fun currentProtectKeys(): PacketProtectionKeys = phases.last().protectKeys

        /** The keys used to unprotect (decrypt) incoming packets at the current generation. */
        fun currentUnprotectKeys(): PacketProtectionKeys =
            phases.last().unprotectKeys ?: phases.last().protectKeys

        /** All retained generations, oldest first (for inspection/testing). */
        fun retainedPhases(): List<KeyPhase> = phases.toList()

        /**
         * Derives the next-generation traffic secret + keys WITHOUT promoting -- the speculative
         * next generation [attemptUnprotect] tries for a peer-signaled key update. Returns null on
         * a derivation failure (never throws).
         */
        private fun deriveNextGeneration(): Pair<ByteArray, PacketProtectionKeys>? {
            // RFC 9001 §6.1: secret_{N+1} = HKDF-Expand-Label(secret_N, "quic ku", "", Hash.length).
            val nextSecret = hkdfExpandLabel(currentSecret, "quic ku", cipherSuite.hashLength, cipherSuite.hmacAlgorithm)
            val keys = deriveKeysFromTrafficSecret(version, cipherSuite, nextSecret) ?: return null
            return nextSecret to keys
        }

        /**
         * Commits a key update: the current generation is demoted to a previous generation (kept
         * unprotect-only), the given [nextSecret]/[nextKeys] become the new current generation, and
         * previous generations beyond [maxRetainedPrevious] are dropped (bounded retirement). Call
         * ONLY after the next-generation keys have authenticated (the promotion half of
         * auth-before-promotion).
         */
        private fun promote(nextSecret: ByteArray, nextKeys: PacketProtectionKeys) {
            currentGeneration += 1
            currentSecret = nextSecret
            phases.add(KeyPhase(key = currentGeneration, protectKeys = nextKeys, unprotectKeys = nextKeys))
            while (phases.size > maxRetainedPrevious + 1) phases.removeAt(0)
        }

        /**
         * Initiates a key update from THIS endpoint's side (we are about to start sending with the
         * next generation). Unlike a peer-signaled update detected in [attemptUnprotect], our own
         * update is trusted, so the promotion is committed immediately and the previous generation
         * is retained for the delayed-packet window. Returns the new current generation's protect
         * keys (to send with).
         */
        fun initiateKeyUpdate(): PacketProtectionKeys {
            val (nextSecret, nextKeys) = deriveNextGeneration()
                ?: throw IllegalStateException("failed to derive next key-phase generation")
            promote(nextSecret, nextKeys)
            return currentProtectKeys()
        }

        /** Outcome of [attemptUnprotect]. */
        data class DecryptResult(
            val plaintext: ByteArray,
            val generation: Int,
            /** True if a peer-signaled key update authenticated and was promoted. */
            val promoted: Boolean,
        )

        /**
         * Attempts to AEAD-decrypt [ciphertext] (the protected payload after header-protection +
         * packet-number removal) for a short-header packet whose KEY_PHASE bit is [keyPhaseBit],
         * using [nonce] (the `quic iv` XOR packet number) and [aad]. Implements auth-before-promotion:
         * - tries the current generation first when its bit matches [keyPhaseBit];
         * - when [keyPhaseBit] signals a peer update (differs from the current bit), derives the
         *   speculative next generation and tries it, promoting ONLY on authentication;
         * - tries retained previous generations whose bit matches, so delayed old-phase packets decrypt.
         *
         * Returns the [DecryptResult] (plaintext + the generation that authenticated + whether a
         * promotion occurred), or null if no candidate authenticates (never throws, never promotes on
         * failure, never disturbs the current generation).
         */
        fun attemptUnprotect(keyPhaseBit: Int, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): DecryptResult? {
            // Build candidate (generation, keys) pairs in try-order. currentBit and nextBit always
            // differ (gen%2 vs (gen+1)%2), so exactly one of the current / speculative-next branches
            // applies for a given keyPhaseBit; the speculative next is derived lazily.
            val currentBit = currentKeyPhaseBit
            val candidates = mutableListOf<Pair<Int, PacketProtectionKeys>>()
            if (currentBit == keyPhaseBit) {
                candidates.add(currentGeneration to currentUnprotectKeys())
            }
            var stagedNext: Pair<ByteArray, PacketProtectionKeys>? = null
            if ((currentGeneration + 1) % 2 == keyPhaseBit) {
                stagedNext = deriveNextGeneration()
                if (stagedNext != null) candidates.add((currentGeneration + 1) to stagedNext.second)
            }
            // Retained previous generations (newest retained prev first) whose bit matches.
            for (i in phases.size - 2 downTo 0) {
                val phase = phases[i]
                if (phase.key % 2 == keyPhaseBit && phase.unprotectKeys != null) {
                    candidates.add(phase.key to phase.unprotectKeys)
                }
            }
            for ((gen, keys) in candidates) {
                val plaintext = try {
                    aeadDecrypt(keys, nonce, ciphertext, aad)
                } catch (e: Exception) {
                    null // wrong generation / bad tag -- try the next candidate
                } ?: continue
                // Authenticated. Promote only if this was the speculative next generation (a retained
                // previous generation is strictly older than currentGeneration+1, so the check is exact).
                val promoted = gen == currentGeneration + 1 && stagedNext != null
                if (promoted) {
                    val (nextSecret, nextKeys) = stagedNext!!
                    promote(nextSecret, nextKeys)
                }
                return DecryptResult(plaintext = plaintext, generation = gen, promoted = promoted)
            }
            return null
        }

        /**
         * The packet-number-aware form of [attemptUnprotect], and the one the live 1-RTT decrypt path
         * uses. [attemptUnprotect] takes a single pre-computed nonce, which is only correct when every
         * candidate generation shares an IV — they do not: `quic iv` is re-derived per generation
         * (RFC 9001 §6.1), so the nonce (`iv XOR packet number`, §5.3) differs per candidate. This
         * overload takes the reconstructed [packetNumber] instead and computes each candidate's own
         * nonce, so a delayed old-phase packet and a promoted new-phase packet both authenticate.
         *
         * Same auth-before-promotion contract as [attemptUnprotect]: the speculative next generation
         * is promoted only when its AEAD authenticates, a failed candidate never disturbs the current
         * generation, and retained previous generations stay decryptable for the bounded window.
         * [aad] is the packet's unprotected header bytes (RFC 9001 §5.3), i.e.
         * [UnprotectedPacket.aad]. Returns null when no candidate authenticates; never throws.
         */
        fun attemptUnprotectPacket(keyPhaseBit: Int, packetNumber: Long, aad: ByteArray, ciphertext: ByteArray): DecryptResult? {
            val currentBit = currentKeyPhaseBit
            val candidates = mutableListOf<Pair<Int, PacketProtectionKeys>>()
            if (currentBit == keyPhaseBit) {
                candidates.add(currentGeneration to currentUnprotectKeys())
            }
            var stagedNext: Pair<ByteArray, PacketProtectionKeys>? = null
            if ((currentGeneration + 1) % 2 == keyPhaseBit) {
                stagedNext = deriveNextGeneration()
                if (stagedNext != null) candidates.add((currentGeneration + 1) to stagedNext.second)
            }
            for (i in phases.size - 2 downTo 0) {
                val phase = phases[i]
                if (phase.key % 2 == keyPhaseBit && phase.unprotectKeys != null) {
                    candidates.add(phase.key to phase.unprotectKeys)
                }
            }
            for ((gen, keys) in candidates) {
                val plaintext = try {
                    aeadDecrypt(keys, nonceFor(keys.iv, packetNumber), ciphertext, aad)
                } catch (e: Exception) {
                    null // wrong generation / bad tag -- try the next candidate
                } ?: continue
                val promoted = gen == currentGeneration + 1 && stagedNext != null
                if (promoted) {
                    val (nextSecret, nextKeys) = stagedNext!!
                    promote(nextSecret, nextKeys)
                }
                return DecryptResult(plaintext = plaintext, generation = gen, promoted = promoted)
            }
            return null
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

            // Nonce = iv XOR packet number (right-aligned, RFC 9001 §5.3) -- the same construction every
            // level uses, so it comes from the one shared helper rather than a per-call-site copy.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonceFor(keys.iv, pn)))
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
     * Removes header protection (RFC 9001 §5.4) from a **real on-wire** QUIC packet and returns the
     * unprotected header (as AEAD Associated Data), the reconstructed packet number, and the
     * protected payload — the correct-by-construction input to [aeadDecrypt].
     *
     * [packet] is the single QUIC packet's bytes starting at its first (form/flags) byte;
     * [pnOffset] is the offset of the still-protected Packet Number field within [packet] (for a
     * long header: just past the Length varint; for a short header: `1 + dcidLen`); [packetEnd] is
     * one past this packet's last byte (== `packet.size` for a short header, which always consumes
     * the datagram remainder, and the Length-field-derived end for a long header inside a coalesced
     * datagram). [largestPn] is the largest packet number already processed in **this packet's own
     * packet-number space and direction** (RFC 9000 §12.3), or `-1L` if none — it only affects
     * packet-number reconstruction ([decodePacketNumber]), never key material.
     *
     * The header-protection sample is the 16 bytes at `pnOffset + 4` (RFC 9001 §5.4.2); the mask is
     * generated with the construction selected by [PacketProtectionKeys.cipherSuite] (AES-ECB, or
     * the ChaCha20 keystream for TLS_CHACHA20_POLY1305_SHA256). The form bit selects whether the
     * first byte's low 4 bits (long header) or low 5 bits (short header) are protected. For a short
     * header the KEY_PHASE bit (`0x04` of the unprotected first byte, RFC 9001 §6) is returned so
     * the caller can drive [KeyPhaseManager.attemptUnprotectPacket].
     *
     * Returns null (never throws) when the packet is too short for a sample, the offsets are
     * inconsistent, or there is not even a full 16-byte AEAD tag.
     */
    fun unprotectPacketHeader(
        keys: PacketProtectionKeys,
        packet: ByteArray,
        pnOffset: Int,
        packetEnd: Int,
        largestPn: Long = -1L,
    ): UnprotectedPacket? {
        return try {
            if (pnOffset < 1 || packetEnd > packet.size || pnOffset >= packetEnd) return null
            val sampleOffset = pnOffset + 4
            if (sampleOffset + 16 > packetEnd) return null
            val sample = packet.copyOfRange(sampleOffset, sampleOffset + 16)
            val mask = headerProtectionMask(keys, sample)

            val working = packet.copyOfRange(0, packetEnd)
            val isLongHeader = (working[0].toInt() and 0x80) != 0
            val firstByteMask = if (isLongHeader) 0x0f else 0x1f
            working[0] = (working[0].toInt() xor (mask[0].toInt() and firstByteMask)).toByte()
            val pnLength = (working[0].toInt() and 0x03) + 1
            if (pnOffset + pnLength > packetEnd) return null
            for (i in 0 until pnLength) {
                working[pnOffset + i] = (working[pnOffset + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
            }

            var truncatedPn = 0L
            for (i in 0 until pnLength) {
                truncatedPn = (truncatedPn shl 8) or (working[pnOffset + i].toLong() and 0xFF)
            }
            val pn = decodePacketNumber(largestPn, truncatedPn, pnLength)

            val headerLen = pnOffset + pnLength
            val ciphertext = working.copyOfRange(headerLen, packetEnd)
            if (ciphertext.size < 16) return null // not even a full AEAD tag
            UnprotectedPacket(
                firstByte = working[0].toInt() and 0xFF,
                packetNumber = pn,
                pnLength = pnLength,
                keyPhaseBit = if (isLongHeader) 0 else (working[0].toInt() shr 2) and 0x01,
                aad = working.copyOfRange(0, headerLen),
                ciphertext = ciphertext,
            )
        } catch (e: Exception) {
            Log.d(TAG, "Header protection removal failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypts a **real on-wire** long-header protected packet (Handshake or 0-RTT, RFC 9000
     * §17.2.2/§17.2.3) with the RFC 9001 §5.3 Associated Data (the full unprotected header), keyed
     * from a TLS 1.3 traffic secret obtained from
     * [com.example.interception.tls.TlsKeyLogRegistry] via [deriveKeysFromTrafficSecret].
     *
     * This is the production Handshake/0-RTT decrypt entry point, and the reason
     * [decryptHandshakePacket]/[decryptProtectedPayload] are not: those authenticate with an **empty**
     * AAD, which cannot decrypt a genuine packet (every QUIC sender authenticates its header). See
     * [unprotectPacketHeader] for [pnOffset]/[packetEnd]/[largestPn] semantics. Returns null on any
     * failure (wrong secret, wrong space, truncated/coalesced boundary mismatch, bad tag).
     */
    fun decryptLongHeaderPacket(
        keys: PacketProtectionKeys,
        packet: ByteArray,
        pnOffset: Int,
        packetEnd: Int,
        largestPn: Long = -1L,
    ): DecryptedPacket? {
        val unprotected = unprotectPacketHeader(keys, packet, pnOffset, packetEnd, largestPn) ?: return null
        return try {
            val plaintext = aeadDecrypt(keys, nonceFor(keys.iv, unprotected.packetNumber), unprotected.ciphertext, unprotected.aad)
            DecryptedPacket(plaintext = plaintext, packetNumber = unprotected.packetNumber, keyPhaseBit = 0)
        } catch (e: Exception) {
            Log.d(TAG, "Long-header packet decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * Removes header protection from a **real on-wire** 1-RTT short-header packet (RFC 9000 §17.3).
     * A short header carries no Length field and no DCID length, so the caller must supply the
     * connection's [dcidLen] for this direction (learned from the peer's Source Connection ID during
     * the handshake) — the Packet Number field starts at `1 + dcidLen` — and the packet always
     * consumes the datagram remainder.
     *
     * The returned [UnprotectedPacket.keyPhaseBit] and [UnprotectedPacket.packetNumber] feed
     * [KeyPhaseManager.attemptUnprotectPacket], which performs the AEAD step under
     * auth-before-promotion. Header protection itself is **not** re-keyed by a key update (RFC 9001
     * §5.4: the header protection key does not change after a key update), so
     * [KeyPhaseManager.headerProtectionKeys] is the correct — and only correct — `hp` source here,
     * regardless of the packet's key phase: a rotated generation's derived `hp` is never on the wire.
     */
    fun unprotectShortHeaderPacket(
        keys: PacketProtectionKeys,
        packet: ByteArray,
        dcidLen: Int,
        largestPn: Long = -1L,
    ): UnprotectedPacket? {
        if (packet.isEmpty()) return null
        if ((packet[0].toInt() and 0x80) != 0) return null // long header, not 1-RTT
        if (dcidLen < 0 || dcidLen > 20) return null // RFC 9000 §17.2: CIDs are at most 20 bytes
        return unprotectPacketHeader(keys, packet, pnOffset = 1 + dcidLen, packetEnd = packet.size, largestPn = largestPn)
    }

    /**
     * Decrypts a **real on-wire** 1-RTT short-header packet with a single, fixed key generation (no
     * key-phase rotation). Used where the caller has exactly one set of application keys — the
     * key-update-aware path is [KeyPhaseManager.attemptUnprotectPacket] on top of
     * [unprotectShortHeaderPacket], which is what the live decoder uses. Returns null on any failure.
     */
    fun decryptShortHeaderPacket(
        keys: PacketProtectionKeys,
        packet: ByteArray,
        dcidLen: Int,
        largestPn: Long = -1L,
    ): DecryptedPacket? {
        val unprotected = unprotectShortHeaderPacket(keys, packet, dcidLen, largestPn) ?: return null
        return try {
            val plaintext = aeadDecrypt(keys, nonceFor(keys.iv, unprotected.packetNumber), unprotected.ciphertext, unprotected.aad)
            DecryptedPacket(
                plaintext = plaintext,
                packetNumber = unprotected.packetNumber,
                keyPhaseBit = unprotected.keyPhaseBit,
            )
        } catch (e: Exception) {
            Log.d(TAG, "Short-header packet decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * Locates an Initial packet's Packet Number offset and end (RFC 9000 §17.2.2) and decrypts it
     * with already-derived [keys] — the client-direction keys from [deriveClientInitialKeys] or the
     * server-direction keys from [deriveServerInitialKeys], both of which are derived from the
     * *client's original* Destination Connection ID (RFC 9001 §5.2), never from the DCID carried in
     * a server-sourced packet.
     *
     * This is what makes a server's Initial packet (carrying the ServerHello, and therefore the
     * negotiated cipher suite) decodable from a passive capture: [decryptInitialPacket] needs the
     * payload offsets, which only a caller that has already walked the Token Length + Length varints
     * can supply. Returns null for a non-Initial/truncated packet or on any decrypt failure.
     */
    fun decryptInitialPacketAt(
        packet: ByteArray,
        keys: InitialKeys,
        largestPn: Long = -1L,
    ): DecryptedInitial? {
        val span = locateInitialPayload(packet) ?: return null
        return decryptInitialPacket(packet, span.first, span.second, keys, largestPn)
    }

    /**
     * Walks an Initial long header (first byte, version, DCID, SCID, Token Length + Token, Length)
     * and returns `(pnOffset, packetEnd)` — the offsets [decryptInitialPacket] needs. Returns null
     * if the packet is not a long header or any field is truncated/out of bounds.
     */
    fun locateInitialPayload(packet: ByteArray): Pair<Int, Int>? {
        if (packet.size < 7) return null
        if ((packet[0].toInt() and 0x80) == 0) return null
        var pos = 5
        if (pos >= packet.size) return null
        val dcidLen = packet[pos].toInt() and 0xFF
        pos += 1 + dcidLen
        if (pos >= packet.size) return null
        val scidLen = packet[pos].toInt() and 0xFF
        pos += 1 + scidLen
        if (pos >= packet.size) return null
        val (tokenLen, tokenLenSize) = readVarint(packet, pos) ?: return null
        val afterToken = pos + tokenLenSize + tokenLen.toInt()
        if (afterToken < 0 || afterToken >= packet.size) return null
        val (payloadLen, lengthSize) = readVarint(packet, afterToken) ?: return null
        val pnOffset = afterToken + lengthSize
        val packetEnd = pnOffset + payloadLen.toInt()
        if (packetEnd <= pnOffset || packetEnd > packet.size) return null
        return pnOffset to packetEnd
    }

    /** AEAD nonce for [packetNumber]: `iv XOR packet number`, right-aligned (RFC 9001 §5.3). */
    private fun nonceFor(iv: ByteArray, packetNumber: Long): ByteArray {
        val nonce = iv.copyOf()
        for (i in 0 until 8) {
            val pnByte = ((packetNumber ushr (i * 8)) and 0xFF).toByte()
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor pnByte.toInt()).toByte()
        }
        return nonce
    }

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
     *
     * **Not the production entry point.** Because the AAD is empty, this cannot decrypt a genuine
     * on-wire packet: every QUIC sender authenticates the packet header as Associated Data (RFC 9001
     * §5.3). It is retained as the simplified payload-level primitive (and its test vectors);
     * [decryptLongHeaderPacket] is what the live decoder uses for real Handshake/0-RTT packets.
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

            // Empty AAD: this primitive authenticates only the ciphertext + packet number, not the
            // stripped header (callers that need full-header authentication keep using
            // [decryptInitialPacket], which AADs the whole long header). The suite's AEAD is selected
            // by [PacketProtectionKeys.cipherSuite] (AES/GCM or ChaCha20-Poly1305).
            aeadDecrypt(keys, nonceFor(keys.iv, pn), ciphertext)
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
     * ChaCha20 header-protection mask (RFC 9001 §5.4.4): the 16-byte [sample] is split into a 4-byte
     * little-endian ChaCha20 block counter (`sample[0..3]`) and a 12-byte nonce (`sample[4..15]`); the
     * 5-byte mask is the first 5 bytes of the ChaCha20 keystream block (keyed by the 32-byte `quic hp`
     * header-protection key) XORed with five zero bytes — i.e. the keystream itself.
     *
     * This computes the ChaCha20 block *directly* (RFC 8439 §2.3/§2.4) rather than via the JCE
     * `"ChaCha20"` stream cipher + `ChaCha20ParameterSpec`, because some installed JCE providers —
     * notably Conscrypt, which registers as the highest-priority provider in the host test JVM —
     * implement the raw `"ChaCha20"` cipher statefully/incorrectly for this counter/nonce mode,
     * producing a keystream that drifts across calls once other tests exercise the cipher. That
     * drift makes the protect-side and unprotect-side masks disagree for the same sample, corrupting
     * the recovered packet number and failing AEAD. The direct block function is deterministic and
     * provider-independent, so header protection is correct on both the host JVM and Android.
     */
    private fun chacha20HeaderProtectionMask(hpKey: ByteArray, sample16: ByteArray): ByteArray {
        val counter = (sample16[0].toInt() and 0xFF) or
            ((sample16[1].toInt() and 0xFF) shl 8) or
            ((sample16[2].toInt() and 0xFF) shl 16) or
            ((sample16[3].toInt() and 0xFF) shl 24)
        val nonce = sample16.copyOfRange(4, 16)
        return chacha20Block(hpKey, counter, nonce).copyOfRange(0, 5)
    }

    /**
     * ChaCha20 block function (RFC 8439 §2.3/§2.4): the 64-byte keystream block for 32-byte [key],
     * block [counter], and 12-byte [nonce], computed directly from the quarter-round ARX construction
     * — no JCE provider, so it is deterministic and identical on the host JVM and Android. The 16
     * state words are the four `"expand 32-byte k"` constants, the eight key words, the counter, and
     * the three nonce words (all little-endian); 20 rounds (10 double rounds) are applied and the
     * original state is added back (mod 2^32) before serializing little-endian.
     */
    private fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        state[12] = counter
        state[13] = leInt(nonce, 0)
        state[14] = leInt(nonce, 4)
        state[15] = leInt(nonce, 8)
        val x = state.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12); quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14); quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15); quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13); quarterRound(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) leIntPut(x[i] + state[i], out, i * 4)
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leIntPut(v: Int, b: ByteArray, off: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    /**
     * AEAD-decrypts [ciphertext] (with its trailing 16-byte tag appended) under [keys]' negotiated
     * suite, using [nonce] (the `quic iv` XOR packet number) and [aad] (empty by default -- the
     * stripped-payload primitive authenticates only the ciphertext + packet number; the 1-RTT
     * key-phase path AADs the short header). Branches on [PacketProtectionKeys.cipherSuite]:
     * AES/GCM via `GCMParameterSpec(128, nonce)` (the 128-bit tag length), or ChaCha20-Poly1305 via
     * `IvParameterSpec(nonce)` (its 16-byte tag is built into the algorithm). Throws on a bad tag /
     * wrong key / malformed input; callers wrap in try/catch.
     */
    private fun aeadDecrypt(keys: PacketProtectionKeys, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val suite = keys.cipherSuite
        val cipher = Cipher.getInstance(suite.aeadTransformation)
        if (suite.headerProtectionIsChaCha20) {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), IvParameterSpec(nonce))
        } else {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), GCMParameterSpec(128, nonce))
        }
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // ── Protect-side primitives (packet construction) ───────────────────────────────────
    //
    // QUIC header protection and AEAD are symmetric: the same mask construction and the same key
    // material protect a packet as unprotect it. These `internal` counterparts to
    // [headerProtectionMask]/[aeadDecrypt] exist so a caller inside this module can build a
    // genuinely protected packet — used by the deterministic QUIC fixtures in the unit-test source
    // set, which must produce real RFC 9001 wire bytes (correct header-protection mask, correct
    // `iv XOR pn` nonce, correct header AAD) rather than re-implementing the crypto and thereby
    // testing a second implementation instead of this one.

    /**
     * The RFC 9001 §5.4 header-protection mask for [keys]' negotiated suite over the 16-byte
     * [sample16] — AES-ECB for the AES-GCM suites, the ChaCha20 keystream construction for
     * TLS_CHACHA20_POLY1305_SHA256. At least 5 bytes: `[0]` masks the first byte, `[1..4]` the
     * packet number.
     */
    internal fun headerProtectionMaskFor(keys: PacketProtectionKeys, sample16: ByteArray): ByteArray =
        headerProtectionMask(keys, sample16)

    /** The AEAD nonce for [packetNumber] under [iv]: `iv XOR packet number`, right-aligned (RFC 9001 §5.3). */
    internal fun aeadNonce(iv: ByteArray, packetNumber: Long): ByteArray = nonceFor(iv, packetNumber)

    /**
     * AEAD-seals [plaintext] under [keys]' negotiated suite with [nonce] and [aad], returning
     * ciphertext with its trailing 16-byte tag — the exact inverse of [aeadDecrypt].
     */
    internal fun aeadSeal(keys: PacketProtectionKeys, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val suite = keys.cipherSuite
        val cipher = Cipher.getInstance(suite.aeadTransformation)
        if (suite.headerProtectionIsChaCha20) {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), IvParameterSpec(nonce))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, suite.aeadKeyAlgorithm), GCMParameterSpec(128, nonce))
        }
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Computes the Retry Integrity Tag (RFC 9001 §5.8 / RFC 9369 §3.3.3) for [retryPacketWithoutTag]
     * under [odcid] -- the protect-side counterpart of [validateRetryIntegrityTag], exposed inside the
     * module so deterministic fixtures can build a *genuinely valid* Retry packet. Returns null for an
     * unrecognized [version] or on any failure, never throws.
     */
    internal fun retryIntegrityTag(version: Int, odcid: ByteArray, retryPacketWithoutTag: ByteArray): ByteArray? {
        if (odcid.size > 255) return null
        val (key, nonce) = when (version) {
            VERSION_1 -> RETRY_INTEGRITY_KEY_V1 to RETRY_INTEGRITY_NONCE_V1
            VERSION_2 -> RETRY_INTEGRITY_KEY_V2 to RETRY_INTEGRITY_NONCE_V2
            else -> return null
        }
        return try {
            val pseudoPacket = ByteArray(1 + odcid.size + retryPacketWithoutTag.size)
            pseudoPacket[0] = odcid.size.toByte()
            System.arraycopy(odcid, 0, pseudoPacket, 1, odcid.size)
            System.arraycopy(retryPacketWithoutTag, 0, pseudoPacket, 1 + odcid.size, retryPacketWithoutTag.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(pseudoPacket)
            cipher.doFinal(ByteArray(0)) // empty plaintext: the output is exactly the 16-byte tag
        } catch (e: Exception) {
            Log.d(TAG, "Retry Integrity Tag computation failed: ${e.message}")
            null
        }
    }

    /**
     * TLS 1.3 HKDF-Expand-Label (RFC 8446 §7.1) over [hmacAlgorithm], exposed inside the module so
     * fixtures can derive the same next-generation key-update secret ([KeyPhaseManager]'s RFC 9001
     * §6.1 `"quic ku"` step) this object derives internally.
     */
    internal fun expandLabel(secret: ByteArray, label: String, length: Int, hmacAlgorithm: String = "HmacSHA256"): ByteArray =
        hkdfExpandLabel(secret, label, length, hmacAlgorithm)

    /**
     * Reads a QUIC variable-length integer (RFC 9000 §16) at [offset]: the top 2 bits of the first
     * byte encode the byte length (00=1, 01=2, 10=4, 11=8), the remaining bits + trailing bytes are
     * the big-endian value. Returns (value, byteCount) or null on truncation. Used by
     * [decryptHandshakePacket] to walk the Handshake long header's Length field.
     */    private fun readVarint(buf: ByteArray, offset: Int): Pair<Long, Int>? {
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
