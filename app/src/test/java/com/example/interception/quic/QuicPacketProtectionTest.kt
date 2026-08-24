package com.example.interception.quic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct unit coverage for the RFC-correct packet-protection primitives added to [QuicCrypto] for the
 * live decode path: [QuicCrypto.unprotectPacketHeader], [QuicCrypto.decryptLongHeaderPacket],
 * [QuicCrypto.unprotectShortHeaderPacket], [QuicCrypto.decryptShortHeaderPacket] and
 * [QuicCrypto.locateInitialPayload].
 *
 * The property that matters most here is the Associated Data. RFC 9001 §5.3 authenticates the packet
 * header — first byte through packet number — as AEAD AAD, so a decrypt path that passes an empty AAD
 * cannot read genuine traffic, and one that passes the *protected* header instead of the unprotected one
 * cannot either. Both mistakes are silent (they look like "wrong keys"), so they are asserted explicitly:
 * tampering with a header byte must break authentication.
 */
class QuicPacketProtectionTest {

    private val version = QuicCrypto.VERSION_1
    private val suite = QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256
    private val secret = ByteArray(32) { 0x3a }
    private val keys = requireNotNull(QuicCrypto.deriveKeysFromTrafficSecret(version, suite, secret))
    private val dcid = QuicWireFixtures.hex("0102030405060708")

    @Test
    fun `a long-header packet round-trips with the packet header as AEAD associated data`() {
        val frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.encryptedExtensions())
        val packet = QuicWireFixtures.handshakePacket(version, dcid, ByteArray(0), 7, frames, keys)
        val pnOffset = requireNotNull(handshakePnOffset(packet))

        val result = QuicCrypto.decryptLongHeaderPacket(keys, packet, pnOffset, packet.size)

