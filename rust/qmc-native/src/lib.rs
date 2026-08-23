//! qmc-native: QUIC（quinn-plaintext）传输的 JNI 桥。
//!
//! 架构（ADR-0001）：
//! - `bridge` 模块持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//!   提供同步的 server/client/state/read/write 原语；
//! - JNI 导出把这些原语暴露给 Java（`top.tangge233.qmc.jni.QuicNative`），
//!   每条 QUIC 连接 = 一个双向流，承载整个 MC 会话字节流。
//!
//! JNI 层（jni 0.22）：导出函数收 FFI 安全的 [`EnvUnowned`]，需要访问 JNI
//! 时经 `with_env` 升级为 `&mut Env`；闭包被 catch_unwind 包裹，panic 不会
//! 穿越原生方法边界 abort 宿主进程。错误统一记 stderr 日志并返回默认值。

pub mod bridge;

use jni::errors::Error;
use jni::objects::{JByteArray, JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jbyte, jbyteArray, jint, jlong, jlongArray, jstring};
use jni::{EnvOutcome, EnvUnowned, Outcome};

pub const QMC_ABI_VERSION: &str = "0.1.0";
pub const QMC_RAW_FEATURE: &str = "quic-raw";

/// 统一解析 `with_env` 结果：Err/panic 记日志并返回调用方指定的默认值
/// （与旧版"错误返回 -1/null"语义一致，细节见 qmc-native 日志）。
fn resolve_default<T>(
    outcome: EnvOutcome<'_, T, Error>,
    context: &str,
    default: impl FnOnce() -> T,
) -> T {
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(e) => {
            bridge::report_error(format!("{context}: {e}"));
            default()
        }
        Outcome::Panic(_) => {
            bridge::report_error(format!("{context}: panicked"));
            default()
        }
    }
}

