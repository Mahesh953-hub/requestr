//! TUN device wrapper that copies raw IP frames to the packet tee sink.
//!
//! Wraps an `AsyncRead + AsyncWrite` TUN device and tees every raw IP frame
//! to the registered `PacketTeeSink`. This is the correct tee point for
//! PCAPng: raw IP frames with headers intact, before ipstack reassembles
//! TCP streams or extracts UDP payloads.
//!
//! Direction convention (`is_tx` in `PacketTeeSink::on_packet`):
//! - Reading from TUN = client app sent a packet outbound → `is_tx = true`
//! - Writing to TUN = ipstack sends a response back to the client → `is_tx = false`

use crate::requestr_hooks;
use std::pin::Pin;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// Wrap a TUN device to tee raw IP frames.
/// When `enabled` is false, the wrapper is a transparent passthrough with no overhead.
/// When enabled, `tee_packet` is still a no-op if no `PacketTeeSink` is registered,
/// but the wrapper does execute the tee check on every read/write.
pub struct TeeDevice<D> {
    inner: D,
    enabled: bool,
}

impl<D> TeeDevice<D> {
    pub fn new(inner: D, enabled: bool) -> Self {
        TeeDevice { inner, enabled }
    }
}

impl<D: AsyncRead + Unpin> AsyncRead for TeeDevice<D> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let me = self.get_mut();
        let before = buf.filled().len();
        let result = Pin::new(&mut me.inner).poll_read(cx, buf);
        if me.enabled {
            if let Poll::Ready(Ok(())) = &result {
                let after = buf.filled().len();
                if after > before {
                    let data = &buf.filled()[before..after];
                    // Client app sent data out → outbound (is_tx=true)
                    requestr_hooks::tee_packet(0, data, true);
                }
            }
        }
        result
    }
}

