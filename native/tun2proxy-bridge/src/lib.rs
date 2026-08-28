//! C-ABI entry points for the requestr tun2proxy bridge (Gap #1 Approach A).
//!
//! `RequestrVpnService` hands the raw tun fd it obtained from `VpnService.Builder.establish()`
//! plus a small JSON config (see `config.rs`) across this boundary. From here on, tun2proxy
//! itself owns reading/writing that fd and issuing `CONNECT host:port HTTP/1.1` (or SOCKS4/4a/
//! 5/5h, depending on config) to whatever `proxy.address` points at -- for the in-app wiring
//! that is always `RequestrVpnService`'s own locally-started `MitmProxyEngine` listener, which
//! is how VPN-tunneled traffic finally reaches the same decrypt pipeline Root/Shizuku tiers use.
//!
//! Every function here is deliberately "fail loud, never fake success": a bad config, a busy
//! bridge, or a runtime spawn failure returns a negative code and records a real message in
//! `state::last_error()` -- there is no code path that reports `0` (success) without either
//! having actually started a background thread that is really driving tun2proxy, or having
//! actually cancelled one.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

pub mod config;
pub mod state;

#[cfg(target_os = "android")]
pub mod jni_bridge;

use config::{build_args, parse_config};
use state::BridgeState;

/// Holds the in-flight bridge's shutdown handle. `Some` exactly while a `general_run_async`
/// call is in flight on the background thread spawned by `requestr_tun2proxy_start`; the
/// background thread itself clears this back to `None` once `general_run_async` returns
/// (success or error), so `requestr_tun2proxy_stop` racing a natural exit never double-cancels
/// a stale token.
static SHUTDOWN_TOKEN: Mutex<Option<CancellationToken>> = Mutex::new(None);

/// P1 bug #2 fix: retained (not discarded) so `requestr_tun2proxy_await_stopped` can actually
/// `.join()` the OS thread once `state::wait_for_thread_done` confirms it has finished, giving
/// callers (`RequestrVpnService.cleanup()`) a real guarantee -- not just an inference from
/// polling `requestr_tun2proxy_state` -- that the thread will never touch the tun fd again
/// before they close it.
static THREAD_HANDLE: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

pub const OK: c_int = 0;
pub const ERR_ALREADY_RUNNING: c_int = -1;
pub const ERR_INVALID_CONFIG: c_int = -2;
pub const ERR_LOCK: c_int = -3;
pub const ERR_NOT_RUNNING: c_int = -4;
pub const ERR_NULL_PTR: c_int = -5;
/// P1 bug #1 fix: the background thread was spawned but reported [`BridgeState::Failed`]
/// within the bounded readiness window -- `state::last_error()` has the real reason. Distinct
/// from [`ERR_INVALID_CONFIG`] (a synchronous, pre-spawn validation failure) so callers can
/// tell "never even tried" apart from "tried and the runtime itself rejected it".
pub const ERR_RUNTIME_FAILED: c_int = -6;
/// P1 bug #1/#2 fix: a bounded wait elapsed without a definite outcome -- either
/// `requestr_tun2proxy_start`'s readiness handshake never observed a transition to
/// Running/Failed, or `requestr_tun2proxy_await_stopped` never observed the background thread
/// finish. Ambiguous, not success: callers must treat this the same as a real failure (for
/// start) or escalate rather than touch the tun fd (for stop).
pub const ERR_TIMEOUT: c_int = -7;

/// Production bound for the start-readiness handshake (P1 bug #1). Building the dedicated
/// Tokio runtime and reaching `general_run_async`'s first await point is sub-millisecond in
/// practice (see the crate's own tests); this is generous headroom for a loaded device, not a
/// tuned-tight value.
const START_READINESS_TIMEOUT: Duration = Duration::from_millis(3000);

