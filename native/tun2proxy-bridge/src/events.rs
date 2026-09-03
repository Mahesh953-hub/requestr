//! Bridge event protocol — bounded channel with consumer-side serialization.
//!
//! Producers (vendor hooks on packet/session paths) build typed `BridgeEvent` values
//! and `try_send` them into a bounded `sync_channel`. If full, the event is dropped
//! and the counter incremented. Producers never serialize or allocate base64 — that
//! happens on the dedicated consumer thread, isolating JNI + serde latency from the
//! packet path.

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use tun2proxy::requestr_hooks;

/// Channel capacity. Events beyond this are dropped with a metric.
const EVENT_CHANNEL_CAPACITY: usize = 256;
/// Maximum raw frame size we copy for tee events (~MTU). Larger frames are truncated.
const MAX_TEE_FRAME_BYTES: usize = 9000;

// ---- Serializable JSON types ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UdpVerdict {
    Allow,
    TeeAndDrop,
    TeeAndPass,
}

impl From<requestr_hooks::UdpVerdict> for UdpVerdict {
    fn from(v: requestr_hooks::UdpVerdict) -> Self {
        match v {
            requestr_hooks::UdpVerdict::Allow => UdpVerdict::Allow,
            requestr_hooks::UdpVerdict::TeeAndDrop => UdpVerdict::TeeAndDrop,
            requestr_hooks::UdpVerdict::TeeAndPass => UdpVerdict::TeeAndPass,
        }
    }
}

/// JSON-serializable event type.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BridgeEvent {
    SessionStart {
        session_id: u64,
        src: String,
        dst: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        hostname: Option<String>,
        is_tcp: bool,
    },
    SessionEnd {
        session_id: u64,
        tx_bytes: u64,
        rx_bytes: u64,
        reason: String,
    },
    Dns {
        name: String,
        address: String,
        is_virtual: bool,
    },
    UdpPolicy {
        session_id: u64,
        src: String,
        dst: String,
        verdict: UdpVerdict,
    },
    PacketTee {
        session_id: u64,
        #[serde(skip_serializing_if = "Option::is_none")]
        data_base64: Option<String>,
        packet_len: usize,
        is_outbound: bool,
    },
    Error {
        #[serde(skip_serializing_if = "Option::is_none")]
        session_id: Option<u64>,
        code: String,
        message: String,
    },
}

// ---- Typed channel payload (no serialization on producer) ----

/// Owned event data sent through the bounded channel. For PacketTee, raw bytes are
/// owned here; Base64 encoding happens in the consumer thread.
#[derive(Debug, Clone)]
pub(crate) enum ChannelEvent {
    SessionStart {
        session_id: u64,
        src: String,
        dst: String,
        hostname: Option<String>,
        is_tcp: bool,
    },
    SessionEnd {
        session_id: u64,
        tx_bytes: u64,
        rx_bytes: u64,
        reason: String,
    },
    Dns {
        name: String,
        address: String,
        is_virtual: bool,
    },
    UdpPolicy {
        session_id: u64,
        src: String,
        dst: String,
        verdict: UdpVerdict,
    },
    PacketTee {
        session_id: u64,
        data: Vec<u8>,
        is_outbound: bool,
    },
    Error {
        session_id: Option<u64>,
        code: String,
        message: String,
    },
}

impl ChannelEvent {
    /// Convert to JSON BridgeEvent and serialize. Done on the consumer thread only.
    fn to_json(&self) -> Option<String> {
        use base64::Engine;
        let event = match self {
            ChannelEvent::SessionStart {
                session_id,
                src,
                dst,
                hostname,
                is_tcp,
            } => BridgeEvent::SessionStart {
                session_id: *session_id,
                src: src.clone(),
                dst: dst.clone(),
                hostname: hostname.clone(),
                is_tcp: *is_tcp,
            },
            ChannelEvent::SessionEnd {
                session_id,
                tx_bytes,
                rx_bytes,
                reason,
            } => BridgeEvent::SessionEnd {
                session_id: *session_id,
                tx_bytes: *tx_bytes,
                rx_bytes: *rx_bytes,
                reason: reason.clone(),
            },
            ChannelEvent::Dns {
                name,
                address,
                is_virtual,
            } => BridgeEvent::Dns {
                name: name.clone(),
                address: address.clone(),
                is_virtual: *is_virtual,
            },
            ChannelEvent::UdpPolicy {
                session_id,
                src,
                dst,
                verdict,
            } => BridgeEvent::UdpPolicy {
                session_id: *session_id,
                src: src.clone(),
                dst: dst.clone(),
                verdict: *verdict,
            },
            ChannelEvent::PacketTee {
                session_id,
                data,
                is_outbound,
            } => BridgeEvent::PacketTee {
                session_id: *session_id,
                data_base64: Some(base64::engine::general_purpose::STANDARD.encode(data)),
                packet_len: data.len(),
                is_outbound: *is_outbound,
            },
            ChannelEvent::Error {
                session_id,
                code,
                message,
            } => BridgeEvent::Error {
                session_id: *session_id,
                code: code.clone(),
                message: message.clone(),
            },
        };
        serde_json::to_string(&event).ok()
    }
}

