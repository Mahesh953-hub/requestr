package com.example.data.models

import com.example.data.local.QuicConnectionEntity
import com.example.data.local.QuicDecodeStatus
import com.example.interception.PacketPump
import com.example.interception.quic.QuicState
import com.example.ui.components.http3RowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14 / Lane D: unit tests for [toHttp3DetailState] (the [QuicConnectionEntity] +
 * [PacketPump.QuicLiveSummary] → [Http3DetailState] mapping behind
 * [com.example.ui.components.Http3DetailSheet]) and for [http3RowState] (the traffic-row pill
 * derivation). Pure functions, no Android/UI.
 */
class Http3StateTest {

    private fun entity(decodeStatus: QuicDecodeStatus, sni: String? = "api.example") =
        QuicConnectionEntity(
            connectionId = "conn-1",
            sessionId = 1,
            srcIp = "10.0.0.2", destIp = "104.18.6.192",
            srcPort = 52014, destPort = 443,
            sniHost = sni,
            alpn = null,
            quicVersion = 1,
            dstConnectionId = "8f2a41c0d95b77e3",
            decodeStatus = decodeStatus,
        )

    private fun live(
        alpn: String? = "h3",
        cipherSuite: String? = "TLS_AES_128_GCM_SHA256",
        decodedHttp3Exchanges: Int = 2,
        missingKeyLabels: List<String> = emptyList(),
        bytesReceived: Long = 22_400_000,
        keyMaterialMissing: Boolean = false,
        cipherSuiteUnavailable: Boolean = false,
    ) = PacketPump.QuicLiveSummary(
        connectionId = "conn-1",
        sniHost = "api.example",
        alpn = alpn,
        quicVersion = 1,
        destIp = "104.18.6.192",
        destPort = 443,
        state = QuicState.HANDSHAKE_COMPLETE,
        decodedFrames = decodedHttp3Exchanges > 0,
        bytesReceived = bytesReceived,
        cipherSuite = cipherSuite,
        decodedHttp3Exchanges = decodedHttp3Exchanges,
        missingKeyLabels = missingKeyLabels,
        keyMaterialMissing = keyMaterialMissing,
        cipherSuiteUnavailable = cipherSuiteUnavailable,
    )

    @Test
    fun `FULLY_DECODED maps to Decoded with non-partial flag`() {
        val state = toHttp3DetailState(entity(QuicDecodeStatus.FULLY_DECODED), live())
        assertTrue(state is Http3DetailState.Decoded)
        val decoded = state as Http3DetailState.Decoded
        assertEquals(false, decoded.partial)
        assertEquals(2, decoded.decodedHttp3Exchanges)
        assertEquals("h3", decoded.alpn)
        assertEquals("10.0.0.2:52014 → 104.18.6.192:443", decoded.fiveTuple)
    }

    @Test
    fun `PARTIALLY_DECODED maps to Decoded with partial flag`() {
        val state = toHttp3DetailState(entity(QuicDecodeStatus.PARTIALLY_DECODED), live(decodedHttp3Exchanges = 1))
        assertTrue(state is Http3DetailState.Decoded)
        assertTrue((state as Http3DetailState.Decoded).partial)
    }

    @Test
    fun `OBSERVED_NO_KEYS maps to Undecrypted carrying missing labels`() {
        val state = toHttp3DetailState(
            entity(QuicDecodeStatus.OBSERVED_NO_KEYS, sni = "video.example"),
            live(alpn = "h3", cipherSuite = null, decodedHttp3Exchanges = 0, missingKeyLabels = listOf("CLIENT_TRAFFIC_SECRET_0"), keyMaterialMissing = true),
        )
        assertTrue(state is Http3DetailState.Undecrypted)
        val undecrypted = state as Http3DetailState.Undecrypted
        assertEquals(listOf("CLIENT_TRAFFIC_SECRET_0"), undecrypted.missingKeyLabels)
        assertTrue(undecrypted.keyMaterialMissing)
        assertEquals(22_400_000, undecrypted.bytesReceived)
    }

    @Test
    fun `DETECTED_ONLY maps to QuicOnly with null alpn`() {
        val state = toHttp3DetailState(
            entity(QuicDecodeStatus.DETECTED_ONLY, sni = "chat.example"),
            live(alpn = null, cipherSuite = null, decodedHttp3Exchanges = 0, bytesReceived = 1232),
        )
        assertTrue(state is Http3DetailState.QuicOnly)
        val quicOnly = state as Http3DetailState.QuicOnly
        assertNull(quicOnly.alpn)
        assertEquals(1232, quicOnly.bytesReceived)
    }

    @Test
    fun `null live summary still derives state from the entity`() {
        val state = toHttp3DetailState(entity(QuicDecodeStatus.FULLY_DECODED), live = null)
        assertTrue(state is Http3DetailState.Decoded)
        assertEquals(0, (state as Http3DetailState.Decoded).decodedHttp3Exchanges)
    }

    @Test
    fun `http3RowState - HTTP3 with status decoded is DECODED`() {
        assertEquals(Http3RowState.DECODED, http3RowState(ProtocolHint.HTTP3, 200))
    }

    @Test
    fun `http3RowState - HTTP3 without status is OBSERVED`() {
        assertEquals(Http3RowState.OBSERVED, http3RowState(ProtocolHint.HTTP3, 0))
    }

    @Test
    fun `http3RowState - QUIC is DETECTED`() {
        assertEquals(Http3RowState.DETECTED, http3RowState(ProtocolHint.QUIC, 0))
    }

    @Test
    fun `http3RowState - non-QUIC protocols return null`() {
        assertNull(http3RowState(ProtocolHint.HTTP2, 200))
        assertNull(http3RowState(ProtocolHint.HTTP11, 301))
        assertNull(http3RowState(ProtocolHint.UNKNOWN, 0))
    }
}