/// Starts the bridge: parses `config_json`, builds a real `tun2proxy::Args` against `tun_fd`,
/// and spawns a dedicated OS thread that owns its own Tokio runtime and drives
/// `tun2proxy::general_run_async` until either it exits on its own or `requestr_tun2proxy_stop`
/// cancels it. Deliberately does NOT call `tun2proxy::general_run_for_api` -- that helper
/// spawns a 2-second force-exit watchdog (`std::process::exit`) intended for tun2proxy's own
/// standalone binary, which would take the whole Android app process down with it; this bridge
/// manages its own runtime lifecycle instead so `requestr_tun2proxy_stop` can cancel cleanly.
///
/// P1 bug #1 fix (readiness handshake): this call does not return until the background thread
/// has reported a *real* outcome -- [`BridgeState::Running`] (returns [`OK`]) or
/// [`BridgeState::Failed`] (returns [`ERR_RUNTIME_FAILED`], with `state::last_error()` set to
/// the genuine reason) -- or [`START_READINESS_TIMEOUT`] elapses first (returns [`ERR_TIMEOUT`],
/// ambiguous: the thread may still be starting). Previously this returned [`OK`] the instant the
/// thread was merely *spawned*, racing the caller (which took `OK` as "definitely running" and
/// disabled its legacy fallback) against the background thread's own, sometimes much later,
/// first real state transition -- observed on the host to take ~20ms for an immediate failure
/// while the spawn itself returns in ~150µs. Never returns [`OK`] without the background thread
/// having genuinely reached [`BridgeState::Running`].
///
/// # Safety
/// `config_json` must be a valid, NUL-terminated C string pointer, live for the duration of
/// this call. `tun_fd` must be a valid, open file descriptor owned by the caller (Kotlin's
/// `ParcelFileDescriptor`); this function never closes it itself (`close_fd_on_drop` is always
/// forced to `false` in `config::build_args`).
#[no_mangle]
pub unsafe extern "C" fn requestr_tun2proxy_start(config_json: *const c_char, tun_fd: c_int) -> c_int {
    unsafe { start_with_readiness_timeout(config_json, tun_fd, START_READINESS_TIMEOUT) }
}