// ---- Sink + channel infrastructure ----

pub trait EventSink: Send + Sync {
    fn on_event(&self, json: &str);
}

static EVENT_SINK: Mutex<Option<Arc<dyn EventSink>>> = Mutex::new(None);
static EVENTS_PRODUCED: AtomicU64 = AtomicU64::new(0);
static EVENTS_DROPPED: AtomicU64 = AtomicU64::new(0);
static CONSUMER_RUNNING: AtomicBool = AtomicBool::new(false);

struct ConsumerState {
    sender: std::sync::mpsc::SyncSender<ChannelEvent>,
    handle: JoinHandle<()>,
}
static CONSUMER: Mutex<Option<ConsumerState>> = Mutex::new(None);

pub fn set_event_sink(sink: Arc<dyn EventSink>) {
    if let Ok(mut g) = EVENT_SINK.lock() {
        *g = Some(sink);
    }
}

pub fn clear_event_sink() {
    if let Ok(mut g) = EVENT_SINK.lock() {
        *g = None;
    }
}

pub fn events_produced() -> u64 {
    EVENTS_PRODUCED.load(Ordering::Relaxed)
}
pub fn events_dropped() -> u64 {
    EVENTS_DROPPED.load(Ordering::Relaxed)
}
pub fn reset_metrics() {
    EVENTS_PRODUCED.store(0, Ordering::Relaxed);
    EVENTS_DROPPED.store(0, Ordering::Relaxed);
}

