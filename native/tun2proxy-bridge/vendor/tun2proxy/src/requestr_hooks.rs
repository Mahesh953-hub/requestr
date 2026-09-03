//! Extension hooks for the requestr vendored fork.
//!
//! This module provides the callback/hook infrastructure that the bridge crate uses
//! to observe and influence tun2proxy's session lifecycle. Each hook is a trait-object
//! slot set once at startup and cleared at shutdown — the bridge owns the lifetime.
//!
//! **This file is a vendor patch.** Keep changes minimal and documented in vendor/README.md.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// Session events
// ---------------------------------------------------------------------------

/// Emitted by the run loop for session lifecycle and DNS observations.
#[derive(Debug, Clone)]
pub enum SessionEvent {
    /// A new TCP or UDP session was accepted from the tun.
    Start {
        session_id: u64,
        src: SocketAddr,
        dst: SocketAddr,
        /// Hostname resolved by virtual-DNS, if any.
        hostname: Option<String>,
        is_tcp: bool,
    },
    /// A session ended.
    End {
        session_id: u64,
        tx_bytes: u64,
        rx_bytes: u64,
        reason: String,
    },
    /// Virtual-DNS answered a query.
    DnsVirtual {
        name: String,
        address: String,
    },
    /// A UDP policy decision was applied.
    UdpPolicyApplied {
        session_id: u64,
        src: SocketAddr,
        dst: SocketAddr,
        verdict: UdpVerdict,
    },
}

/// Receives session events. Must not block.
pub trait SessionEventSink: Send + Sync {
    fn on_event(&self, event: SessionEvent);
}

static SESSION_EVENT_SINK: Mutex<Option<Arc<dyn SessionEventSink>>> = Mutex::new(None);

pub fn set_session_event_sink(sink: Arc<dyn SessionEventSink>) {
    if let Ok(mut g) = SESSION_EVENT_SINK.lock() {
        *g = Some(sink);
    }
}

pub fn clear_session_event_sink() {
    if let Ok(mut g) = SESSION_EVENT_SINK.lock() {
        *g = None;
    }
}

pub(crate) fn emit_session_event(event: SessionEvent) {
    let sink = match SESSION_EVENT_SINK.lock() {
        Ok(g) => g.clone(),
        Err(_) => return,
    };
    if let Some(sink) = sink {
        sink.on_event(event);
    }
}

// ---------------------------------------------------------------------------
// UDP policy
// ---------------------------------------------------------------------------

/// Per-datagram UDP policy decision.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpVerdict {
    /// Pass the datagram upstream untouched.
    Allow,
    /// Tee the datagram to the capture sink, then drop it.
    TeeAndDrop,
    /// Tee the datagram, then forward it upstream.
    TeeAndPass,
}

/// Decides per-datagram what to do with a UDP flow.
pub trait UdpPolicyHook: Send + Sync {
    /// Called for each new UDP session. The hook decides what happens.
    /// `dst_port` is the destination port of the datagram.
    fn evaluate(&self, src: SocketAddr, dst: SocketAddr) -> UdpVerdict;
}

static UDP_POLICY_HOOK: Mutex<Option<Arc<dyn UdpPolicyHook>>> = Mutex::new(None);

pub fn set_udp_policy_hook(hook: Arc<dyn UdpPolicyHook>) {
    if let Ok(mut g) = UDP_POLICY_HOOK.lock() {
        *g = Some(hook);
    }
}

pub fn clear_udp_policy_hook() {
    if let Ok(mut g) = UDP_POLICY_HOOK.lock() {
        *g = None;
    }
}

/// Evaluate UDP policy for a datagram. Returns `Allow` if no hook is set.
pub(crate) fn evaluate_udp_policy(src: SocketAddr, dst: SocketAddr) -> UdpVerdict {
    let hook = match UDP_POLICY_HOOK.lock() {
        Ok(g) => g.clone(),
        Err(_) => return UdpVerdict::Allow,
    };
    match hook {
        Some(h) => h.evaluate(src, dst),
        None => UdpVerdict::Allow,
    }
}

