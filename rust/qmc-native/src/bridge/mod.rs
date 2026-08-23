//! bridge 模块：持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//! 提供同步的 server/client/state/read/write 原语（ADR-0001 JNI 桥）。

mod client;
mod connection;
mod registry;
mod server;

pub use client::connect;
pub use connection::{close_connection, connection_state, read_chunk, write_chunk};
pub use registry::{last_error, set_last_error};
pub use server::{accept_connections, server_port, start_server, stop_server};

/// 发往 QUIC 写任务的控制命令。
#[derive(Debug)]
pub enum Command {
    Write(Vec<u8>),
    Close,
}

/// 连接状态（与 Java `QuicConnectionState` 一致）。
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

use std::sync::atomic::{AtomicBool, AtomicU32};
use std::sync::{Arc, Mutex};

use tokio::sync::mpsc;

/// 单条 QUIC 连接的 Java 侧句柄。
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    /// Java 读侧队列 + 未取走的残留字节（防 chunk 被截断丢弃）。
    /// Arc 化：读路径克隆后即可释放 DashMap guard。
    pub to_java: Arc<Mutex<(mpsc::Receiver<Vec<u8>>, Vec<u8>)>>,
    pub to_quic: mpsc::Sender<Command>,
    pub server_id: Option<u64>,
    /// 服务端连接是否已被 Java 通过 acceptConnections 取走。
    pub reported: AtomicBool,
}

/// 注册表中的服务端句柄。
pub struct ServerHandle {
    pub endpoint: quinn::Endpoint,
    pub port: u16,
}