        assertNotNull(result)
        assertEquals(7L, result!!.packetNumber)
        assertArrayEquals(frames, result.plaintext.copyOfRange(0, frames.size))
    }

    @Test
    fun `tampering with a header byte breaks authentication`() {
        val frames = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.encryptedExtensions())
        val packet = QuicWireFixtures.handshakePacket(version, dcid, ByteArray(0), 1, frames, keys)
        val pnOffset = requireNotNull(handshakePnOffset(packet))
        val tampered = packet.copyOf()
        tampered[6] = (tampered[6].toInt() xor 0x01).toByte() // flip a DCID byte inside the header

        assertNull(
            "the header is authenticated, so a modified header must fail AEAD",
            QuicCrypto.decryptLongHeaderPacket(keys, tampered, pnOffset, tampered.size),
        )
    }

    @Test
    fun `a short-header packet round-trips and exposes its key phase`() {
        val frames = QuicWireFixtures.streamFrame(0, 0, "1-rtt-body".toByteArray(), fin = true)
        val packet = QuicWireFixtures.shortHeaderPacket(dcid, 3, frames, keys, keyPhase = 1)

        val unprotected = QuicCrypto.unprotectShortHeaderPacket(keys, packet, dcid.size)
        assertNotNull(unprotected)
        assertEquals(1, unprotected!!.keyPhaseBit)
        assertEquals(3L, unprotected.packetNumber)
        assertEquals("AAD spans the first byte through the packet number", 1 + dcid.size + 1, unprotected.aad.size)

        val decrypted = QuicCrypto.decryptShortHeaderPacket(keys, packet, dcid.size)
        assertNotNull(decrypted)
        assertArrayEquals(frames, decrypted!!.plaintext.copyOfRange(0, frames.size))
    }

    @Test
    fun `a wrong DCID length shifts the packet number field and fails to decrypt`() {
        // A short header carries no CID length, so an observer that guesses it wrong reads the wrong
        // bytes as the packet number. That must fail closed, never produce plausible plaintext.
        val frames = QuicWireFixtures.streamFrame(0, 0, "body".toByteArray())
        val packet = QuicWireFixtures.shortHeaderPacket(dcid, 0, frames, keys)

        assertNull(QuicCrypto.decryptShortHeaderPacket(keys, packet, dcid.size - 1))
        assertNull(QuicCrypto.decryptShortHeaderPacket(keys, packet, dcid.size + 1))
        assertNotNull(QuicCrypto.decryptShortHeaderPacket(keys, packet, dcid.size))
    }

    @Test
    fun `an oversized or negative DCID length is rejected without throwing`() {
        val packet = QuicWireFixtures.shortHeaderPacket(dcid, 0, QuicWireFixtures.ping(), keys)

        assertNull(QuicCrypto.unprotectShortHeaderPacket(keys, packet, dcidLen = -1))
        assertNull("RFC 9000 §17.2 caps a CID at 20 bytes", QuicCrypto.unprotectShortHeaderPacket(keys, packet, dcidLen = 21))
    }

    @Test
    fun `a long header is rejected by the short-header entry point and vice versa`() {
        val longPacket = QuicWireFixtures.handshakePacket(version, dcid, ByteArray(0), 0, QuicWireFixtures.ping(), keys)
        assertNull(QuicCrypto.unprotectShortHeaderPacket(keys, longPacket, dcid.size))

        val shortPacket = QuicWireFixtures.shortHeaderPacket(dcid, 0, QuicWireFixtures.ping(), keys)
        assertNull("a short header has no Initial payload span", QuicCrypto.locateInitialPayload(shortPacket))
    }

    @Test
    fun `a packet too short for a header-protection sample is rejected`() {
        // RFC 9001 §5.4.2: the sample starts 4 bytes past the packet number field, so a packet without
        // those bytes cannot be unprotected at all -- it must be refused, not read out of bounds.
        val truncated = byteArrayOf(0x40) + dcid + byteArrayOf(0x00) + ByteArray(8)

        assertNull(QuicCrypto.unprotectShortHeaderPacket(keys, truncated, dcid.size))
    }

    @Test
    fun `locateInitialPayload finds the packet number span of a real Initial packet`() {
        val initialKeys = requireNotNull(QuicCrypto.deriveClientInitialKeys(version, dcid))
        val crypto = QuicWireFixtures.cryptoFrame(0, QuicWireFixtures.clientHello(QuicWireFixtures.clientRandom(), "span.test", "h3"))
        val packet = QuicWireFixtures.initialPacket(version, dcid, QuicWireFixtures.hex("aabb"), 0, crypto, initialKeys)

        val span = requireNotNull(QuicCrypto.locateInitialPayload(packet))
        val decrypted = requireNotNull(QuicCrypto.decryptInitialPacketAt(packet, initialKeys))

        assertEquals("the located span ends at the packet's own end", packet.size, span.second)
        assertArrayEquals(crypto, decrypted.cryptoData.copyOfRange(0, crypto.size))
    }

    @Test
    fun `locateInitialPayload rejects truncated and non-long-header input`() {
        assertNull(QuicCrypto.locateInitialPayload(ByteArray(0)))
        assertNull(QuicCrypto.locateInitialPayload(QuicWireFixtures.bytes(0xC0, 0x00, 0x00, 0x00)))
        assertNull(QuicCrypto.locateInitialPayload(QuicWireFixtures.bytes(0xC0, 0x00, 0x00, 0x00, 0x01, 0xFF, 0xFF)))
    }

    @Test
    fun `key-phase decryption uses each generation's own nonce`() {
        // attemptUnprotectPacket must build the nonce from the candidate generation's own iv; a single
        // pre-computed nonce (the older attemptUnprotect contract) cannot decrypt across a rotation.
        val manager = QuicCrypto.KeyPhaseManager(version, suite, secret)
        val gen0 = manager.currentProtectKeys()
        val gen1 = manager.initiateKeyUpdate()
        val packetNumber = 42L
        val aad = QuicWireFixtures.bytes(0x44, 0x01, 0x02)

        val gen1Ciphertext = QuicCrypto.aeadSeal(gen1, QuicCrypto.aeadNonce(gen1.iv, packetNumber), "new".toByteArray(), aad)
        val gen1Result = manager.attemptUnprotectPacket(keyPhaseBit = 1, packetNumber = packetNumber, aad = aad, ciphertext = gen1Ciphertext)
        assertNotNull(gen1Result)
        assertEquals("new", String(gen1Result!!.plaintext))
        assertEquals(1, gen1Result.generation)

        val gen0Ciphertext = QuicCrypto.aeadSeal(gen0, QuicCrypto.aeadNonce(gen0.iv, packetNumber), "old".toByteArray(), aad)
        val gen0Result = manager.attemptUnprotectPacket(keyPhaseBit = 0, packetNumber = packetNumber, aad = aad, ciphertext = gen0Ciphertext)
        assertNotNull("the retired generation is retained for delayed packets", gen0Result)
        assertEquals("old", String(gen0Result!!.plaintext))
        assertEquals(0, gen0Result.generation)
    }

    @Test
    fun `the header protection key does not change across key updates`() {
        val manager = QuicCrypto.KeyPhaseManager(version, suite, secret)
        val originalHp = manager.headerProtectionKeys().hp.copyOf()

        manager.initiateKeyUpdate()
        manager.initiateKeyUpdate()

        assertArrayEquals(
            "RFC 9001 §5.4 fixes header protection for the connection's lifetime",
            originalHp,
            manager.headerProtectionKeys().hp,
        )
    }

    /**
     * Recomputes the Packet Number offset of a Handshake long header (first byte + version + CIDs +
     * Length varint) so the test addresses the same offset the router hands the decoder.
     */
    private fun handshakePnOffset(packet: ByteArray): Int? {
        var pos = 5
        if (pos >= packet.size) return null
        pos += 1 + (packet[pos].toInt() and 0xFF)
        if (pos >= packet.size) return null
        pos += 1 + (packet[pos].toInt() and 0xFF)
        if (pos >= packet.size) return null
        val first = packet[pos].toInt() and 0xFF
        val varintLen = 1 shl (first shr 6)
        return pos + varintLen
    }
}