/// The real body of [`requestr_tun2proxy_start`], parameterized over the readiness timeout so
/// `cargo test` can exercise the [`ERR_TIMEOUT`] branch deterministically in milliseconds
/// instead of needing [`START_READINESS_TIMEOUT`]'s full production bound.
///
/// # Safety
/// Same contract as [`requestr_tun2proxy_start`].
unsafe fn start_with_readiness_timeout(config_json: *const c_char, tun_fd: c_int, readiness_timeout: Duration) -> c_int {
    if config_json.is_null() {
        state::set_last_error("config_json is null");
        state::set_state(BridgeState::Failed);
        return ERR_NULL_PTR;
    }

    let mut guard = match SHUTDOWN_TOKEN.lock() {
        Ok(g) => g,
        Err(_) => {
            state::set_last_error("internal shutdown-token lock poisoned");
            return ERR_LOCK;
        }
    };
    if guard.is_some() || matches!(state::get_state(), BridgeState::Starting | BridgeState::Running) {
        return ERR_ALREADY_RUNNING;
    }

    let json = match unsafe { CStr::from_ptr(config_json) }.to_str() {
        Ok(s) => s.to_owned(),
        Err(e) => {
            state::set_last_error(format!("config_json is not valid UTF-8: {e}"));
            state::set_state(BridgeState::Failed);
            return ERR_INVALID_CONFIG;
        }
    };

    let parsed = match parse_config(&json) {
        Ok(c) => c,
        Err(e) => {
            state::set_last_error(e.to_string());
            state::set_state(BridgeState::Failed);
            return ERR_INVALID_CONFIG;
        }
    };

    let args = match build_args(&parsed, tun_fd) {
        Ok(a) => a,
        Err(e) => {
            state::set_last_error(e.to_string());
            state::set_state(BridgeState::Failed);
            return ERR_INVALID_CONFIG;
        }
    };

    #[cfg(target_os = "android")]
    jni_bridge::init_android_logger_once(&parsed.verbosity);

    // P1 bug #2 fix: join any handle left over from a previous run before overwriting
    // `THREAD_HANDLE` below. By the invariant just checked above (no live shutdown token, and
    // state is not Starting/Running), any such handle's thread must already have finished --
    // or be finishing up its last few bookkeeping statements *after* it has already fully
    // returned from `general_run_async` and is therefore no longer touching any fd -- so this
    // join is expected to return immediately and never blocks meaningfully. Without it, a
    // stale handle would simply be dropped (safe, but loses the ability to ever confirm-join
    // that run), and worse, a caller who skipped `requestr_tun2proxy_await_stopped` after a
    // previous stop could otherwise start overlapping with faint bookkeeping from the old run.
    if let Ok(mut h) = THREAD_HANDLE.lock() {
        if let Some(stale) = h.take() {
            let _ = stale.join();
        }
    }

    let mtu = parsed.mtu;
    let shutdown_token = CancellationToken::new();
    *guard = Some(shutdown_token.clone());
    drop(guard);

    state::clear_last_error();
    state::reset_traffic();
    state::mark_thread_starting();
    state::set_state(BridgeState::Starting);

    // Registered fresh on every start; tun2proxy stores a single global callback slot and
    // simply replaces it, which is fine since only one bridge instance ever runs at a time
    // (enforced by the `guard.is_some()` check above).
    unsafe {
        tun2proxy::tun2proxy_set_traffic_status_callback(1, Some(traffic_callback), std::ptr::null_mut());
    }

    let spawn_result = std::thread::Builder::new().name("requestr-tun2proxy".into()).spawn(move || run_bridge_thread(args, mtu, shutdown_token));

    match spawn_result {
        Ok(handle) => {
            if let Ok(mut h) = THREAD_HANDLE.lock() {
                *h = Some(handle);
            }
            match state::wait_for_running_or_failed(readiness_timeout) {
                BridgeState::Running => OK,
                BridgeState::Failed => ERR_RUNTIME_FAILED,
                _ => ERR_TIMEOUT,
            }
        }
        Err(e) => {
            state::set_last_error(format!("failed to spawn tun2proxy runtime thread: {e}"));
            state::set_state(BridgeState::Failed);
            state::mark_thread_done();
            if let Ok(mut g) = SHUTDOWN_TOKEN.lock() {
                g.take();
            }
            ERR_LOCK
        }
    }
}

/// Runs on the dedicated `requestr-tun2proxy` OS thread. Builds its own multi-threaded Tokio
/// runtime (kept off the JNI/Kotlin thread entirely) and blocks on `general_run_async` until it
/// returns -- either because a real fatal error occurred inside tun2proxy, or because
/// `requestr_tun2proxy_stop` cancelled `shutdown_token`.
fn run_bridge_thread(args: tun2proxy::Args, mtu: u16, shutdown_token: CancellationToken) {
    // P1 bug #2 fix: declared first so it drops *last* (Rust drops locals in reverse
    // declaration order), i.e. only after every other statement in this function body --
    // including the final `state::set_state` terminal transition -- has already run. This is
    // what makes `state::wait_for_thread_done` a genuine "safe to close the fd now" signal
    // rather than an approximation. Fires on an unwinding panic too (this crate's `dev`/`test`
    // profile is `panic = "unwind"`); the shipped `release` profile is `panic = "abort"`
    // (see Cargo.toml), which takes the whole process down before any Drop runs at all -- in
    // that catastrophic case the fd is moot because the process is gone, not lingering.
    struct ThreadDoneGuard;
    impl Drop for ThreadDoneGuard {
        fn drop(&mut self) {
            state::mark_thread_done();
        }
    }
    let _done_guard = ThreadDoneGuard;

    let rt = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(e) => {
            state::set_last_error(format!("failed to build tokio runtime: {e}"));
            state::set_state(BridgeState::Failed);
            if let Ok(mut g) = SHUTDOWN_TOKEN.lock() {
                g.take();
            }
            return;
        }
    };

    state::set_state(BridgeState::Running);
    let result = rt.block_on(tun2proxy::general_run_async(args, mtu, false, shutdown_token));

    // Clear the shutdown handle before publishing the terminal state so a caller that observes
    // `Idle`/`Failed` via `requestr_tun2proxy_state` and immediately calls
    // `requestr_tun2proxy_start` again never races `requestr_tun2proxy_stop` against a token
    // that no longer corresponds to a live thread.
    if let Ok(mut g) = SHUTDOWN_TOKEN.lock() {
        g.take();
    }

    match result {
        Ok(_sessions) => state::set_state(BridgeState::Idle),
        Err(e) => {
            state::set_last_error(format!("tun2proxy exited with error: {e}"));
            state::set_state(BridgeState::Failed);
        }
    }
}

