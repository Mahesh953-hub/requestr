package com.example.interception.gateway.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Socks4HandlerTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun nul(s: String) = s.toByteArray(Charsets.UTF_8) + bytes(0x00)

    private fun run(wire: ByteArray): Pair<Socks4Handler.Outcome, ByteArray> {
        val out = ByteArrayOutputStream()
        val outcome = Socks4Handler().negotiate(ByteArrayInputStream(wire), out)
        return outcome to out.toByteArray()
    }

    @Test
    fun `socks4 connect with ipv4 destination`() {
        val (outcome, replied) = run(bytes(0x04, 0x01, 0x01, 0xBB, 93, 184, 216, 34) + nul("alice"))
        val connect = outcome as Socks4Handler.Outcome.Connect
        assertEquals(Destination("93.184.216.34", 443), connect.destination)
        assertEquals("alice", connect.userId)
        assertEquals(0, replied.size) // reply deferred to GatewaySession
    }

    @Test
    fun `socks4a connect carries the hostname after the user id`() {
        val (outcome, _) = run(bytes(0x04, 0x01, 0x00, 0x50, 0, 0, 0, 1) + nul("") + nul("example.com"))
        assertEquals(Destination("example.com", 80), (outcome as Socks4Handler.Outcome.Connect).destination)
    }

    @Test
    fun `bind is refused with 0x5B and recorded as unsupported`() {
        val (outcome, replied) = run(bytes(0x04, 0x02, 0x00, 0x50, 1, 2, 3, 4) + nul(""))
        assertEquals(GatewayRejectReason.SOCKS_COMMAND_UNSUPPORTED, (outcome as Socks4Handler.Outcome.Rejected).reason)
        assertArrayEquals(bytes(0x00, 0x5B, 0, 0, 0, 0, 0, 0), replied)
    }

    @Test
    fun `truncated request is a client-closed rejection`() {
        val (outcome, _) = run(bytes(0x04, 0x01, 0x00))
        assertEquals(GatewayRejectReason.CLIENT_CLOSED, (outcome as Socks4Handler.Outcome.Rejected).reason)
    }

    @Test
    fun `reply helper encodes granted and rejected`() {
        assertArrayEquals(bytes(0x00, 0x5A, 0, 0, 0, 0, 0, 0), Socks4Handler.reply(Socks4Handler.CD_GRANTED))
        assertTrue(Socks4Handler.reply(Socks4Handler.CD_REJECTED)[1] == 0x5B.toByte())
    }
}
