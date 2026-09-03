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

use crate::udp_policy::UdpPolicyMode;

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

/// Suffix the vendored `tun2proxy::socks` recognises on the SOCKS5 username to
/// enable per-flow source attribution (`"<base>|<proto>|<src_ip>|<src_port>"`).
pub const SOCKS5_INFO_MARKER: &str = "+info";

/// Metadata that opts a SOCKS5 session into `+info` source attribution.
/// When present, the bridge suffixes the SOCKS5 auth username with
/// [`SOCKS5_INFO_MARKER`] so the vendored client appends the originating flow's
/// source tuple, which the local proxy (`LocalGateway` in S1) decodes.
/// `app_package`/`uid` are retained for Kotlin-side bookkeeping; they are not
/// carried on the wire (the gateway attributes per flow via the source port).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, serde::Serialize)]
pub struct Socks5InfoMetadata {
    /// Android package name of the originating app.
    #[serde(default)]
    pub app_package: Option<String>,
    /// UID of the originating app.
    #[serde(default)]
    pub uid: Option<u32>,
    /// Opaque session tag for correlation with Kotlin-side capture state.
    #[serde(default)]
    pub session_tag: Option<String>,
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

    // --- S0 task 2: new fields for the extended bridge protocol ---
    /// UDP policy mode for capture-scoped QUIC handling (Decision D6).
    /// Default `pass_all` preserves legacy behavior; S1 refines to per-UID scoping.
    #[serde(default)]
    pub udp_policy: UdpPolicyMode,

    /// Enable the packet tee device. When true, every IP-layer packet flowing through
    /// the tun is copied to the registered PacketSink (feeds PCAPng and passive QUIC
    /// decode). Default false.
    #[serde(default)]
    pub tee_enabled: bool,

    /// Whether a socket-protect callback is available. When true, the bridge calls back
    /// into Kotlin's `VpnService.protect()` before every egress connect to prevent
    /// routing loops. Set by `BridgeSupervisor` based on VPN lifecycle state.
    #[serde(default)]
    pub protect_available: bool,

    /// SOCKS5 authentication metadata carrying the `+info` session tuple.
    /// Present when `proxy.proxy_type == socks5` and the proxy supports `+info`
    /// metadata encoding in the username field. Structure:
    /// `{"app_package":"com.example.app","uid":10123,"session_tag":"capture-42"}`.
    #[serde(default)]
    pub socks5_info: Option<Socks5InfoMetadata>,
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
    serde_json::from_str(json)
        .map_err(|e| ConfigError(format!("invalid tun2proxy bridge config JSON: {e}")))
}

