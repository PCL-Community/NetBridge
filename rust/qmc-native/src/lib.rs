//! qmc-native: QUIC（quinn-plaintext）传输的 JNI 桥。
//!
//! 架构（ADR-0001）：
//! - `bridge` 模块持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//!   提供同步的 server/client/state/read/write 原语；
//! - JNI 导出把这些原语暴露给 Java（`top.tangge233.qmc.jni.QuicNative`），
//!   每条 QUIC 连接 = 一个双向流，承载整个 MC 会话字节流。

pub mod bridge;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jstring};

pub const QMC_ABI_VERSION: &str = "0.1.0";
pub const QMC_RAW_FEATURE: &str = "quic-raw";

macro_rules! jni_err {
    ($env:expr, $msg:expr, $default:expr) => {{
        bridge::report_error($msg.to_string());
        $default
    }};
}

/// JNI 端口参数校验：仅接受 0（系统分配）与 1..=65535，其余拒绝。
fn valid_port(port: jint) -> Option<u16> {
    u16::try_from(port).ok()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(QMC_ABI_VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_rawFeature(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(QMC_RAW_FEATURE) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_startServer(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
    max_connections: jint,
) -> jlong {
    let Some(port) = valid_port(port) else {
        return jni_err!(_env, format!("invalid listen port {port}"), -1);
    };
    if max_connections < 1 {
        return jni_err!(_env, format!("invalid max connections {max_connections}"), -1);
    }
    match bridge::start_server(port, max_connections as usize) {
        Ok(id) => id as jlong,
        Err(msg) => jni_err!(_env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_serverPort(
    _env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jint {
    bridge::server_port(server as u64)
        .map(|p| p as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_acceptConnections(
    env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jlongArray {
    let ids = bridge::accept_connections(server as u64);
    match env.new_long_array(ids.len() as jint) {
        Ok(arr) => {
            let raw: Vec<jlong> = ids.into_iter().map(|id| id as jlong).collect();
            if !raw.is_empty() {
                let _ = env.set_long_array_region(&arr, 0, &raw);
            }
            arr.as_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_stopServer(
    _env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jboolean {
    bridge::stop_server(server as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connect(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
) -> jlong {
    let host_str = match env.get_string(&host) {
        Ok(s) => String::from(s),
        Err(_) => return -1,
    };
    let Some(port) = valid_port(port) else {
        return jni_err!(env, format!("invalid remote port {port}"), -1);
    };
    match bridge::connect(&host_str, port) {
        Ok(id) => id as jlong,
        Err(msg) => jni_err!(env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connectionState(
    _env: JNIEnv,
    _class: JClass,
    conn: jlong,
) -> jint {
    bridge::connection_state(conn as u64)
        .map(|s| s as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_closeConnection(
    _env: JNIEnv,
    _class: JClass,
    conn: jlong,
) -> jboolean {
    bridge::close_connection(conn as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_writeChunk(
    env: JNIEnv,
    _class: JClass,
    conn: jlong,
    data: JByteArray,
) -> jint {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    // Vec<u8> → Bytes 为零成本接管分配，无拷贝。
    match bridge::write_chunk(conn as u64, bytes.into()) {
        Ok(n) => n as jint,
        Err(msg) => jni_err!(env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_readChunk(
    env: JNIEnv,
    _class: JClass,
    conn: jlong,
    max_bytes: jint,
) -> jbyteArray {
    let max = max_bytes.max(0) as usize;
    let bytes = match bridge::read_chunk(conn as u64, max) {
        Ok(b) => b,
        Err(msg) => {
            bridge::report_error(msg);
            return std::ptr::null_mut();
        }
    };
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.as_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests;
