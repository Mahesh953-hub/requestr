package com.example.interception.quic

import com.example.interception.tls.TlsKeyLogRegistry

/**
 * A reusable, fully deterministic QUIC connection scenario: builds the real protected packets of one
 * client↔server HTTP/3 connection (client Initial with a ClientHello, server Initial with a
 * ServerHello, server Handshake, and 1-RTT packets in both directions) and drives them through a real
 * [QuicDatagramRouter] into a real [QuicConnectionDecoder].
 *
 * The point is that nothing in the pipeline is stubbed: routing, coalesced-packet splitting, CID
 * routing, header-protection removal, AEAD, packet-number reconstruction, CRYPTO/STREAM reassembly,
 * QPACK and HTTP/3 framing all run on genuine RFC-shaped bytes, and the key material is resolved
 * through the production [TlsKeyLogRegistry] lookup keyed by the ClientHello random the decoder itself
 * recovered. Tests then assert only on observable outcomes.
 *
 * Connection-ID choreography follows RFC 9000 §7.2, because the decrypt path depends on it:
 * the client's *original* DCID keys **both** directions' Initial secrets (RFC 9001 §5.2); the client's
 * SCID becomes the server's 1-RTT DCID; the server's SCID becomes the client's 1-RTT DCID.
 */
internal class QuicConnectionScenario(
    val suite: QuicCrypto.CipherSuite = QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
    val version: Int = QuicCrypto.VERSION_1,
    val clientRandom: ByteArray = QuicWireFixtures.clientRandom(),
    val originalDcid: ByteArray = QuicWireFixtures.hex("8394c8f03e515708"),
    val clientScid: ByteArray = QuicWireFixtures.hex("c0ffee01"),
    val serverScid: ByteArray = QuicWireFixtures.hex("5e2fe200aabbccdd"),
    val sni: String = "quic.test",
    val alpn: String = "h3",
    maxTrackedStreams: Int = QuicConnectionDecoder.MAX_TRACKED_STREAMS,
    maxTrackedExchanges: Int = QuicConnectionDecoder.MAX_TRACKED_EXCHANGES,
    maxBodyBytes: Int = QuicConnectionDecoder.MAX_BODY_BYTES,
) {

    val cidTable = QuicCidTable()
    val router = QuicDatagramRouter(cidTable)
    val frameWalker = QuicFrameWalker(cidTable)

    val decoder = QuicConnectionDecoder(
        connectionId = CONNECTION_ID,
        initialVersion = version,
        frameWalker = frameWalker,
        cidTable = cidTable,
        secretLookup = { label, random -> TlsKeyLogRegistry.lookup(label, random) },
        maxTrackedStreams = maxTrackedStreams,
        maxTrackedExchanges = maxTrackedExchanges,
        maxBodyBytes = maxBodyBytes,
    )

    // Traffic secrets sized to the suite's HKDF hash, as a real TLS 1.3 key schedule emits them.
    val clientHandshakeSecret: ByteArray = ByteArray(suite.hashLength) { 0x11 }
    val serverHandshakeSecret: ByteArray = ByteArray(suite.hashLength) { 0x22 }
    val clientApplicationSecret: ByteArray = ByteArray(suite.hashLength) { 0x33 }
    val serverApplicationSecret: ByteArray = ByteArray(suite.hashLength) { 0x44 }

    val clientInitialKeys: QuicCrypto.InitialKeys =
        requireNotNull(QuicCrypto.deriveClientInitialKeys(version, originalDcid))
    val serverInitialKeys: QuicCrypto.InitialKeys =
        requireNotNull(QuicCrypto.deriveServerInitialKeys(version, originalDcid))

    val serverHandshakeKeys: QuicCrypto.PacketProtectionKeys =
        requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(version, suite, serverHandshakeSecret))
    val clientHandshakeKeys: QuicCrypto.PacketProtectionKeys =
        requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(version, suite, clientHandshakeSecret))
    val clientApplicationKeys: QuicCrypto.PacketProtectionKeys =
        requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(version, suite, clientApplicationSecret))
    val serverApplicationKeys: QuicCrypto.PacketProtectionKeys =
        requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(version, suite, serverApplicationSecret))

    /** The IANA cipher-suite code this connection's ServerHello will select. */
    val ianaCipherSuite: Int = when (suite) {
        QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256 -> 0x1301
        QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384 -> 0x1302
        QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256 -> 0x1303
    }

    /** Registers this connection's four traffic secrets in the production key-log registry. */
    fun registerSecrets() = QuicWireFixtures.registerTrafficSecrets(
        clientRandom = clientRandom,
        clientHandshake = clientHandshakeSecret,
        serverHandshake = serverHandshakeSecret,
        clientApplication = clientApplicationSecret,
        serverApplication = serverApplicationSecret,
    )

    /** The client's Initial packet: a real AEAD-protected ClientHello carrying SNI, ALPN and random. */
    fun clientInitial(packetNumber: Long = 0): ByteArray = QuicWireFixtures.initialPacket(
        version = version,
        dcid = originalDcid,
        scid = clientScid,
        packetNumber = packetNumber,
        frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.clientHello(clientRandom, sni, alpn, ianaCipherSuite)) +
            QuicWireFixtures.padding(8),
        keys = clientInitialKeys,
    )

    /**
     * The server's Initial packet: a real AEAD-protected ServerHello. Its keys derive from the
     * client's *original* DCID, not from the DCID this packet carries — which is exactly why only a
     * decoder holding per-connection state can decrypt it.
     */
    fun serverInitial(packetNumber: Long = 0, cipherSuiteId: Int = ianaCipherSuite): ByteArray =
        QuicWireFixtures.initialPacket(
            version = version,
            dcid = clientScid,
            scid = serverScid,
            packetNumber = packetNumber,
            frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.serverHello(cipherSuiteId)) +
                QuicWireFixtures.padding(8),
            keys = serverInitialKeys,
        )

    /** A server Handshake packet keyed from `SERVER_HANDSHAKE_TRAFFIC_SECRET`. */
    fun serverHandshake(
        packetNumber: Long = 0,
        cryptoPayload: ByteArray = QuicWireFixtures.encryptedExtensions(),
        keys: QuicCrypto.PacketProtectionKeys = serverHandshakeKeys,
    ): ByteArray = QuicWireFixtures.handshakePacket(
        version = version,
        dcid = clientScid,
        scid = serverScid,
        packetNumber = packetNumber,
        frames = QuicWireFixtures.cryptoFrame(0, cryptoPayload),
        keys = keys,
    )

    /**
     * A client 1-RTT packet addressed to the server's CID. [keys] default to the generation-0
     * application keys; header protection always uses the generation-0 `hp` key, which RFC 9001 §5.4
     * fixes for the connection's lifetime even across a key update.
     */
    fun clientOneRtt(
        packetNumber: Long,
        frames: ByteArray,
        keyPhase: Int = 0,
        keys: QuicCrypto.PacketProtectionKeys = clientApplicationKeys,
        pnLength: Int = 1,
    ): ByteArray = QuicWireFixtures.shortHeaderPacket(
        dcid = serverScid,
        packetNumber = packetNumber,
        frames = frames,
        keys = keys,
        keyPhase = keyPhase,
        pnLength = pnLength,
        headerProtectionKeys = clientApplicationKeys,
    )

    /** A server 1-RTT packet addressed to the client's CID (see [clientOneRtt] on header protection). */
    fun serverOneRtt(
        packetNumber: Long,
        frames: ByteArray,
        keyPhase: Int = 0,
        keys: QuicCrypto.PacketProtectionKeys = serverApplicationKeys,
        pnLength: Int = 1,
    ): ByteArray = QuicWireFixtures.shortHeaderPacket(
        dcid = clientScid,
        packetNumber = packetNumber,
        frames = frames,
        keys = keys,
        keyPhase = keyPhase,
        pnLength = pnLength,
        headerProtectionKeys = serverApplicationKeys,
    )

    /**
     * A server Retry packet (RFC 9000 §17.2.5) carrying a genuinely valid Retry Integrity Tag computed
     * over the client's original DCID, plus [token]. [scid] is the new connection ID the client must
     * switch to; pass [validTag] = false to produce the spoofed/corrupted case.
     */
    fun serverRetry(
        scid: ByteArray = retryScid,
        token: ByteArray = QuicWireFixtures.hex("d0d0cafe"),
        odcid: ByteArray = requireNotNull(originalDcidForRetry),
        validTag: Boolean = true,
    ): ByteArray = QuicWireFixtures.retryPacket(
        version = version,
        dcid = clientScid,
        scid = scid,
        token = token,
        odcid = odcid,
        validTag = validTag,
    )

    /** The connection ID a Retry in this scenario re-keys the connection to. */
    val retryScid: ByteArray = QuicWireFixtures.hex("11aa22bb33cc44dd")

    /** The DCID a Retry's integrity tag must be computed over — the client's original DCID. */
    val originalDcidForRetry: ByteArray? get() = originalDcid

    /** Routes a client→server datagram (dest port 443) and feeds every packet it carries. */
    fun feedOutbound(datagram: ByteArray): List<QuicConnectionDecoder.IngestResult> =
        router.ingest(datagram, CLIENT_IP, CLIENT_PORT, SERVER_IP, QUIC_PORT).map { decoder.ingest(it) }

    /** Routes a server→client datagram (source port 443) and feeds every packet it carries. */
    fun feedInbound(datagram: ByteArray): List<QuicConnectionDecoder.IngestResult> =
        router.ingest(datagram, SERVER_IP, QUIC_PORT, CLIENT_IP, CLIENT_PORT).map { decoder.ingest(it) }

    /**
     * The client's retried Initial: after a Retry the client's DCID becomes the Retry's SCID, and both
     * directions' Initial secrets re-derive from that new CID (RFC 9001 §5.2), so this packet is
     * protected with keys derived from [retryScid] rather than [originalDcid].
     */
    fun retriedClientInitial(
        packetNumber: Long = 1,
        random: ByteArray = retriedClientRandom,
        newDcid: ByteArray = retryScid,
    ): ByteArray = QuicWireFixtures.initialPacket(
        version = version,
        dcid = newDcid,
        scid = clientScid,
        packetNumber = packetNumber,
        frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.clientHello(random, sni, alpn, ianaCipherSuite)) +
            QuicWireFixtures.padding(8),
        keys = requireNotNull(QuicCrypto.deriveClientInitialKeys(version, newDcid)),
        token = QuicWireFixtures.hex("d0d0cafe"),
    )

    /** The server's Initial in the retried exchange, keyed from the post-Retry connection ID. */
    fun retriedServerInitial(
        packetNumber: Long = 0,
        cipherSuiteId: Int = ianaCipherSuite,
        newDcid: ByteArray = retryScid,
    ): ByteArray = QuicWireFixtures.initialPacket(
        version = version,
        dcid = clientScid,
        scid = serverScid,
        packetNumber = packetNumber,
        frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.serverHello(cipherSuiteId)) +
            QuicWireFixtures.padding(8),
        keys = requireNotNull(QuicCrypto.deriveServerInitialKeys(version, newDcid)),
    )

    /** The ClientHello random of the retried handshake — a new value, as a real client sends. */
    val retriedClientRandom: ByteArray = QuicWireFixtures.clientRandom(0x7c)

    /**
     * Drives the handshake far enough for 1-RTT decryption: the ClientHello establishes identity and
     * the original DCID, the ServerHello locks the negotiated suite, and the server Handshake packet
     * proves the key-log-sourced Handshake keys work.
     */
    fun completeHandshake() {
        feedOutbound(clientInitial())
        feedInbound(serverInitial())
        feedInbound(serverHandshake())
    }

    companion object {
        const val CLIENT_IP = "10.0.0.7"
        const val SERVER_IP = "93.184.216.34"
        const val CLIENT_PORT = 54321
        const val QUIC_PORT = 443

        /**
         * The routing key the real [QuicDatagramRouter] resolves for this 5-tuple, in the canonical
         * client->server orientation it uses. The decoder is created with exactly this id so CID-table
         * interactions (NEW_CONNECTION_ID registration, Retry re-keying) behave as they do in
         * production, where the decoder's id *is* the router's routing key.
         */
        val CONNECTION_ID: String = com.example.correlation.ConnectionKey.forQuic(CLIENT_IP, CLIENT_PORT, SERVER_IP, QUIC_PORT)
    }
}
