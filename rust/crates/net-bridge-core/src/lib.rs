//! net-bridge-core: Pure Rust transport core for QUIC and KCP.
#![forbid(unsafe_code)]

pub mod context;
pub mod dataplane;
pub mod error;
pub mod event;
pub mod registry;
pub mod server_ops;
pub mod socket_util;
pub mod transport;

#[cfg(test)]
mod tests;

pub use context::NativeContext;
pub use dataplane::{close_connection, connection_state, read_chunk, write_chunk};
pub use error::BridgeError;
pub use event::{EventSink, NoopEventSink, get_event_sink, set_event_sink};
pub use registry::{conn_remote_addr, report_error};
pub use server_ops::{accept_connections, server_port, stop_server};
pub use transport::TransportKind;

use std::any::Any;
use std::collections::VecDeque;
use std::future::Future;
use std::net::SocketAddr;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::mpsc;

pub const NET_BRIDGE_ABI_VERSION: &str = "0.3.0";

/// 发往传输写任务的通用控制命令。
#[derive(Debug)]
pub enum Command {
    Write(Bytes),
    Close,
}

/// 内部连接状态常量（core 内部值；对外暴露时经 `abi_connection_state` 映射为 ABI 值 1..4）。
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

/// 单条连接的句柄。
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    /// Java 读侧 chunk 队列 + 未取走的残留块。Bytes 共享视图切分零拷贝；
    /// Arc 化：读路径克隆后即可释放 DashMap guard。
    pub to_java: Arc<Mutex<(mpsc::Receiver<Bytes>, VecDeque<Bytes>)>>,
    pub to_transport: mpsc::Sender<Command>,
    pub server_id: Option<u64>,
    /// 服务端连接的每实例活跃计数（客户端连接为 None）；remove 时递减。
    pub server_count: Option<Arc<AtomicUsize>>,
    /// 连接期即可写入：KCP 客户端握手未完成时允许写入（命令先入 channel，
    /// 握手完成后立即下发；kcp-rs 内建握手，不依赖首帧判定）。QUIC 客户端为 false。
    pub early_write: bool,
    /// 服务端连接是否已被 Java 通过 acceptConnections 取走。
    pub reported: AtomicBool,
    /// 服务端连接真实对端地址（Java 侧 ban/限速等 IP 管控）；
    /// 客户端连接在 DNS 解析前注册，此处为 None。
    pub remote_addr: Option<SocketAddr>,
}

impl ConnHandle {
    /// 构造句柄：读侧 channel 与空残留队列包装进共享锁。
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        server_id: Option<u64>,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
        reported: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            to_java: Arc::new(Mutex::new((to_java_rx, VecDeque::new()))),
            to_transport,
            server_id,
            server_count,
            early_write,
            reported: AtomicBool::new(reported),
            remote_addr,
        }
    }
}

/// 注册表中的服务端句柄。endpoint 为多态传输端点；计数与上限为本实例私有。
pub struct ServerHandle {
    pub endpoint: TransportEndpoint,
    pub port: u16,
    /// 本实例活跃连接上限（accept 阶段超限即丢弃）。
    pub max_connections: usize,
    /// 本实例活跃连接数（独立于其他 server 实例）。
    pub conn_count: Arc<AtomicUsize>,
}

/// 传输端点：`stop_server` 按此分支关闭。
pub enum TransportEndpoint {
    Quic(quinn::Endpoint),
    /// KCP 停止触发器：发送即令 accept 任务退出并 Drop listener
    /// （中止任务、关闭 socket）。listener 本体留在 accept 任务内。
    Kcp(tokio::sync::mpsc::Sender<()>),
}

/// 启动指定传输的服务端 acceptor。`port` 0 表示系统分配；
/// `profile` 仅对 KCP 有意义（QUIC 忽略）。
pub fn start_server(
    kind: TransportKind,
    port: u16,
    max_connections: usize,
    bind: Option<std::net::IpAddr>,
    profile: transport::kcp::config::KcpProfile,
) -> Result<u64, BridgeError> {
    match kind {
        TransportKind::Quic => transport::quic::start_server(port, max_connections, bind),
        TransportKind::Kcp => transport::kcp::start_server(port, max_connections, bind, profile),
    }
}

/// 经指定传输发起客户端连接（异步建立，立即返回连接 id）。
pub fn connect(
    kind: TransportKind,
    host: &str,
    port: u16,
    profile: transport::kcp::config::KcpProfile,
) -> Result<u64, BridgeError> {
    match kind {
        TransportKind::Quic => transport::quic::connect(host, port),
        TransportKind::Kcp => transport::kcp::connect(host, port, profile),
    }
}

/// 尝试把一条新连接计入服务端实例活跃数；超限回滚并拒绝。
pub(crate) fn try_admit(count: &AtomicUsize, max: usize) -> bool {
    let prev = count.fetch_add(1, Ordering::Relaxed);
    if prev >= max {
        count.fetch_sub(1, Ordering::Relaxed);
        return false;
    }
    true
}

/// 任务 panic 防护：catch_unwind 包裹 future，panic 记 stderr 日志并返回 true。
/// 调用方在返回 true 时执行连接/服务端句柄清理——panic 任务不能留下注册表残留。
pub(crate) async fn guarded<F: Future<Output = ()>>(what: &str, fut: F) -> bool {
    let result = catch_unwind(AssertUnwindSafe(move || fut));
    match result {
        Ok(f) => {
            f.await;
            false
        }
        Err(payload) => {
            report_error(format!("{what} panicked: {}", describe_panic(&payload)));
            true
        }
    }
}

/// 提取 panic payload 的可读信息；非字符串 payload 记占位。
fn describe_panic(payload: &Box<dyn Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}
