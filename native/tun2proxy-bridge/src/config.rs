//! JSON config marshalling for the tun2proxy bridge.
//!
//! Kotlin builds a small JSON document (see `Tun2ProxyConfig.kt`) describing the proxy
//! target, DNS handling strategy, udpgw settings and misc tunables, and hands it across the
//! JNI boundary as a single string. This module is the single place that JSON is parsed and
//! turned into a real `tun2proxy::Args` -- there is no other path into `tun2proxy::Args`
//! construction in this crate, so a config bug here is the one place to look.
//!
//! Kept independent of `jni`/Android so `cargo test` can exercise every branch on the host.

use serde::Deserialize;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;
use tun2proxy::{ArgDns, ArgProxy, Args, ProxyType, UserKey};

/// Mirrors `tun2proxy::ProxyType`, spelled out so the wire format is stable even if the
/// upstream crate ever reorders its enum discriminants.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WireProxyType {
    Http,
    Socks4,
    Socks5,
}

impl WireProxyType {
    fn into_proxy_type(self) -> ProxyType {
        match self {
            WireProxyType::Http => ProxyType::Http,
            WireProxyType::Socks4 => ProxyType::Socks4,
            WireProxyType::Socks5 => ProxyType::Socks5,
        }
    }
}

/// Mirrors `tun2proxy::ArgDns`. `Virtual` is the fake-IP virtual-DNS mode; `OverTcp` forces
/// DNS lookups over TCP to the configured resolver; `Direct` leaves DNS alone (the flow just
/// needs to resolve outside the tun, e.g. the OS resolver).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WireDnsMode {
    Virtual,
    OverTcp,
    Direct,
}

impl WireDnsMode {
    fn into_arg_dns(self) -> ArgDns {
        match self {
            WireDnsMode::Virtual => ArgDns::Virtual,
            WireDnsMode::OverTcp => ArgDns::OverTcp,
            WireDnsMode::Direct => ArgDns::Direct,
        }
    }
}

