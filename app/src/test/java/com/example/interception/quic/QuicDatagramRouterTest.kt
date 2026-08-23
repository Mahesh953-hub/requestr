package com.example.interception.quic

import com.example.correlation.ConnectionKey
import org.junit.Assert.*
import org.junit.Test

/**
 * Focused unit tests for [QuicDatagramRouter] — the QUIC datagram ingestion router (RFC 9000
 * §12.2 coalescing + §5.8 CID routing). Covers the behaviors [PacketPump.ingestQuicDatagram]
 * depends on: coalesced-packet splitting, version-aware v1/v2 long-header classification,
 * DCID-based routing that tolerates the Initial→Handshake DCID change and the Retry re-key, the
 * zero-length-DCID 5-tuple fallback (correlating both directions to one connection), the
 * remainder-consuming short header / Retry / Version-Negotiation cases, and first-Initial SNI/ALPN
 * recovery through [QuicParser.parseInitial].
 *
 * No Room/Android — this exercises the pure routing/classification layer; the end-to-end persist
 * path is covered by `PacketPumpQuicDatagramTest` (Robolectric + in-memory Room).
 */
class QuicDatagramRouterTest {

    private val cidTable = QuicCidTable()
    private val router = QuicDatagramRouter(cidTable)

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /** Encodes a value as a QUIC varint (RFC 9000 §16); the 1/2-byte forms suffice here. */
    private fun encodeVarint(value: Long): ByteArray = when {
        value < 64 -> byteArrayOf(value.toByte())
        value < 16384 -> byteArrayOf((0x40 or (value shr 8).toInt()).toByte(), (value and 0xFF).toByte())
        else -> throw IllegalArgumentException("varint too large for test: $value")
    }

    /**
     * Builds a structurally-valid (unprotected) long-header packet carrying [payload] after a
     * 1-byte packet number, with a correct Length field so the router can walk coalesced packets.
     * [firstByte] carries the form/fixed/type/pn-len bits; [isInitial] adds the Token Length field
     * (a 1-byte zero) only Initial packets carry. The router does not AEAD-decrypt non-Initial
     * packets, so an unprotected packet is sufficient to exercise routing + classification.
     */
    private fun buildLongHeader(firstByte: Int, version: Int, dcid: ByteArray, scid: ByteArray, isInitial: Boolean, payload: ByteArray): ByteArray {
        val pnLen = 1
        val lengthVarint = encodeVarint((pnLen + payload.size).toLong())
        val size = 1 + 4 + 1 + dcid.size + 1 + scid.size + (if (isInitial) 1 else 0) + lengthVarint.size + pnLen + payload.size
        val out = ByteArray(size)
        var p = 0
        out[p++] = firstByte.toByte()
        out[p++] = ((version shr 24) and 0xFF).toByte()
        out[p++] = ((version shr 16) and 0xFF).toByte()
        out[p++] = ((version shr 8) and 0xFF).toByte()
        out[p++] = (version and 0xFF).toByte()
        out[p++] = dcid.size.toByte()
        System.arraycopy(dcid, 0, out, p, dcid.size); p += dcid.size
        out[p++] = scid.size.toByte()
        System.arraycopy(scid, 0, out, p, scid.size); p += scid.size
        if (isInitial) out[p++] = 0x00 // Token Length varint = 0
        System.arraycopy(lengthVarint, 0, out, p, lengthVarint.size); p += lengthVarint.size
        out[p] = 0x00 // packet number (1 byte)
        p += pnLen
        System.arraycopy(payload, 0, out, p, payload.size)
        return out
    }

    /** The RFC 9001 Appendix A.2 sample client Initial — a real AEAD-protected packet decrypting to SNI "example.com", ALPN "alpn". */
    private val rfcA2ClientInitial: ByteArray = hex(
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
            "e221af44860018ab0856972e194cd934",
    )

    private val rfcA2Dcid = hex("8394c8f03e515708")