/// Start the event consumer thread. Idempotent: stops any existing consumer first.
pub fn start_event_consumer() {
    stop_event_consumer(); // idempotent: clean up previous

    let (tx, rx) = std::sync::mpsc::sync_channel::<ChannelEvent>(EVENT_CHANNEL_CAPACITY);
    CONSUMER_RUNNING.store(true, Ordering::SeqCst);

    let handle = std::thread::Builder::new()
        .name("requestr-event-consumer".into())
        .spawn(move || {
            while CONSUMER_RUNNING.load(Ordering::SeqCst) {
                match rx.recv_timeout(std::time::Duration::from_millis(100)) {
                    Ok(event) => {
                        if let Some(json) = event.to_json() {
                            let sink = match EVENT_SINK.lock() {
                                Ok(g) => g.clone(),
                                Err(_) => continue,
                            };
                            if let Some(sink) = sink {
                                sink.on_event(&json);
                            }
                        }
                    }
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
        })
        .expect("failed to spawn event consumer thread");

    if let Ok(mut g) = CONSUMER.lock() {
        *g = Some(ConsumerState { sender: tx, handle });
    }
}

/// Stop the event consumer thread with bounded 200ms timeout. Idempotent.
/// Under the production nonblocking JNI contract the consumer always joins
/// within timeout. On contract violation, the thread is detached.
pub fn stop_event_consumer() {
    CONSUMER_RUNNING.store(false, Ordering::SeqCst);
    let state = if let Ok(mut g) = CONSUMER.lock() {
        g.take()
    } else {
        None
    };
    if let Some(state) = state {
        drop(state.sender); // unblock recv
                            // Bounded join: poll is_finished with deadline
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(200);
        loop {
            if state.handle.is_finished() {
                let _ = state.handle.join();
                return;
            }
            if std::time::Instant::now() >= deadline {
                log::warn!("Event consumer did not join within 200ms; detaching");
                return; // detach — thread exits on next recv_timeout
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
    }
}

/// Non-blocking event emission. Copies typed data; never serializes.
fn try_send(event: ChannelEvent) {
    EVENTS_PRODUCED.fetch_add(1, Ordering::Relaxed);
    let sender = match CONSUMER.lock() {
        Ok(g) => g.as_ref().map(|s| s.sender.clone()),
        Err(_) => {
            EVENTS_DROPPED.fetch_add(1, Ordering::Relaxed);
            return;
        }
    };
    if let Some(sender) = sender {
        if sender.try_send(event).is_err() {
            EVENTS_DROPPED.fetch_add(1, Ordering::Relaxed);
        }
    }
}

/// Emit a typed event through the bounded channel. All BridgeEvent variants supported.
pub fn emit_event(event: &BridgeEvent) {
    let channel_event = match event {
        BridgeEvent::SessionStart {
            session_id,
            src,
            dst,
            hostname,
            is_tcp,
        } => ChannelEvent::SessionStart {
            session_id: *session_id,
            src: src.clone(),
            dst: dst.clone(),
            hostname: hostname.clone(),
            is_tcp: *is_tcp,
        },
        BridgeEvent::SessionEnd {
            session_id,
            tx_bytes,
            rx_bytes,
            reason,
        } => ChannelEvent::SessionEnd {
            session_id: *session_id,
            tx_bytes: *tx_bytes,
            rx_bytes: *rx_bytes,
            reason: reason.clone(),
        },
        BridgeEvent::Dns {
            name,
            address,
            is_virtual,
        } => ChannelEvent::Dns {
            name: name.clone(),
            address: address.clone(),
            is_virtual: *is_virtual,
        },
        BridgeEvent::UdpPolicy {
            session_id,
            src,
            dst,
            verdict,
        } => ChannelEvent::UdpPolicy {
            session_id: *session_id,
            src: src.clone(),
            dst: dst.clone(),
            verdict: *verdict,
        },
        BridgeEvent::PacketTee {
            session_id,
            data_base64,
            packet_len: _,
            is_outbound,
        } => {
            use base64::Engine;
            let data = data_base64
                .as_ref()
                .and_then(|b| base64::engine::general_purpose::STANDARD.decode(b).ok())
                .unwrap_or_default();
            ChannelEvent::PacketTee {
                session_id: *session_id,
                data,
                is_outbound: *is_outbound,
            }
        }
        BridgeEvent::Error {
            session_id,
            code,
            message,
        } => ChannelEvent::Error {
            session_id: *session_id,
            code: code.clone(),
            message: message.clone(),
        },
    };
    try_send(channel_event);
}

// ---- Vendor hook adapters ----

pub(crate) struct VendorEventAdapter;

impl requestr_hooks::SessionEventSink for VendorEventAdapter {
    fn on_event(&self, event: requestr_hooks::SessionEvent) {
        let ce = match event {
            requestr_hooks::SessionEvent::Start {
                session_id,
                src,
                dst,
                hostname,
                is_tcp,
            } => ChannelEvent::SessionStart {
                session_id,
                src: src.to_string(),
                dst: dst.to_string(),
                hostname,
                is_tcp,
            },
            requestr_hooks::SessionEvent::End {
                session_id,
                tx_bytes,
                rx_bytes,
                reason,
            } => ChannelEvent::SessionEnd {
                session_id,
                tx_bytes,
                rx_bytes,
                reason,
            },
            requestr_hooks::SessionEvent::DnsVirtual { name, address } => ChannelEvent::Dns {
                name,
                address,
                is_virtual: true,
            },
            requestr_hooks::SessionEvent::UdpPolicyApplied {
                session_id,
                src,
                dst,
                verdict,
            } => ChannelEvent::UdpPolicy {
                session_id,
                src: src.to_string(),
                dst: dst.to_string(),
                verdict: verdict.into(),
            },
        };
        try_send(ce);
    }
}

/// Receives raw IP frames from TeeDevice and sends through the bounded channel.
/// The consumer thread does Base64 encoding — this adapter only copies bytes up to MTU.
pub(crate) struct VendorTeeSinkAdapter;

impl requestr_hooks::PacketTeeSink for VendorTeeSinkAdapter {
    fn on_packet(&self, session_id: u64, data: &[u8], is_tx: bool) {
        let len = data.len().min(MAX_TEE_FRAME_BYTES);
        try_send(ChannelEvent::PacketTee {
            session_id,
            data: data[..len].to_vec(),
            is_outbound: is_tx,
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct CollectingSink(Mutex<Vec<String>>);
    impl CollectingSink {
        fn new() -> Arc<Self> {
            Arc::new(CollectingSink(Mutex::new(Vec::new())))
        }
        fn drain(&self) -> Vec<String> {
            self.0.lock().unwrap().drain(..).collect()
        }
    }
    impl EventSink for CollectingSink {
        fn on_event(&self, json: &str) {
            self.0.lock().unwrap().push(json.to_string());
        }
    }

    #[test]
    fn bounded_channel_delivers_events() {
        let _g = crate::state::lock_test_state();
        reset_metrics();
        let sink = CollectingSink::new();
        set_event_sink(sink.clone());
        start_event_consumer();

        try_send(ChannelEvent::SessionStart {
            session_id: 1,
            src: "10.0.0.1:1".into(),
            dst: "1.2.3.4:80".into(),
            hostname: None,
            is_tcp: true,
        });

        std::thread::sleep(std::time::Duration::from_millis(300));
        let collected = sink.drain();
        assert_eq!(collected.len(), 1);
        assert!(collected[0].contains(r#""type":"session_start""#));
        assert_eq!(events_produced(), 1);
        assert_eq!(events_dropped(), 0);

        stop_event_consumer();
        clear_event_sink();
    }

    #[test]
    fn bounded_channel_drops_on_overflow() {
        let _g = crate::state::lock_test_state();
        reset_metrics();

        // Start consumer but use a sink that sleeps to block it
        struct SlowSink;
        impl EventSink for SlowSink {
            fn on_event(&self, _: &str) {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
        }
        set_event_sink(Arc::new(SlowSink));
        start_event_consumer();

        // Flood: 300 events should overflow channel capacity of 256
        for i in 0..300u64 {
            try_send(ChannelEvent::Dns {
                name: format!("t{i}.local"),
                address: "1.2.3.4".into(),
                is_virtual: true,
            });
        }

        assert!(events_produced() > 0);
        assert!(events_dropped() > 0, "Expected overflow drops");

        stop_event_consumer();
        clear_event_sink();
    }

    #[test]
    fn start_stop_idempotent() {
        let _g = crate::state::lock_test_state();
        reset_metrics();
        let sink = CollectingSink::new();
        set_event_sink(sink.clone());

        start_event_consumer();
        start_event_consumer(); // should stop previous, start new
        try_send(ChannelEvent::Dns {
            name: "a.local".into(),
            address: "1.1.1.1".into(),
            is_virtual: false,
        });
        std::thread::sleep(std::time::Duration::from_millis(200));
        assert_eq!(sink.drain().len(), 1);

        stop_event_consumer();
        stop_event_consumer(); // idempotent
        clear_event_sink();
    }

    #[test]
    fn emit_safe_when_no_consumer() {
        let _g = crate::state::lock_test_state();
        stop_event_consumer();
        try_send(ChannelEvent::Error {
            session_id: None,
            code: "test".into(),
            message: "test".into(),
        });
        // must not panic
    }

    #[test]
    fn packet_tee_base64_round_trips() {
        let raw = vec![0x45u8, 0x00, 0x00, 0x28, 0xAB, 0xCD, 0xEF, 0x01];
        let ce = ChannelEvent::PacketTee {
            session_id: 0,
            data: raw.clone(),
            is_outbound: true,
        };
        let json = ce.to_json().unwrap();
        let parsed: BridgeEvent = serde_json::from_str(&json).unwrap();
        if let BridgeEvent::PacketTee {
            data_base64,
            packet_len,
            is_outbound,
            ..
        } = parsed
        {
            use base64::Engine;
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(data_base64.unwrap())
                .unwrap();
            assert_eq!(decoded, raw);
            assert_eq!(packet_len, 8);
            assert!(is_outbound);
        } else {
            panic!("wrong variant");
        }
    }

    #[test]
    fn session_start_json_round_trips() {
        let event = BridgeEvent::SessionStart {
            session_id: 42,
            src: "10.0.0.1:12345".into(),
            dst: "93.184.216.34:443".into(),
            hostname: Some("example.com".into()),
            is_tcp: true,
        };
        let json = serde_json::to_string(&event).unwrap();
        let parsed: BridgeEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, event);
    }

    #[test]
    fn error_event_omits_none_session_id() {
        let event = BridgeEvent::Error {
            session_id: None,
            code: "test".into(),
            message: "msg".into(),
        };
        let json = serde_json::to_string(&event).unwrap();
        assert!(!json.contains("session_id"));
    }

    #[test]
    fn all_udp_verdicts_snake_case() {
        for (v, expected) in [
            (UdpVerdict::Allow, "\"allow\""),
            (UdpVerdict::TeeAndDrop, "\"tee_and_drop\""),
            (UdpVerdict::TeeAndPass, "\"tee_and_pass\""),
        ] {
            assert_eq!(serde_json::to_string(&v).unwrap(), expected);
        }
    }
}
