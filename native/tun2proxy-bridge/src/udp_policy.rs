//! UDP policy adapter — configurable default that plugs into the vendor hook.

use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use tun2proxy::requestr_hooks;

/// Default UDP policy mode set from the bridge config.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UdpPolicyMode {
    PassAll,
    BlockQuic,
    ObserveQuic,
}

impl Default for UdpPolicyMode {
    fn default() -> Self {
        UdpPolicyMode::PassAll
    }
}

/// A default policy hook driven by the bridge config.
pub(crate) struct ConfigDrivenUdpPolicy {
    mode: UdpPolicyMode,
}

impl ConfigDrivenUdpPolicy {
    pub fn new(mode: UdpPolicyMode) -> Arc<Self> {
        Arc::new(ConfigDrivenUdpPolicy { mode })
    }
}

impl requestr_hooks::UdpPolicyHook for ConfigDrivenUdpPolicy {
    fn evaluate(&self, _src: SocketAddr, dst: SocketAddr) -> requestr_hooks::UdpVerdict {
        match self.mode {
            UdpPolicyMode::PassAll => requestr_hooks::UdpVerdict::Allow,
            UdpPolicyMode::BlockQuic => {
                if dst.port() == 443 {
                    requestr_hooks::UdpVerdict::TeeAndDrop
                } else {
                    requestr_hooks::UdpVerdict::Allow
                }
            }
            UdpPolicyMode::ObserveQuic => {
                if dst.port() == 443 {
                    requestr_hooks::UdpVerdict::TeeAndPass
                } else {
                    requestr_hooks::UdpVerdict::Allow
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tun2proxy::requestr_hooks::UdpPolicyHook;

    #[test]
    fn pass_all_allows_everything() {
        let hook = ConfigDrivenUdpPolicy::new(UdpPolicyMode::PassAll);
        let src: SocketAddr = "10.0.0.1:1".parse().unwrap();
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:443".parse().unwrap()),
            requestr_hooks::UdpVerdict::Allow
        );
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:53".parse().unwrap()),
            requestr_hooks::UdpVerdict::Allow
        );
    }

    #[test]
    fn block_quic_drops_443() {
        let hook = ConfigDrivenUdpPolicy::new(UdpPolicyMode::BlockQuic);
        let src: SocketAddr = "10.0.0.1:1".parse().unwrap();
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:443".parse().unwrap()),
            requestr_hooks::UdpVerdict::TeeAndDrop
        );
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:53".parse().unwrap()),
            requestr_hooks::UdpVerdict::Allow
        );
    }

    #[test]
    fn observe_quic_passes_443() {
        let hook = ConfigDrivenUdpPolicy::new(UdpPolicyMode::ObserveQuic);
        let src: SocketAddr = "10.0.0.1:1".parse().unwrap();
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:443".parse().unwrap()),
            requestr_hooks::UdpVerdict::TeeAndPass
        );
        assert_eq!(
            hook.evaluate(src, "8.8.8.8:53".parse().unwrap()),
            requestr_hooks::UdpVerdict::Allow
        );
    }

    #[test]
    fn mode_round_trips_through_json() {
        for mode in [
            UdpPolicyMode::PassAll,
            UdpPolicyMode::BlockQuic,
            UdpPolicyMode::ObserveQuic,
        ] {
            let json = serde_json::to_string(&mode).unwrap();
            let parsed: UdpPolicyMode = serde_json::from_str(&json).unwrap();
            assert_eq!(parsed, mode);
        }
    }

    #[test]
    fn default_mode_is_pass_all() {
        assert_eq!(UdpPolicyMode::default(), UdpPolicyMode::PassAll);
    }
}