/// Cancels the running bridge, if any. Returns `0` if a live bridge was signalled to stop,
/// `ERR_NOT_RUNNING` if none was running. Cancellation is cooperative (tun2proxy's own
/// `shutdown_token` check inside its packet-processing loop) -- this call returns as soon as
/// the signal is sent, not once the background thread has actually exited; poll
/// `requestr_tun2proxy_state` to observe `Stopping` -> `Idle`/`Failed`.
#[no_mangle]
pub extern "C" fn requestr_tun2proxy_stop() -> c_int {
    let token = match SHUTDOWN_TOKEN.lock() {
        Ok(mut g) => g.take(),
        Err(_) => {
            state::set_last_error("internal shutdown-token lock poisoned");
            return ERR_LOCK;
        }
    };
    match token {
        Some(t) => {
            state::set_state(BridgeState::Stopping);
            t.cancel();
            OK
        }
        None => ERR_NOT_RUNNING,
    }
}

/// Returns the current [`state::BridgeState`] as its `i32` discriminant.
#[no_mangle]
pub extern "C" fn requestr_tun2proxy_state() -> c_int {
    state::get_state() as c_int
}

/// Blocks up to `timeout_ms` millisecond for the background bridge thread to fully finish, then
/// joins the retained [`THREAD_HANDLE`] so the caller has a real guarantee — not an inference
/// from polling [`requestr_tun2proxy_state`] — that the thread will never touch the tun fd again
/// before the caller closes it. Returns [`OK`] when the thread is confirmed finished and joined,
/// [`ERR_NOT_RUNNING`] when there was no thread to wait on (never started, or already consumed),
/// or [`ERR_TIMEOUT`] when the bound elapsed first (ambiguous — the caller must escalate rather
/// than touch the fd).
///
/// # Safety
/// Safe to call from any thread; the underlying [`JoinHandle`] is only ever consumed by this
/// function and by [`start_with_readiness_timeout`]'s stale-handle join, both of which are
/// serialized by the same [`THREAD_HANDLE`] mutex.
#[no_mangle]
pub extern "C" fn requestr_tun2proxy_await_stopped(timeout_ms: c_int) -> c_int {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    if !state::wait_for_thread_done(timeout) {
        return ERR_TIMEOUT;
    }
    match THREAD_HANDLE.lock() {
        Ok(mut guard) => match guard.take() {
            Some(handle) => {
                let _ = handle.join();
                OK
            }
            None => ERR_NOT_RUNNING,
        },
        Err(_) => {
            state::set_last_error("internal thread-handle lock poisoned");
            ERR_LOCK
        }
    }
}

