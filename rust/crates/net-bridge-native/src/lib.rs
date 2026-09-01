//! net-bridge-native：QUIC / KCP 传输的 JNI 桥（临时过渡适配器）。

use std::sync::{Arc, OnceLock};

use jni::errors::Error;
use jni::ids::JStaticMethodID;
use jni::objects::{Global, JByteArray, JByteBuffer, JClass, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jbyte, jbyteArray, jint, jlong, jlongArray, jstring};
use jni::{Env, EnvOutcome, EnvUnowned, JavaVM, Outcome, jni_sig, jni_str};

use net_bridge_core::error::BridgeError;
use net_bridge_core::transport::TransportKind;
use net_bridge_core::transport::kcp::config::KcpProfile;

pub const NET_BRIDGE_ABI_VERSION: &str = net_bridge_core::NET_BRIDGE_ABI_VERSION;

/// 反向通知回调目标：NativeBridge 类 GlobalRef + `onDataAvailable(J)V` 方法 id。
static NOTIFY: OnceLock<NotifyTarget> = OnceLock::new();

struct NotifyTarget {
    class: Global<JClass<'static>>,
    method: JStaticMethodID,
}

struct JniEventSink;

impl net_bridge_core::EventSink for JniEventSink {
    fn on_event(&self, event_kind: u32, object_id: u64, _arg0: i64, _arg1: i64) {
        if event_kind == net_bridge_core::event::NB_EVENT_DATA_AVAILABLE {
            notify_data(object_id);
        }
    }
}

pub(crate) fn notify_data(conn_id: u64) {
    let Some(target) = NOTIFY.get() else {
        return;
    };
    let Ok(vm) = JavaVM::singleton() else {
        return;
    };
    let _ = vm.attach_current_thread_for_scope(|env| {
        let arg = JValue::Long(conn_id as i64).as_jni();
        // SAFETY: method id 来自 get_static_method_id，class 由 GlobalRef 持有；
        // args 指向本调用栈上的 jvalue。
        unsafe {
            env.call_static_method_unchecked(
                &target.class,
                target.method,
                ReturnType::Primitive(Primitive::Void),
                &[arg],
            )
        }
        .map(|_| ())
    });
}

fn resolve_default<T>(
    outcome: EnvOutcome<'_, T, Error>,
    context: &str,
    default: impl FnOnce() -> T,
) -> T {
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(e) => {
            net_bridge_core::report_error(format!("{context}: {e}"));
            default()
        }
        Outcome::Panic(_) => {
            net_bridge_core::report_error(format!("{context}: panicked"));
            default()
        }
    }
}

fn valid_port(port: jint) -> Option<u16> {
    u16::try_from(port).ok()
}

fn resolve_kind(kind: jint, context: &str) -> Option<TransportKind> {
    match TransportKind::from_jint(kind) {
        Some(k) => Some(k),
        None => {
            net_bridge_core::report_error(format!("{context}: invalid transport kind {kind}"));
            None
        }
    }
}

fn resolve_profile(profile: Option<String>) -> KcpProfile {
    let Some(value) = profile else {
        return KcpProfile::default();
    };
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return KcpProfile::default();
    }
    match KcpProfile::parse(trimmed) {
        Some(p) => p,
        None => {
            net_bridge_core::report_error(format!(
                "invalid kcp profile '{trimmed}', falling back to balance"
            ));
            KcpProfile::default()
        }
    }
}

fn optional_string(env: &mut EnvUnowned, value: &JString, context: &str) -> Option<String> {
    resolve_default(
        env.with_env(|env| {
            if value.is_null() {
                Ok::<_, Error>(None)
            } else {
                Ok(Some(value.try_to_string(env)?))
            }
        }),
        context,
        || None,
    )
}

fn report_conn_err(err: &BridgeError) {
    if !matches!(
        err,
        BridgeError::NoSuchConnection | BridgeError::ConnectionClosed
    ) {
        net_bridge_core::report_error(err.message());
    }
}

fn copy_region(env: &mut Env, data: &JByteArray, len: usize) -> Result<Vec<u8>, Error> {
    let mut buf = vec![0u8; len];
    if len > 0 {
        // SAFETY: jbyte 与 u8 同尺寸同布局，仅按位重解释用于 JNI 拷出。
        let region: &mut [jbyte] =
            unsafe { std::slice::from_raw_parts_mut(buf.as_mut_ptr().cast(), len) };
        data.get_region(env, 0, region)?;
    }
    Ok(buf)
}

