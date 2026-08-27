//! KCP 客户端：异步建连（kcp-rs 内建 SYN 握手），立即返回连接 id。
//!
//! 流程镜像 QUIC 客户端：JNI 线程只注册占位句柄，DNS 解析、socket 绑定与
//! KCP 握手全部在 runtime 任务内——任何同步阻塞都会冻结同一 Netty
//! EventLoop。失败路径置 FAILED、上报并就地自清理。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use kcp::{KcpStream, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::bridge::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

/// 发起 KCP 连接（异步建立，立即返回连接 id）。
pub fn connect(host: &str, port: u16, profile: KcpProfile) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let (conn_id, state, to_java_tx, to_transport_rx) = register_client();
    let host = host.to_string();
    rt.spawn(async move {
        connect_task(
            conn_id,
            state,
            host,
            port,
            profile,
            to_transport_rx,
            to_java_tx,
        )
        .await;
    });
    Ok(conn_id)
}

/// 注册占位句柄：编造通道与状态原子并插入注册表，返回建连任务所需各端。
fn register_client() -> (
    u64,
    Arc<AtomicU32>,
    mpsc::Sender<Bytes>,
    mpsc::Receiver<Command>,
) {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            None,
            None,
            true,
            true,
            None,
        ),
    );
    (conn_id, state, to_java_tx, to_transport_rx)
}

/// 建连任务链：解析 → 绑定 → 握手 → CONNECTED → 数据循环。
async fn connect_task(
    conn_id: u64,
    state: Arc<AtomicU32>,
    host: String,
    port: u16,
    profile: KcpProfile,
    to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
) {
    let Some(addr) = resolve_host(conn_id, &state, &host, port).await else {
        return;
    };
    let Some(udp) = client_udp(conn_id, &state, addr) else {
        return;
    };
    let Some(stream) = establish_kcp(conn_id, &state, addr, udp, profile).await else {
        return;
    };
    // 握手完成即双向可达（相对旧探测帧模型：无需首个数据帧）。
    // 外部已置 CLOSED（watchdog 超时收口）时不复活；run_kcp_connection
    // 见 CLOSED 即收尾清理，避免向已关闭的 Java 侧通道续写。
    if state.load(Ordering::SeqCst) != STATE_CLOSED {
        state.store(STATE_CONNECTED, Ordering::SeqCst);
    }
    run_kcp_connection(
        conn_id,
        FecStream::new(stream),
        to_transport_rx,
        to_java_tx,
        state,
        true,
    )
    .await;
}

/// DNS 解析；失败经 `fail` 收尾（上报 + FAILED + 登出）并返回 None。
async fn resolve_host(
    conn_id: u64,
    state: &Arc<AtomicU32>,
    host: &str,
    port: u16,
) -> Option<SocketAddr> {
    match tokio::net::lookup_host((host, port)).await {
        Ok(mut addrs) => addrs.next().or_else(|| {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source: std::io::Error::new(
                        std::io::ErrorKind::NotFound,
                        "no address resolved",
                    ),
                },
            );
            None
        }),
        Err(source) => {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source,
                },
            );
            None
        }
    }
}

/// 客户端 UDP socket 底座：按地址族绑定 + 置非阻塞。
fn client_udp(conn_id: u64, state: &Arc<AtomicU32>, addr: SocketAddr) -> Option<UdpSocket> {
    let socket = match crate::bridge::socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "client bind",
                    source: e,
                },
            );
            return None;
        }
    };
    match socket
        .set_nonblocking(true)
        .and_then(|()| UdpSocket::from_std(socket))
    {
        Ok(udp) => Some(udp),
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "from_std",
                    source: e,
                },
            );
            None
        }
    }
}

/// KCP SYN 握手；等待服务端确认直至 `connect_timeout`（8s）或失败。
async fn establish_kcp(
    conn_id: u64,
    state: &Arc<AtomicU32>,
    addr: SocketAddr,
    udp: UdpSocket,
    profile: KcpProfile,
) -> Option<KcpStream> {
    let config = Arc::new(build_config(profile));
    match KcpUdpStream::socket_connect(config, addr, udp).await {
        Ok((stream, _)) => Some(stream),
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Connect {
                    transport: Transport::Kcp,
                    addr,
                    source: Box::new(e),
                },
            );
            None
        }
    }
}

/// 失败统一收尾：置 FAILED、上报日志并移除注册表条目（自清理，无泄漏窗口）。
fn fail(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) {
    state.store(crate::bridge::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
}
