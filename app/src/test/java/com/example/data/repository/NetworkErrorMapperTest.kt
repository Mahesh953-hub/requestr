package com.example.data.repository

import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master plan §2.21 — locks [NetworkErrorMapper]'s per-exception-type category strings so a
 * regression can't silently collapse everything back to the old one-size-fits-all
 * "Network Failure" message. Tested as a pure function with directly-constructed exceptions
 * rather than real sockets, since DNS/connect-refused conditions are unreliable to trigger
 * deterministically in a sandboxed test JVM.
 */
class NetworkErrorMapperTest {

    private val url = "https://example.com/api"

    @Test
    fun `UnknownHostException maps to Unknown Host`() {
        val mapped = NetworkErrorMapper.map(UnknownHostException("no such host"), url)
        assertEquals("Unknown Host", mapped.statusMessage)
        assertTrue(mapped.body.contains(url))
    }

    @Test
    fun `SSLPeerUnverifiedException maps to TLS Trust Failure and mentions CA install`() {
        val mapped = NetworkErrorMapper.map(SSLPeerUnverifiedException("peer not verified"), url)
        assertEquals("TLS Trust Failure", mapped.statusMessage)
        assertTrue(mapped.body.contains("CA"))
    }

    @Test
    fun `SSLHandshakeException maps to TLS Handshake Failure and mentions CA install`() {
        val mapped = NetworkErrorMapper.map(SSLHandshakeException("handshake failed"), url)
        assertEquals("TLS Handshake Failure", mapped.statusMessage)
        assertTrue(mapped.body.contains("CA"))
    }

    @Test
    fun `SocketTimeoutException with connect in message reports connecting phase`() {
        val mapped = NetworkErrorMapper.map(SocketTimeoutException("failed to connect to host"), url)
        assertEquals("Timeout", mapped.statusMessage)
        assertTrue(mapped.body.contains("connecting to"))
    }

    @Test
    fun `SocketTimeoutException with read in message reports reading phase`() {
        val mapped = NetworkErrorMapper.map(SocketTimeoutException("timeout while read"), url)
        assertEquals("Timeout", mapped.statusMessage)
        assertTrue(mapped.body.contains("reading a response from"))
    }

    @Test
    fun `SocketTimeoutException with ambiguous message falls back to talking to`() {
        val mapped = NetworkErrorMapper.map(SocketTimeoutException("timed out"), url)
        assertEquals("Timeout", mapped.statusMessage)
        assertTrue(mapped.body.contains("talking to"))
    }

    @Test
    fun `ConnectException maps to Connection Refused`() {
        val mapped = NetworkErrorMapper.map(ConnectException("Connection refused"), url)
        assertEquals("Connection Refused", mapped.statusMessage)
    }

    @Test
    fun `ProtocolException maps to Protocol Error`() {
        val mapped = NetworkErrorMapper.map(ProtocolException("unexpected end of stream"), url)
        assertEquals("Protocol Error", mapped.statusMessage)
    }

    @Test
    fun `generic IOException falls back to Network Failure`() {
        val mapped = NetworkErrorMapper.map(java.io.IOException("boom"), url)
        assertEquals("Network Failure", mapped.statusMessage)
    }
}