impl<D: AsyncWrite + Unpin> AsyncWrite for TeeDevice<D> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let me = self.get_mut();
        // Call inner write first — only tee the bytes actually written
        let result = Pin::new(&mut me.inner).poll_write(cx, buf);
        if me.enabled {
            if let Poll::Ready(Ok(n)) = &result {
                if *n > 0 {
                    // Response going back to client → inbound (is_tx=false)
                    requestr_hooks::tee_packet(0, &buf[..*n], false);
                }
            }
        }
        result
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::requestr_hooks::{self, PacketTeeSink};
    use std::io::Cursor;
    use std::sync::{Arc, Mutex as StdMutex};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // Serialize all tee_device tests since they share the global PACKET_TEE_SINK.
    static TEST_LOCK: StdMutex<()> = StdMutex::new(());

    struct CollectorSink {
        packets: StdMutex<Vec<(u64, Vec<u8>, bool)>>,
    }
    impl CollectorSink {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                packets: StdMutex::new(Vec::new()),
            })
        }
        fn take(&self) -> Vec<(u64, Vec<u8>, bool)> {
            self.packets.lock().unwrap().drain(..).collect()
        }
    }
    impl PacketTeeSink for CollectorSink {
        fn on_packet(&self, id: u64, data: &[u8], is_tx: bool) {
            self.packets
                .lock()
                .unwrap()
                .push((id, data.to_vec(), is_tx));
        }
    }

    fn install_collector() -> Arc<CollectorSink> {
        let c = CollectorSink::new();
        requestr_hooks::set_packet_tee_sink(c.clone());
        c
    }

    #[tokio::test]
    async fn read_tees_exact_bytes_outbound() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let data = vec![0x45u8, 0x00, 0x00, 0x28, 0xDE, 0xAD];
        let inner = Cursor::new(data.clone());
        let mut tee = TeeDevice::new(inner, true);

        let mut buf = vec![0u8; 64];
        let n = tee.read(&mut buf).await.unwrap();
        assert_eq!(n, 6);
        assert_eq!(&buf[..n], &data);

        let teed = col.take();
        assert_eq!(teed.len(), 1);
        assert_eq!(teed[0].1, data);
        assert!(teed[0].2); // is_tx=true (read from TUN = outbound)
        requestr_hooks::clear_packet_tee_sink();
    }

    #[tokio::test]
    async fn write_tees_only_written_bytes_inbound() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let inner = Cursor::new(vec![0u8; 64]); // pre-sized buffer for writes
        let mut tee = TeeDevice::new(inner, true);

        let data = vec![0x60u8, 0x00, 0x00, 0x00, 0xBE, 0xEF];
        let n = tee.write(&data).await.unwrap();
        assert_eq!(n, 6);

        let teed = col.take();
        assert_eq!(teed.len(), 1);
        assert_eq!(teed[0].1, data);
        assert!(!teed[0].2); // is_tx=false (write to TUN = inbound)
        requestr_hooks::clear_packet_tee_sink();
    }

    #[tokio::test]
    async fn disabled_tee_does_not_tee() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let inner = Cursor::new(vec![0x45, 0x00]);
        let mut tee = TeeDevice::new(inner, false);

        let mut buf = vec![0u8; 16];
        let _ = tee.read(&mut buf).await.unwrap();

        assert!(col.take().is_empty());
        requestr_hooks::clear_packet_tee_sink();
    }

    #[tokio::test]
    async fn read_eof_does_not_tee() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let inner = Cursor::new(Vec::new());
        let mut tee = TeeDevice::new(inner, true);

        let mut buf = vec![0u8; 16];
        let n = tee.read(&mut buf).await.unwrap();
        assert_eq!(n, 0);
        assert!(col.take().is_empty());
        requestr_hooks::clear_packet_tee_sink();
    }

    /// Mock writer that accepts only 3 bytes per poll_write (partial write).
    struct PartialWriter;
    impl AsyncWrite for PartialWriter {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            let n = buf.len().min(3);
            Poll::Ready(Ok(n))
        }
        fn poll_flush(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn partial_write_tees_only_accepted_bytes() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let mut tee = TeeDevice::new(PartialWriter, true);

        let data = vec![0xAA, 0xBB, 0xCC, 0xDD, 0xEE];
        let n = tee.write(&data).await.unwrap();
        assert_eq!(n, 3);

        let teed = col.take();
        assert_eq!(teed.len(), 1);
        assert_eq!(teed[0].1, vec![0xAA, 0xBB, 0xCC]);
        requestr_hooks::clear_packet_tee_sink();
    }

    /// Mock writer that always errors.
    struct ErrorWriter;
    impl AsyncWrite for ErrorWriter {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "test",
            )))
        }
        fn poll_flush(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn error_write_does_not_tee() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let mut tee = TeeDevice::new(ErrorWriter, true);

        let result = tee.write(&[0xAA, 0xBB]).await;
        assert!(result.is_err());
        assert!(col.take().is_empty());
        requestr_hooks::clear_packet_tee_sink();
    }

    /// Mock writer whose first `poll_write` call returns `Poll::Pending` (after arranging a
    /// wakeup so the executor polls again), and every subsequent call accepts the full buffer.
    /// Used to prove `TeeDevice` only tees bytes once the underlying write is actually
    /// accepted (`Poll::Ready(Ok(n))`), never on a `Poll::Pending` poll -- a `Pending` poll
    /// must produce zero tee callbacks even though `poll_write` was invoked.
    struct PendingThenAcceptWriter {
        calls: StdMutex<u32>,
    }
    impl PendingThenAcceptWriter {
        fn new() -> Self {
            Self {
                calls: StdMutex::new(0),
            }
        }
    }
    impl AsyncWrite for PendingThenAcceptWriter {
        fn poll_write(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            let mut calls = self.calls.lock().unwrap();
            *calls += 1;
            if *calls == 1 {
                cx.waker().wake_by_ref();
                Poll::Pending
            } else {
                Poll::Ready(Ok(buf.len()))
            }
        }
        fn poll_flush(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn pending_write_does_not_tee_until_accepted_retry() {
        let _lock = TEST_LOCK.lock().unwrap();
        let col = install_collector();
        let mut tee = TeeDevice::new(PendingThenAcceptWriter::new(), true);

        let data = vec![0x11, 0x22, 0x33, 0x44];
        // `write().await` drives poll_write to completion, retrying across the Pending poll.
        let n = tee.write(&data).await.unwrap();
        assert_eq!(n, data.len());

        // Exactly one tee callback -- proving the Pending poll (call #1) produced zero tees
        // and only the accepted retry (call #2) teed the bytes.
        let teed = col.take();
        assert_eq!(teed.len(), 1);
        assert_eq!(teed[0].1, data);
        assert!(!teed[0].2); // is_tx=false (write to TUN = inbound)
        requestr_hooks::clear_packet_tee_sink();
    }
}