// ---------------------------------------------------------------------------
// Packet tee
// ---------------------------------------------------------------------------

/// Receives copies of raw IP packets for capture/decode.
pub trait PacketTeeSink: Send + Sync {
    /// Called with a raw IP-layer packet. `is_tx`: true = client→upstream.
    fn on_packet(&self, session_id: u64, data: &[u8], is_tx: bool);
}

static PACKET_TEE_SINK: Mutex<Option<Arc<dyn PacketTeeSink>>> = Mutex::new(None);

pub fn set_packet_tee_sink(sink: Arc<dyn PacketTeeSink>) {
    if let Ok(mut g) = PACKET_TEE_SINK.lock() {
        *g = Some(sink);
    }
}

pub fn clear_packet_tee_sink() {
    if let Ok(mut g) = PACKET_TEE_SINK.lock() {
        *g = None;
    }
}

/// Returns true if a PacketTeeSink is registered.
pub fn has_packet_tee_sink() -> bool {
    PACKET_TEE_SINK.lock().map(|g| g.is_some()).unwrap_or(false)
}

/// Tee a packet to the registered sink, if any. No-op when no sink is set.
pub(crate) fn tee_packet(session_id: u64, data: &[u8], is_tx: bool) {
    let sink = match PACKET_TEE_SINK.lock() {
        Ok(g) => g.clone(),
        Err(_) => return,
    };
    if let Some(sink) = sink {
        sink.on_packet(session_id, data, is_tx);
    }
}

// ---------------------------------------------------------------------------
// Socket protect
// ---------------------------------------------------------------------------

/// Called before every egress TCP connect or UDP bind so the VPN service can
/// `protect()` the fd and prevent routing loops.
pub trait SocketProtectHook: Send + Sync {
    /// Protect the given raw fd. Returns `true` on success, `false` to fail
    /// the connect attempt closed (preventing unprotected egress).
    fn protect_fd(&self, fd: i32) -> bool;
}

static SOCKET_PROTECT_HOOK: Mutex<Option<Arc<dyn SocketProtectHook>>> = Mutex::new(None);
/// When true, missing protect hook fails closed (VPN bridge mode).
/// When false, missing hook allows (non-VPN / optional mode).
static PROTECT_REQUIRED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_socket_protect_hook(hook: Arc<dyn SocketProtectHook>) {
    if let Ok(mut g) = SOCKET_PROTECT_HOOK.lock() {
        *g = Some(hook);
    }
}

pub fn clear_socket_protect_hook() {
    if let Ok(mut g) = SOCKET_PROTECT_HOOK.lock() {
        *g = None;
    }
}

/// Set whether socket protection is required (VPN bridge mode).
pub fn set_protect_required(required: bool) {
    PROTECT_REQUIRED.store(required, std::sync::atomic::Ordering::SeqCst);
}

/// Protect a raw fd before connect. Behavior when no hook is registered
/// depends on `protect_required`: if true, fails closed; if false, allows.
/// Poisoned mutex always fails closed.
pub(crate) fn protect_socket(fd: i32) -> bool {
    let hook = match SOCKET_PROTECT_HOOK.lock() {
        Ok(g) => g.clone(),
        Err(_) => return false, // poisoned mutex — fail closed
    };
    match hook {
        Some(h) => h.protect_fd(fd),
        None => !PROTECT_REQUIRED.load(std::sync::atomic::Ordering::SeqCst),
    }
}

// ---------------------------------------------------------------------------
// Clear all hooks (called on bridge shutdown)
// ---------------------------------------------------------------------------

