package com.example.correlation

import com.example.interception.tcp.TcpStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W5: locks [ConnectionKey]'s literal formats against the legacy hand-rolled strings that
 * [com.example.interception.tcp.TcpStream.key] / `.keyFrom(...)` and
 * [com.example.interception.PacketPump]'s QUIC connectionId used before this object existed --
 * a format drift here would silently orphan every already-persisted row keyed the old way.
 */
class ConnectionKeyTest {

    @After
    fun tearDown() {
        ConnectionAliasRegistry.clear()
    }

    @Test
    fun `forTcp matches the legacy literal format`() {
        val key = ConnectionKey.forTcp("10.0.0.2", 41234, "1.2.3.4", 443)
        assertEquals("6|10.0.0.2:41234>1.2.3.4:443", key)
    }

    @Test
    fun `forTcp with explicit protocol number`() {
        val key = ConnectionKey.forTcp("10.0.0.2", 41234, "1.2.3.4", 53, protocol = 17)
        assertEquals("17|10.0.0.2:41234>1.2.3.4:53", key)
    }

    @Test
    fun `forQuic matches PacketPump's existing quic-prefixed literal`() {
        val key = ConnectionKey.forQuic("10.0.0.2", 55000, "8.8.8.8", 443)
        assertEquals("quic|10.0.0.2:55000>8.8.8.8:443", key)
    }

    @Test
    fun `forHostPort matches MitmProxyEngine's host-port convention`() {
        assertEquals("example.com:443", ConnectionKey.forHostPort("example.com", 443))
    }

    @Test
    fun `parse round-trips a forTcp key`() {
        val key = ConnectionKey.forTcp("10.0.0.2", 41234, "1.2.3.4", 443)
        val parsed = ConnectionKey.parse(key)
        assertEquals(ConnectionKey.Parsed("6", "10.0.0.2", 41234, "1.2.3.4", 443), parsed)
    }

    @Test
    fun `parse round-trips a forQuic key`() {
        val key = ConnectionKey.forQuic("10.0.0.2", 55000, "8.8.8.8", 443)
        val parsed = ConnectionKey.parse(key)
        assertEquals(ConnectionKey.Parsed("quic", "10.0.0.2", 55000, "8.8.8.8", 443), parsed)
    }

    @Test
    fun `parse returns null for a forHostPort key`() {
        assertNull(ConnectionKey.parse(ConnectionKey.forHostPort("example.com", 443)))
    }

    @Test
    fun `parse returns null for garbage input`() {
        assertNull(ConnectionKey.parse(""))
        assertNull(ConnectionKey.parse("not-a-key-at-all"))
        assertNull(ConnectionKey.parse("6|10.0.0.2:abc>1.2.3.4:443"))
    }

    @Test
    fun `TcpStream key delegates to ConnectionKey forTcp for the same tuple`() {
        val stream = TcpStream(srcIp = "10.0.0.2", srcPort = 41234, dstIp = "1.2.3.4", dstPort = 443)
        assertEquals(ConnectionKey.forTcp("10.0.0.2", 41234, "1.2.3.4", 443), stream.key)
    }

    @Test
    fun `alias registry resolves a registered alias to its canonical key`() {
        val canonical = ConnectionKey.forTcp("10.0.0.2", 41234, "93.184.216.34", 443)
        val alias = ConnectionKey.forHostPort("example.com", 443)
        ConnectionAliasRegistry.register(alias, canonical)
        assertEquals(canonical, ConnectionAliasRegistry.resolve(alias))
    }

    @Test
    fun `alias registry returns null for an unknown alias`() {
        assertNull(ConnectionAliasRegistry.resolve("never-registered"))
    }

    @Test
    fun `alias registry no-ops when alias equals canonical`() {
        val key = ConnectionKey.forHostPort("example.com", 443)
        ConnectionAliasRegistry.register(key, key)
        assertNull(ConnectionAliasRegistry.resolve(key))
    }
}
