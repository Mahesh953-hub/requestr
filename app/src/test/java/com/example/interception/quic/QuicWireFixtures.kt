package com.example.interception.quic

import com.example.interception.tls.KeyLogLabel
import com.example.interception.tls.TlsKeyLogRegistry

/**
 * Deterministic QUIC/TLS/HTTP-3 wire fixtures: builds **real** RFC 9000/9001/9114/9204 packets —
 * correct header-protection masks, correct `iv XOR packet number` AEAD nonces, correct packet-header
 * Associated Data — so the decode pipeline is exercised against genuine protected bytes rather than
 * against a mock.
 *
 * The protect side deliberately reuses [QuicCrypto]'s own module-internal primitives
 * ([QuicCrypto.headerProtectionMaskFor], [QuicCrypto.aeadNonce], [QuicCrypto.aeadSeal]) instead of
 * re-implementing AEAD/ChaCha20 here. Re-implementing them would mean a test that passes when two
 * independent bugs agree; reusing them means every test below still fails if the *packet layout*,
 * the *offsets*, the *AAD*, the *key derivation*, or the *key-phase logic* is wrong — which is what
 * these tests are actually about. The published RFC 9001 Appendix A vectors in `QuicCryptoTest`
 * remain the independent check that the primitives themselves are correct.
 *
 * Everything here is pure host-JVM code with no network and no Android dependency, so the fixtures
 * are byte-identical on every run (plan requirement: no reliance on emulator network egress).
 */
internal object QuicWireFixtures {

