//! Lifecycle state + traffic counters shared between the tun-read thread that drives
//! tun2proxy and the JNI calls that report status back to Kotlin.
//!
//! Deliberately dumb: every field here is written from exactly one place (documented on the
//! field) and read from `lib.rs`'s FFI getters. No field is ever set to a "looks plausible"
//! value -- state starts at [`BridgeState::Idle`] and only advances when something real
//! happens (a start attempt, tun2proxy itself reporting bytes, an error).

use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BridgeState {
    Idle = 0,
    Starting = 1,
    Running = 2,
    Stopping = 3,
    Failed = 4,
}

impl From<i32> for BridgeState {
    fn from(v: i32) -> Self {
        match v {
            1 => BridgeState::Starting,
            2 => BridgeState::Running,
            3 => BridgeState::Stopping,
            4 => BridgeState::Failed,
            _ => BridgeState::Idle,
        }
    }
}

static STATE: AtomicI32 = AtomicI32::new(BridgeState::Idle as i32);
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
static TX_BYTES: AtomicU64 = AtomicU64::new(0);
static RX_BYTES: AtomicU64 = AtomicU64::new(0);

/// Bug fix (P1 #1, readiness handshake): every [`set_state`] call notifies this condvar, so
/// [`wait_for_running_or_failed`] can block efficiently instead of polling, and returns as
/// soon as a real transition happens rather than sleeping out a fixed interval every time.
static STATE_CHANGED_LOCK: Mutex<()> = Mutex::new(());
static STATE_CHANGED: Condvar = Condvar::new();

/// Bug fix (P1 #2, confirmed shutdown): set to `true` exactly once per bridge run, as the very
/// last thing `lib.rs::run_bridge_thread` does (via a `Drop` guard, so it fires even if the
/// thread body panics under a `panic = "unwind"` profile -- release builds use
/// `panic = "abort"`, which takes the whole process down before any Drop runs, so there is
/// never a live "zombie" native thread lingering in that catastrophic case either way).
/// Reset to `false` by [`mark_thread_starting`] immediately before a new thread is spawned.
static THREAD_DONE_LOCK: Mutex<bool> = Mutex::new(true);
static THREAD_DONE: Condvar = Condvar::new();

/// Serializes every `#[cfg(test)]` test that mutates the crate's process-global statics
/// (`STATE`, `LAST_ERROR`, `TX_BYTES`, `RX_BYTES`, `THREAD_DONE_*`). There must be exactly one
/// crate-wide lock: the FFI tests in `lib.rs`'s `tests` module mutate the same statics (plus
/// that module's own `SHUTDOWN_TOKEN`), so two module-local locks would still let both modules
/// stomp each other's globals in parallel and turn the `wait_for_*` assertions into
/// order-dependent flakes.
#[cfg(test)]
pub(crate) static TEST_SERIALIZATION_LOCK: Mutex<()> = Mutex::new(());

/// Locks [TEST_SERIALIZATION_LOCK], recovering from a poisoned mutex so a single failing test
/// does not cascade into every subsequent test panicking with `PoisonError`.
#[cfg(test)]
pub(crate) fn lock_test_state() -> std::sync::MutexGuard<'static, ()> {
    TEST_SERIALIZATION_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub fn set_state(state: BridgeState) {
    STATE.store(state as i32, Ordering::SeqCst);
    STATE_CHANGED.notify_all();
}

pub fn get_state() -> BridgeState {
    BridgeState::from(STATE.load(Ordering::SeqCst))
}

