package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies [QuicCrypto] against official RFC test vectors: RFC 9001 Appendix A.1/A.2 (v1 client
 * Initial keys + packet), RFC 9001 Appendix A.3 and RFC 9369 Appendix A.1/A.3 (v1/v2 server
 * Initial keys + packet), RFC 9000 Appendix A.3 (packet-number reconstruction), plus a generic
 * traffic-secret-to-AEAD-keys cross-check for both AES-128-GCM-SHA256 and AES-256-GCM-SHA384. If
 * these ever fail, the Initial-packet decryption wired into [QuicParser]/[PacketPump] is broken
 * and must not be trusted.
 */
class QuicCryptoTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `derives RFC 9001 Appendix A1 client initial keys`() {
        val dcid = hex("8394c8f03e515708")
        val keys = QuicCrypto.deriveClientInitialKeys(QuicCrypto.VERSION_1, dcid)
        assertNotNull(keys)
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", toHex(keys!!.key))
        assertEquals("fa044b2f42a3fd3b46fb255c", toHex(keys.iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", toHex(keys.hp))
    }

    @Test
    fun `decrypts RFC 9001 Appendix A2 sample client initial packet`() {
        // Full protected packet from RFC 9001 Appendix A.2.
        val packet = hex(
            "c000000001088394c8f03e5157080000 449e7b9aec34d1b1c98dd7689fb8ec11" +
                "d242b123dc9bd8bab936b47d92ec356c 0bab7df5976d27cd449f63300099f399" +
                "1c260ec4c60d17b31f8429157bb35a12 82a643a8d2262cad67500cadb8e7378c" +
                "8eb7539ec4d4905fed1bee1fc8aafba1 7c750e2c7ace01e6005f80fcb7df6212" +
                "30c83711b39343fa028cea7f7fb5ff89 eac2308249a02252155e2347b63d58c5" +
                "457afd84d05dfffdb20392844ae81215 4682e9cf012f9021a6f0be17ddd0c208" +
                "4dce25ff9b06cde535d0f920a2db1bf3 62c23e596d11a4f5a6cf3948838a3aec" +
                "4e15daf8500a6ef69ec4e3feb6b1d98e 610ac8b7ec3faf6ad760b7bad1db4ba3" +
                "485e8a94dc250ae3fdb41ed15fb6a8e5 eba0fc3dd60bc8e30c5c4287e53805db" +
                "059ae0648db2f64264ed5e39be2e20d8 2df566da8dd5998ccabdae053060ae6c" +
                "7b4378e846d29f37ed7b4ea9ec5d82e7 961b7f25a9323851f681d582363aa5f8" +
                "9937f5a67258bf63ad6f1a0b1d96dbd4 faddfcefc5266ba6611722395c906556" +
                "be52afe3f565636ad1b17d508b73d874 3eeb524be22b3dcbc2c7468d54119c74" +
                "68449a13d8e3b95811a198f3491de3e7 fe942b330407abf82a4ed7c1b311663a" +
                "c69890f4157015853d91e923037c227a 33cdd5ec281ca3f79c44546b9d90ca00" +
                "f064c99e3dd97911d39fe9c5d0b23a22 9a234cb36186c4819e8b9c5927726632" +
                "291d6a418211cc2962e20fe47feb3edf 330f2c603a9d48c0fcb5699dbfe58964" +
                "25c5bac4aee82e57a85aaf4e2513e4f0 5796b07ba2ee47d80506f8d2c25e50fd" +
                "14de71e6c418559302f939b0e1abd576 f279c4b2e0feb85c1f28ff18f58891ff" +
                "ef132eef2fa09346aee33c28eb130ff2 8f5b766953334113211996d20011a198" +
                "e3fc433f9f2541010ae17c1bf202580f 6047472fb36857fe843b19f5984009dd" +
                "c324044e847a4f4a0ab34f719595de37 252d6235365e9b84392b061085349d73" +
                "203a4a13e96f5432ec0fd4a1ee65accd d5e3904df54c1da510b0ff20dcc0c77f" +
                "cb2c0e0eb605cb0504db87632cf3d8b4 dae6e705769d1de354270123cb11450e" +
                "fc60ac47683d7b8d0f811365565fd98c 4c8eb936bcab8d069fc33bd801b03ade" +
                "a2e1fbc5aa463d08ca19896d2bf59a07 1b851e6c239052172f296bfb5e724047" +
                "90a2181014f3b94a4e97d117b4381303 68cc39dbb2d198065ae3986547926cd2" +
                "162f40a29f0c3c8745c0f50fba3852e5 66d44575c29d39a03f0cda721984b6f4" +
                "40591f355e12d439ff150aab7613499d bd49adabc8676eef023b15b65bfc5ca0" +
                "6948109f23f350db82123535eb8a7433 bdabcb909271a6ecbcb58b936a88cd4e" +
                "8f2e6ff5800175f113253d8fa9ca8885 c2f552e657dc603f252e1a8e308f76f0" +
                "be79e2fb8f5d5fbbe2e30ecadd220723 c8c0aea8078cdfcb3868263ff8f09400" +
                "54da48781893a7e49ad5aff4af300cd8 04a6b6279ab3ff3afb64491c85194aab" +
                "760d58a606654f9f4400e8b38591356f bf6425aca26dc85244259ff2b19c41b9" +
                "f96f3ca9ec1dde434da7d2d392b905dd f3d1f9af93d1af5950bd493f5aa731b4" +
                "056df31bd267b6b90a079831aaf579be 0a39013137aac6d404f518cfd4684064" +
                "7e78bfe706ca4cf5e9c5453e9f7cfd2b 8b4c8d169a44e55c88d4a9a7f9474241" +
                "e221af44860018ab0856972e194cd934"
        )
        val dcid = hex("8394c8f03e515708")
        val keys = QuicCrypto.deriveClientInitialKeys(QuicCrypto.VERSION_1, dcid)!!

        // Header layout: [0]=first byte, [1..4]=version, [5]=DCID len(8), [6..13]=DCID,
        // [14]=SCID len(0), [15]=Token len varint(0x00), [16]=Length varint (2-byte: 0x449e),
        // pn starts at 18, 4-byte packet number.
        val pnOffset = 18
        val payloadEnd = packet.size

        val result = QuicCrypto.decryptInitialPacket(packet, pnOffset, payloadEnd, keys)
        assertNotNull("decryption should succeed with correct RFC test-vector keys", result)
        assertEquals(2L, result!!.packetNumber)

        // First bytes of the decrypted CRYPTO frame payload should be the CRYPTO frame header
        // (frame type 0x06) followed by the ClientHello (handshake type 0x01).
        assertEquals(0x06, result.cryptoData[0].toInt() and 0xFF)
        // offset varint (0x00) then length varint (0x40 0xf1 = 2-byte length, 0x00f1 & 0x3fff = 0xf1 = 241)
        assertEquals(0x00, result.cryptoData[1].toInt() and 0xFF)
    }

    // ── Server Initial (RFC 9001 §5.2/Appendix A.1, A.3) ────────────────────────────

    @Test
    fun `derives RFC 9001 Appendix A1 server initial keys`() {
        // Same initial_secret (same dcid, same v1 salt) as the client-direction test above, but
        // expanded with the "server in" label instead of "client in" -- RFC 9001 Appendix A.1's
        // published server_initial_secret-derived key/iv/hp.
        val dcid = hex("8394c8f03e515708")
        val keys = QuicCrypto.deriveServerInitialKeys(QuicCrypto.VERSION_1, dcid)
        assertNotNull(keys)
        assertEquals("cf3a5331653c364c88f0f379b6067e37", toHex(keys!!.key))
        assertEquals("0ac1493ca1905853b0bba03e", toHex(keys.iv))
        assertEquals("c206b8d9b9f0f37644430b490eeaa314", toHex(keys.hp))
    }

    @Test
    fun `decrypts RFC 9001 Appendix A3 sample server initial packet (ServerHello)`() {
        // Full protected server Initial packet from RFC 9001 Appendix A.3 -- the server's
        // response to the client Initial decrypted above, carrying an ACK frame + a CRYPTO
        // frame with the ServerHello (no PADDING).
        val packet = hex(
            "cf000000010008f067a5502a4262b500 4075c0d95a482cd0991cd25b0aac406a" +
                "5816b6394100f37a1c69797554780bb3 8cc5a99f5ede4cf73c3ec2493a1839b3" +
                "dbcba3f6ea46c5b7684df3548e7ddeb9 c3bf9c73cc3f3bded74b562bfb19fb84" +
                "022f8ef4cdd93795d77d06edbb7aaf2f 58891850abbdca3d20398c276456cbc4" +
                "2158407dd074ee"
        )
        val dcid = hex("8394c8f03e515708") // still the client-chosen DCID -- Initial secrets never key off SCID
        val keys = QuicCrypto.deriveServerInitialKeys(QuicCrypto.VERSION_1, dcid)!!

        // Header layout: first byte(1) + version(4) + DCID len(1, zero-length here) +
        // SCID len(1) + SCID(8 bytes: f067a5502a4262b5) + Token len varint(1, zero) +
        // Length varint (2-byte: 0x4075) -> pn starts at offset 18, 2-byte packet number.
        val pnOffset = 18
        val payloadEnd = packet.size

        val result = QuicCrypto.decryptInitialPacket(packet, pnOffset, payloadEnd, keys)
        assertNotNull("server Initial should decrypt with correct RFC test-vector server keys", result)
        assertEquals(1L, result!!.packetNumber)

        // The decrypted payload starts with an ACK frame (type 0x02), followed later by the
        // CRYPTO frame (type 0x06) carrying the ServerHello (handshake type 0x02).
        assertEquals(0x02, result.cryptoData[0].toInt() and 0xFF) // ACK frame type
        val cryptoFrameOffset = result.cryptoData.indexOfFirst { it.toInt() and 0xFF == 0x06 }
        assertTrue("CRYPTO frame (type 0x06) should be present", cryptoFrameOffset >= 0)
        // CRYPTO frame: type(1) + offset varint(1, =0x00) + length varint(2, =0x405a) + data.
        // First data byte is the ServerHello's handshake-type byte, 0x02.
        val serverHelloTypeOffset = cryptoFrameOffset + 1 + 1 + 2
        assertEquals(0x02, result.cryptoData[serverHelloTypeOffset].toInt() and 0xFF)
    }

    @Test
    fun `derives and decrypts RFC 9369 v2 server initial (ServerHello)`() {
        // RFC 9369 Appendix A.1/A.3 -- same ServerHello plaintext and DCID as the v1 vectors
        // above, but with v2's different initial_salt, "quicv2 " labels, and shifted long-header
        // packet type bits (Initial=0b01 in v2, vs 0b00 in v1 -- irrelevant to key derivation
        // itself, which never reads the type bits, but included here so the packet used is a
        // genuine v2-encoded Initial rather than a v1 packet with the version field swapped).
        val dcid = hex("8394c8f03e515708")
        val keys = QuicCrypto.deriveServerInitialKeys(QuicCrypto.VERSION_2, dcid)
        assertNotNull(keys)
        assertEquals("82db637861d55e1d011f19ea71d5d2a7", toHex(keys!!.key))
        assertEquals("dd13c276499c0249d3310652", toHex(keys.iv))
        assertEquals("edf6d05c83121201b436e16877593c3a", toHex(keys.hp))

        val packet = hex(
            "dc6b3343cf0008f067a5502a4262b500 4075d92faaf16f05d8a4398c47089698" +
                "baeea26b91eb761d9b89237bbf872630 17915358230035f7fd3945d88965cf17" +
                "f9af6e16886c61bfc703106fbaf3cb4c fa52382dd16a393e42757507698075b2" +
                "c984c707f0a0812d8cd5a6881eaf21ce da98f4bd23f6fe1a3e2c43edd9ce7ca8" +
                "4bed8521e2e140"
        )
        val pnOffset = 18
        val result = QuicCrypto.decryptInitialPacket(packet, pnOffset, packet.size, keys)
        assertNotNull("v2 server Initial should decrypt with the v2-labelled server keys", result)
        assertEquals(1L, result!!.packetNumber)
        assertEquals(0x02, result.cryptoData[0].toInt() and 0xFF) // ACK frame type, same plaintext as v1
    }

    // ── Generic traffic-secret key derivation (AES-128/AES-256) ─────────────────────

    @Test
    fun `deriveKeysFromTrafficSecret for AES-128-GCM-SHA256 matches the Initial-secret-derived keys`() {
        // Cross-check: RFC 9001 Appendix A.1's published client_initial_secret run through the
        // *generic* traffic-secret path (SHA-256, 16-byte AEAD key) must land on the exact same
        // key/iv/hp the RFC publishes for that secret -- proving the generic function is not a
        // reimplementation drifting from the Initial-specific one, just the same HKDF-Expand-Label
        // machinery generalized over cipher suite.
        val clientInitialSecret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1,
            QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
            clientInitialSecret,
        )
        assertNotNull(keys)
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", toHex(keys!!.key))
        assertEquals("fa044b2f42a3fd3b46fb255c", toHex(keys.iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", toHex(keys.hp))
    }

    @Test
    fun `deriveKeysFromTrafficSecret for AES-256-GCM-SHA384 derives correctly-sized SHA-384 keys`() {
        // RFC 9001 Appendix A only publishes AES-128-GCM-SHA256 vectors, so this uses a fixed,
        // reproducible 48-byte secret (SHA-384 digest of a literal label -- deterministic, not
        // random) and asserts against independently-computed HKDF-Expand-Label output verified
        // two ways outside this codebase: (1) by hand against RFC 8446 §7.1's HkdfLabel encoding,
        // and (2) against Python's `cryptography` library's HKDFExpand with the identical info
        // block. This is what guards TLS_AES_256_GCM_SHA384 traffic-secret derivation (SHA-384
        // HMAC, 32-byte AEAD key) from silently falling back to SHA-256 or the wrong key length.
        val secret = hex("c7bfee2fd77625391d210c6f07e8a3cd0d96ade9d3dab25759b2fb5e74433efb5a2d6be4f52a83175b439d75fe8f9ee8")
        assertEquals(48, secret.size)

        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1,
            QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384,
            secret,
        )
        assertNotNull(keys)
        assertEquals(32, keys!!.key.size) // AES-256 key size
        assertEquals(12, keys.iv.size) // AEAD nonce length is always 12 bytes regardless of suite
        assertEquals(32, keys.hp.size) // AES-256-ECB header-protection key size
        assertEquals("2db5daceb5a997b2fa9a595e92ae630730a9fb68b28444beff90791bd7265b7d", toHex(keys.key))
        assertEquals("363ac22dcae4b6ed74bba362", toHex(keys.iv))
        assertEquals("ab49a320d5387c34acf79ec4352c4f590b4f8beec553bb47f86ba8d478862495", toHex(keys.hp))
    }

    @Test
    fun `deriveKeysFromTrafficSecret uses the quicv2 labels for QUIC version 2`() {
        // Same AES-256-GCM-SHA384 secret as above, but under VERSION_2 -- must expand with the
        // "quicv2 key"/"quicv2 iv"/"quicv2 hp" labels (RFC 9369 §3.3.2), producing different key
        // material than the v1 labels even though the secret and suite are identical.
        val secret = hex("c7bfee2fd77625391d210c6f07e8a3cd0d96ade9d3dab25759b2fb5e74433efb5a2d6be4f52a83175b439d75fe8f9ee8")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_2,
            QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384,
            secret,
        )
        assertNotNull(keys)
        assertEquals("5b948f35bb610a1a930f27a62f3e03ac4404036378a0e61ca1eaa670de4af152", toHex(keys!!.key))
        assertEquals("5c8d42ff624bdb637cf07cc3", toHex(keys.iv))
        assertEquals("8f3fb1e37182f979193a3fdebe86abfe3b1404822d73dbea725a64db49928077", toHex(keys.hp))
    }

