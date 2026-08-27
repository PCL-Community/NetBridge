//! KCP 客户端：异步建连（kcp-rs 内建握手），立即返回连接 id（与 QUIC 客户端同构）。
//!
//! 流程镜像 bridge/quic/client.rs：JNI 调用线程只注册占位句柄；DNS 解析、
//! socket 绑定与 KCP 握手全部在 runtime 任务内——任何同步阻塞都会冻结
//! 同一 Netty EventLoop。失败路径置 FAILED、上报并就地自清理。
//!
//! 握手模型（kcp-rs）：`connect` 发 SYN（含随机会话 id），服务端按会话 id
//! 接纳并回确认；`KcpUdpStream::socket_connect` 返回时 CONNECTED 置位——
//! 双向可达已有证明，不再需要 FRAME_PROBE/FRAME_PONG 探测路径。
//! 出站不受门控：Java 在握手期写入命令先入 channel，握手完成后立即下发。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use kcp::KcpUdpStream;
use tokio::sync::mpsc;

use super::config::{build_config, KcpProfile};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::bridge::{ConnHandle, STATE_CONNECTED, STATE_CONNECTING};

/// 发起 KCP 连接（异步建立，立即返回连接 id）。
pub fn connect(host: &str, port: u16, profile: KcpProfile) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<crate::bridge::Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            None,
            None,
            true,
            true,
            None,
        ),
    );

    let host = host.to_string();
    rt.spawn(async move {
        let addr = match tokio::net::lookup_host((host.as_str(), port)).await {
            Ok(mut addrs) => match addrs.next() {
                Some(addr) => addr,
                None => {
                    fail(
                        conn_id,
                        &state,
                        BridgeError::Dns {
                            host: host.clone(),
                            port,
                            source: std::io::Error::new(
                                std::io::ErrorKind::NotFound,
                                "no address resolved",
                            ),
                        },
                    );
                    return;
                }
            },
            Err(source) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Dns { host: host.clone(), port, source },
                );
                return;
            }
        };
        let socket = match crate::bridge::socket_util::bind_client(addr.is_ipv6()) {
            Ok(s) => s,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Setup { transport: Transport::Kcp, stage: "client bind", source: e },
                );
                return;
            }
        };
        let udp = match socket.set_nonblocking(true).and_then(|()| tokio::net::UdpSocket::from_std(socket)) {
            Ok(u) => u,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Setup { transport: Transport::Kcp, stage: "from_std", source: e },
                );
                return;
            }
        };
        let config = Arc::new(build_config(profile));
        // SYN 握手：服务端确认后才返回（connect_timeout=8s，黑洞尽早 FAILED）。
        let stream = match KcpUdpStream::socket_connect(config, addr, udp).await {
            Ok((stream, _)) => stream,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Connect {
                        transport: Transport::Kcp,
                        addr,
                        source: Box::new(e),
                    },
                );
                return;
            }
        };
        // 握手完成即双向可达：CONNECTED，先于首个数据帧（相对旧探测帧模型）。
        state.store(STATE_CONNECTED, Ordering::SeqCst);
        run_kcp_connection(
            conn_id,
            FecStream::new(stream),
            to_transport_rx,
            to_java_tx,
            state,
            true,
        )
        .await;
    });
    Ok(conn_id)
}

/// 标记失败、上报日志并就地移除注册表条目（自清理，无泄漏窗口）。
fn fail(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) {
    state.store(crate::bridge::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
}
