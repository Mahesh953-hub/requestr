//! Android JNI exports for the requestr tun2proxy bridge.
//!
//! Kotlin's `NativeTun2Proxy` (`app/src/main/java/com/example/interception/tun/`) declares
//! matching `external fun` signatures and loads `librequestr_tun2proxy.so` via
//! `System.loadLibrary`. Every export here is a thin marshalling wrapper around the plain
//! C-ABI functions in `lib.rs` -- no bridge logic lives in this file, so the C-ABI surface
//! stays testable on the host (see `lib.rs`'s `#[cfg(test)]` module) without a JVM.
//!
//! Class name baked into the JNI symbol names: `com.example.interception.tun.NativeTun2Proxy`.

use std::ffi::CString;
use std::os::raw::c_int;
use std::sync::{Arc, Mutex, Once};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong};
use jni::{JNIEnv, JavaVM};

use crate::{
    requestr_tun2proxy_await_stopped, requestr_tun2proxy_start, requestr_tun2proxy_state,
    requestr_tun2proxy_stop, requestr_tun2proxy_traffic_stats, ERR_INVALID_CONFIG, ERR_NULL_PTR,
};

static LOGGER_INIT: Once = Once::new();

/// Initializes `android_logger` exactly once for the process, at whatever verbosity the first
/// successfully-parsed config asked for. Mirrors tun2proxy's own `android.rs` JNI entry
/// points (which this bridge does not call into, since it drives `tun2proxy::Args` directly
/// instead of tun2proxy's CLI-string JNI surface) so `adb logcat` output is consistent with
/// upstream tun2proxy's own conventions.
pub(crate) fn init_android_logger_once(verbosity: &str) {
    let level = match verbosity.to_ascii_lowercase().as_str() {
        "off" => log::LevelFilter::Off,
        "error" => log::LevelFilter::Error,
        "warn" | "warning" => log::LevelFilter::Warn,
        "debug" => log::LevelFilter::Debug,
        "trace" => log::LevelFilter::Trace,
        _ => log::LevelFilter::Info,
    };
    LOGGER_INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(level)
                .with_tag("requestr_tun2proxy"),
        );
    });
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeStart`.
///
/// Converts the JVM `configJson` string to a C string and the JVM `int tunFd` straight through,
/// then delegates to [`requestr_tun2proxy_start`]. Returns the same negative `ERR_*` codes on
/// failure (see `lib.rs`) -- a JVM-side string conversion failure is reported as
/// `ERR_INVALID_CONFIG`/`ERR_NULL_PTR` rather than silently starting with an empty config.
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
    tun_fd: jint,
) -> jint {
    let json: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => {
            crate::state::set_last_error("failed to read configJson string from JVM");
            crate::state::set_state(crate::state::BridgeState::Failed);
            return ERR_NULL_PTR as jint;
        }
    };
    let c_json = match CString::new(json) {
        Ok(c) => c,
        Err(_) => {
            crate::state::set_last_error("configJson contained an embedded NUL byte");
            crate::state::set_state(crate::state::BridgeState::Failed);
            return ERR_INVALID_CONFIG as jint;
        }
    };
    (unsafe { requestr_tun2proxy_start(c_json.as_ptr(), tun_fd as c_int) }) as jint
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeStop`.
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    requestr_tun2proxy_stop() as jint
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeAwaitStopped`.
///
/// Bounded confirmed-shutdown wait (P1 bug #2): blocks up to `timeout_ms` for the bridge thread
/// to finish, then joins it so Kotlin can close the tun fd only after the thread provably cannot
/// touch it again. Returns `OK` on confirmed join, `ERR_NOT_RUNNING` when there was no thread, or
/// `ERR_TIMEOUT` (ambiguous — caller must escalate) when the bound elapsed first.
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeAwaitStopped<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> jint {
    requestr_tun2proxy_await_stopped(timeout_ms as c_int) as jint
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeState`.
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeState<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    requestr_tun2proxy_state() as jint
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeLastError`.
///
/// Returns the last recorded error as a fresh Java `String`, or `null` if none is recorded, so
/// Kotlin never has to pre-size a native buffer.
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeLastError<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    match crate::state::last_error() {
        Some(message) => env
            .new_string(message)
            .unwrap_or_else(|_| JString::from(jni::objects::JObject::null())),
        None => JString::from(jni::objects::JObject::null()),
    }
}

/// `Java_com_example_interception_tun_NativeTun2Proxy_nativeTrafficStats`.
///
/// Packs `(tx, rx)` into a single `long` as `(tx << 32) | (rx & 0xffffffff)` since JNI has no
/// tuple return type; both counters are truncated to 32 bits each, which matches the packet
/// counters `PacketPump`/`InterceptionStateRepository` already use elsewhere for session byte
/// counts (see `Tun2ProxyBridge.kt`'s unpacking helper, which is unit-tested against this exact
/// packing scheme).
#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeTrafficStats<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    let mut tx: u64 = 0;
    let mut rx: u64 = 0;
    let rc = unsafe { requestr_tun2proxy_traffic_stats(&mut tx, &mut rx) };
    if rc != crate::OK {
        return -1;
    }
    let tx32 = (tx & 0xffff_ffff) as i64;
    let rx32 = (rx & 0xffff_ffff) as i64;
    ((tx32 << 32) | rx32) as jlong
}

// ---------------------------------------------------------------------------
// Event listener (JavaVM + GlobalRef lifecycle)
// ---------------------------------------------------------------------------

/// Stores the JavaVM handle and the GlobalRef to the Kotlin BridgeSupervisor instance.
/// The JNI callback attaches as a daemon thread if needed, calls `onNativeEvent(String)`,
/// and clears any pending exception.
static EVENT_LISTENER: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// JNI event sink that calls BridgeSupervisor.onNativeEvent(String) via JavaVM.
struct JniEventSink;

impl crate::events::EventSink for JniEventSink {
    fn on_event(&self, json: &str) {
        let guard = match EVENT_LISTENER.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        let (vm, listener_ref) = match guard.as_ref() {
            Some(pair) => pair,
            None => return,
        };
        // Attach as daemon thread (no-op if already attached)
        let mut env = match vm.attach_current_thread_as_daemon() {
            Ok(env) => env,
            Err(_) => return,
        };
        let jstr = match env.new_string(json) {
            Ok(s) => s,
            Err(_) => return,
        };
        let result = env.call_method(
            listener_ref.as_obj(),
            "onNativeEvent",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&JObject::from(jstr))],
        );
        if result.is_err() || env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeRegisterEventListener<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    listener: JObject<'local>,
) -> jint {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return crate::ERR_LOCK as jint,
    };
    let global_ref = match env.new_global_ref(listener) {
        Ok(r) => r,
        Err(_) => return crate::ERR_LOCK as jint,
    };
    if let Ok(mut guard) = EVENT_LISTENER.lock() {
        *guard = Some((vm, global_ref));
    }
    // Install the JNI sink as the event sink for the bridge
    crate::events::set_event_sink(Arc::new(JniEventSink));
    crate::OK as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeUnregisterEventListener<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    crate::events::clear_event_sink();
    if let Ok(mut guard) = EVENT_LISTENER.lock() {
        *guard = None; // drops GlobalRef
    }
    crate::OK as jint
}

// ---------------------------------------------------------------------------
// Protect callback (JavaVM + GlobalRef lifecycle)
// ---------------------------------------------------------------------------

/// Stores the JavaVM handle and GlobalRef to the Kotlin NativeFdProtector object.
static PROTECT_CALLBACK: Mutex<Option<(JavaVM, GlobalRef)>> = Mutex::new(None);

/// Vendor hook implementation that calls Kotlin's protectFd(int) via JNI.
struct JniProtectHook;

impl tun2proxy::requestr_hooks::SocketProtectHook for JniProtectHook {
    fn protect_fd(&self, fd: i32) -> bool {
        let guard = match PROTECT_CALLBACK.lock() {
            Ok(g) => g,
            Err(_) => return false, // poisoned — fail closed
        };
        let (vm, protector_ref) = match guard.as_ref() {
            Some(pair) => pair,
            None => return false, // no callback — fail closed when protect was configured
        };
        let mut env = match vm.attach_current_thread_as_daemon() {
            Ok(env) => env,
            Err(_) => return false,
        };
        let result = env.call_method(
            protector_ref.as_obj(),
            "protectFd",
            "(I)Z",
            &[JValue::Int(fd)],
        );
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
            return false;
        }
        match result {
            Ok(val) => val.z().unwrap_or(false),
            Err(_) => false,
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeRegisterProtectCallback<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    protector: JObject<'local>,
) -> jint {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return crate::ERR_LOCK as jint,
    };
    let global_ref = match env.new_global_ref(protector) {
        Ok(r) => r,
        Err(_) => return crate::ERR_LOCK as jint,
    };
    if let Ok(mut guard) = PROTECT_CALLBACK.lock() {
        *guard = Some((vm, global_ref));
    }
    tun2proxy::requestr_hooks::set_socket_protect_hook(Arc::new(JniProtectHook));
    crate::OK as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_interception_tun_NativeTun2Proxy_nativeUnregisterProtectCallback<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    tun2proxy::requestr_hooks::clear_socket_protect_hook();
    if let Ok(mut guard) = PROTECT_CALLBACK.lock() {
        *guard = None;
    }
    crate::OK as jint
}