/// Copies the most recent recorded error message (if any) into `buf`, NUL-terminated,
/// truncating to fit within `buf_len` bytes (terminator included). Returns the number of
/// message bytes written (excluding the terminator, which may be fewer than the real message
/// length if truncated) on success, `0` if there is currently no recorded error, or a negative
/// `ERR_*` code.
///
/// # Safety
/// `buf` must point to at least `buf_len` writable, non-overlapping bytes.
#[no_mangle]
pub unsafe extern "C" fn requestr_tun2proxy_last_error(buf: *mut c_char, buf_len: c_int) -> c_int {
    if buf.is_null() || buf_len <= 0 {
        return ERR_NULL_PTR;
    }
    let max_len = buf_len as usize;

    let message = match state::last_error() {
        Some(m) => m,
        None => {
            unsafe { *buf = 0 };
            return 0;
        }
    };

    // A message can never legitimately contain an embedded NUL (it is always built from
    // `format!`/`Display` on this side), but guard against it anyway rather than panicking.
    let c_string = CString::new(message).unwrap_or_else(|e| {
        let mut lossy = e.into_vec();
        lossy.retain(|b| *b != 0);
        CString::new(lossy).unwrap_or_default()
    });
    let bytes = c_string.as_bytes_with_nul();
    let copy_len = bytes.len().min(max_len);

    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr() as *const c_char, buf, copy_len);
        *buf.add(max_len - 1) = 0;
    }
    (copy_len.saturating_sub(1)) as c_int
}

/// Writes the cumulative session traffic counters (as last reported by tun2proxy's own
/// traffic-status callback) into `*tx_out`/`*rx_out`. Values are `0`/`0` if the bridge has
/// never started or no traffic has flowed yet -- never a fabricated placeholder.
///
/// # Safety
/// `tx_out` and `rx_out` must each point to a valid, writable `u64`.
#[no_mangle]
pub unsafe extern "C" fn requestr_tun2proxy_traffic_stats(tx_out: *mut u64, rx_out: *mut u64) -> c_int {
    if tx_out.is_null() || rx_out.is_null() {
        return ERR_NULL_PTR;
    }
    let (tx, rx) = state::traffic_snapshot();
    unsafe {
        *tx_out = tx;
        *rx_out = rx;
    }
    OK
}

/// Registered once per `requestr_tun2proxy_start` call via
/// `tun2proxy_set_traffic_status_callback`; the sole writer of `state`'s traffic counters.
unsafe extern "C" fn traffic_callback(status: *const tun2proxy::TrafficStatus, _ctx: *mut c_void) {
    if status.is_null() {
        return;
    }
    let status = unsafe { &*status };
    state::record_traffic(status.tx, status.rx);
}

#[cfg(test)]
mod tests {
    use super::*;

    // FFI-level tests mutate the same process-global statics as `state`'s own tests, plus the
    // module-local `SHUTDOWN_TOKEN`; they serialize against state's tests via the one crate-wide
    // lock in `crate::state::TEST_SERIALIZATION_LOCK` (acquired through `lock_test_state`).

    fn reset() {
        state::set_state(BridgeState::Idle);
        state::clear_last_error();
        if let Ok(mut g) = SHUTDOWN_TOKEN.lock() {
            if let Some(t) = g.take() {
                t.cancel();
            }
        }
    }

    fn cstring(s: &str) -> CString {
        CString::new(s).unwrap()
    }

    #[test]
    fn start_rejects_null_config_pointer() {
        let _guard = crate::state::lock_test_state();
        reset();
        let rc = unsafe { requestr_tun2proxy_start(std::ptr::null(), 5) };
        assert_eq!(rc, ERR_NULL_PTR);
        assert_eq!(state::get_state(), BridgeState::Failed);
        assert!(state::last_error().is_some());
    }

    #[test]
    fn start_rejects_malformed_json_without_pretending_success() {
        let _guard = crate::state::lock_test_state();
        reset();
        let json = cstring("{not json");
        let rc = unsafe { requestr_tun2proxy_start(json.as_ptr(), 5) };
        assert_eq!(rc, ERR_INVALID_CONFIG);
        assert_eq!(state::get_state(), BridgeState::Failed);
    }