    @Test
    fun `deriveKeysFromTrafficSecret accepts a short or empty secret without throwing`() {
        // A traffic secret shorter than the suite's hash length (or empty) is not something a
        // real TLS 1.3 stack ever produces, but this function must never propagate an exception
        // out to a caller processing untrusted network input (same defensive contract as every
        // other decode/derive function in this object) -- HMAC itself accepts keys of any
        // length (RFC 2104), so both calls below succeed rather than null or throw.
        assertNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(
                QuicCrypto.VERSION_1,
                QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384,
                ByteArray(0),
            ),
        )
        assertNotNull(
            QuicCrypto.deriveKeysFromTrafficSecret(
                QuicCrypto.VERSION_1,
                QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256,
                hex("aabbcc"),
            ),
        )
    }

    // ── Packet-number reconstruction (RFC 9000 §17.1 / Appendix A.3) ────────────────

    @Test
    fun `decodePacketNumber reproduces the RFC 9000 Appendix A3 worked example`() {
        // largest_pn = 0xa82f30ea, truncated 16-bit value 0x9b32 decodes to 0xa82f9b32.
        assertEquals(0xa82f9b32L, QuicCrypto.decodePacketNumber(0xa82f30eaL, 0x9b32L, 2))
    }

    @Test
    fun `decodePacketNumber returns the truncated value for the first packet in a space`() {
        // largestPn = -1 (sentinel: nothing processed yet) must reproduce exactly the RFC 9001
        // Appendix A.2/A.3 first-Initial-packet packet numbers this file already decrypts above:
        // pn=2 with a 4-byte field (client), pn=1 with a 2-byte field (server).
        assertEquals(2L, QuicCrypto.decodePacketNumber(-1L, 2L, 4))
        assertEquals(1L, QuicCrypto.decodePacketNumber(-1L, 1L, 2))
    }

    @Test
    fun `decodePacketNumber handles the plain non-wrapping case`() {
        assertEquals(101L, QuicCrypto.decodePacketNumber(100L, 101L, 1))
    }

    @Test
    fun `decodePacketNumber adds one window when the candidate falls below the low edge`() {
        // largest=199 (expected=200), pn_nbits=8 (window 256, half-window 128). A truncated
        // value of 10 decodes literally to candidate=10, which is <= expected-halfWindow (72),
        // so the algorithm must add a full window to land on 266, not stay at 10.
        assertEquals(266L, QuicCrypto.decodePacketNumber(199L, 10L, 1))
    }

    @Test
    fun `decodePacketNumber subtracts one window when the candidate overshoots the high edge`() {
        // largest=299 (expected=300), pn_nbits=8. expected's high bits (300 & ~0xFF = 256) OR'd
        // with a truncated value of 200 yields candidate=456, which is above expected+halfWindow
        // (428), so the algorithm must subtract a window to land back on 200, not stay at 456.
        assertEquals(200L, QuicCrypto.decodePacketNumber(299L, 200L, 1))
    }

    // ── Version-aware long-header packet-type classification (RFC 9000 §17.2 / RFC 9369 §3.2) ──

    @Test
    fun `classifyLongHeaderPacketType maps v1 type bits per RFC 9000 Table 5`() {
        // v1: Initial=0b00, 0-RTT=0b01, Handshake=0b10, Retry=0b11 -- packed into bits 4-5 of a
        // long-header first byte (header form=1, fixed bit=1 -> high nibble starts at 0xC/0xD/
        // 0xE/0xF depending on the type bits, low nibble irrelevant to this function).
        assertEquals(
            QuicCrypto.LongHeaderPacketType.INITIAL,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xC0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.ZERO_RTT,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xD0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.HANDSHAKE,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xE0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.RETRY,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xF0),
        )
    }

    @Test
    fun `classifyLongHeaderPacketType maps v2 shifted type bits per RFC 9369 section 3-2`() {
        // v2 shifts every value by one (mod 4) vs v1: the *same* 2 bits (0b00) that mean Initial
        // in v1 mean Retry in v2, and vice versa -- this is the exact ossification-resistance
        // property under test, so each assertion below uses the v1 bit pattern that would
        // decode to a *different* type if this function ever fell back to v1-only constants.
        assertEquals(
            QuicCrypto.LongHeaderPacketType.RETRY, // v1's Initial bits (0b00) mean Retry in v2
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xC0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.INITIAL, // v1's 0-RTT bits (0b01) mean Initial in v2
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xD0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.ZERO_RTT, // v1's Handshake bits (0b10) mean 0-RTT in v2
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xE0),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.HANDSHAKE, // v1's Retry bits (0b11) mean Handshake in v2
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xF0),
        )
    }

    @Test
    fun `classifyLongHeaderPacketType classifies the real RFC test-vector Initial and Retry packets`() {
        // First byte of the RFC 9001 A.2 client Initial (0xc3, before header-protection removal
        // -- the type bits at 0x30 are unprotected for a long header) must classify as Initial;
        // the RFC 9001 A.4 v1 Retry's first byte (0xff) must classify as Retry.
        assertEquals(
            QuicCrypto.LongHeaderPacketType.INITIAL,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xc3),
        )
        assertEquals(
            QuicCrypto.LongHeaderPacketType.RETRY,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_1, 0xff),
        )
        // RFC 9369 A.2 v2 client Initial first byte 0xd7 -> type bits 0b01 -> Initial in v2.
        assertEquals(
            QuicCrypto.LongHeaderPacketType.INITIAL,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xd7),
        )
        // RFC 9369 A.4 v2 Retry first byte 0xcf -> type bits 0b00 -> Retry in v2.
        assertEquals(
            QuicCrypto.LongHeaderPacketType.RETRY,
            QuicCrypto.classifyLongHeaderPacketType(QuicCrypto.VERSION_2, 0xcf),
        )
    }

    @Test
    fun `classifyLongHeaderPacketType returns null for an unrecognized version`() {
        assertNull(QuicCrypto.classifyLongHeaderPacketType(0x12345678, 0xc3))
    }

    // ── Retry Integrity Tag validation (RFC 9001 §5.8 v1 / RFC 9369 §3.3.3 v2) ──────

    @Test
    fun `validateRetryIntegrityTag accepts the RFC 9001 Appendix A4 v1 Retry`() {
        // Full v1 Retry packet from RFC 9001 Appendix A.4, split into the packet-minus-tag and
        // the trailing 16-byte tag; the ODCID is the client-chosen DCID from the Initial this
        // Retry answers (RFC 9001 Appendix A.2's DCID), not any field carried in the Retry
        // packet itself.
        val odcid = hex("8394c8f03e515708")
        val withoutTag = hex("ff000000010008f067a5502a4262b5746f6b656e")
        val tag = hex("04a265ba2eff4d829058fb3f0f2496ba")

        assertTrue(
            "a genuine RFC test-vector v1 Retry must validate",
            QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_1, odcid, withoutTag, tag),
        )
    }

    @Test
    fun `validateRetryIntegrityTag accepts the RFC 9369 Appendix A4 v2 Retry`() {
        // Same ODCID and retry token as the v1 vector, but the v2-encoded packet (shifted type
        // bits, v2 version field) and its own tag, computed with the v2 key/nonce.
        val odcid = hex("8394c8f03e515708")
        val withoutTag = hex("cf6b3343cf0008f067a5502a4262b5746f6b656e")
        val tag = hex("c8646ce8bfe33952d955543665dcc7b6")

        assertTrue(
            "a genuine RFC test-vector v2 Retry must validate",
            QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_2, odcid, withoutTag, tag),
        )
    }

    @Test
    fun `validateRetryIntegrityTag rejects a corrupted v1 tag and does not throw`() {
        val odcid = hex("8394c8f03e515708")
        val withoutTag = hex("ff000000010008f067a5502a4262b5746f6b656e")
        val corruptedTag = hex("04a265ba2eff4d829058fb3f0f249645") // last byte flipped from ba to 45

        assertFalse(
            "a corrupted v1 tag must be rejected, not accepted",
            QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_1, odcid, withoutTag, corruptedTag),
        )
    }

    @Test
    fun `validateRetryIntegrityTag rejects a corrupted v2 tag and does not throw`() {
        val odcid = hex("8394c8f03e515708")
        val withoutTag = hex("cf6b3343cf0008f067a5502a4262b5746f6b656e")
        val corruptedTag = hex("c8646ce8bfe33952d955543665dcc700") // last byte flipped from b6 to 00

        assertFalse(
            "a corrupted v2 tag must be rejected, not accepted",
            QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_2, odcid, withoutTag, corruptedTag),
        )
    }

    @Test
    fun `validateRetryIntegrityTag rejects a v1-valid tag checked against the v2 key and vice versa`() {
        // Cross-version rejection: the v1 packet+tag must not validate under the v2 key/nonce
        // (and the v2 packet+tag must not validate under the v1 key/nonce) -- proves the two
        // constant sets are actually wired to their own version, not silently sharing one.
        val odcid = hex("8394c8f03e515708")
        val v1WithoutTag = hex("ff000000010008f067a5502a4262b5746f6b656e")
        val v1Tag = hex("04a265ba2eff4d829058fb3f0f2496ba")
        val v2WithoutTag = hex("cf6b3343cf0008f067a5502a4262b5746f6b656e")
        val v2Tag = hex("c8646ce8bfe33952d955543665dcc7b6")

        assertFalse(QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_2, odcid, v1WithoutTag, v1Tag))
        assertFalse(QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_1, odcid, v2WithoutTag, v2Tag))
    }

    @Test
    fun `validateRetryIntegrityTag rejects the wrong ODCID and an unrecognized version`() {
        val withoutTag = hex("ff000000010008f067a5502a4262b5746f6b656e")
        val tag = hex("04a265ba2eff4d829058fb3f0f2496ba")

        // Wrong ODCID -- the AAD no longer matches what the tag authenticated over.
        val wrongOdcid = hex("0000000000000000")
        assertFalse(QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_1, wrongOdcid, withoutTag, tag))

        // Unrecognized version -- no known Retry Integrity Tag key/nonce, must fail closed.
        val odcid = hex("8394c8f03e515708")
        assertFalse(QuicCrypto.validateRetryIntegrityTag(0x0a0a0a0a, odcid, withoutTag, tag))
    }

    @Test
    fun `validateRetryIntegrityTag rejects a malformed (wrong-length) tag without throwing`() {
        val odcid = hex("8394c8f03e515708")
        val withoutTag = hex("ff000000010008f067a5502a4262b5746f6b656e")
        val shortTag = hex("04a265ba2eff4d") // 7 bytes, not 16

        assertFalse(QuicCrypto.validateRetryIntegrityTag(QuicCrypto.VERSION_1, odcid, withoutTag, shortTag))
    }

    // ── Handshake long-header decrypt + shared protected-payload decrypt (key-log sourced) ──

    /** A reproducible 32-byte traffic secret: RFC 9001 Appendix A.1's published client_initial_secret. */
    private val handshakeSecret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
    private val handshakeDcid = hex("8394c8f03e515708")

    /** A small CRYPTO-frame-shaped plaintext the round-trip tests encrypt and expect back. */
    private val cryptoFramePlaintext: ByteArray =
        byteArrayOf(0x06, 0x00, 0x0c) + "CRYPTO-HELLO".toByteArray(Charsets.US_ASCII)

    /** Encodes a value as a QUIC varint (RFC 9000 §16); only the 1/2/4-byte forms are needed here. */
    private fun encodeVarint(value: Long): ByteArray = when {
        value < 64 -> byteArrayOf(value.toByte())
        value < 16384 -> byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toByte())
        value < 1073741824 -> byteArrayOf(
            (0x80 or (value shr 24).toInt()).toByte(),
            ((value shr 16) and 0xFF).toInt().toByte(),
            ((value shr 8) and 0xFF).toInt().toByte(),
            (value and 0xFF).toInt().toByte(),
        )
        else -> throw IllegalArgumentException("varint too large for test: $value")
    }

    /** nonce = iv XOR packet number, right-aligned over the 12-byte IV (RFC 9001 §5.3). */
    private fun nonceFor(keys: QuicCrypto.PacketProtectionKeys, pn: Long): ByteArray {
        val nonce = keys.iv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor ((pn ushr (i * 8)) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /**
     * Builds a protected v1 Handshake long-header packet carrying [plaintext] as its AEAD payload,
     * keyed from [keys], with packet number [pn] of [pnLen] bytes. AEAD uses an empty AAD (matching
     * [QuicCrypto.decryptHandshakePacket]'s contract) and header protection is applied with the long
     * header's low-4-bit mask. This is the encrypt side of the round-trip; the decrypt side under
     * test must recover [plaintext] verbatim.
     */
    private fun buildProtectedHandshakePacket(
        keys: QuicCrypto.PacketProtectionKeys,
        pn: Long,
        pnLen: Int,
        plaintext: ByteArray,
        dcid: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonceFor(keys, pn)))
        val ciphertext = cipher.doFinal(plaintext) // plaintext + 16-byte tag

        val lengthValue = pnLen + ciphertext.size
        val lengthVarint = encodeVarint(lengthValue.toLong())
        val pnOffset = 1 + 4 + 1 + dcid.size + 1 + 0 + lengthVarint.size // first+ver+dcidLen+dcid+scidLen+length
        val packet = ByteArray(pnOffset + pnLen + ciphertext.size)
        var p = 0
        packet[p++] = (0xE0 or (pnLen - 1)).toByte() // v1 Handshake: form=1 fixed=1 type=10, pn_len in bits 0-1
        packet[p++] = 0x00; packet[p++] = 0x00; packet[p++] = 0x00; packet[p++] = 0x01 // version 1
        packet[p++] = dcid.size.toByte()
        System.arraycopy(dcid, 0, packet, p, dcid.size); p += dcid.size
        packet[p++] = 0 // SCID length
        System.arraycopy(lengthVarint, 0, packet, p, lengthVarint.size); p += lengthVarint.size
        for (i in 0 until pnLen) packet[pnOffset + (pnLen - 1 - i)] = ((pn ushr (i * 8)) and 0xFF).toByte()
        p += pnLen
        System.arraycopy(ciphertext, 0, packet, p, ciphertext.size)

        // header protection: long header protects the low 4 bits of byte 0 + the PN bytes.
        val sample = packet.copyOfRange(pnOffset + 4, pnOffset + 4 + 16)
        val hpCipher = Cipher.getInstance("AES/ECB/NoPadding")
        hpCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
        val mask = hpCipher.doFinal(sample)
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
        return packet
    }

    @Test
    fun `decryptHandshakePacket round-trips an encrypted Handshake CRYPTO frame`() {
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val packet = buildProtectedHandshakePacket(keys, 1L, 1, cryptoFramePlaintext, handshakeDcid)

        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret, handshakeDcid, packet,
        )
        assertNotNull("handshake decrypt should succeed with the correct secret", result)
        assertArrayEquals(cryptoFramePlaintext, result)
    }

    @Test
    fun `decryptHandshakePacket round-trips with AES-256-GCM-SHA384 keys`() {
        val secret = hex("c7bfee2fd77625391d210c6f07e8a3cd0d96ade9d3dab25759b2fb5e74433efb5a2d6be4f52a83175b439d75fe8f9ee8")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384, secret,
        )!!
        assertEquals(32, keys.key.size) // AES-256
        val packet = buildProtectedHandshakePacket(keys, 1L, 1, cryptoFramePlaintext, handshakeDcid)

        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384, secret, handshakeDcid, packet,
        )
        assertNotNull(result)
        assertArrayEquals(cryptoFramePlaintext, result)
    }

    @Test
    fun `decryptHandshakePacket round-trips a multi-byte packet number`() {
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val packet = buildProtectedHandshakePacket(keys, 0x01FFL, 2, cryptoFramePlaintext, handshakeDcid)

        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret, handshakeDcid, packet,
        )
        assertNotNull(result)
        assertArrayEquals(cryptoFramePlaintext, result)
    }

    @Test
    fun `decryptHandshakePacket returns null for a wrong handshake secret`() {
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val packet = buildProtectedHandshakePacket(keys, 1L, 1, cryptoFramePlaintext, handshakeDcid)

        val wrongSecret = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, wrongSecret, handshakeDcid, packet,
        )
        assertNull("a wrong handshake secret must fail AEAD and return null", result)
    }

    @Test
    fun `decryptHandshakePacket rejects a packet whose DCID does not match`() {
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val packet = buildProtectedHandshakePacket(keys, 1L, 1, cryptoFramePlaintext, handshakeDcid)

        val wrongDcid = hex("0000000000000000")
        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret, wrongDcid, packet,
        )
        assertNull("a DCID mismatch must be rejected before AEAD", result)
    }

    @Test
    fun `decryptHandshakePacket returns null for a truncated long header without throwing`() {
        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret, handshakeDcid, ByteArray(3),
        )
        assertNull(result)
    }

    @Test
    fun `decryptProtectedPayload round-trips an aligned long-header payload directly`() {
        // Exercises the shared helper with the long-header (low-4-bit) first-byte mask, without the
        // long-header parsing that decryptHandshakePacket does: aligned = [firstByte, pn, ciphertext].
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val pn = 1L
        val pnLen = 1
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonceFor(keys, pn)))
        val ciphertext = cipher.doFinal(cryptoFramePlaintext)

        val aligned = ByteArray(1 + pnLen + ciphertext.size)
        aligned[0] = 0xE0.toByte() // long header, pn_len = 1
        aligned[1] = pn.toByte()
        System.arraycopy(ciphertext, 0, aligned, 2, ciphertext.size)
        val sample = aligned.copyOfRange(5, 21) // 4 bytes after the PN field (at offset 1)
        val hpCipher = Cipher.getInstance("AES/ECB/NoPadding")
        hpCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
        val mask = hpCipher.doFinal(sample)
        aligned[0] = (aligned[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLen) aligned[1 + i] = (aligned[1 + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()

        val result = QuicCrypto.decryptProtectedPayload(keys, aligned)
        assertNotNull(result)
        assertArrayEquals(cryptoFramePlaintext, result)
    }

    @Test
    fun `decryptProtectedPayload handles a 1-RTT short header (low 5 bits protected)`() {
        // Exercises the shared helper's short-header branch (form bit 0 -> 0x1f mask), the path a
        // future 1-RTT decryptor feeds once it has stripped the DCID from a short header.
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, handshakeSecret,
        )!!
        val plaintext = "1-rtt-payload".toByteArray(Charsets.US_ASCII)
        val pn = 0L
        val pnLen = 1
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonceFor(keys, pn)))
        val ciphertext = cipher.doFinal(plaintext)

        val aligned = ByteArray(1 + pnLen + ciphertext.size)
        aligned[0] = 0x40.toByte() // short header: form=0 fixed=1 key_phase=0 reserved=0 pn_len=1
        aligned[1] = pn.toByte()
        System.arraycopy(ciphertext, 0, aligned, 2, ciphertext.size)
        val sample = aligned.copyOfRange(5, 21)
        val hpCipher = Cipher.getInstance("AES/ECB/NoPadding")
        hpCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
        val mask = hpCipher.doFinal(sample)
        aligned[0] = (aligned[0].toInt() xor (mask[0].toInt() and 0x1f)).toByte() // short header: low 5 bits
        for (i in 0 until pnLen) aligned[1 + i] = (aligned[1 + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()

        val result = QuicCrypto.decryptProtectedPayload(keys, aligned)
        assertNotNull(result)
        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `decryptProtectedPayload returns null for a truncated payload without throwing`() {
        val keys = QuicCrypto.PacketProtectionKeys(key = ByteArray(16), iv = ByteArray(12), hp = ByteArray(16))
        assertNull(QuicCrypto.decryptProtectedPayload(keys, ByteArray(5))) // too short for the 16-byte sample
        assertNull(QuicCrypto.decryptProtectedPayload(keys, ByteArray(0)))
    }

    // ── ChaCha20-Poly1305 cipher suite (RFC 9001 §5.3 AEAD / §5.4.4 header protection) ──

    /** ChaCha20-Poly1305 AEAD encrypt: plaintext -> ciphertext + 16-byte Poly1305 tag (RFC 8439). */
    private fun chacha20AeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(plaintext)
    }

    /**
     * RFC 9001 §5.4.4 ChaCha20 header-protection mask (encrypt side, mirrors [QuicCrypto]'s decrypt
     * construction): the 16-byte [sample] is split into a 4-byte little-endian ChaCha20 block
     * counter (sample[0..3]) and a 12-byte nonce (sample[4..15]); the 5-byte mask is the ChaCha20
     * keystream from encrypting five zero bytes with the header-protection key under that
     * counter/nonce.
     */
    private fun chacha20HeaderMask(hpKey: ByteArray, sample16: ByteArray): ByteArray {
        val counter = (sample16[0].toInt() and 0xFF) or
            ((sample16[1].toInt() and 0xFF) shl 8) or
            ((sample16[2].toInt() and 0xFF) shl 16) or
            ((sample16[3].toInt() and 0xFF) shl 24)
        val nonce = sample16.copyOfRange(4, 16)
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hpKey, "ChaCha20"), ChaCha20ParameterSpec(nonce, counter))
        return cipher.doFinal(ByteArray(5))
    }

    /** Encrypt side of a v1 Handshake long-header packet using ChaCha20-Poly1305 (mirrors the AES [buildProtectedHandshakePacket]). */
    private fun buildProtectedChaCha20HandshakePacket(
        keys: QuicCrypto.PacketProtectionKeys,
        pn: Long,
        pnLen: Int,
        plaintext: ByteArray,
        dcid: ByteArray,
    ): ByteArray {
        val ciphertext = chacha20AeadEncrypt(keys.key, nonceFor(keys, pn), plaintext)

        val lengthValue = pnLen + ciphertext.size
        val lengthVarint = encodeVarint(lengthValue.toLong())
        val pnOffset = 1 + 4 + 1 + dcid.size + 1 + 0 + lengthVarint.size
        val packet = ByteArray(pnOffset + pnLen + ciphertext.size)
        var p = 0
        packet[p++] = (0xE0 or (pnLen - 1)).toByte() // v1 Handshake: form=1 fixed=1 type=10, pn_len in bits 0-1
        packet[p++] = 0x00; packet[p++] = 0x00; packet[p++] = 0x00; packet[p++] = 0x01
        packet[p++] = dcid.size.toByte()
        System.arraycopy(dcid, 0, packet, p, dcid.size); p += dcid.size
        packet[p++] = 0 // SCID length
        System.arraycopy(lengthVarint, 0, packet, p, lengthVarint.size); p += lengthVarint.size
        for (i in 0 until pnLen) packet[pnOffset + (pnLen - 1 - i)] = ((pn ushr (i * 8)) and 0xFF).toByte()
        p += pnLen
        System.arraycopy(ciphertext, 0, packet, p, ciphertext.size)

        // ChaCha20 header protection (RFC 9001 §5.4.4): low 4 bits of byte 0 + PN bytes.
        val sample = packet.copyOfRange(pnOffset + 4, pnOffset + 4 + 16)
        val mask = chacha20HeaderMask(keys.hp, sample)
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()
        return packet
    }

    @Test
    fun `fromIanaCode maps all three QUIC AEAD suites including ChaCha20`() {
        assertEquals(QuicCrypto.CipherSuite.TLS_AES_128_GCM_SHA256, QuicCrypto.CipherSuite.fromIanaCode(0x1301))
        assertEquals(QuicCrypto.CipherSuite.TLS_AES_256_GCM_SHA384, QuicCrypto.CipherSuite.fromIanaCode(0x1302))
        assertEquals(QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, QuicCrypto.CipherSuite.fromIanaCode(0x1303))
        assertNull("an unknown code must not map to a suite", QuicCrypto.CipherSuite.fromIanaCode(0x1304))
        assertNull("a non-AEAD TLS suite must not map", QuicCrypto.CipherSuite.fromIanaCode(0x002f))
    }

    @Test
    fun `TLS_CHACHA20_POLY1305_SHA256 declares the RFC 9001 suite parameters`() {
        val suite = QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256
        assertEquals(32, suite.aeadKeyLength) // 256-bit ChaCha20 key
        assertEquals(12, suite.ivLength) // AEAD nonce always 12 bytes
        assertEquals(16, suite.tagLength) // 128-bit Poly1305 tag
        assertEquals("HmacSHA256", suite.hmacAlgorithm) // SHA-256 HKDF hash
        assertEquals(32, suite.hashLength) // hkdfHashLength
        assertTrue("ChaCha20 uses the §5.4.4 keystream header protection", suite.headerProtectionIsChaCha20)
        assertEquals("ChaCha20-Poly1305", suite.aeadTransformation)
        assertEquals("ChaCha20", suite.aeadKeyAlgorithm)
    }

    @Test
    fun `deriveKeysFromTrafficSecret for ChaCha20-Poly1305-SHA256 derives correctly-sized SHA-256 keys carrying the suite`() {
        val secret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret,
        )!!
        assertEquals(32, keys.key.size) // 256-bit ChaCha20 AEAD key
        assertEquals(12, keys.iv.size) // AEAD nonce always 12 bytes
        assertEquals(32, keys.hp.size) // 256-bit header-protection key (same length as the AEAD key)
        assertEquals(QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, keys.cipherSuite)
    }

    @Test
    fun `decryptHandshakePacket round-trips a ChaCha20-Poly1305-encrypted Handshake CRYPTO frame`() {
        val secret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret,
        )!!
        assertEquals(32, keys.key.size)
        val packet = buildProtectedChaCha20HandshakePacket(keys, 1L, 1, cryptoFramePlaintext, handshakeDcid)

        val result = QuicCrypto.decryptHandshakePacket(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret, handshakeDcid, packet,
        )
        assertNotNull("ChaCha20 handshake decrypt should succeed with the correct secret", result)
        assertArrayEquals(cryptoFramePlaintext, result)
    }

    @Test
    fun `decryptProtectedPayload round-trips a ChaCha20-Poly1305 1-RTT short header`() {
        val secret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret,
        )!!
        val plaintext = "1-rtt-chacha".toByteArray(Charsets.US_ASCII)
        val pn = 0L
        val pnLen = 1
        val ciphertext = chacha20AeadEncrypt(keys.key, nonceFor(keys, pn), plaintext)

        val aligned = ByteArray(1 + pnLen + ciphertext.size)
        aligned[0] = 0x40.toByte() // short header: form=0 fixed=1 key_phase=0 pn_len=1
        aligned[1] = pn.toByte()
        System.arraycopy(ciphertext, 0, aligned, 2, ciphertext.size)
        val sample = aligned.copyOfRange(5, 21) // 4 bytes after the PN field (at offset 1)
        val mask = chacha20HeaderMask(keys.hp, sample)
        aligned[0] = (aligned[0].toInt() xor (mask[0].toInt() and 0x1f)).toByte() // short header: low 5 bits
        for (i in 0 until pnLen) aligned[1 + i] = (aligned[1 + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()

        val result = QuicCrypto.decryptProtectedPayload(keys, aligned)
        assertNotNull(result)
        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `ChaCha20 header protection unprotects a 4-byte packet number and decrypts the payload`() {
        // Exercises the full 5-byte ChaCha20 header-protection mask (first-byte mask + 4 PN bytes)
        // under the long-header low-4-bit first-byte mask — the maximum PN length, so every byte the
        // RFC 9001 §5.4.4 keystream supplies is touched, and a wrong mask byte would corrupt the PN
        // and make the AEAD nonce wrong, failing the round-trip.
        val secret = hex("c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea")
        val keys = QuicCrypto.deriveKeysFromTrafficSecret(
            QuicCrypto.VERSION_1, QuicCrypto.CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret,
        )!!
        val plaintext = byteArrayOf(0x06, 0x00, 0x05) + "HPN!!".toByteArray() // a CRYPTO-shaped frame
        val pn = 0x01020304L
        val pnLen = 4
        val ciphertext = chacha20AeadEncrypt(keys.key, nonceFor(keys, pn), plaintext)

        val aligned = ByteArray(1 + pnLen + ciphertext.size)
        aligned[0] = 0xE3.toByte() // long header: form=1 fixed=1 type=10 pn_len=4 (0b11)
        for (i in 0 until pnLen) aligned[1 + (pnLen - 1 - i)] = ((pn ushr (i * 8)) and 0xFF).toByte()
        System.arraycopy(ciphertext, 0, aligned, 1 + pnLen, ciphertext.size)

        val sample = aligned.copyOfRange(5, 21) // 4 bytes after the PN field start (offset 1)
        val mask = chacha20HeaderMask(keys.hp, sample)
        assertEquals("ChaCha20 header-protection mask is exactly 5 bytes (vs AES-ECB's 16)", 5, mask.size)
        aligned[0] = (aligned[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte() // long header: low 4 bits
        for (i in 0 until pnLen) aligned[1 + i] = (aligned[1 + i].toInt() xor (mask[1 + i].toInt() and 0xFF)).toByte()

        val result = QuicCrypto.decryptProtectedPayload(keys, aligned)
        assertNotNull("ChaCha20 header protection + AEAD should round-trip a 4-byte PN", result)
        assertArrayEquals(plaintext, result)
    }
}
