//! KCP 服务端：acceptor 生命周期与连接接纳（kcp-rs 内建握手）。
//!
//! `KcpUdpStream` 在握手完成后才产出 `(KcpStream, peer)`，accept 阶段与
//! QUIC 对齐：软限预检 + 计数原子兜底，竞态窗口内的超发即弃。

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use kcp::{KcpConfig, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, remove_conn, runtime, servers};
use crate::bridge::server_ops::{register_connection, stop_server};
use crate::bridge::{ServerHandle, TransportEndpoint, try_admit};

/// 启动 KCP 服务端（端口 0 系统分配）。
///
/// socket 经 [`crate::bridge::socket_util`] 统一底座创建；`socket_listen`
/// 内部无网络往返，以带超时的同步等待完成注册后再返回句柄——保证 Java
/// 拿到 id 时 `server_port` 已可查、Ping 可宣告真实端口。
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
    let (stop_tx, stop_rx) = mpsc::channel::<()>(1);
    rt.spawn(async move {
        let panicked = crate::bridge::guarded("kcp accept task", async move {
            server_task(
                server_id,
                port,
                bind,
                max_connections,
                config,
                tx,
                stop_tx,
                stop_rx,
            )
            .await;
        })
        .await;
        if panicked {
            // acceptor 死亡：注销服务端句柄，避免 SERVERS 残留。
            servers().remove(&server_id);
        }
    });

    match rx.recv_timeout(Duration::from_secs(5)) {
        Ok(Ok(_port)) => Ok(server_id),
        Ok(Err(msg)) => Err(msg),
        Err(_) => Err(BridgeError::Other("kcp listener setup timeout".into())),
    }
}

/// acceptor 任务：绑定监听 → 注册句柄 → accept 循环。
#[allow(clippy::too_many_arguments)]
async fn server_task(
    server_id: u64,
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: KcpConfig,
    tx: std::sync::mpsc::Sender<Result<u16, BridgeError>>,
    stop_tx: mpsc::Sender<()>,
    mut stop_rx: mpsc::Receiver<()>,
) {
    let (mut listener, local) = match bind_listener(port, bind, max_connections, &config).await {
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

    accept_loop(
        &mut listener,
        &mut stop_rx,
        server_id,
        max_connections,
        conn_count,
    )
    .await;
    // 循环退出：listener Drop → token 取消、socket 关闭。
    // 正常停止路径 entry 已被 stop_server 移除（此处返回 false 无操作）；
    // accept 错误路径在此兜底注销服务端并关闭既有连接，避免 SERVERS 残留。
    drop(listener);
    let _ = stop_server(server_id);
}

/// 绑定并启动监听。socket 经 socket_util 统一底座（双栈/缓冲/REUSEADDR）。
async fn bind_listener(
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: &KcpConfig,
) -> Result<(KcpUdpStream, SocketAddr), BridgeError> {
    let (socket, _) = crate::bridge::socket_util::bind_server(port, bind).map_err(|source| {
        BridgeError::Bind {
            transport: Transport::Kcp,
            port,
            source,
        }
    })?;
    let socket = nonblocking(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "set_nonblocking",
        source,
    })?;
    let udp = UdpSocket::from_std(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "from_std",
        source,
    })?;
    let local = udp.local_addr().map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "local addr",
        source,
    })?;
    let listener =
        KcpUdpStream::socket_listen(Arc::new(config.clone()), udp, max_connections.max(8), None)
            .map_err(|e| BridgeError::Other(format!("kcp listener: {e}")))?;
    Ok((listener, local))
}

/// accept 循环：停止信号或 accept 失败即退出；新连接经软限接纳后启动数据循环。
async fn accept_loop(
    listener: &mut KcpUdpStream,
    stop_rx: &mut mpsc::Receiver<()>,
    server_id: u64,
    max_connections: usize,
    conn_count: Arc<AtomicUsize>,
) {
    loop {
        let accepted = tokio::select! {
            _ = stop_rx.recv() => None,
            acc = listener.accept() => acc.ok(),
        };
        let Some((stream, peer)) = accepted else {
            break;
        };
        if !admit(&conn_count, max_connections) {
            continue; // 超限：drop 握手成功的流，会话由 session_expire 回收。
        }
        let reg = register_connection(server_id, peer, Arc::clone(&conn_count));
        let conn_id = reg.conn_id;
        tokio::spawn(async move {
            let panicked = crate::bridge::guarded("kcp connection task", async move {
                run_kcp_connection(
                    conn_id,
                    FecStream::new(stream),
                    reg.to_transport_rx,
                    reg.to_java_tx,
                    reg.state,
                    false,
                )
                .await;
            })
            .await;
            if panicked {
                remove_conn(conn_id);
            }
        });
    }
}

/// 软限接纳：快路径预检 + 原子计数兜底（并发窗口超发即回滚拒绝）。
fn admit(conn_count: &AtomicUsize, max: usize) -> bool {
    conn_count.load(Ordering::Relaxed) < max && try_admit(conn_count, max)
}

/// 置非阻塞（socket2 → tokio 前置步骤）。
fn nonblocking(s: std::net::UdpSocket) -> std::io::Result<std::net::UdpSocket> {
    s.set_nonblocking(true)?;
    Ok(s)
}