/// Proxy authentication (Basic for HTTP CONNECT, username/password auth for SOCKS5,
/// user-id for SOCKS4/4a). `tun2proxy` decides SOCKS4 vs 4a and SOCKS5 vs 5h automatically
/// from whether a hostname is known for the flow (see `WireDnsMode::Virtual`'s doc on
/// `BridgeConfig::validate`) -- there is no separate wire toggle for it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct WireProxyAuth {
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WireProxyTarget {
    pub proxy_type: WireProxyType,
    /// "host:port" of the upstream proxy. For the Approach-A wiring this is always
    /// `127.0.0.1:<MitmProxyEngine.port>` -- MitmProxyEngine's own `ServerSocket` already
    /// speaks literal `CONNECT host:port HTTP/1.1`, which is exactly what `ProxyType::Http`
    /// sends. SOCKS4/4a/5/5h + auth are supported for completeness (e.g. chaining through an
    /// external proxy) even though the in-app wiring only exercises `Http`.
    pub address: String,
    #[serde(default)]
    pub auth: Option<WireProxyAuth>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WireUdpGw {
    /// "host:port" of the udpgw TCP server that gateways UDP-over-TCP for proxies that
    /// cannot carry raw UDP (SOCKS4/HTTP CONNECT proxies, in particular MitmProxyEngine's
    /// own listener, which is TCP CONNECT-only).
    pub server: String,
    #[serde(default)]
    pub max_connections: Option<u32>,
    #[serde(default)]
    pub keepalive_secs: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BridgeConfig {
    pub proxy: WireProxyTarget,
    #[serde(default)]
    pub dns_mode: WireDnsMode,
    /// Resolver `tun2proxy` itself talks to for `OverTcp`/direct-passthrough DNS. Ignored
    /// for `Virtual`, which never dials a resolver -- it always answers from its own pool.
    #[serde(default = "default_dns_addr")]
    pub dns_addr: String,
    /// CIDR pool virtual DNS hands out fake answers from (RFC 5737/6598-style private
    /// range recommended). Ignored unless `dns_mode == Virtual`.
    #[serde(default = "default_virtual_dns_pool")]
    pub virtual_dns_pool: String,
    #[serde(default)]
    pub udpgw: Option<WireUdpGw>,
    #[serde(default)]
    pub bypass_cidrs: Vec<String>,
    #[serde(default = "default_mtu")]
    pub mtu: u16,
    #[serde(default = "default_tcp_timeout_secs")]
    pub tcp_timeout_secs: u64,
    #[serde(default = "default_udp_timeout_secs")]
    pub udp_timeout_secs: u64,
    #[serde(default)]
    pub ipv6_enabled: bool,
    #[serde(default = "default_verbosity")]
    pub verbosity: String,
    #[serde(default = "default_max_sessions")]
    pub max_sessions: usize,
}

impl Default for WireDnsMode {
    fn default() -> Self {
        WireDnsMode::Direct
    }
}

fn default_dns_addr() -> String {
    "8.8.8.8".to_string()
}
fn default_virtual_dns_pool() -> String {
    "198.18.0.0/15".to_string()
}
fn default_mtu() -> u16 {
    1500
}
fn default_tcp_timeout_secs() -> u64 {
    600
}
fn default_udp_timeout_secs() -> u64 {
    10
}
fn default_verbosity() -> String {
    "info".to_string()
}
fn default_max_sessions() -> usize {
    200
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigError(pub String);

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}
impl std::error::Error for ConfigError {}

impl From<String> for ConfigError {
    fn from(s: String) -> Self {
        ConfigError(s)
    }
}

/// Parses `json` into a [`BridgeConfig`]. The only entry point Kotlin's config JSON goes
/// through -- never hand-assembled `tun2proxy::Args` fields anywhere else in this crate.
pub fn parse_config(json: &str) -> Result<BridgeConfig, ConfigError> {
    serde_json::from_str(json).map_err(|e| ConfigError(format!("invalid tun2proxy bridge config JSON: {e}")))
}

/// Builds a real `tun2proxy::Args` from a parsed [`BridgeConfig`] plus the tun fd handed
/// across JNI. `close_fd_on_drop` is always forced to `false`: the Kotlin
/// `ParcelFileDescriptor` retains sole ownership of the fd's lifecycle (closed exactly once,
/// from `RequestrVpnService.cleanup()`), so tun2proxy must never close it itself -- doing so
/// would race the two independent "owners" of one fd number.
pub fn build_args(config: &BridgeConfig, tun_fd: i32) -> Result<Args, ConfigError> {
    let proxy_addr: SocketAddr = config
        .proxy
        .address
        .parse()
        .map_err(|e| ConfigError(format!("invalid proxy address '{}': {e}", config.proxy.address)))?;

    let credentials = config.proxy.auth.as_ref().filter(|a| !a.username.is_empty() || !a.password.is_empty()).map(|a| UserKey::new(a.username.clone(), a.password.clone()));

    let arg_proxy = ArgProxy {
        proxy_type: config.proxy.proxy_type.into_proxy_type(),
        addr: proxy_addr,
        credentials,
    };

    let dns_addr: IpAddr = config
        .dns_addr
        .parse()
        .map_err(|e| ConfigError(format!("invalid dns_addr '{}': {e}", config.dns_addr)))?;

    let virtual_dns_pool = tproxy_config_ip_cidr(&config.virtual_dns_pool)?;

    let mut args = Args::default();
    args.proxy(arg_proxy);
    args.dns(config.dns_mode.into_arg_dns());
    args.dns_addr(dns_addr);
    args.tun_fd(Some(tun_fd));
    args.close_fd_on_drop(false);
    args.ipv6_enabled(config.ipv6_enabled);
    args.verbosity(parse_verbosity(&config.verbosity)?);
    args.virtual_dns_pool = virtual_dns_pool;
    args.mtu = config.mtu;
    args.tcp_timeout = config.tcp_timeout_secs;
    args.udp_timeout = config.udp_timeout_secs;
    args.max_sessions = config.max_sessions;
    // Never let tun2proxy touch routing/interfaces -- Android's VpnService.Builder already
    // established and owns the tun's routes; `--setup` is a Linux/macOS/Windows-only concept
    // (tproxy_config route/DNS mutation) that would be a no-op-or-worse on Android.
    args.setup(false);

    for cidr in &config.bypass_cidrs {
        args.bypass(tproxy_config_ip_cidr(cidr)?);
    }

    if let Some(udpgw) = &config.udpgw {
        let addr: SocketAddr = udpgw
            .server
            .parse()
            .map_err(|e| ConfigError(format!("invalid udpgw server '{}': {e}", udpgw.server)))?;
        args.udpgw_server(addr);
        if let Some(n) = udpgw.max_connections {
            args.udpgw_connections(n as usize);
        }
        if let Some(secs) = udpgw.keepalive_secs {
            args.udpgw_keepalive = Some(secs);
        }
    }

    Ok(args)
}

fn tproxy_config_ip_cidr(s: &str) -> Result<tproxy_config::IpCidr, ConfigError> {
    tproxy_config::IpCidr::from_str(s).map_err(|e| ConfigError(format!("invalid CIDR '{s}': {e}")))
}

fn parse_verbosity(s: &str) -> Result<tun2proxy::ArgVerbosity, ConfigError> {
    use tun2proxy::ArgVerbosity::*;
    Ok(match s.to_ascii_lowercase().as_str() {
        "off" => Off,
        "error" => Error,
        "warn" | "warning" => Warn,
        "info" => Info,
        "debug" => Debug,
        "trace" => Trace,
        other => return Err(ConfigError(format!("invalid verbosity '{other}'"))),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal_json(proxy_type: &str, port: u16) -> String {
        format!(r#"{{"proxy":{{"proxy_type":"{proxy_type}","address":"127.0.0.1:{port}"}}}}"#)
    }

    #[test]
    fn parses_minimal_http_config_with_defaults() {
        let cfg = parse_config(&minimal_json("http", 18080)).expect("should parse");
        assert_eq!(cfg.proxy.proxy_type, WireProxyType::Http);
        assert_eq!(cfg.proxy.address, "127.0.0.1:18080");
        assert_eq!(cfg.dns_mode, WireDnsMode::Direct);
        assert_eq!(cfg.mtu, 1500);
        assert_eq!(cfg.max_sessions, 200);
        assert!(cfg.udpgw.is_none());
        assert!(cfg.bypass_cidrs.is_empty());
    }

    #[test]
    fn rejects_malformed_json() {
        let err = parse_config("{not json").unwrap_err();
        assert!(err.0.contains("invalid tun2proxy bridge config JSON"));
    }

    #[test]
    fn builds_args_for_http_proxy_target_mitm_engine() {
        let cfg = parse_config(&minimal_json("http", 41234)).unwrap();
        let args = build_args(&cfg, 42).expect("should build args");
        assert_eq!(args.proxy.proxy_type, ProxyType::Http);
        assert_eq!(args.proxy.addr.port(), 41234);
        assert_eq!(args.tun_fd, Some(42));
        assert_eq!(args.close_fd_on_drop, Some(false));
        assert!(!args.setup);
    }

    #[test]
    fn socks5_with_auth_round_trips_credentials() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"10.0.2.2:1080","auth":{"username":"alice","password":"s3cret"}},
            "dns_mode": "virtual"
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 7).unwrap();
        assert_eq!(args.proxy.proxy_type, ProxyType::Socks5);
        let creds = args.proxy.credentials.expect("credentials must round-trip");
        assert_eq!(creds.username, "alice");
        assert_eq!(creds.password, "s3cret");
        assert_eq!(args.dns, ArgDns::Virtual);
    }

    #[test]
    fn socks4_without_auth_has_no_credentials() {
        let cfg = parse_config(&minimal_json("socks4", 1080)).unwrap();
        let args = build_args(&cfg, 3).unwrap();
        assert_eq!(args.proxy.proxy_type, ProxyType::Socks4);
        assert!(args.proxy.credentials.is_none());
    }

    #[test]
    fn udpgw_server_is_wired_through() {
        let json = r#"{
            "proxy": {"proxy_type":"http","address":"127.0.0.1:8181"},
            "udpgw": {"server":"127.0.0.1:7300","max_connections":8,"keepalive_secs":25}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 9).unwrap();
        assert_eq!(args.udpgw_server.unwrap().to_string(), "127.0.0.1:7300");
        assert_eq!(args.udpgw_connections, Some(8));
        assert_eq!(args.udpgw_keepalive, Some(25));
    }

    #[test]
    fn bypass_cidrs_accumulate_in_order() {
        let json = r#"{
            "proxy": {"proxy_type":"http","address":"127.0.0.1:8181"},
            "bypass_cidrs": ["10.0.0.0/8", "192.168.0.0/16"]
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        assert_eq!(args.bypass.len(), 2);
    }

    #[test]
    fn invalid_proxy_address_is_rejected_before_reaching_tun2proxy() {
        let json = r#"{"proxy": {"proxy_type":"http","address":"not-an-address"}}"#;
        let cfg = parse_config(json).unwrap();
        let err = build_args(&cfg, 1).unwrap_err();
        assert!(err.0.contains("invalid proxy address"));
    }

    #[test]
    fn invalid_verbosity_is_rejected() {
        let json = r#"{"proxy": {"proxy_type":"http","address":"127.0.0.1:1"},"verbosity":"screaming"}"#;
        let cfg = parse_config(json).unwrap();
        let err = build_args(&cfg, 1).unwrap_err();
        assert!(err.0.contains("invalid verbosity"));
    }

    #[test]
    fn close_fd_on_drop_is_always_false_regardless_of_input() {
        // There is no wire field for this at all -- Kotlin cannot accidentally request the
        // native side to close a fd the ParcelFileDescriptor still owns.
        let cfg = parse_config(&minimal_json("http", 1)).unwrap();
        let args = build_args(&cfg, 5).unwrap();
        assert_eq!(args.close_fd_on_drop, Some(false));
    }
}
