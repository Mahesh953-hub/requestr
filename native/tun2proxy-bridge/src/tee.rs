//! Packet tee configuration — the actual tee is in vendor/tun2proxy/src/tee_device.rs.
//!
//! TeeDevice wraps the raw TUN AsyncRead+AsyncWrite device at the ipstack boundary,
//! copying every raw IP frame to the registered PacketTeeSink. VendorTeeSinkAdapter
//! (in events.rs) sends frame bytes through the bounded event channel to the JNI
//! consumer, which Base64-encodes and delivers to Kotlin.
