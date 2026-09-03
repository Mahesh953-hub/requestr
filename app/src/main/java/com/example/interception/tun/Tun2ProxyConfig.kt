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
    val virtualDnsPool: String = VIRTUAL_DNS_POOL,
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
         * S1 task 11: the virtual-DNS pool the bridge answers from. Shared with the VPN service,
         * which advertises a resolver *inside* this pool (`VpnDnsPlan.VIRTUAL_DNS_RESOLVER`) so
         * every app query terminates in tun2proxy's virtual resolver instead of leaking to a
         * public one.
         */
        const val VIRTUAL_DNS_POOL = "198.18.0.0/15"

        /**
         * Legacy Approach-A wiring: HTTP CONNECT target pointed at the local proxy on
         * `127.0.0.1:mitmPort`. Production (`RequestrVpnService`) has moved to [forLocalGateway]
         * (SOCKS5 + `+info`, S1 task 6); this stays for the HTTP-CONNECT entry, which the
         * gateway still accepts, and for existing bridge tests.
         *
         * Uses VIRTUAL DNS so tun2proxy preserves hostnames. Protection is required by default
         * when used from VPN service (caller should set protectAvailable=true when
         * SocketProtector is installed).
         *
         * Defect G (S0 gaps audit): [teeEnabled] defaults to `true` here (the call-site
         * default), NOT via [Tun2ProxyConfig]'s own `teeEnabled` data-class default (which stays
         * `false` so an explicit [Tun2ProxyConfig] construction elsewhere doesn't silently
         * enable teeing). The one production call site
         * ([com.example.interception.RequestrVpnService]) overrides this default by resolving
         * `TeeRolloutFlag` via its `applicationContext` and passing the result through, so the
         * tee surface can be rolled back process-wide without a code change. This class
         * deliberately stays Android/Context-decoupled (see its class doc) -- it does not read
         * the flag itself.
         */
        fun forMitmProxyEngine(
            mitmPort: Int,
            mtu: Int = 1500,
            protectAvailable: Boolean = true,
            teeEnabled: Boolean = true,
        ): Tun2ProxyConfig = Tun2ProxyConfig(
            proxy = ProxyTarget(proxyType = ProxyType.HTTP, address = "127.0.0.1:$mitmPort"),
            dnsMode = DnsMode.VIRTUAL,
            mtu = mtu,
            protectAvailable = protectAvailable,
            teeEnabled = teeEnabled,
        )

        /**
         * S1 task 6 wiring: SOCKS5 target pointed at the [com.example.interception.gateway.LocalGateway]
         * on `127.0.0.1:gatewayPort`, authenticated with per-session [credentials] and opted into
         * the bridge's `+info` protocol via [Socks5InfoMetadata] so every flow's real source
         * tuple reaches the gateway (`"<username>|tcp|<src_ip>|<src_port>"`; decoded by
         * `Socks5Handler`). Same VIRTUAL DNS / tee / protect conventions as [forMitmProxyEngine].
         *
         * [udpPolicy] defaults to PASS_ALL until S1 task 9 (UdpPolicyRouter, D6) lands.
         */
        fun forLocalGateway(
            gatewayPort: Int,
            credentials: com.example.interception.gateway.local.Socks5Credentials,
            sessionTag: String? = null,
            mtu: Int = 1500,
            ipv6Enabled: Boolean = false,
            udpPolicy: UdpPolicyMode = UdpPolicyMode.PASS_ALL,
            protectAvailable: Boolean = true,
            teeEnabled: Boolean = true,
        ): Tun2ProxyConfig = Tun2ProxyConfig(
            proxy = ProxyTarget(
                proxyType = ProxyType.SOCKS5,
                address = "127.0.0.1:$gatewayPort",
                auth = ProxyAuth(credentials.username, credentials.password),
            ),
            dnsMode = DnsMode.VIRTUAL,
            mtu = mtu,
            ipv6Enabled = ipv6Enabled,
            udpPolicy = udpPolicy,
            protectAvailable = protectAvailable,
            teeEnabled = teeEnabled,
            socks5Info = Socks5InfoMetadata(sessionTag = sessionTag),
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
