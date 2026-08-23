//! bridge 模块：持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//! 提供同步的 server/client/state/read/write 原语（ADR-0001 JNI 桥）。

mod client;
mod connection;
mod registry;
mod server;

pub use client::connect;
pub use connection::{close_connection, connection_state, read_chunk, write_chunk};
pub use registry::{conn_remote_addr, report_error};
pub use server::{accept_connections, server_port, start_server, stop_server};

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::mpsc;

/// 发往 QUIC 写任务的控制命令。
#[derive(Debug)]
pub enum Command {
    Write(Bytes),
    Close,
}

/// 连接状态（与 Java `QuicConnectionState` 一致）。
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

/// 单条 QUIC 连接的 Java 侧句柄。
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    /// Java 读侧 chunk 队列 + 未取走的残留块。Bytes 共享视图切分零拷贝；
    /// Arc 化：读路径克隆后即可释放 DashMap guard。
    pub to_java: Arc<Mutex<(mpsc::Receiver<Bytes>, VecDeque<Bytes>)>>,
    pub to_quic: mpsc::Sender<Command>,
    pub server_id: Option<u64>,
    /// 服务端连接是否已被 Java 通过 acceptConnections 取走。
    pub reported: AtomicBool,
    /// 服务端连接真实对端地址（Java 侧 ban/限速等 IP 管控）；
    /// 客户端连接在 DNS 解析前注册，此处为 None。
    pub remote_addr: Option<SocketAddr>,
}

impl ConnHandle {
    /// 构造句柄：读侧 channel 与空残留队列包装进共享锁。
    pub fn new(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_quic: mpsc::Sender<Command>,
        server_id: Option<u64>,
        reported: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            to_java: Arc::new(Mutex::new((to_java_rx, VecDeque::new()))),
            to_quic,
            server_id,
            reported: AtomicBool::new(reported),
            remote_addr,
        }
    }
}

/// 注册表中的服务端句柄。
pub struct ServerHandle {
    pub endpoint: quinn::Endpoint,
    pub port: u16,
}