    @Test
    fun `a coalesced initial and handshake datagram splits into two routed packets with recovered SNI`() {
        // A server commonly coalesces its Initial and Handshake responses into one datagram. Here a
        // real RFC 9001 A.2 client Initial (decrypts to SNI "example.com"/ALPN "alpn") is followed
        // by a Handshake packet sharing the same DCID so both route to one connection.
        val handshake = buildLongHeader(
            firstByte = 0xE0, // v1 Handshake: form=1 fixed=1 type=10
            version = QuicCrypto.VERSION_1,
            dcid = rfcA2Dcid,
            scid = ByteArray(0),
            isInitial = false,
            payload = ByteArray(8),
        )
        val coalesced = rfcA2ClientInitial + handshake

        val routed = router.ingest(coalesced, CLIENT_IP, 54321, SERVER_IP, 443, isIpv6 = false)

        assertEquals("coalesced datagram splits into exactly two packets", 2, routed.size)

        val initial = routed[0]
        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, initial.packetType)
        assertEquals(QuicCrypto.VERSION_1, initial.version)
        assertEquals(QuicDatagramRouter.Direction.OUTBOUND, initial.direction)
        assertTrue("Initial SNI recovered via real AEAD decrypt", initial.decrypted)
        assertEquals("example.com", initial.sniHostname)
        assertEquals("alpn", initial.alpn)
        assertArrayEquals(rfcA2Dcid, initial.dcid)
        assertEquals(0, initial.offset)
        assertEquals(rfcA2ClientInitial.size, initial.length)