    // ── Encoders ─────────────────────────────────────────────────────────────────────────

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** QUIC variable-length integer (RFC 9000 §16). */
    fun varint(value: Long): ByteArray {
        require(value >= 0) { "varint must be non-negative" }
        return when {
            value <= 0x3F -> byteArrayOf(value.toByte())
            value <= 0x3FFF -> byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toByte())
            value <= 0x3FFF_FFFF -> byteArrayOf(
                (0x80 or (value shr 24).toInt()).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )
            else -> ByteArray(8) { i ->
                if (i == 0) (0xC0 or (value shr 56).toInt()).toByte()
                else ((value shr ((7 - i) * 8)) and 0xFF).toByte()
            }
        }
    }

    /** HPACK/QPACK prefix integer (RFC 7541 §5.1 / RFC 9204 §4.1.1). */
    fun prefixInt(value: Int, prefixBits: Int): ByteArray {
        val maxPrefix = (1 shl prefixBits) - 1
        if (value < maxPrefix) return byteArrayOf(value.toByte())
        val out = mutableListOf(maxPrefix.toByte())
        var v = value - maxPrefix
        while (v >= 128) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    /** QPACK raw (non-Huffman) string literal with an [prefixBits]-bit length prefix. */
    fun prefixString(value: String, prefixBits: Int): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        return prefixInt(raw.size, prefixBits) + raw
    }

    // ── QUIC frames (RFC 9000 §19) ───────────────────────────────────────────────────────

    /** CRYPTO frame (§19.6): type 0x06 + offset + length + data. */
    fun cryptoFrame(offset: Long, data: ByteArray): ByteArray =
        byteArrayOf(0x06) + varint(offset) + varint(data.size.toLong()) + data

    /** STREAM frame (§19.8) with explicit OFF+LEN bits (and FIN when [fin]). */
    fun streamFrame(streamId: Long, offset: Long, data: ByteArray, fin: Boolean = false): ByteArray {
        val type = 0x08 or 0x04 /* OFF */ or 0x02 /* LEN */ or (if (fin) 0x01 else 0x00)
        return byteArrayOf(type.toByte()) + varint(streamId) + varint(offset) + varint(data.size.toLong()) + data
    }

    /** PADDING frames (§19.1) — pure filler, used to reach a minimum packet size. */
    fun padding(count: Int): ByteArray = ByteArray(count)

    /** PING frame (§19.2). */
    fun ping(): ByteArray = byteArrayOf(0x01)

    /** CONNECTION_CLOSE, application variant 0x1d (§19.19): error code + reason length + reason. */
    fun connectionCloseApp(errorCode: Long, reason: String): ByteArray {
        val raw = reason.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x1d) + varint(errorCode) + varint(raw.size.toLong()) + raw
    }

    /** NEW_CONNECTION_ID frame (§19.15): sequence + retire-prior-to + CID + 16-byte reset token. */
    fun newConnectionId(sequence: Long, retirePriorTo: Long, cid: ByteArray): ByteArray =
        byteArrayOf(0x18) + varint(sequence) + varint(retirePriorTo) +
            byteArrayOf(cid.size.toByte()) + cid + ByteArray(16) { 0x5a }

    // ── TLS handshake messages (carried in CRYPTO frames, RFC 9001 §4.1.3) ───────────────

    /**
     * A structurally-valid TLS 1.3 ClientHello (RFC 8446 §4.1.2) with the given [random], SNI and
     * ALPN — what [QuicParser.parseInitial] recovers from a client Initial and where the connection's
     * key-log identity ([random]) comes from.
     */
    fun clientHello(random: ByteArray, sni: String, alpn: String, cipherSuite: Int = 0x1301): ByteArray {
        require(random.size == 32) { "ClientHello random must be 32 bytes" }
        val sniName = sni.toByteArray(Charsets.UTF_8)
        val sniExtData = u16(sniName.size + 3) + byteArrayOf(0x00) + u16(sniName.size) + sniName
        val sniExt = u16(0x0000) + u16(sniExtData.size) + sniExtData
        val alpnRaw = alpn.toByteArray(Charsets.UTF_8)
        val alpnExtData = u16(alpnRaw.size + 1) + byteArrayOf(alpnRaw.size.toByte()) + alpnRaw
        val alpnExt = u16(0x0010) + u16(alpnExtData.size) + alpnExtData
        val groupsData = u16(2) + u16(0x001d) // x25519
        val groupsExt = u16(0x000a) + u16(groupsData.size) + groupsData
        val extensions = sniExt + alpnExt + groupsExt

        val body = u16(0x0303) + random +
            byteArrayOf(0x00) + // legacy_session_id (empty)
            u16(2) + u16(cipherSuite) + // cipher suites
            byteArrayOf(0x01, 0x00) + // compression methods: [null]
            u16(extensions.size) + extensions
        return handshakeMessage(0x01, body)
    }

    /** A structurally-valid TLS 1.3 ServerHello (RFC 8446 §4.1.3) selecting [cipherSuiteId]. */
    fun serverHello(cipherSuiteId: Int, random: ByteArray = ByteArray(32) { 0x2b }): ByteArray {
        require(random.size == 32) { "ServerHello random must be 32 bytes" }
        val supportedVersions = u16(0x002b) + u16(2) + u16(0x0304)
        val body = u16(0x0304) + random +
            byteArrayOf(0x00) + // legacy_session_id_echo (empty)
            u16(cipherSuiteId) +
            byteArrayOf(0x00) + // legacy_compression_method
            u16(supportedVersions.size) + supportedVersions
        return handshakeMessage(0x02, body)
    }

    /** An EncryptedExtensions message (RFC 8446 §4.3.1) — a Handshake-level CRYPTO payload. */
    fun encryptedExtensions(): ByteArray = handshakeMessage(0x08, u16(0))

    /** `type(1) || length(3) || body` — the TLS handshake framing QUIC CRYPTO frames carry. */
    fun handshakeMessage(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), ((body.size shr 16) and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte()) + body

    private fun u16(v: Int): ByteArray = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    // ── HTTP/3 + QPACK (RFC 9114 / RFC 9204) ────────────────────────────────────────────

    /** HTTP/3 frame: type varint + length varint + payload (RFC 9114 §7.1). */
    fun h3Frame(type: Int, payload: ByteArray): ByteArray =
        varint(type.toLong()) + varint(payload.size.toLong()) + payload

    /** SETTINGS payload pair (varint id + varint value). */
    fun setting(id: Int, value: Int): ByteArray = varint(id.toLong()) + varint(value.toLong())

    /** A control-stream byte run: the 0x00 stream-type prefix followed by SETTINGS. */
    fun controlStreamWithSettings(maxTableCapacity: Int, blockedStreams: Int): ByteArray =
        bytes(0x00) + h3Frame(H3_SETTINGS, setting(0x01, maxTableCapacity) + setting(0x07, blockedStreams))

    /** QPACK Indexed Field Line referencing static entry [index] (RFC 9204 §4.5.3): `1T` + 6-bit index. */
    fun qpackStatic(index: Int): ByteArray {
        val enc = prefixInt(index, 6)
        enc[0] = (enc[0].toInt() or 0x80 or 0x40).toByte()
        return enc
    }

    /**
     * QPACK Literal With Name Reference to static [nameIndex] (RFC 9204 §4.5.5): `01NT` + 4-bit name
     * index + raw value. The 4-bit prefix only holds 0..14 directly, so indices from 15 up (e.g. 24 =
     * `:status`) continue into a following byte — handled by [prefixInt] rather than truncated.
     */
    fun qpackStaticNameLiteralValue(nameIndex: Int, value: String): ByteArray {
        val enc = prefixInt(nameIndex, 4)
        enc[0] = (enc[0].toInt() or 0x40 or 0x10).toByte() // 01, N=0, T=1 (static)
        return enc + prefixString(value, 7)
    }

    /** QPACK Literal With Literal Name (RFC 9204 §4.5.6): both name and value as raw strings. */
    fun qpackLiteral(name: String, value: String): ByteArray {
        val nameLen = prefixInt(name.toByteArray(Charsets.UTF_8).size, 3)
        nameLen[0] = (nameLen[0].toInt() or 0x20).toByte() // `001` pattern, N=0, H=0
        return nameLen + name.toByteArray(Charsets.UTF_8) + prefixString(value, 7)
    }

    /** A field-section prefix with Required Insert Count [ric] and Delta Base [deltaBase]. */
    fun qpackSectionPrefix(ric: Int, deltaBase: Int): ByteArray = bytes(ric, deltaBase)

    /**
     * A HEADERS frame for an HTTP/3 request built from static/literal representations only, so it
     * decodes with an empty QPACK dynamic table (Required Insert Count 0 → never blocked).
     */
    fun requestHeadersFrame(method: String, path: String, authority: String, scheme: String = "https", extra: Map<String, String> = emptyMap()): ByteArray {
        var section = qpackSectionPrefix(0, 0) +
            qpackLiteral(":method", method) +
            qpackLiteral(":path", path) +
            qpackLiteral(":scheme", scheme) +
            qpackStaticNameLiteralValue(0, authority) // static 0 = :authority
        for ((name, value) in extra) section += qpackLiteral(name, value)
        return h3Frame(H3_HEADERS, section)
    }

    /**
     * A HEADERS frame for an HTTP/3 response. `:status` is encoded as a literal value against static
     * name index 24 (`:status`), so any status code -- not just the few with their own static entry --
     * round-trips, and the section stays Required-Insert-Count 0 (never blocked).
     */
    fun responseHeadersFrame(status: String, extra: Map<String, String> = emptyMap()): ByteArray {
        var section = qpackSectionPrefix(0, 0) + qpackStaticNameLiteralValue(QPACK_STATIC_STATUS_NAME, status)
        for ((name, value) in extra) section += qpackLiteral(name, value)
        return h3Frame(H3_HEADERS, section)
    }

    /** An HTTP/3 DATA frame carrying [body]. */
    fun dataFrame(body: ByteArray): ByteArray = h3Frame(H3_DATA, body)

    // ── QUIC packets ─────────────────────────────────────────────────────────────────────

    /** [QuicCrypto.InitialKeys] adapted to the generic protection-key shape (Initial is always AES-128-GCM). */
    fun initialProtectionKeys(keys: QuicCrypto.InitialKeys): QuicCrypto.PacketProtectionKeys =
        QuicCrypto.PacketProtectionKeys(keys.key, keys.iv, keys.hp, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256)

    /**
     * Builds a real protected Initial packet (RFC 9000 §17.2.2) carrying [frames], protected with
     * [keys] (client- or server-direction Initial keys, both derived from the client's original DCID).
     */
    fun initialPacket(
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        packetNumber: Long,
        frames: ByteArray,
        keys: QuicCrypto.InitialKeys,
        pnLength: Int = 1,
        token: ByteArray = ByteArray(0),
    ): ByteArray = longHeaderPacket(
        typeBits = initialTypeBits(version),
        version = version,
        dcid = dcid,
        scid = scid,
        packetNumber = packetNumber,
        frames = frames,
        keys = initialProtectionKeys(keys),
        pnLength = pnLength,
        token = token,
    )

    /** Builds a real protected Handshake packet (RFC 9000 §17.2.4) carrying [frames]. */
    fun handshakePacket(
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        packetNumber: Long,
        frames: ByteArray,
        keys: QuicCrypto.PacketProtectionKeys,
        pnLength: Int = 1,
    ): ByteArray = longHeaderPacket(
        typeBits = handshakeTypeBits(version),
        version = version,
        dcid = dcid,
        scid = scid,
        packetNumber = packetNumber,
        frames = frames,
        keys = keys,
        pnLength = pnLength,
        token = null,
    )

    /** Builds a real protected 0-RTT packet (RFC 9000 §17.2.3) carrying [frames]. */
    fun zeroRttPacket(
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        packetNumber: Long,
        frames: ByteArray,
        keys: QuicCrypto.PacketProtectionKeys,
        pnLength: Int = 1,
    ): ByteArray = longHeaderPacket(
        typeBits = zeroRttTypeBits(version),
        version = version,
        dcid = dcid,
        scid = scid,
        packetNumber = packetNumber,
        frames = frames,
        keys = keys,
        pnLength = pnLength,
        token = null,
    )

    /**
     * Builds a Retry packet (RFC 9000 §17.2.5) with a genuinely valid Retry Integrity Tag (RFC 9001
     * §5.8) computed over [odcid] — the client's *original* Destination Connection ID, which is what
     * makes the tag verifiable only by an entity that actually observed the client's Initial.
     *
     * A Retry has no Length field, no packet number and no protected payload: it is the long header,
     * the token, and the trailing 16-byte tag. Pass [validTag] = false to corrupt the tag and reproduce
     * a spoofed or damaged Retry, which must be rejected rather than acted upon.
     */
    fun retryPacket(
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        token: ByteArray,
        odcid: ByteArray,
        validTag: Boolean = true,
    ): ByteArray {
        val firstByte = 0xC0 or (retryTypeBits(version) shl 4)
        val withoutTag = byteArrayOf(firstByte.toByte()) +
            byteArrayOf(
                ((version shr 24) and 0xFF).toByte(),
                ((version shr 16) and 0xFF).toByte(),
                ((version shr 8) and 0xFF).toByte(),
                (version and 0xFF).toByte(),
            ) +
            byteArrayOf(dcid.size.toByte()) + dcid +
            byteArrayOf(scid.size.toByte()) + scid +
            token
        val tag = requireNotNull(QuicCrypto.retryIntegrityTag(version, odcid, withoutTag)) {
            "retry integrity tag could not be computed for version $version"
        }
        val finalTag = if (validTag) tag else tag.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        return withoutTag + finalTag
    }

    /**
     * Builds a real protected 1-RTT short-header packet (RFC 9000 §17.3) carrying [frames], with
     * KEY_PHASE bit [keyPhase] (RFC 9001 §6) and the connection's [dcid] verbatim in the header.
     *
     * [headerProtectionKeys] is separate from [keys] on purpose: RFC 9001 §5.4 fixes the header
     * protection key for the whole connection ("the value not changing after a key update"), while a key
     * update replaces the AEAD key/iv. A key-rotation fixture therefore passes the new generation's
     * [keys] and keeps the original generation's [headerProtectionKeys] — exactly what a real endpoint
     * puts on the wire.
     */
    fun shortHeaderPacket(
        dcid: ByteArray,
        packetNumber: Long,
        frames: ByteArray,
        keys: QuicCrypto.PacketProtectionKeys,
        keyPhase: Int = 0,
        pnLength: Int = 1,
        headerProtectionKeys: QuicCrypto.PacketProtectionKeys = keys,
    ): ByteArray {
        val firstByte = 0x40 or ((keyPhase and 1) shl 2) or (pnLength - 1)
        val header = byteArrayOf(firstByte.toByte()) + dcid + truncatedPn(packetNumber, pnLength)
        val pnOffset = 1 + dcid.size
        val payload = padForHeaderProtectionSample(frames, pnLength)
        val ciphertext = QuicCrypto.aeadSeal(keys, QuicCrypto.aeadNonce(keys.iv, packetNumber), payload, header)
        return applyHeaderProtection(header + ciphertext, pnOffset, pnLength, headerProtectionKeys, longHeader = false)
    }

    private fun longHeaderPacket(
        typeBits: Int,
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        packetNumber: Long,
        frames: ByteArray,
        keys: QuicCrypto.PacketProtectionKeys,
        pnLength: Int,
        token: ByteArray?,
    ): ByteArray {
        val firstByte = 0xC0 or (typeBits shl 4) or (pnLength - 1)
        var prefix = byteArrayOf(firstByte.toByte()) +
            byteArrayOf(
                ((version shr 24) and 0xFF).toByte(),
                ((version shr 16) and 0xFF).toByte(),
                ((version shr 8) and 0xFF).toByte(),
                (version and 0xFF).toByte(),
            ) +
            byteArrayOf(dcid.size.toByte()) + dcid +
            byteArrayOf(scid.size.toByte()) + scid
        if (token != null) prefix += varint(token.size.toLong()) + token
        val payload = padForHeaderProtectionSample(frames, pnLength)
        // Length covers the packet number plus the AEAD ciphertext (frames + 16-byte tag).
        val length = (pnLength + payload.size + 16).toLong()
        val header = prefix + varint(length) + truncatedPn(packetNumber, pnLength)
        val pnOffset = header.size - pnLength
        val ciphertext = QuicCrypto.aeadSeal(keys, QuicCrypto.aeadNonce(keys.iv, packetNumber), payload, header)
        return applyHeaderProtection(header + ciphertext, pnOffset, pnLength, keys, longHeader = true)
    }

    /**
     * RFC 9001 §5.4.2 requires the 16-byte header-protection sample to start 4 bytes past the Packet
     * Number field, so a packet must carry at least `4 - pnLength` payload bytes beyond its AEAD tag
     * for the sample to exist -- which is why real senders pad small packets. PADDING frames (0x00,
     * RFC 9000 §19.1) are legal anywhere in a payload and are skipped by the frame walker, so padding
     * here reproduces genuine sender behaviour rather than working around the decoder.
     */
    private fun padForHeaderProtectionSample(frames: ByteArray, pnLength: Int): ByteArray {
        val minimum = (4 - pnLength).coerceAtLeast(0)
        return if (frames.size >= minimum) frames else frames + padding(minimum - frames.size)
    }

    /** RFC 9001 §5.4: masks the first byte's low 4/5 bits and the packet number with the HP mask. */
    private fun applyHeaderProtection(
        packet: ByteArray,
        pnOffset: Int,
        pnLength: Int,
        keys: QuicCrypto.PacketProtectionKeys,
        longHeader: Boolean,
    ): ByteArray {
        val sample = packet.copyOfRange(pnOffset + 4, pnOffset + 4 + 16)
        val mask = QuicCrypto.headerProtectionMaskFor(keys, sample)
        val out = packet.copyOf()
        val firstByteMask = if (longHeader) 0x0f else 0x1f
        out[0] = (out[0].toInt() xor (mask[0].toInt() and firstByteMask)).toByte()
        for (i in 0 until pnLength) {
            out[pnOffset + i] = (out[pnOffset + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
        }
        return out
    }

    private fun truncatedPn(packetNumber: Long, pnLength: Int): ByteArray =
        ByteArray(pnLength) { i -> ((packetNumber shr ((pnLength - 1 - i) * 8)) and 0xFF).toByte() }

    private fun initialTypeBits(version: Int): Int = if (version == QuicCrypto.VERSION_2) 1 else 0
    private fun zeroRttTypeBits(version: Int): Int = if (version == QuicCrypto.VERSION_2) 2 else 1
    private fun handshakeTypeBits(version: Int): Int = if (version == QuicCrypto.VERSION_2) 3 else 2
    private fun retryTypeBits(version: Int): Int = if (version == QuicCrypto.VERSION_2) 0 else 3

    // ── Key material ─────────────────────────────────────────────────────────────────────

    /**
     * Registers a full, self-consistent set of TLS 1.3 traffic secrets for [clientRandom] in the real
     * [TlsKeyLogRegistry] — exactly the shape an `SSLKEYLOGFILE` producer or the SDK key-log plane
     * emits, so the decoder resolves them through its production lookup path.
     */
    fun registerTrafficSecrets(
        clientRandom: ByteArray,
        clientHandshake: ByteArray = secret(0x11),
        serverHandshake: ByteArray = secret(0x22),
        clientApplication: ByteArray = secret(0x33),
        serverApplication: ByteArray = secret(0x44),
    ) {
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_HANDSHAKE_TRAFFIC_SECRET, clientRandom, clientHandshake)
        TlsKeyLogRegistry.record(KeyLogLabel.SERVER_HANDSHAKE_TRAFFIC_SECRET, clientRandom, serverHandshake)
        TlsKeyLogRegistry.record(KeyLogLabel.CLIENT_TRAFFIC_SECRET_0, clientRandom, clientApplication)
        TlsKeyLogRegistry.record(KeyLogLabel.SERVER_TRAFFIC_SECRET_0, clientRandom, serverApplication)
    }

    /** A deterministic 32-byte traffic secret (SHA-256-sized, valid for every QUIC AEAD suite). */
    fun secret(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }

    /** A deterministic 32-byte ClientHello random. */
    fun clientRandom(fill: Int = 0xAB): ByteArray = ByteArray(32) { fill.toByte() }

    /** The next key-update generation's secret (RFC 9001 §6.1 `"quic ku"`), for key-rotation fixtures. */
    fun nextKeyUpdateSecret(secret: ByteArray, suite: QuicCrypto.CipherSuite): ByteArray =
        QuicCrypto.expandLabel(secret, "quic ku", suite.hashLength, suite.hmacAlgorithm)

    const val H3_DATA = 0x00
    const val H3_HEADERS = 0x01
    const val H3_SETTINGS = 0x04

    /** QPACK static-table index of `:authority` (RFC 9204 Appendix A). */
    const val QPACK_STATIC_AUTHORITY = 0
    const val QPACK_STATIC_PATH_ROOT = 1
    const val QPACK_STATIC_METHOD_GET = 17
    const val QPACK_STATIC_SCHEME_HTTPS = 23
    const val QPACK_STATIC_STATUS_200 = 25

    /** QPACK static index whose *name* is `:status` (its value, 103, is replaced by a literal). */
    const val QPACK_STATIC_STATUS_NAME = 24
}
