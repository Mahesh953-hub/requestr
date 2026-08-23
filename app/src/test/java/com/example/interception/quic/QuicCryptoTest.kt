package com.example.interception.quic

import org.junit.Assert.*
import org.junit.Test

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
}