pub fn clear_all_hooks() {
    clear_session_event_sink();
    clear_udp_policy_hook();
    clear_packet_tee_sink();
    clear_socket_protect_hook();
    set_protect_required(false);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    struct CountingSink(AtomicU32);
    impl SessionEventSink for CountingSink {
        fn on_event(&self, _event: SessionEvent) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    struct BlockQuicPolicy;
    impl UdpPolicyHook for BlockQuicPolicy {
        fn evaluate(&self, _src: SocketAddr, dst: SocketAddr) -> UdpVerdict {
            if dst.port() == 443 { UdpVerdict::TeeAndDrop } else { UdpVerdict::Allow }
        }
    }

    struct RejectAllProtect;
    impl SocketProtectHook for RejectAllProtect {
        fn protect_fd(&self, _fd: i32) -> bool { false }
    }

    struct CountingTeeSink(AtomicU32);
    impl PacketTeeSink for CountingTeeSink {
        fn on_packet(&self, _id: u64, _data: &[u8], _is_tx: bool) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn session_event_sink_receives_events() {
        let sink = Arc::new(CountingSink(AtomicU32::new(0)));
        set_session_event_sink(sink.clone());
        emit_session_event(SessionEvent::Start {
            session_id: 1, src: "10.0.0.1:1".parse().unwrap(),
            dst: "1.2.3.4:80".parse().unwrap(), hostname: None, is_tcp: true,
        });
        assert_eq!(sink.0.load(Ordering::SeqCst), 1);
        clear_session_event_sink();
    }

    #[test]
    fn no_session_sink_does_not_panic() {
        clear_session_event_sink();
        emit_session_event(SessionEvent::End {
            session_id: 0, tx_bytes: 0, rx_bytes: 0, reason: "test".into(),
        });
    }

    #[test]
    fn udp_policy_hook_blocks_443() {
        set_udp_policy_hook(Arc::new(BlockQuicPolicy));
        let src: SocketAddr = "10.0.0.1:1000".parse().unwrap();
        assert_eq!(evaluate_udp_policy(src, "8.8.8.8:443".parse().unwrap()), UdpVerdict::TeeAndDrop);
        assert_eq!(evaluate_udp_policy(src, "8.8.8.8:53".parse().unwrap()), UdpVerdict::Allow);
        clear_udp_policy_hook();
    }

    #[test]
    fn no_udp_hook_allows_all() {
        clear_udp_policy_hook();
        assert_eq!(evaluate_udp_policy("10.0.0.1:1".parse().unwrap(), "8.8.8.8:443".parse().unwrap()), UdpVerdict::Allow);
    }

    #[test]
    fn socket_protect_rejects_when_hook_says_false() {
        set_socket_protect_hook(Arc::new(RejectAllProtect));
        assert!(!protect_socket(42));
        clear_socket_protect_hook();
    }

    #[test]
    fn no_protect_hook_allows_when_not_required() {
        clear_socket_protect_hook();
        set_protect_required(false);
        assert!(protect_socket(42));
    }

    #[test]
    fn no_protect_hook_fails_when_required() {
        clear_socket_protect_hook();
        set_protect_required(true);
        assert!(!protect_socket(42));
        set_protect_required(false); // reset
    }

    #[test]
    fn packet_tee_delivers_to_sink() {
        let sink = Arc::new(CountingTeeSink(AtomicU32::new(0)));
        set_packet_tee_sink(sink.clone());
        tee_packet(1, &[0x45, 0x00], true);
        assert_eq!(sink.0.load(Ordering::SeqCst), 1);
        clear_packet_tee_sink();
    }

    #[test]
    fn clear_all_hooks_clears_everything() {
        set_session_event_sink(Arc::new(CountingSink(AtomicU32::new(0))));
        set_udp_policy_hook(Arc::new(BlockQuicPolicy));
        set_socket_protect_hook(Arc::new(RejectAllProtect));
        set_packet_tee_sink(Arc::new(CountingTeeSink(AtomicU32::new(0))));
        clear_all_hooks();
        // After clearing, all should use defaults
        assert!(protect_socket(1));
        assert_eq!(evaluate_udp_policy("10.0.0.1:1".parse().unwrap(), "8.8.8.8:443".parse().unwrap()), UdpVerdict::Allow);
    }
}