/// Blocks the calling thread until the bridge reaches [`BridgeState::Running`] (a real success)
/// or [`BridgeState::Failed`] (a real, reportable failure) -- whichever happens first -- or
/// `timeout` elapses, whichever is sooner. Wakes as soon as [`set_state`] is called with a
/// matching state rather than polling, so a fast success/failure is observed in microseconds,
/// not after sleeping out the whole timeout.
///
/// Returns the [`BridgeState`] observed when it stopped waiting. A return value of
/// [`BridgeState::Starting`] (or any other non-terminal value) specifically means the timeout
/// elapsed before a real transition happened -- an ambiguous, ""real error"" outcome that
/// callers must treat as a failure (never as success), since the background thread may still
/// transition later and callers are responsible for a defensive stop in that case.
pub fn wait_for_running_or_failed(timeout: Duration) -> BridgeState {
    let deadline = Instant::now() + timeout;
    let current = get_state();
    if matches!(current, BridgeState::Running | BridgeState::Failed) {
        return current;
    }
    let guard = match STATE_CHANGED_LOCK.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let mut guard = guard;
    loop {
        let current = get_state();
        if matches!(current, BridgeState::Running | BridgeState::Failed) {
            return current;
        }
        let now = Instant::now();
        if now >= deadline {
            return current;
        }
        let remaining = deadline - now;
        let wait_result = STATE_CHANGED.wait_timeout(guard, remaining);
        let (next_guard, timeout_result) = match wait_result {
            Ok(r) => r,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard = next_guard;
        if timeout_result.timed_out() {
            return get_state();
        }
        // Spurious wakeup (or a state change to something other than Running/Failed, e.g.
        // Starting -> Stopping) -- loop back around and re-check against the deadline.
    }
}

/// Call immediately before spawning the background thread for a new bridge run. Resets the
/// "thread finished" signal so a stale `true` left over from a previous run (already consumed
/// by [`wait_for_thread_done`]) never causes a fresh run's very first await call to return
/// instantly without actually having observed *this* run's completion.
pub fn mark_thread_starting() {
    if let Ok(mut guard) = THREAD_DONE_LOCK.lock() {
        *guard = false;
    }
}

/// The sole writer of the "thread finished" signal -- called exactly once per run, from
/// `run_bridge_thread`'s `Drop` guard, after every other piece of state that run will ever set
/// has already been set.
pub fn mark_thread_done() {
    if let Ok(mut guard) = THREAD_DONE_LOCK.lock() {
        *guard = true;
    }
    THREAD_DONE.notify_all();
}

/// Blocks up to `timeout` waiting for [`mark_thread_done`] to have been called. Returns `true`
/// if confirmed done (immediately, if it already was), `false` if `timeout` elapsed first --
/// a real, bounded "could not confirm" outcome, never a guess.
pub fn wait_for_thread_done(timeout: Duration) -> bool {
    let guard = match THREAD_DONE_LOCK.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    if *guard {
        return true;
    }
    let result = THREAD_DONE.wait_timeout_while(guard, timeout, |done| !*done);
    match result {
        Ok((guard, _)) => *guard,
        Err(poisoned) => *poisoned.into_inner().0,
    }
}

/// Records a real failure. Always paired with `set_state(BridgeState::Failed)` at the call
/// site -- kept as two calls (not one) so `cargo test` can assert each independently.
pub fn set_last_error(message: impl Into<String>) {
    if let Ok(mut guard) = LAST_ERROR.lock() {
        *guard = Some(message.into());
    }
}

pub fn clear_last_error() {
    if let Ok(mut guard) = LAST_ERROR.lock() {
        *guard = None;
    }
}

pub fn last_error() -> Option<String> {
    LAST_ERROR.lock().ok().and_then(|g| g.clone())
}

/// Called from the `tun2proxy_set_traffic_status_callback` C callback this crate registers
/// once at start -- the only writer. Values are cumulative session totals, matching what
/// `tun2proxy::TrafficStatus` itself reports (never resets mid-session).
pub fn record_traffic(tx: u64, rx: u64) {
    TX_BYTES.store(tx, Ordering::Relaxed);
    RX_BYTES.store(rx, Ordering::Relaxed);
}

pub fn traffic_snapshot() -> (u64, u64) {
    (TX_BYTES.load(Ordering::Relaxed), RX_BYTES.load(Ordering::Relaxed))
}

pub fn reset_traffic() {
    TX_BYTES.store(0, Ordering::Relaxed);
    RX_BYTES.store(0, Ordering::Relaxed);
}

#[cfg(test)]
mod tests {
    use super::*;

    // These tests mutate the crate's process-global statics, so they serialize against every
    // other test that touches them -- including lib.rs's FFI tests -- via the single shared
    // lock in `super::TEST_SERIALIZATION_LOCK` (acquired through `super::lock_test_state`).

    #[test]
    fn state_round_trips_through_i32() {
        let _guard = super::lock_test_state();
        for s in [BridgeState::Idle, BridgeState::Starting, BridgeState::Running, BridgeState::Stopping, BridgeState::Failed] {
            set_state(s);
            assert_eq!(get_state(), s);
        }
    }

    #[test]
    fn unknown_i32_falls_back_to_idle_not_a_crash() {
        assert_eq!(BridgeState::from(999), BridgeState::Idle);
    }

    #[test]
    fn last_error_lifecycle() {
        let _guard = super::lock_test_state();
        clear_last_error();
        assert_eq!(last_error(), None);
        set_last_error("boom");
        assert_eq!(last_error(), Some("boom".to_string()));
        clear_last_error();
        assert_eq!(last_error(), None);
    }

    #[test]
    fn traffic_counters_are_cumulative_snapshots() {
        let _guard = super::lock_test_state();
        reset_traffic();
        assert_eq!(traffic_snapshot(), (0, 0));
        record_traffic(100, 50);
        assert_eq!(traffic_snapshot(), (100, 50));
        record_traffic(150, 90);
        assert_eq!(traffic_snapshot(), (150, 90));
    }

    // ---- P1 bug #1: bounded readiness handshake ----

    #[test]
    fn wait_for_running_or_failed_returns_immediately_if_already_terminal() {
        let _guard = super::lock_test_state();
        set_state(BridgeState::Running);
        let start = Instant::now();
        let observed = wait_for_running_or_failed(Duration::from_millis(500));
        assert_eq!(observed, BridgeState::Running);
        // Must not have slept out any meaningful fraction of the bound -- proves this is the
        // fast "already there" path, not a fixed-interval poll.
        assert!(start.elapsed() < Duration::from_millis(100));
    }

    #[test]
    fn wait_for_running_or_failed_wakes_promptly_on_a_delayed_transition_to_running() {
        let _guard = super::lock_test_state();
        set_state(BridgeState::Starting);
        let handle = std::thread::spawn(|| {
            std::thread::sleep(Duration::from_millis(20));
            set_state(BridgeState::Running);
        });
        let start = Instant::now();
        let observed = wait_for_running_or_failed(Duration::from_millis(2000));
        let elapsed = start.elapsed();
        handle.join().unwrap();
        assert_eq!(observed, BridgeState::Running);
        // Woke on the notification (~20ms), nowhere close to the 2s bound -- proves this is
        // condvar-driven, not polling until the timeout.
        assert!(elapsed < Duration::from_millis(500), "elapsed={elapsed:?}");
        set_state(BridgeState::Idle);
    }

    #[test]
    fn wait_for_running_or_failed_wakes_promptly_on_a_delayed_transition_to_failed() {
        let _guard = super::lock_test_state();
        set_state(BridgeState::Starting);
        let handle = std::thread::spawn(|| {
            std::thread::sleep(Duration::from_millis(20));
            set_state(BridgeState::Failed);
        });
        let observed = wait_for_running_or_failed(Duration::from_millis(2000));
        handle.join().unwrap();
        assert_eq!(observed, BridgeState::Failed);
        set_state(BridgeState::Idle);
    }

    #[test]
    fn wait_for_running_or_failed_is_bounded_when_stuck_starting() {
        let _guard = super::lock_test_state();
        set_state(BridgeState::Starting);
        let start = Instant::now();
        let observed = wait_for_running_or_failed(Duration::from_millis(50));
        let elapsed = start.elapsed();
        // Never transitions away from Starting in this test -- the ambiguous-timeout outcome
        // the caller must treat as a real failure, never as success.
        assert_eq!(observed, BridgeState::Starting);
        assert!(elapsed >= Duration::from_millis(50), "elapsed={elapsed:?}");
        assert!(elapsed < Duration::from_millis(1000), "elapsed={elapsed:?} (not bounded)");
        set_state(BridgeState::Idle);
    }

    // ---- P1 bug #2: confirmed shutdown signal ----

    #[test]
    fn wait_for_thread_done_returns_true_immediately_once_marked_done() {
        let _guard = super::lock_test_state();
        mark_thread_done();
        let start = Instant::now();
        let confirmed = wait_for_thread_done(Duration::from_millis(500));
        assert!(confirmed);
        assert!(start.elapsed() < Duration::from_millis(100));
    }

    #[test]
    fn wait_for_thread_done_wakes_promptly_on_a_delayed_completion() {
        let _guard = super::lock_test_state();
        mark_thread_starting();
        let handle = std::thread::spawn(|| {
            std::thread::sleep(Duration::from_millis(20));
            mark_thread_done();
        });
        let confirmed = wait_for_thread_done(Duration::from_millis(2000));
        handle.join().unwrap();
        assert!(confirmed);
    }

    #[test]
    fn wait_for_thread_done_times_out_when_never_marked_done() {
        let _guard = super::lock_test_state();
        mark_thread_starting();
        let start = Instant::now();
        let confirmed = wait_for_thread_done(Duration::from_millis(50));
        let elapsed = start.elapsed();
        assert!(!confirmed);
        assert!(elapsed >= Duration::from_millis(50), "elapsed={elapsed:?}");
        assert!(elapsed < Duration::from_millis(1000), "elapsed={elapsed:?} (not bounded)");
        // Leave it confirmable for any later test in this module that shares the process.
        mark_thread_done();
    }

    #[test]
    fn mark_thread_starting_resets_a_stale_done_flag_from_a_previous_run() {
        let _guard = super::lock_test_state();
        mark_thread_done();
        assert!(wait_for_thread_done(Duration::from_millis(10)));
        mark_thread_starting();
        // A fresh run must not appear "done" just because the previous one was.
        assert!(!wait_for_thread_done(Duration::from_millis(10)));
        mark_thread_done();
    }
}
