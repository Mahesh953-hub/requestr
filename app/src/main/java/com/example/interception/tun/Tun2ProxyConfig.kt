package com.example.interception.tun

/**
 * Kotlin mirror of `native/tun2proxy-bridge/src/config.rs`'s `BridgeConfig` JSON wire schema
 * (Gap #1 Approach A). [toJson] is the single place that JSON is produced for
 * [NativeTun2Proxy.start]/[Tun2ProxyBridge.start] -- keep it byte-for-byte in sync with the
 * `#[derive(Deserialize)]` structs in `config.rs` (field names, defaults, snake_case wire
 * values) or the native side will reject the config.
 *
 * Deliberately does not use `org.json.JSONObject`: that class is an unmocked stub under this
 * project's plain JVM unit tests (see e.g. `RuleEnforcerBodyReplacedFlagTest`'s doc comment,
 * which explains the same constraint), so building JSON by hand here keeps config marshalling
 * itself unit-testable without Robolectric.
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
     * SOCKS4a/5h (remote DNS resolution) instead of plain SOCKS4/5 -- there is no separate wire
     * toggle for 4/4a or 5/5h, see `config.rs`'s doc comment on `WireProxyAuth`.
     */
    enum class DnsMode(val wireName: String) {
        VIRTUAL("virtual"),
        OVER_TCP("over_tcp"),
        DIRECT("direct"),
    }

    data class ProxyAuth(val username: String, val password: String)

    data class ProxyTarget(
        val proxyType: ProxyType,
        /** "host:port" of the upstream proxy target. */
        val address: String,
        val auth: ProxyAuth? = null,
    )

    /** UDP-over-TCP gateway, needed because [ProxyType.HTTP]/CONNECT proxies (in particular
     * `MitmProxyEngine`'s own listener, the Approach-A target) cannot carry raw UDP. */
    data class UdpGw(
        val server: String,
        val maxConnections: Int? = null,
        val keepaliveSecs: Long? = null,
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

    companion object {
        /**
         * Builds the config for the one shape Approach A actually uses in production: an HTTP
         * CONNECT target pointed at [RequestrVpnService]'s own locally-started `MitmProxyEngine`
         * instance on `127.0.0.1:mitmPort`. SOCKS4/4a/5/5h + auth + udpgw remain configurable on
         * [Tun2ProxyConfig] directly for completeness (chaining through an external proxy), but
         * are not exercised by this factory.
         */
        fun forMitmProxyEngine(mitmPort: Int, mtu: Int = 1500): Tun2ProxyConfig = Tun2ProxyConfig(
            proxy = ProxyTarget(proxyType = ProxyType.HTTP, address = "127.0.0.1:$mitmPort"),
            mtu = mtu,
        )

        /** Minimal-but-correct JSON string escaping. Every field this config ever serializes is
         * either a fixed enum literal or something Kotlin code constructs (host:port,
         * credentials) -- never raw untrusted external input -- but this escapes properly
         * regardless rather than assuming that stays true forever. */
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
