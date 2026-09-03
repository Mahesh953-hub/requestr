package com.example.interception.gateway.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Socks5HandlerTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun str(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun lp(s: String) = bytes(s.length) + str(s)

    private fun greeting(vararg methods: Int) = bytes(0x05, methods.size) + bytes(*methods)
    private fun userPass(user: String, pass: String) = bytes(0x01) + lp(user) + lp(pass)
    private fun connectDomain(host: String, port: Int) =
        bytes(0x05, 0x01, 0x00, 0x03) + lp(host) + bytes(port shr 8, port and 0xFF)
    private fun connectIpv4(a: Int, b: Int, c: Int, d: Int, port: Int) =
        bytes(0x05, 0x01, 0x00, 0x01, a, b, c, d, port shr 8, port and 0xFF)

    private fun run(handler: Socks5Handler, wire: ByteArray): Pair<Socks5Handler.Outcome, ByteArray> {
        val out = ByteArrayOutputStream()
        val outcome = handler.negotiate(ByteArrayInputStream(wire), out)
        return outcome to out.toByteArray()
    }

    @Test
    fun `no-auth CONNECT to a domain yields the destination and no origin`() {
        val (outcome, replied) = run(Socks5Handler(), greeting(0x00) + connectDomain("example.com", 443))
        val connect = outcome as Socks5Handler.Outcome.Connect
        assertEquals(Destination("example.com", 443), connect.destination)
        assertNull(connect.origin)
        // Method selection only -- the CONNECT reply is deferred to GatewaySession.reply*().
        assertArrayEquals(bytes(0x05, 0x00), replied)
    }

    @Test
    fun `ipv4 and ipv6 destinations are rendered as literals`() {
        val (v4, _) = run(Socks5Handler(), greeting(0x00) + connectIpv4(10, 0, 0, 7, 8080))
        assertEquals(Destination("10.0.0.7", 8080), (v4 as Socks5Handler.Outcome.Connect).destination)

        val v6addr = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 0x01 }
        val (v6, _) = run(
            Socks5Handler(),
            greeting(0x00) + bytes(0x05, 0x01, 0x00, 0x04) + v6addr + bytes(0x01, 0xBB),
        )
        val dest = (v6 as Socks5Handler.Outcome.Connect).destination
        assertEquals(443, dest.port)
        assertTrue(dest.isIpLiteral)
        assertTrue(dest.host.startsWith("2001:"))
    }

    @Test
    fun `plus-info username is decoded into ClientOrigin and base is verified`() {
        val creds = Socks5Credentials("requestr", "s3cret")
        val wire = greeting(0x02) + userPass("requestr|tcp|10.0.0.5|51234", "s3cret") + connectDomain("api.example", 443)
        val (outcome, replied) = run(Socks5Handler(creds), wire)
        val connect = outcome as Socks5Handler.Outcome.Connect
        assertEquals(ClientOrigin(ip = "10.0.0.5", port = 51234, transport = "tcp"), connect.origin)
        assertEquals(Destination("api.example", 443), connect.destination)
        assertArrayEquals(bytes(0x05, 0x02, 0x01, 0x00), replied)
    }

    @Test
    fun `wrong base username or password is rejected with auth failure`() {
        val creds = Socks5Credentials("requestr", "s3cret")
        val (badUser, r1) = run(Socks5Handler(creds), greeting(0x02) + userPass("other|tcp|1.2.3.4|5", "s3cret"))
        assertEquals(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, (badUser as Socks5Handler.Outcome.Rejected).reason)
        assertArrayEquals(bytes(0x05, 0x02, 0x01, 0x01), r1)

        val (badPass, r2) = run(Socks5Handler(creds), greeting(0x02) + userPass("requestr", "nope"))
        assertEquals(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, (badPass as Socks5Handler.Outcome.Rejected).reason)
        assertArrayEquals(bytes(0x05, 0x02, 0x01, 0x01), r2)
    }

    @Test
    fun `credentials configured but client offers only no-auth is refused with 0xFF`() {
        val (outcome, replied) = run(Socks5Handler(Socks5Credentials("u", "p")), greeting(0x00) + connectDomain("x", 1))
        assertEquals(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, (outcome as Socks5Handler.Outcome.Rejected).reason)
        assertArrayEquals(bytes(0x05, 0xFF), replied)
    }

    @Test
    fun `without configured credentials user-pass is still accepted so plus-info can flow`() {
        val wire = greeting(0x02) + userPass("sess-7|tcp|192.168.1.9|40000", "") + connectDomain("h", 80)
        val (outcome, _) = run(Socks5Handler(credentials = null), wire)
        val connect = outcome as Socks5Handler.Outcome.Connect
        assertEquals("192.168.1.9", connect.origin?.ip)
        assertEquals(40000, connect.origin?.port)
    }

    @Test
    fun `udp associate is answered with command-not-supported and recorded`() {
        val wire = greeting(0x00) + bytes(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        val (outcome, replied) = run(Socks5Handler(), wire)
        val rejected = outcome as Socks5Handler.Outcome.Rejected
        assertEquals(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, rejected.reason)
        assertTrue(rejected.detail.contains("UDP ASSOCIATE"))
        assertArrayEquals(bytes(0x05, 0x00) + Socks5Handler.reply(0x07), replied)
    }

    @Test
    fun `bind is answered with command-not-supported`() {
        val wire = greeting(0x00) + bytes(0x05, 0x02, 0x00, 0x03) + lp("h") + bytes(0x00, 0x50)
        val (outcome, replied) = run(Socks5Handler(), wire)
        assertEquals(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, (outcome as Socks5Handler.Outcome.Rejected).reason)
        // bytes 0-1 are the method selection; byte 3 is REP of the 10-byte reply that follows.
        assertEquals(0x07, replied[3].toInt() and 0xFF)
    }

    @Test
    fun `bad version and truncated stream are rejected without crashing`() {
        val (badVersion, _) = run(Socks5Handler(), bytes(0x06, 0x01, 0x00))
        assertEquals(GatewayRejectReason.SOCKS_HANDSHAKE_FAILED, (badVersion as Socks5Handler.Outcome.Rejected).reason)

        val (truncated, _) = run(Socks5Handler(), greeting(0x00) + bytes(0x05, 0x01, 0x00, 0x03, 0x0B, 'e'.code))
        assertEquals(GatewayRejectReason.CLIENT_CLOSED, (truncated as Socks5Handler.Outcome.Rejected).reason)
    }

    @Test
    fun `plus-info parser rejects malformed payloads`() {
        assertNull(ClientOrigin.parseInfoUsername("requestr"))
        assertNull(ClientOrigin.parseInfoUsername("a|b|c"))
        assertNull(ClientOrigin.parseInfoUsername("a|tcp||1"))
        assertNull(ClientOrigin.parseInfoUsername("a|tcp|1.2.3.4|notaport"))
        assertNull(ClientOrigin.parseInfoUsername("a|tcp|1.2.3.4|70000"))
        val parsed = ClientOrigin.parseInfoUsername("base|udp|fe80::1|53")!!
        assertEquals("base", parsed.base)
        assertEquals(ClientOrigin("fe80::1", 53, "udp"), parsed.origin)
    }

    @Test
    fun `random credentials are unique and url-safe`() {
        val a = Socks5Credentials.random()
        val b = Socks5Credentials.random()
        assertEquals("requestr", a.username)
        assertTrue(a.password != b.password)
        assertTrue(a.password.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(a.password.length >= 32)
    }

    @Test
    fun `reply helper encodes a 10 byte ipv4 reply`() {
        assertArrayEquals(bytes(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0), Socks5Handler.reply(0x02))
    }
}