        val hs = routed[1]
        assertEquals(QuicCrypto.LongHeaderPacketType.HANDSHAKE, hs.packetType)
        assertEquals(QuicCrypto.VERSION_1, hs.version)
        assertEquals(rfcA2ClientInitial.size, hs.offset)
        assertEquals(handshake.size, hs.length)
        // The Handshare shares the Initial's DCID, so it routes to the same connection.
        assertEquals("both coalesced packets route to one connection", initial.connectionId, hs.connectionId)
        assertEquals(ConnectionKey.forQuic(CLIENT_IP, 54321, SERVER_IP, 443), initial.connectionId)
    }

    @Test
    fun `initial to handshake dcid change routes every packet to the same connection`() {
        // The client's DCID changes from its Initial DCID (C) to the server's SCID (S) once the
        // server responds. The router must correlate the client Initial, the server Initial (whose
        // SCID=S is registered), and the client Handshare (DCID=S) to one connection.
        val c = hex("8394c8f03e515708")
        val x = hex("1122334455667788")
        val s = hex("aabbccddeeff0011")
        val clientInitial = buildLongHeader(0xC0, QuicCrypto.VERSION_1, c, x, isInitial = true, payload = ByteArray(4))
        val serverInitial = buildLongHeader(0xC0, QuicCrypto.VERSION_1, x, s, isInitial = true, payload = ByteArray(4))
        val clientHandshake = buildLongHeader(0xE0, QuicCrypto.VERSION_1, s, x, isInitial = false, payload = ByteArray(4))

        val r1 = router.ingest(clientInitial, CLIENT_IP, 54321, SERVER_IP, 443, false).single()
        val r2 = router.ingest(serverInitial, SERVER_IP, 443, CLIENT_IP, 54321, false).single()
        val r3 = router.ingest(clientHandshake, CLIENT_IP, 54321, SERVER_IP, 443, false).single()

        val expected = ConnectionKey.forQuic(CLIENT_IP, 54321, SERVER_IP, 443)
        assertEquals(expected, r1.connectionId)
        assertEquals("server Initial (DCID=X) routes to the connection registered from the client Initial's SCID", expected, r2.connectionId)
        assertEquals("client Handshake (DCID=S) routes via the server Initial's registered SCID", expected, r3.connectionId)
        assertNotEquals("the DCID genuinely changed across the handshake", c.toList(), s.toList())
        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, r1.packetType)
        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, r2.packetType)
        assertEquals(QuicCrypto.LongHeaderPacketType.HANDSHAKE, r3.packetType)
        assertEquals(QuicDatagramRouter.Direction.INBOUND, r2.direction)
    }

    @Test
    fun `a zero-length dcid connection routes by tuple and correlates across directions`() {
        // When the client uses a zero-length SCID, the server's packets carry a zero-length DCID,
        // so they cannot route by CID. The router falls back to a direction-normalized 4-tuple so
        // the outbound Initial and the inbound response reach the same connection.
        val outbound = buildLongHeader(0xC0, QuicCrypto.VERSION_1, ByteArray(0), ByteArray(0), isInitial = true, payload = ByteArray(4))
        val inbound = buildLongHeader(0xC0, QuicCrypto.VERSION_1, ByteArray(0), ByteArray(0), isInitial = true, payload = ByteArray(4))

        val r1 = router.ingest(outbound, CLIENT_IP, 54321, SERVER_IP, 443, false).single()
        val r2 = router.ingest(inbound, SERVER_IP, 443, CLIENT_IP, 54321, false).single()

        assertEquals(ConnectionKey.forQuic(CLIENT_IP, 54321, SERVER_IP, 443), r1.connectionId)
        assertEquals("reversed-direction zero-length-DCID packet routes to the same connection", r1.connectionId, r2.connectionId)
    }

    @Test
    fun `an ipv6 datagram routes identically to an ipv4 datagram`() {
        val handshake = buildLongHeader(0xE0, QuicCrypto.VERSION_1, rfcA2Dcid, ByteArray(0), isInitial = false, payload = ByteArray(8))
        val coalesced = rfcA2ClientInitial + handshake

        val routed = router.ingest(coalesced, CLIENT_IP_V6, 54321, SERVER_IP_V6, 443, isIpv6 = true)

        assertEquals(2, routed.size)
        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, routed[0].packetType)
        assertEquals("example.com", routed[0].sniHostname)
        assertEquals(QuicCrypto.LongHeaderPacketType.HANDSHAKE, routed[1].packetType)
        assertEquals("address family does not change DCID-based routing", routed[0].connectionId, routed[1].connectionId)
        assertEquals(ConnectionKey.forQuic(CLIENT_IP_V6, 54321, SERVER_IP_V6, 443), routed[0].connectionId)
    }

    @Test
    fun `a short header consumes the datagram remainder and classifies as 1-rtt`() {
        // A short header (form bit 0) carries no Length field, so it consumes the remainder; its
        // packet type is null (1-RTT), distinguished from every long-header type.
        val datagram = byteArrayOf(0x40, 0x01, 0x02, 0x03, 0x04, 0x05)
        val routed = router.ingest(datagram, CLIENT_IP, 54321, SERVER_IP, 443, false)

        assertEquals(1, routed.size)
        val p = routed.single()
        assertNull("short header has no long-header packet type", p.packetType)
        assertEquals(datagram.size, p.length)
        assertEquals(0, p.offset)
        assertEquals(QuicDatagramRouter.Direction.OUTBOUND, p.direction)
    }

    @Test
    fun `a retry packet consumes the remainder, classifies as retry, and re-keys routing via its scid`() {
        // A Retry (no Length field) consumes the remainder. Its SCID is the client's next DCID, so
        // registering it lets the client's re-keyed Initial (DCID=S) route to the same connection.
        val c = hex("8394c8f03e515708")
        val x = hex("1122334455667788")
        val s = hex("aabbccddeeff0011")
        val clientInitial = buildLongHeader(0xC0, QuicCrypto.VERSION_1, c, x, isInitial = true, payload = ByteArray(4))

        // Retry: 0xF0 (v1 Retry type=11) + version + DCID-len + DCID + SCID-len + SCID + remainder.
        val retry = ByteArray(1 + 4 + 1 + x.size + 1 + s.size + 8)
        var p = 0
        retry[p++] = 0xF0.toByte()
        retry[p++] = 0x00; retry[p++] = 0x00; retry[p++] = 0x00; retry[p++] = 0x01
        retry[p++] = x.size.toByte(); System.arraycopy(x, 0, retry, p, x.size); p += x.size
        retry[p++] = s.size.toByte(); System.arraycopy(s, 0, retry, p, s.size); p += s.size

        val rekeyedInitial = buildLongHeader(0xC0, QuicCrypto.VERSION_1, s, x, isInitial = true, payload = ByteArray(4))

        val expected = ConnectionKey.forQuic(CLIENT_IP, 54321, SERVER_IP, 443)
        val r1 = router.ingest(clientInitial, CLIENT_IP, 54321, SERVER_IP, 443, false).single()
        val r2 = router.ingest(retry, SERVER_IP, 443, CLIENT_IP, 54321, false).single()
        val r3 = router.ingest(rekeyedInitial, CLIENT_IP, 54321, SERVER_IP, 443, false).single()

        assertEquals(expected, r1.connectionId)
        assertEquals(QuicCrypto.LongHeaderPacketType.RETRY, r2.packetType)
        assertEquals("Retry routes (by its DCID=X) to the connection", expected, r2.connectionId)
        assertEquals(retry.size, r2.length)
        assertEquals("client's re-keyed Initial (DCID=S) routes via the Retry's registered SCID", expected, r3.connectionId)
    }

    @Test
    fun `a version negotiation long header consumes the remainder with a null packet type`() {
        // Version 0 = Version Negotiation: no Length field, type bits are not a real packet type.
        val vn = ByteArray(1 + 4 + 1 + 8 + 1 + 0 + 8)
        var p = 0
        vn[p++] = 0xC0.toByte()
        vn[p++] = 0x00; vn[p++] = 0x00; vn[p++] = 0x00; vn[p++] = 0x00 // version 0
        vn[p++] = 0x08; for (i in 0 until 8) vn[p++] = i.toByte() // DCID
        vn[p++] = 0x00 // SCID len 0
        val routed = router.ingest(vn, CLIENT_IP, 54321, SERVER_IP, 443, false)
        assertEquals(1, routed.size)
        assertNull(routed.single().packetType)
        assertEquals(0, routed.single().version)
        assertEquals(vn.size, routed.single().length)
    }

    @Test
    fun `v1 and v2 initial packets classify via the version-aware type-bit map`() {
        // RFC 9369 shifts the long-header type bits: v1 Initial=0b00 (firstByte 0xC0), v2
        // Initial=0b01 (firstByte 0xD0). The same high nibble means a different type per version.
        val v1Initial = buildLongHeader(0xC0, QuicCrypto.VERSION_1, rfcA2Dcid, ByteArray(0), isInitial = true, payload = ByteArray(4))
        val v2Initial = buildLongHeader(0xD0, QuicCrypto.VERSION_2, rfcA2Dcid, ByteArray(0), isInitial = true, payload = ByteArray(4))

        val r1 = router.ingest(v1Initial, CLIENT_IP, 54321, SERVER_IP, 443, false).single()
        val r2 = router.ingest(v2Initial, CLIENT_IP, 54321, SERVER_IP, 443, false).single()

        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, r1.packetType)
        assertEquals(QuicCrypto.VERSION_1, r1.version)
        assertEquals(QuicCrypto.LongHeaderPacketType.INITIAL, r2.packetType)
        assertEquals("v2 initial classifies as INITIAL despite the shifted type bits", QuicCrypto.VERSION_2, r2.version)
    }

    @Test
    fun `a truncated long header yields no packets without throwing`() {
        val routed = router.ingest(byteArrayOf(0xC0.toByte(), 0x00, 0x00), CLIENT_IP, 54321, SERVER_IP, 443, false)
        assertTrue("a too-short header is dropped, not crashed on", routed.isEmpty())
    }

    private companion object {
        private const val CLIENT_IP = "10.0.0.7"
        private const val SERVER_IP = "93.184.216.34"
        private const val CLIENT_IP_V6 = "2001:db8::7"
        private const val SERVER_IP_V6 = "2606:2800:220:1:248:1893:25c8:1946"
    }
}
