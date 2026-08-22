package com.example.interception.tun

import org.junit.Assert.*
import org.junit.Test

class IpAddressCodecTest {

    @Test
    fun `formatIpv6 matches the existing bare-hex expression byte-for-byte`() {
        val bytes = ByteArray(16) { (it + 1).toByte() }
        val packet = ByteArray(24) // offset 8 within a fake buffer
        System.arraycopy(bytes, 0, packet, 8, 16)
        val existingExpr = packet.copyOfRange(8, 24).joinToString("") { "%02x".format(it) }
        assertEquals(existingExpr, IpAddressCodec.formatIpv6(packet, 8))
    }

    @Test
    fun `ipv4 format and parse round-trip`() {
        val addr = "192.168.1.42"
        val bytes = IpAddressCodec.parseIpv4(addr)
        val packet = ByteArray(4 + 10)
        System.arraycopy(bytes, 0, packet, 4, 4)
        assertEquals(addr, IpAddressCodec.formatIpv4(packet, 4))
    }

    @Test
    fun `ipv6 format and parse round-trip`() {
        val hex = "fe800000000000000000000000000001"
        // 32 hex chars only (drop leading if too long) -- construct a known 32-char string
        val addr = "20010db8000000000000000000000001"
        val bytes = IpAddressCodec.parseIpv6(addr)
        assertEquals(16, bytes.size)
        val packet = ByteArray(16)
        System.arraycopy(bytes, 0, packet, 0, 16)
        assertEquals(addr, IpAddressCodec.formatIpv6(packet, 0))
    }

    @Test
    fun `toInetAddress resolves via getByAddress for both families`() {
        val v4 = IpAddressCodec.toInetAddress("10.0.0.1", 4)
        assertEquals(4, v4.address.size)
        val v6 = IpAddressCodec.toInetAddress("20010db8000000000000000000000001", 6)
        assertEquals(16, v6.address.size)
    }
}
