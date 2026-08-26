//! KCP 服务端：acceptor 生命周期与连接接纳（无握手模型）。
//!
//! 接纳语义（tokio_kcp listener）：未知 `(peer_addr, conv)` 组合即新连接
//! 候选——`max_connection` 软限超限直接丢弃 KcpStream（会话随 session_expire
//! 回收）。与 QUIC 的 accept 阶段拒绝语义对齐：快路径预检 + 计数原子
//! 兜底，竞态窗口内的超发即弃。

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio_kcp::KcpListener;

use super::config::{build_config, KcpProfile};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, runtime, servers};
use crate::bridge::server_ops::register_connection;
use crate::bridge::{try_admit, ServerHandle, TransportEndpoint};

/// 启动 KCP 服务端（端口 0 系统分配）。
///
/// socket 经 [`crate::bridge::socket_util`] 统一底座创建（双栈/缓冲/
/// REUSEADDR）。`from_socket` 内部无网络往返（仅建 channel + 起任务），
/// 以带超时的同步等待完成注册后再返回句柄——保证 Java 拿到 id 时
/// `server_port` 已可查、Ping 可宣告真实端口。
pub fn start_server(
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
    profile: KcpProfile,
) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let config = build_config(profile);
    let server_id = allocate_id();

    let (tx, rx) = std::sync::mpsc::channel::<Result<u16, BridgeError>>();
    let (stop_tx, mut stop_rx) = tokio::sync::mpsc::channel::<()>(1);
    let rt_accept = Arc::clone(rt);
    rt.spawn(async move {
        let result = async {
            let (socket, _) = crate::bridge::socket_util::bind_server(port, bind)
                .map_err(|source| BridgeError::Bind { transport: Transport::Kcp, port, source })?;
            let socket = nonblocking(socket).map_err(|source| {
                BridgeError::Setup { transport: Transport::Kcp, stage: "set_nonblocking", source }
            })?;
            let udp = tokio::net::UdpSocket::from_std(socket).map_err(|source| {
                BridgeError::Setup { transport: Transport::Kcp, stage: "from_std", source }
            })?;
            let listener =
                KcpListener::from_socket(config, udp).await.map_err(|e| {
                    BridgeError::Other(format!("kcp listener: {e}"))
                })?;
            let local = listener.local_addr().map_err(|source| BridgeError::Setup {
                transport: Transport::Kcp,
                stage: "local addr",
                source,
            })?;
            Ok::<_, BridgeError>((listener, local))
        }
        .await;

        let (mut listener, local) = match result {
            Ok(pair) => pair,
            Err(msg) => {
                let _ = tx.send(Err(msg));
                return;
            }
        };
        let conn_count = Arc::new(AtomicUsize::new(0));
        servers().insert(
            server_id,
            ServerHandle {
                endpoint: TransportEndpoint::Kcp(stop_tx),
                port: local.port(),
                max_connections,
                conn_count: Arc::clone(&conn_count),
            },
        );
        let _ = tx.send(Ok(local.port()));

        // Accept 循环：stop_server 发停止信号后 Drop listener（任务收尾、
        // socket 关闭）。
        loop {
            let accepted = tokio::select! {
                _ = stop_rx.recv() => break,
                acc = listener.accept() => match acc {
                    Ok(pair) => pair,
                    Err(_) => break,
                },
            };
            let (stream, peer) = accepted;
            // 软限快路径：超限直接丢弃（会话由 session_expire 回收）。
            if conn_count.load(Ordering::Relaxed) >= max_connections {
                drop(stream);
                continue;
            }
            // 并发窗口兜底：计数超限回滚后放弃本连接。
            if !try_admit(&conn_count, max_connections) {
                drop(stream);
                continue;
            }
            let reg = register_connection(server_id, peer, Arc::clone(&conn_count));
            rt_accept.spawn(run_kcp_connection(
                reg.conn_id,
                FecStream::new(stream),
                reg.to_transport_rx,
                reg.to_java_tx,
                reg.state,
                false,
            ));
        }
    });

    match rx.recv_timeout(Duration::from_secs(5)) {
        Ok(Ok(_port)) => Ok(server_id),
        Ok(Err(msg)) => Err(msg),
        Err(_) => Err(BridgeError::Other("kcp listener setup timeout".into())),
    }
}

/// 置非阻塞（socket2 → tokio 前置步骤）。
fn nonblocking(s: std::net::UdpSocket) -> std::io::Result<std::net::UdpSocket> {
    s.set_nonblocking(true)?;
    Ok(s)
}
