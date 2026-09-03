package com.example.interception.tun

/**
 * Kotlin mirror of `native/tun2proxy-bridge/src/config.rs`'s `BridgeConfig` JSON wire schema.
 * [toJson] is the single place that JSON is produced for [NativeTun2Proxy.start] /
 * [Tun2ProxyBridge.start] -- keep it byte-for-byte in sync with the `#[derive(Deserialize)]`
 * structs in `config.rs`.
 *
 * Deliberately does not use `org.json.JSONObject`: that class is an unmocked stub under this
 * project's plain JVM unit tests, so building JSON by hand keeps config marshalling unit-testable
 * without Robolectric.
 */
data class Tun2ProxyConfig(
    val proxy: ProxyTarget,
    val dnsMode: DnsMode = DnsMode.DIRECT,
    val dnsAddr: String = "8.8.8.8",
    val virtualDnsPool: String = "198.18.0.0/15",
    val udpGw: UdpGw? = null,
    val bypassCidrs: List<String> = emptyList(),
    val mtu: Int = 1500,
    val tcpTimeoutSecs: Long = 600,
    val udpTimeoutSecs: Long = 10,
    val ipv6Enabled: Boolean = false,
    /** One of "off"/"error"/"warn"/"info"/"debug"/"trace" -- mirrors `ArgVerbosity`. */
    val verbosity: String = "info",
    val maxSessions: Int = 200,

    // --- Vendored fork extensions (S0 task 2) ---

    /** UDP policy mode for capture-scoped QUIC handling (Decision D6). */
    val udpPolicy: UdpPolicyMode = UdpPolicyMode.PASS_ALL,

    /** Enable the packet tee device for PCAPng capture and passive QUIC decode. */
    val teeEnabled: Boolean = false,

    /** Whether VpnService.protect() is available for native socket protection. */
    val protectAvailable: Boolean = false,

    /** SOCKS5 `+info` metadata for session attribution through the local proxy. */
    val socks5Info: Socks5InfoMetadata? = null,
) {
    /** Mirrors `tun2proxy::ProxyType` via `config::WireProxyType`. */
    enum class ProxyType(val wireName: String) {
        HTTP("http"),
        SOCKS4("socks4"),
        SOCKS5("socks5"),
    }

    /**
     * Mirrors `tun2proxy::ArgDns` via `config::WireDnsMode`. `VIRTUAL` is what makes tun2proxy
     * preserve the original hostname per-flow, which is also what makes it automatically speak
     * SOCKS4a/5h (remote DNS resolution) instead of plain SOCKS4/5.
     */
    enum class DnsMode(val wireName: String) {
        VIRTUAL("virtual"),
        OVER_TCP("over_tcp"),
        DIRECT("direct"),
    }

    /** UDP policy mode (mirrors `UdpPolicyMode` in Rust `config.rs`). */
    enum class UdpPolicyMode(val wireName: String) {
        PASS_ALL("pass_all"),
        BLOCK_QUIC("block_quic"),
        OBSERVE_QUIC("observe_quic"),
    }

    data class ProxyAuth(val username: String, val password: String)

    data class ProxyTarget(
        val proxyType: ProxyType,
        /** "host:port" of the upstream proxy target. */
        val address: String,
        val auth: ProxyAuth? = null,
    )

    /** UDP-over-TCP gateway. */
    data class UdpGw(
        val server: String,
        val maxConnections: Int? = null,
        val keepaliveSecs: Long? = null,
    )

    /** SOCKS5 `+info` metadata for session attribution. */
    data class Socks5InfoMetadata(
        val appPackage: String? = null,
        val uid: Int? = null,
        val sessionTag: String? = null,
    )

    /** Serializes to exactly the JSON shape `config::parse_config` (Rust) expects. */
    fun toJson(): String = buildString {
        append('{')
        append("\"proxy\":").append(proxy.toWireJson())
        append(",\"dns_mode\":").append(jsonString(dnsMode.wireName))
        append(",\"dns_addr\":").append(jsonString(dnsAddr))
        append(",\"virtual_dns_pool\":").append(jsonString(virtualDnsPool))
        if (udpGw != null) {
            append(",\"udpgw\":").append(udpGw.toWireJson())
        }
        append(",\"bypass_cidrs\":[")
        append(bypassCidrs.joinToString(",") { jsonString(it) })
        append(']')
        append(",\"mtu\":").append(mtu)
        append(",\"tcp_timeout_secs\":").append(tcpTimeoutSecs)
        append(",\"udp_timeout_secs\":").append(udpTimeoutSecs)
        append(",\"ipv6_enabled\":").append(ipv6Enabled)
        append(",\"verbosity\":").append(jsonString(verbosity))
        append(",\"max_sessions\":").append(maxSessions)
        append(",\"udp_policy\":").append(jsonString(udpPolicy.wireName))
        append(",\"tee_enabled\":").append(teeEnabled)
        append(",\"protect_available\":").append(protectAvailable)
        if (socks5Info != null) {
            append(",\"socks5_info\":").append(socks5Info.toWireJson())
        }
        append('}')
    }

    private fun ProxyTarget.toWireJson(): String = buildString {
        append('{')
        append("\"proxy_type\":").append(jsonString(proxyType.wireName))
        append(",\"address\":").append(jsonString(address))
        if (auth != null) {
            append(",\"auth\":{")
            append("\"username\":").append(jsonString(auth.username))
            append(",\"password\":").append(jsonString(auth.password))
            append('}')
        }
        append('}')
    }

    private fun UdpGw.toWireJson(): String = buildString {
        append('{')
        append("\"server\":").append(jsonString(server))
        if (maxConnections != null) append(",\"max_connections\":").append(maxConnections)
        if (keepaliveSecs != null) append(",\"keepalive_secs\":").append(keepaliveSecs)
        append('}')
    }

    private fun Socks5InfoMetadata.toWireJson(): String = buildString {
        append('{')
        var first = true
        if (appPackage != null) {
            append("\"app_package\":").append(jsonString(appPackage))
            first = false
        }
        if (uid != null) {
            if (!first) append(',')
            append("\"uid\":").append(uid)
            first = false
        }
        if (sessionTag != null) {
            if (!first) append(',')
            append("\"session_tag\":").append(jsonString(sessionTag))
        }
        append('}')
    }

    companion object {
        /**
         * Builds the config for the standard Approach-A wiring: HTTP CONNECT target pointed
         * at the locally-started MitmProxyEngine on `127.0.0.1:mitmPort`.
         *
         * Uses VIRTUAL DNS so tun2proxy preserves hostnames. Protection is required by default
         * when used from VPN service (caller should set protectAvailable=true when
         * SocketProtector is installed). SOCKS5 +info attribution activates in S1 when
         * LocalGateway replaces MitmProxyEngine as the local proxy.
         */
        fun forMitmProxyEngine(
            mitmPort: Int,
            mtu: Int = 1500,
            protectAvailable: Boolean = true,
        ): Tun2ProxyConfig = Tun2ProxyConfig(
            proxy = ProxyTarget(proxyType = ProxyType.HTTP, address = "127.0.0.1:$mitmPort"),
            dnsMode = DnsMode.VIRTUAL,
            mtu = mtu,
            protectAvailable = protectAvailable,
        )

        /** Minimal JSON string escaping. */
        internal fun jsonString(value: String): String = buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
