//! 服务端句柄原语：实例级注册表操作与连接登记（传输无关）。
//!
//! QUIC 与 KCP 的 acceptor 都把运行实例注册进
//! [`crate::bridge::registry::servers`] 并共享这里的分派原语：
//! 端口查询（[`server_port`]）、停止（[`stop_server`]，按
//! [`TransportEndpoint`] 分支关闭端点）、上报新连接（[`accept_connections`]）。
//!
//! [`register_connection`] 为两种传输共用的连接登记器：建好命令/数据通道与
//! 状态原子后插入注册表（QUIC 在握手完成、KCP 在会话创建时调用），
//! acceptor 拿到 [`Registered`] 即可启动各自的数据循环。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use crate::bridge::registry::{allocate_id, conns, servers};
use crate::bridge::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, TransportEndpoint};

/// 查询服务端实际绑定端口。
pub fn server_port(server: u64) -> Option<u16> {
    servers().get(&server).map(|h| h.port)
}

/// 停止服务端并关闭其全部连接。
pub fn stop_server(server: u64) -> bool {
    let Some((_, handle)) = servers().remove(&server) else {
        return false;
    };
    match handle.endpoint {
        TransportEndpoint::Quic(endpoint) => endpoint.close(0u32.into(), b"net-bridge stop"),
        // 触发 accept 任务退出：任务内 Drop listener（中止任务、关 socket）；
        // 既有会话由 session_expire 与各连接任务收尾清理。
        TransportEndpoint::Kcp(stop_tx) => {
            let _ = stop_tx.try_send(());
        }
    }
    // 先收集后修改：不在 DashMap 迭代期间变更同一 map。
    let conn_ids: Vec<u64> = conns()
        .iter()
        .filter(|e| e.server_id == Some(server))
        .map(|e| *e.key())
        .collect();
    for id in conn_ids {
        if let Some(h) = conns().get_mut(&id) {
            h.state.store(STATE_CLOSED, Ordering::SeqCst);
            let _ = h.to_transport.try_send(Command::Close);
        }
    }
    true
}

/// 取回服务端尚未上报的新连接 id 列表。
pub fn accept_connections(server: u64) -> Vec<u64> {
    let mut out = Vec::new();
    for e in conns().iter_mut() {
        if e.server_id == Some(server) && !e.reported.swap(true, Ordering::SeqCst) {
            out.push(*e.key());
        }
    }
    out
}

/// 会话建立完成后立即注册连接句柄（不等待首个流数据）。
/// 供 QUIC 与 KCP acceptor 共用（KCP 侧会话建立同样意味着对端可达）。
pub struct Registered {
    pub conn_id: u64,
    pub to_transport_rx: mpsc::Receiver<Command>,
    pub to_java_tx: mpsc::Sender<Bytes>,
    pub to_transport_tx: mpsc::Sender<Command>,
    pub state: Arc<AtomicU32>,
}

pub(crate) fn register_connection(
    server_id: u64,
    peer: SocketAddr,
    conn_count: Arc<AtomicUsize>,
) -> Registered {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTED));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            Some(server_id),
            Some(conn_count),
            false,
            false,
            Some(peer),
        ),
    );
    Registered {
        conn_id,
        to_transport_rx,
        to_java_tx,
        to_transport_tx,
        state,
    }
}
