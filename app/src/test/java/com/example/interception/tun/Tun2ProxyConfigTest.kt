package com.example.interception.tun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [Tun2ProxyConfig.toJson] -- no Android/Context involved, so plain
 * JUnit suffices. Asserts the exact JSON field names/shape `native/tun2proxy-bridge/src/config.rs`
 * expects (see that file's `BridgeConfig`/`WireProxyTarget`/`WireUdpGw` structs), since a
 * field-name/shape drift here would make the native side silently reject every config and the
 * "never pretend success" contract depends on that rejection actually happening (not on this
 * class fabricating a config that never leaves the JVM).
 */
class Tun2ProxyConfigTest {

    @Test
    fun `forMitmProxyEngine builds an http proxy target on loopback`() {
        val config = Tun2ProxyConfig.forMitmProxyEngine(mitmPort = 34567)

        assertEquals(Tun2ProxyConfig.ProxyType.HTTP, config.proxy.proxyType)
        assertEquals("127.0.0.1:34567", config.proxy.address)
        assertEquals(1500, config.mtu)
    }

    @Test
    fun `forMitmProxyEngine honors a custom mtu`() {
        val config = Tun2ProxyConfig.forMitmProxyEngine(mitmPort = 1, mtu = 9000)
        assertEquals(9000, config.mtu)
    }

    @Test
    fun `toJson emits every field name the rust side expects`() {
        val json = Tun2ProxyConfig.forMitmProxyEngine(mitmPort = 8443).toJson()

        assertTrue(json.contains("\"proxy\":"))
        assertTrue(json.contains("\"proxy_type\":\"http\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1:8443\""))
        assertTrue(json.contains("\"dns_mode\":\"direct\""))
        assertTrue(json.contains("\"dns_addr\":\"8.8.8.8\""))
        assertTrue(json.contains("\"virtual_dns_pool\":\"198.18.0.0/15\""))
        assertTrue(json.contains("\"bypass_cidrs\":[]"))
        assertTrue(json.contains("\"mtu\":1500"))
        assertTrue(json.contains("\"tcp_timeout_secs\":600"))
        assertTrue(json.contains("\"udp_timeout_secs\":10"))
        assertTrue(json.contains("\"ipv6_enabled\":false"))
        assertTrue(json.contains("\"verbosity\":\"info\""))
        assertTrue(json.contains("\"max_sessions\":200"))
        // No udpgw key at all when udpGw is null -- config.rs's `#[serde(default)]` on
        // `Option<WireUdpGw>` treats an absent key as None, so omitting rather than emitting
        // "udpgw":null is the correct wire shape either way, but omission is what this class does.
        assertFalse(json.contains("\"udpgw\""))
    }

    @Test
    fun `toJson round trips socks5 proxy auth and udpgw`() {
        val config = Tun2ProxyConfig(
            proxy = Tun2ProxyConfig.ProxyTarget(
                proxyType = Tun2ProxyConfig.ProxyType.SOCKS5,
                address = "10.0.0.1:1080",
                auth = Tun2ProxyConfig.ProxyAuth(username = "alice", password = "s3cret"),
            ),
            dnsMode = Tun2ProxyConfig.DnsMode.VIRTUAL,
            udpGw = Tun2ProxyConfig.UdpGw(server = "10.0.0.2:7300", maxConnections = 64, keepaliveSecs = 30),
            bypassCidrs = listOf("192.168.0.0/16", "10.0.0.0/8"),
        )
        val json = config.toJson()

        assertTrue(json.contains("\"proxy_type\":\"socks5\""))
        assertTrue(json.contains("\"address\":\"10.0.0.1:1080\""))
        assertTrue(json.contains("\"auth\":{\"username\":\"alice\",\"password\":\"s3cret\"}"))
        assertTrue(json.contains("\"dns_mode\":\"virtual\""))
        assertTrue(json.contains("\"udpgw\":{\"server\":\"10.0.0.2:7300\",\"max_connections\":64,\"keepalive_secs\":30}"))
        assertTrue(json.contains("\"bypass_cidrs\":[\"192.168.0.0/16\",\"10.0.0.0/8\"]"))
    }

    @Test
    fun `toJson escapes special characters in string fields`() {
        val config = Tun2ProxyConfig(
            proxy = Tun2ProxyConfig.ProxyTarget(
                proxyType = Tun2ProxyConfig.ProxyType.HTTP,
                address = "proxy.example.com:8080",
                auth = Tun2ProxyConfig.ProxyAuth(username = "a\"b\\c", password = "line1\nline2"),
            ),
        )
        val json = config.toJson()

        assertTrue(json.contains("\"username\":\"a\\\"b\\\\c\""))
        assertTrue(json.contains("\"password\":\"line1\\nline2\""))
    }

    @Test
    fun `udpgw omits optional fields when absent`() {
        val config = Tun2ProxyConfig(
            proxy = Tun2ProxyConfig.ProxyTarget(Tun2ProxyConfig.ProxyType.HTTP, "127.0.0.1:1"),
            udpGw = Tun2ProxyConfig.UdpGw(server = "127.0.0.1:9000"),
        )
        val json = config.toJson()

        assertTrue(json.contains("\"udpgw\":{\"server\":\"127.0.0.1:9000\"}"))
        assertFalse(json.contains("max_connections"))
        assertFalse(json.contains("keepalive_secs"))
    }
}