fn direct_slice<'e>(env: &mut Env, buffer: &JByteBuffer) -> Result<&'e mut [u8], Error> {
    let addr = env.get_direct_buffer_address(buffer)?;
    let cap = env.get_direct_buffer_capacity(buffer)?;
    if cap == 0 {
        return Ok(&mut []);
    }
    // SAFETY: addr 指向 JVM 直接缓冲区内存且 cap 为其可写字节数；
    // JNI 约定该指针仅在本次原生调用内使用。
    Ok(unsafe { std::slice::from_raw_parts_mut(addr.cast(), cap) })
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_version(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    resolve_default(
        env.with_env(|env| Ok(env.new_string(NET_BRIDGE_ABI_VERSION)?.into_raw())),
        "version",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_register_notify_callback(
    mut env: EnvUnowned,
    class: JClass,
) {
    let _ = net_bridge_core::set_event_sink(Arc::new(JniEventSink));
    let _ = resolve_default(
        env.with_env(|env| {
            let method =
                env.get_static_method_id(&class, jni_str!("onDataAvailable"), jni_sig!("(J)V"))?;
            let class_ref = env.new_global_ref(class)?;
            let _ = NOTIFY.set(NotifyTarget {
                class: class_ref,
                method,
            });
            Ok::<_, Error>(())
        }),
        "registerNotifyCallback",
        || (),
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_start_server(
    mut env: EnvUnowned,
    _class: JClass,
    kind: jint,
    port: jint,
    max_connections: jint,
    host: JString,
    profile: JString,
) -> jlong {
    let Some(kind) = resolve_kind(kind, "startServer") else {
        return -1;
    };
    let Some(port) = valid_port(port) else {
        net_bridge_core::report_error(format!("invalid listen port {port}"));
        return -1;
    };
    if max_connections < 1 {
        net_bridge_core::report_error(format!("invalid max connections {max_connections}"));
        return -1;
    }
    let host = optional_string(&mut env, &host, "startServer").unwrap_or_default();
    let bind = match host.trim() {
        "" => None,
        s => match s.parse::<std::net::IpAddr>() {
            Ok(ip) => Some(ip),
            Err(e) => {
                net_bridge_core::report_error(format!("invalid bind address '{s}': {e}"));
                return -1;
            }
        },
    };
    let kcp_profile = resolve_profile(optional_string(&mut env, &profile, "startServer"));
    match net_bridge_core::start_server(kind, port, max_connections as usize, bind, kcp_profile) {
        Ok(id) => id as jlong,
        Err(err) => {
            net_bridge_core::report_error(err.message());
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_server_port(
    _env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jint {
    net_bridge_core::server_port(server as u64)
        .map(|p| p as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_stop_server(
    _env: EnvUnowned,
    _class: JClass,
    server: jlong,
) {
    net_bridge_core::stop_server(server as u64);
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_accept_connections(
    mut env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jlongArray {
    let ids = net_bridge_core::accept_connections(server as u64);
    resolve_default(
        env.with_env(|env| {
            let arr = env.new_long_array(ids.len())?;
            if !ids.is_empty() {
                let raw: Vec<jlong> = ids.iter().map(|id| *id as jlong).collect();
                arr.set_region(env, 0, &raw)?;
            }
            Ok(arr.as_raw())
        }),
        "acceptConnections",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_connect(
    mut env: EnvUnowned,
    _class: JClass,
    kind: jint,
    host: JString,
    port: jint,
    profile: JString,
) -> jlong {
    let Some(kind) = resolve_kind(kind, "connect") else {
        return -1;
    };
    let Some(port) = valid_port(port) else {
        net_bridge_core::report_error(format!("invalid connect port {port}"));
        return -1;
    };
    let Some(host) = optional_string(&mut env, &host, "connect") else {
        net_bridge_core::report_error("connect: host is null".to_string());
        return -1;
    };
    if host.trim().is_empty() {
        net_bridge_core::report_error("connect: host is empty".to_string());
        return -1;
    }
    let kcp_profile = resolve_profile(optional_string(&mut env, &profile, "connect"));
    match net_bridge_core::connect(kind, &host, port, kcp_profile) {
        Ok(id) => id as jlong,
        Err(err) => {
            net_bridge_core::report_error(err.message());
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_connection_state(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jint {
    net_bridge_core::connection_state(conn as u64)
        .map(|s| s as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_remote_address(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jstring {
    let Some(addr) = net_bridge_core::conn_remote_addr(conn as u64) else {
        return std::ptr::null_mut();
    };
    resolve_default(
        env.with_env(|env| Ok(env.new_string(addr)?.into_raw())),
        "remoteAddress",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_close(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) {
    net_bridge_core::close_connection(conn as u64);
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_write_chunk(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    data: JByteArray,
    length: jint,
) -> jint {
    let arr_len: Option<usize> = resolve_default(
        env.with_env(|env| data.len(env).map(Some)),
        "writeChunk",
        || None,
    );
    let Some(arr_len) = arr_len else {
        net_bridge_core::report_error("writeChunk: failed to read array length".to_string());
        return -1;
    };
    if length < 0 || length as usize > arr_len {
        net_bridge_core::report_error(format!(
            "writeChunk: invalid length {length} for array of {arr_len}"
        ));
        return -1;
    }
    let len = length as usize;
    let bytes = resolve_default(
        env.with_env(|env| copy_region(env, &data, len).map(Some)),
        "writeChunk",
        || None,
    );
    let Some(bytes) = bytes else {
        return -1;
    };
    match net_bridge_core::write_chunk(conn as u64, bytes.into()) {
        Ok(n) => n as jint,
        Err(err) => {
            report_conn_err(&err);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_read_chunk(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    max_bytes: jint,
) -> jbyteArray {
    let max = max_bytes.max(0) as usize;
    let Ok(bytes) = net_bridge_core::read_chunk(conn as u64, max) else {
        return std::ptr::null_mut();
    };
    resolve_default(
        env.with_env(|env| Ok(env.byte_array_from_slice(&bytes)?.as_raw())),
        "readChunk",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn java_top_tangge233_netbridge_jni_native_bridge_read_chunk_into(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    buffer: JByteBuffer,
    max_bytes: jint,
) -> jint {
    if max_bytes < 0 {
        net_bridge_core::report_error(format!("readChunkInto: invalid max_bytes {max_bytes}"));
        return -1;
    }
    let Some(dst) = resolve_default(
        env.with_env(|env| direct_slice(env, &buffer).map(Some)),
        "readChunkInto",
        || None,
    ) else {
        net_bridge_core::report_error("readChunkInto: failed to access direct buffer".to_string());
        return -1;
    };
    let want = (max_bytes as usize).min(dst.len());
    match net_bridge_core::read_chunk(conn as u64, want) {
        Ok(bytes) => {
            let n = bytes.len();
            dst[..n].copy_from_slice(&bytes);
            n as jint
        }
        Err(err) => {
            net_bridge_core::report_error(err.message());
            -1
        }
    }
}
