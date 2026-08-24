package com.example.interception.quic

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RequestrDatabase
import com.example.interception.PacketPump
import com.example.data.local.QuicDecodeStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * End-to-end Robolectric coverage for [PacketPump.ingestQuicDatagram] — the IPv4/IPv6 QUIC ingestion
 * path rewired in the gap fix to route through [QuicDatagramRouter]. This is the persist-side
 * complement to [QuicDatagramRouterTest] (which covers the pure routing/classification layer): it
 * drives a real [PacketPump] with an in-memory Room database and asserts that a coalesced
 * Initial+Handshake datagram (a server's common response shape) persists the connection with its
 * recovered SNI/ALPN, that two distinct connections persist as two rows, and that a v6 datagram
 * persists identically to v4.
 *
 * [PacketPump]'s constructor only initializes in-memory relay/state objects (no read loop starts),
 * so constructing one in Robolectric is safe; [persistQuicConnection] writes its Room row
 * synchronously via `runBlocking`, so no polling is needed. Each test uses a distinct [sessionId]
 * because [RequestrDatabase.get]'s companion singleton is not reset between test methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class PacketPumpQuicDatagramTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = RequestrDatabase.get(context)

    private var inputFile: File? = null
    private var outputFile: File? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null

    private fun newPump(sessionId: Long): PacketPump {
        val inFile = File.createTempFile("pump-in", ".bin")
        val outFile = File.createTempFile("pump-out", ".bin")
        inputFile = inFile
        outputFile = outFile
        val ins = FileInputStream(inFile)
        val outs = FileOutputStream(outFile)
        input = ins
        output = outs
        return PacketPump(
            context = context,
            input = ins,
            output = outs,
            sessionId = sessionId,
            db = db,
        )
    }

    @After
    fun tearDown() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { inputFile?.delete() }
        runCatching { outputFile?.delete() }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

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
     * packets, so an unprotected packet is sufficient to exercise routing + persist.
     */
    private fun buildLongHeader(
        firstByte: Int,
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        isInitial: Boolean,
        payload: ByteArray,
    ): ByteArray {
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

    private companion object {
        private const val CLIENT_IP = "10.0.0.7"
        private const val SERVER_IP = "93.184.216.34"
        private const val CLIENT_IP_V6 = "2001:db8::7"
        private const val SERVER_IP_V6 = "2606:2800:220:1:248:1893:25c8:1946"
    }

    @Test
    fun `a coalesced initial and handshake datagram persists one connection with the recovered SNI`() {
        // A server commonly coalesces its Initial and Handshake responses into one datagram. Both
        // packets share the client's DCID, so the router resolves them to ONE connection; the Room
        // row is written once (on the Initial) carrying the AEAD-decrypted SNI/ALPN.
        val handshake = buildLongHeader(
            firstByte = 0xE0, // v1 Handshake: form=1 fixed=1 type=10
            version = QuicCrypto.VERSION_1,
            dcid = rfcA2Dcid,
            scid = ByteArray(0),
            isInitial = false,
            payload = ByteArray(4),
        )
        val datagram = rfcA2ClientInitial + handshake
        val pump = newPump(sessionId = 101L)

        pump.ingestQuicDatagram(datagram, CLIENT_IP, 54321, SERVER_IP, 443, isIpv6 = false)

        val rows = runBlocking { db.quicConnectionDao().getBySession(101L) }
        assertEquals("both coalesced packets route to one connection -> one row", 1, rows.size)
        val row = rows.single()
        assertEquals("example.com", row.sniHost)
        assertEquals("alpn", row.alpn)
        assertEquals(QuicCrypto.VERSION_1, row.quicVersion)
        // Gap closure: the client Initial's ClientHello really decrypts here (public Initial keys), so
        // the authoritative status is PARTIALLY_DECODED, not DETECTED_ONLY -- and the row now carries the
        // ClientHello random that later 1-RTT key lookups are keyed by. The coalesced Handshake packet
        // stays undecrypted because no traffic secret is registered in this test, which is exactly the
        // honest key-missing state (no keylogLabels recorded).
        assertEquals(QuicDecodeStatus.PARTIALLY_DECODED, row.decodeStatus)
        assertEquals("HANDSHAKE_IN_PROGRESS", row.lifecycleState)
        assertEquals(64, row.clientRandomHex?.length)
        assertNull("no traffic secret resolved, so no key-log provenance may be claimed", row.keylogLabels)
    }

    @Test
    fun `two datagrams with distinct destination connection ids persist as two connections`() {
        // Two separate QUIC handshakes (distinct DCIDs, distinct client ports) are two connections.
        val first = buildLongHeader(
            firstByte = 0xC0, // v1 Initial
            version = QuicCrypto.VERSION_1,
            dcid = hex("0011223344556677"),
            scid = ByteArray(0),
            isInitial = true,
            payload = ByteArray(4),
        )
        val second = buildLongHeader(
            firstByte = 0xC0,
            version = QuicCrypto.VERSION_1,
            dcid = hex("aabbccddeeff0011"),
            scid = ByteArray(0),
            isInitial = true,
            payload = ByteArray(4),
        )
        val pump = newPump(sessionId = 102L)

        pump.ingestQuicDatagram(first, CLIENT_IP, 53001, SERVER_IP, 443, isIpv6 = false)
        pump.ingestQuicDatagram(second, CLIENT_IP, 53002, SERVER_IP, 443, isIpv6 = false)

        val rows = runBlocking { db.quicConnectionDao().getBySession(102L) }
        assertEquals("two distinct DCIDs persist as two connections", 2, rows.size)
        // Neither unprotected Initial recovered an SNI, so the rows are detected-only with null SNI.
        assertTrue(rows.all { it.decodeStatus == QuicDecodeStatus.DETECTED_ONLY })
    }

    @Test
    fun `an ipv6 datagram persists identically to an ipv4 datagram`() {
        // Address family does not change DCID-based routing; a v6 datagram with the real A.2 Initial
        // persists the same single connection row as the v4 path.
        val pump = newPump(sessionId = 103L)
        pump.ingestQuicDatagram(rfcA2ClientInitial, CLIENT_IP_V6, 54321, SERVER_IP_V6, 443, isIpv6 = true)

        val rows = runBlocking { db.quicConnectionDao().getBySession(103L) }
        assertEquals(1, rows.size)
        assertEquals("example.com", rows.single().sniHost)
        assertEquals("alpn", rows.single().alpn)
    }
}