/// JNI 端口参数校验：仅接受 0（系统分配）与 1..=65535，其余拒绝。
fn valid_port(port: jint) -> Option<u16> {
    u16::try_from(port).ok()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_version(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    resolve_default(
        env.with_env(|env| Ok(env.new_string(QMC_ABI_VERSION)?.into_raw())),
        "version",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_rawFeature(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    resolve_default(
        env.with_env(|env| Ok(env.new_string(QMC_RAW_FEATURE)?.into_raw())),
        "rawFeature",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_startServer(
    mut env: EnvUnowned,
    _class: JClass,
    port: jint,
    max_connections: jint,
    host: JString,
) -> jlong {
    let Some(port) = valid_port(port) else {
        bridge::report_error(format!("invalid listen port {port}"));
        return -1;
    };
    if max_connections < 1 {
        bridge::report_error(format!("invalid max connections {max_connections}"));
        return -1;
    }
    // bind 为空/null 时用默认地址族（[::] 双栈 → 0.0.0.0），否则仅绑定指定 IP。
    let host = resolve_default(
        env.with_env(|env| {
            if host.is_null() {
                Ok::<_, Error>(String::new())
            } else {
                host.try_to_string(env)
            }
        }),
        "startServer",
        || String::new(),
    );
    let bind = match host.trim() {
        "" => None,
        s => match s.parse::<std::net::IpAddr>() {
            Ok(ip) => Some(ip),
            Err(e) => {
                bridge::report_error(format!("invalid bind address '{s}': {e}"));
                return -1;
            }
        },
    };
    match bridge::start_server(port, max_connections as usize, bind) {
        Ok(id) => id as jlong,
        Err(msg) => {
            bridge::report_error(msg);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_serverPort(
    _env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jint {
    bridge::server_port(server as u64)
        .map(|p| p as jint)
        .unwrap_or(-1)
}

/// 查询连接对端地址（"ip:port"）；客户端连接或不存在返回 null。
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_remoteAddress(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jstring {
    let Some(addr) = bridge::conn_remote_addr(conn as u64) else {
        return std::ptr::null_mut();
    };
    resolve_default(
        env.with_env(|env| Ok(env.new_string(addr)?.into_raw())),
        "remoteAddress",
        std::ptr::null_mut,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_acceptConnections(
    mut env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jlongArray {
    let ids = bridge::accept_connections(server as u64);
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
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_stopServer(
    _env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jboolean {
    bridge::stop_server(server as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connect(
    mut env: EnvUnowned,
    _class: JClass,
    host: JString,
    port: jint,
) -> jlong {
    let Some(host) = resolve_default(
        env.with_env(|env| Ok::<_, Error>(Some(host.try_to_string(env)?))),
        "connect",
        || None,
    ) else {
        return -1;
    };
    let Some(port) = valid_port(port) else {
        bridge::report_error(format!("invalid remote port {port}"));
        return -1;
    };
    match bridge::connect(&host, port) {
        Ok(id) => id as jlong,
        Err(msg) => {
            bridge::report_error(msg);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connectionState(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jint {
    bridge::connection_state(conn as u64)
        .map(|s| s as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_closeConnection(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jboolean {
    bridge::close_connection(conn as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_writeChunk(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    data: JByteArray,
    length: jint,
) -> jint {
    // 长度在拷贝前校验：负数或超出数组边界为调用方 bug，拒绝而非截断。
    let arr_len: Option<usize> = resolve_default(
        env.with_env(|env| data.len(env).map(Some)),
        "writeChunk",
        || None,
    );
    let Some(arr_len) = arr_len else {
        bridge::report_error("writeChunk: failed to read array length".to_string());
        return -1;
    };
    if length < 0 || length as usize > arr_len {
        bridge::report_error(format!(
            "writeChunk: invalid length {length} for array of {arr_len}"
        ));
        return -1;
    }
    let len = length as usize;
    // None = 数组读取失败（区别于空数组：空数据合法返回 0）。
    let bytes = resolve_default(
        env.with_env(|env| {
            // 只取 data[0..len)：Java 侧复用暂存区时避免整块冗余拷出。
            let mut buf = vec![0u8; len];
            if len > 0 {
                // SAFETY: jbyte 与 u8 同尺寸同布局，仅按位重解释用于 JNI 拷出。
                let region: &mut [jbyte] =
                    unsafe { std::slice::from_raw_parts_mut(buf.as_mut_ptr().cast(), len) };
                data.get_region(env, 0, region)?;
            }
            Ok::<_, Error>(Some(buf))
        }),
        "writeChunk",
        || None,
    );
    let Some(bytes) = bytes else {
        return -1;
    };
    // Vec<u8> → Bytes 为零成本接管分配，无拷贝。
    match bridge::write_chunk(conn as u64, bytes.into()) {
        Ok(n) => n as jint,
        Err(msg) => {
            bridge::report_error(msg);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_readChunk(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    max_bytes: jint,
) -> jbyteArray {
    let max = max_bytes.max(0) as usize;
    let Ok(bytes) = bridge::read_chunk(conn as u64, max) else {
        // read_chunk 内部已上报错误。
        return std::ptr::null_mut();
    };
    resolve_default(
        env.with_env(|env| Ok(env.byte_array_from_slice(&bytes)?.as_raw())),
        "readChunk",
        std::ptr::null_mut,
    )
}

/// [`bridge::read_chunk`] 的直写变体：native 把数据写进调用方提供的
/// 直接缓冲区，避免每次调用分配新 jbyteArray。
///
/// ABI 约定：JNI `GetDirectBufferAddress` 返回传入缓冲区对象自身的基址，
/// 不感知其 position/limit，故从该地址绝对偏移 0 开始写入——写入点由
/// 视图基址决定（如 Netty 在目标偏移处建视图），与 position 无关，调用方
/// 不得依赖 position 定位。返回值：读取字节数；0 = 暂无数据；
/// -1 = 连接不存在/已关闭或参数非法（与 Java 侧声明一致）。
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_readChunkInto(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    buffer: JByteBuffer,
    max_bytes: jint,
) -> jint {
    if max_bytes < 0 {
        bridge::report_error(format!("readChunkInto: invalid max_bytes {max_bytes}"));
        return -1;
    }
    // 非直接缓冲 / 已释放 / null 均为调用方 bug：-1 而非截断。
    let Some(dst) = resolve_default(
        env.with_env(|env| {
            let addr = env.get_direct_buffer_address(&buffer)?;
            let cap = env.get_direct_buffer_capacity(&buffer)?;
            // SAFETY: addr 指向 JVM 直接缓冲区内存且 cap 为其可写字节数；
            // JNI 约定该指针仅在本次原生调用内使用。
            Ok::<_, Error>(Some(unsafe { std::slice::from_raw_parts_mut(addr, cap) }))
        }),
        "readChunkInto",
        || None,
    ) else {
        bridge::report_error("readChunkInto: failed to access direct buffer".to_string());
        return -1;
    };
    let want = (max_bytes as usize).min(dst.len());
    match bridge::read_chunk(conn as u64, want) {
        Ok(bytes) => {
            // read_chunk 保证返回长度 ≤ want ≤ dst.len()。
            let n = bytes.len();
            dst[..n].copy_from_slice(&bytes);
            n as jint
        }
        Err(msg) => {
            bridge::report_error(msg);
            -1
        }
    }
}

#[cfg(test)]
mod tests;