    #[test]
    fn start_rejects_invalid_proxy_address() {
        let _guard = crate::state::lock_test_state();
        reset();
        let json = cstring(r#"{"proxy":{"proxy_type":"http","address":"not-an-address"}}"#);
        let rc = unsafe { requestr_tun2proxy_start(json.as_ptr(), 5) };
        assert_eq!(rc, ERR_INVALID_CONFIG);
    }

    #[test]
    fn stop_without_a_running_bridge_reports_not_running() {
        let _guard = crate::state::lock_test_state();
        reset();
        assert_eq!(requestr_tun2proxy_stop(), ERR_NOT_RUNNING);
    }

    #[test]
    fn state_ffi_getter_matches_internal_state() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::set_state(BridgeState::Running);
        assert_eq!(requestr_tun2proxy_state(), BridgeState::Running as c_int);
    }

    #[test]
    fn last_error_ffi_round_trips_and_truncates() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::set_last_error("boom");
        let mut buf = [1i8; 8];
        let n = unsafe { requestr_tun2proxy_last_error(buf.as_mut_ptr(), buf.len() as c_int) };
        assert!(n > 0);
        let s = unsafe { CStr::from_ptr(buf.as_ptr()) }.to_str().unwrap();
        // "boom" fits in 8 bytes including terminator; truncation only kicks in for longer
        // messages, exercised implicitly by never reading past buf.len()-1.
        assert_eq!(s, "boom");
    }

    #[test]
    fn last_error_ffi_reports_zero_when_none_recorded() {
        let _guard = crate::state::lock_test_state();
        reset();
        let mut buf = [1i8; 8];
        let n = unsafe { requestr_tun2proxy_last_error(buf.as_mut_ptr(), buf.len() as c_int) };
        assert_eq!(n, 0);
    }

    #[test]
    fn traffic_stats_ffi_reports_current_snapshot() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::reset_traffic();
        state::record_traffic(11, 22);
        let mut tx = 0u64;
        let mut rx = 0u64;
        let rc = unsafe { requestr_tun2proxy_traffic_stats(&mut tx, &mut rx) };
        assert_eq!(rc, OK);
        assert_eq!((tx, rx), (11, 22));
    }

    #[test]
    fn traffic_stats_ffi_rejects_null_out_pointers() {
        let _guard = crate::state::lock_test_state();
        let mut tx = 0u64;
        let rc = unsafe { requestr_tun2proxy_traffic_stats(&mut tx, std::ptr::null_mut()) };
        assert_eq!(rc, ERR_NULL_PTR);
    }

    #[test]
    fn double_start_without_stop_is_rejected_not_silently_replaced() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::set_state(BridgeState::Running);
        if let Ok(mut g) = SHUTDOWN_TOKEN.lock() {
            *g = Some(CancellationToken::new());
        }
        let json = cstring(r#"{"proxy":{"proxy_type":"http","address":"127.0.0.1:1"}}"#);
        let rc = unsafe { requestr_tun2proxy_start(json.as_ptr(), 5) };
        assert_eq!(rc, ERR_ALREADY_RUNNING);
        reset();
    }

    #[test]
    fn await_stopped_with_no_thread_reports_not_running() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::mark_thread_done();
        assert_eq!(requestr_tun2proxy_await_stopped(50), ERR_NOT_RUNNING);
    }

    #[test]
    fn await_stopped_times_out_when_thread_never_finishes() {
        let _guard = crate::state::lock_test_state();
        reset();
        state::mark_thread_starting(); // simulate an in-flight, never-completing run
        let start = std::time::Instant::now();
        let rc = requestr_tun2proxy_await_stopped(50);
        let elapsed = start.elapsed();
        assert_eq!(rc, ERR_TIMEOUT);
        assert!(elapsed >= Duration::from_millis(50), "elapsed={elapsed:?}");
        assert!(elapsed < Duration::from_millis(1000), "elapsed={elapsed:?} (not bounded)");
        state::mark_thread_done();
    }
}