/// Builds a real `tun2proxy::Args` from a parsed [`BridgeConfig`] plus the tun fd handed
/// across JNI. `close_fd_on_drop` is always forced to `false`: the Kotlin
/// `ParcelFileDescriptor` retains sole ownership of the fd's lifecycle (closed exactly once,
/// from `RequestrVpnService.cleanup()`), so tun2proxy must never close it itself -- doing so
/// would race the two independent "owners" of one fd number.
pub fn build_args(config: &BridgeConfig, tun_fd: i32) -> Result<Args, ConfigError> {
    let proxy_addr: SocketAddr = config.proxy.address.parse().map_err(|e| {
        ConfigError(format!(
            "invalid proxy address '{}': {e}",
            config.proxy.address
        ))
    })?;

    let credentials = config
        .proxy
        .auth
        .as_ref()
        .filter(|a| !a.username.is_empty() || !a.password.is_empty())
        .map(|a| UserKey::new(a.username.clone(), a.password.clone()));

    // requestr: when socks5_info is set for a SOCKS5 proxy, opt the session into
    // the vendored tun2proxy `+info` username protocol. `socks.rs::send_auth_data`
    // only recognises a username that *ends with* the literal `+info` marker; it
    // then strips the marker and sends `"<base>|<tcp|udp>|<src_ip>|<src_port>"`
    // as the RFC 1929 username, which is how the per-flow source tuple reaches
    // the LocalGateway. (A previous revision sent `+info:{json}` as a prefix,
    // which the vendored code never matched -- the JSON went over the wire
    // verbatim and no source tuple was ever appended.)
    //
    // `<base>` is the explicit auth username when configured (so the gateway can
    // verify it), else the session tag, else "requestr". The password is the
    // explicit auth password (empty when none) -- the gateway authenticates on
    // `<base>` + password after decoding the tuple.
    let credentials = if config.proxy.proxy_type == WireProxyType::Socks5 {
        if let Some(ref info) = config.socks5_info {
            let (base, password) = match credentials {
                Some(ref explicit) => (explicit.username.clone(), explicit.password.clone()),
                None => (
                    info.session_tag
                        .clone()
                        .filter(|t| !t.is_empty())
                        .unwrap_or_else(|| "requestr".to_string()),
                    String::new(),
                ),
            };
            Some(UserKey::new(
                format!("{base}{SOCKS5_INFO_MARKER}"),
                password,
            ))
        } else {
            credentials
        }
    } else {
        credentials
    };

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
        let json =
            r#"{"proxy": {"proxy_type":"http","address":"127.0.0.1:1"},"verbosity":"screaming"}"#;
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

    // --- S0 task 2: new config field tests ---

    #[test]
    fn new_fields_default_correctly_when_absent() {
        let cfg = parse_config(&minimal_json("http", 8080)).unwrap();
        assert_eq!(cfg.udp_policy, UdpPolicyMode::PassAll);
        assert!(!cfg.tee_enabled);
        assert!(!cfg.protect_available);
        assert!(cfg.socks5_info.is_none());
    }

    #[test]
    fn udp_policy_parses_from_config_json() {
        let json = r#"{
            "proxy": {"proxy_type":"http","address":"127.0.0.1:8080"},
            "udp_policy": "block_quic"
        }"#;
        let cfg = parse_config(json).unwrap();
        assert_eq!(cfg.udp_policy, UdpPolicyMode::BlockQuic);
    }

    #[test]
    fn tee_and_protect_flags_parse() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080"},
            "tee_enabled": true,
            "protect_available": true
        }"#;
        let cfg = parse_config(json).unwrap();
        assert!(cfg.tee_enabled);
        assert!(cfg.protect_available);
    }

    #[test]
    fn socks5_info_metadata_round_trips() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080"},
            "socks5_info": {
                "app_package": "com.example.browser",
                "uid": 10042,
                "session_tag": "capture-7"
            }
        }"#;
        let cfg = parse_config(json).unwrap();
        let info = cfg.socks5_info.expect("socks5_info must be present");
        assert_eq!(info.app_package.as_deref(), Some("com.example.browser"));
        assert_eq!(info.uid, Some(10042));
        assert_eq!(info.session_tag.as_deref(), Some("capture-7"));
    }

    #[test]
    fn socks5_info_with_partial_fields() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080"},
            "socks5_info": {"uid": 10001}
        }"#;
        let cfg = parse_config(json).unwrap();
        let info = cfg.socks5_info.unwrap();
        assert!(info.app_package.is_none());
        assert_eq!(info.uid, Some(10001));
        assert!(info.session_tag.is_none());
    }

    #[test]
    fn full_config_with_all_new_fields() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080","auth":{"username":"u","password":"p"}},
            "dns_mode": "virtual",
            "ipv6_enabled": true,
            "max_sessions": 64,
            "udp_policy": "observe_quic",
            "tee_enabled": true,
            "protect_available": true,
            "socks5_info": {"app_package":"com.test","uid":99,"session_tag":"s1"}
        }"#;
        let cfg = parse_config(json).unwrap();
        assert_eq!(cfg.udp_policy, UdpPolicyMode::ObserveQuic);
        assert!(cfg.tee_enabled);
        assert!(cfg.protect_available);
        assert!(cfg.ipv6_enabled);
        assert_eq!(cfg.max_sessions, 64);
        assert_eq!(cfg.socks5_info.as_ref().unwrap().uid, Some(99));
        // Verify the existing fields still work with args builder
        let args = build_args(&cfg, 10).unwrap();
        assert_eq!(args.proxy.proxy_type, ProxyType::Socks5);
        assert!(args.proxy.credentials.is_some());
        // Verify the username opts into the vendored `+info` protocol: explicit
        // auth base + marker suffix, password preserved for gateway verification.
        let creds = args.proxy.credentials.as_ref().unwrap();
        assert_eq!(creds.username, "u+info");
        assert_eq!(creds.password, "p");
    }

    #[test]
    fn socks5_info_without_auth_uses_session_tag_as_base() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080"},
            "socks5_info": {"session_tag": "sess-7"}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        let creds = args.proxy.credentials.unwrap();
        assert_eq!(creds.username, "sess-7+info");
        assert_eq!(creds.password, "");
    }

    #[test]
    fn socks5_info_without_auth_or_tag_uses_default_base() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080"},
            "socks5_info": {"uid": 42}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        let creds = args.proxy.credentials.unwrap();
        assert_eq!(creds.username, "requestr+info");
    }

    #[test]
    fn socks5_info_keeps_explicit_auth_and_appends_marker() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080","auth":{"username":"manual","password":"pw"}},
            "socks5_info": {"uid": 42}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        let creds = args.proxy.credentials.unwrap();
        assert!(
            creds.username.ends_with(SOCKS5_INFO_MARKER),
            "username must end with the +info marker the vendored client matches, got '{}'",
            creds.username
        );
        assert_eq!(creds.username, "manual+info");
        assert_eq!(creds.password, "pw");
    }

    #[test]
    fn socks5_without_info_leaves_plain_credentials() {
        let json = r#"{
            "proxy": {"proxy_type":"socks5","address":"127.0.0.1:1080","auth":{"username":"manual","password":"pw"}}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        let creds = args.proxy.credentials.unwrap();
        assert_eq!(creds.username, "manual");
    }

    #[test]
    fn http_proxy_ignores_socks5_info() {
        let json = r#"{
            "proxy": {"proxy_type":"http","address":"127.0.0.1:8080"},
            "socks5_info": {"uid": 42}
        }"#;
        let cfg = parse_config(json).unwrap();
        let args = build_args(&cfg, 1).unwrap();
        // HTTP proxy should not have +info credentials
        assert!(args.proxy.credentials.is_none());
    }
}
