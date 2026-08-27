//! net-bridge-native：QUIC / KCP 传输的 JNI 桥。
//!
//! 架构：
//! - [`bridge`] 模块持有连接注册表与批量字节队列，按 [`transport::TransportKind`]
//!   分派 QUIC（quinn-plaintext）与 KCP（kcp-rs + FEC + smux）实现；
//! - JNI 导出把这些原语暴露给 Java（`top.tangge233.netbridge.jni.NativeBridge`），
//!   每条连接 = 一个双向字节流，承载整个 MC 会话。
//!
//! JNI 层（jni 0.22）：导出函数收 FFI 安全的 [`EnvUnowned`]，需要访问 JNI
//! 时经 `with_env` 升级为 `&mut Env`；闭包被 catch_unwind 包裹，panic 不会
//! 穿越原生方法边界 abort 宿主进程。错误统一记 stderr 日志并返回默认值。
//!
//! 反向通知：数据到达时由 tokio 线程回调 Java `NativeBridge.onDataAvailable`，
//! 仅作唤醒信号（实际读取在 Java 侧 EventLoop 上执行），消除 Java 侧轮询
//! 等待。回调未注册（单测/旧流程）时静默跳过，Java 轮询兜底仍在。

pub mod bridge;
pub mod transport;

use std::sync::OnceLock;

use jni::errors::Error;
use jni::ids::JStaticMethodID;
use jni::objects::{Global, JByteArray, JByteBuffer, JClass, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jbyte, jbyteArray, jint, jlong, jlongArray, jstring};
use jni::{Env, EnvOutcome, EnvUnowned, JavaVM, Outcome, jni_sig, jni_str};

use bridge::error::BridgeError;
use transport::TransportKind;

pub const NET_BRIDGE_ABI_VERSION: &str = "0.3.0";

/// 反向通知回调目标：NativeBridge 类 GlobalRef + `onDataAvailable(J)V` 方法 id。
/// 两类句柄均为 JNI 规范允许跨线程使用的不透明引用（Send+Sync）。
static NOTIFY: OnceLock<NotifyTarget> = OnceLock::new();

struct NotifyTarget {
    class: Global<JClass<'static>>,
    method: JStaticMethodID,
}

/// 数据到达通知（tokio 线程调用）：回调 Java `onDataAvailable(connId)`。
/// 未注册回调（单测/旧流程）或 attach 失败时静默跳过——Java 轮询兜底。
pub(crate) fn notify_data(conn_id: u64) {
    let Some(target) = NOTIFY.get() else {
        return;
    };
    let Ok(vm) = JavaVM::singleton() else {
        return;
    };
    let _ = vm.attach_current_thread(|env| {
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

/// 统一解析 `with_env` 结果：Err/panic 记日志并返回调用方指定的默认值
/// （错误返回 -1/null 的 ABI 契约，细节见 net-bridge-native 日志）。
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

/// 解析传输类别标签；非法值上报并拒绝。
fn resolve_kind(kind: jint, context: &str) -> Option<TransportKind> {
    match TransportKind::from_jint(kind) {
        Some(k) => Some(k),
        None => {
            bridge::report_error(format!("{context}: invalid transport kind {kind}"));
            None
        }
    }
}

/// 解析 KCP profile 字符串：null/空 = 默认 balance；非法值告警后回退默认
/// （配置错误不致命）。
fn resolve_profile(profile: Option<String>) -> bridge::kcp::config::KcpProfile {
    use bridge::kcp::config::KcpProfile;
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
            bridge::report_error(format!(
                "invalid kcp profile '{trimmed}', falling back to balance"
            ));
            KcpProfile::default()
        }
    }
}

/// 读取可选字符串参数（null → None）。
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

/// 连接不存在/已关闭属正常收尾（-1 已表达语义），不刷 stderr；其余错误照常上报。
fn report_conn_err(err: &BridgeError) {
    if !matches!(
        err,
        BridgeError::NoSuchConnection | BridgeError::ConnectionClosed
    ) {
        bridge::report_error(err.message());
    }
}

/// 把数组前 `len` 字节拷入新 Vec：写方向的边界拷贝，只拷有效区
/// （Java 侧复用暂存区时避免整块冗余拷出）。
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

/// 取直接缓冲区的可写区（从对象基址绝对偏移 0 起）；容量为 0 时返回空
/// 切片——JNI 允许空直接缓冲返回空指针，from_raw_parts_mut(null, 0) 属 UB。
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_version(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    resolve_default(
        env.with_env(|env| Ok(env.new_string(NET_BRIDGE_ABI_VERSION)?.into_raw())),
        "version",
        std::ptr::null_mut,
    )
}

/// 注册 Rust→Java 数据到达回调（Java 侧初始化时调用一次；重复调用幂等）。
/// 保存 NativeBridge 类 GlobalRef 与 `onDataAvailable(J)V` 静态方法 id。
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_registerNotifyCallback(
    mut env: EnvUnowned,
    class: JClass,
) {
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_startServer(
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
        bridge::report_error(format!("invalid listen port {port}"));
        return -1;
    };
    if max_connections < 1 {
        bridge::report_error(format!("invalid max connections {max_connections}"));
        return -1;
    }
    // bind 为空/null 时用默认地址族（[::] 双栈 → 0.0.0.0），否则仅绑定指定 IP。
    let host = optional_string(&mut env, &host, "startServer").unwrap_or_default();
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
    let kcp_profile = resolve_profile(optional_string(&mut env, &profile, "startServer"));
    match bridge::start_server(kind, port, max_connections as usize, bind, kcp_profile) {
        Ok(id) => id as jlong,
        Err(err) => {
            bridge::report_error(err.message());
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_serverPort(
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_remoteAddress(
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_acceptConnections(
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_stopServer(
    _env: EnvUnowned,
    _class: JClass,
    server: jlong,
) -> jboolean {
    bridge::stop_server(server as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_connect(
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
    if port == 0 {
        // 「系统分配」仅服务端有意义；客户端 connect 端口 0 直接拒绝。
        bridge::report_error("invalid remote port 0".to_string());
        return -1;
    }
    let kcp_profile = resolve_profile(optional_string(&mut env, &profile, "connect"));
    match bridge::connect(kind, &host, port, kcp_profile) {
        Ok(id) => id as jlong,
        Err(err) => {
            report_conn_err(&err);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_connectionState(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jint {
    bridge::connection_state(conn as u64)
        .map(|s| s as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_closeConnection(
    _env: EnvUnowned,
    _class: JClass,
    conn: jlong,
) -> jboolean {
    bridge::close_connection(conn as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_writeChunk(
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
        env.with_env(|env| copy_region(env, &data, len).map(Some)),
        "writeChunk",
        || None,
    );
    let Some(bytes) = bytes else {
        return -1;
    };
    // Vec<u8> → Bytes 为零成本接管分配，无拷贝。
    match bridge::write_chunk(conn as u64, bytes.into()) {
        Ok(n) => n as jint,
        Err(err) => {
            report_conn_err(&err);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_readChunk(
    mut env: EnvUnowned,
    _class: JClass,
    conn: jlong,
    max_bytes: jint,
) -> jbyteArray {
    let max = max_bytes.max(0) as usize;
    let Ok(bytes) = bridge::read_chunk(conn as u64, max) else {
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
pub extern "system" fn Java_top_tangge233_netbridge_jni_NativeBridge_readChunkInto(
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
        env.with_env(|env| direct_slice(env, &buffer).map(Some)),
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
        Err(err) => {
            bridge::report_error(err.message());
            -1
        }
    }
}
