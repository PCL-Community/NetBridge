//! KCP 客户端：异步建连，立即返回连接 id（与 QUIC 客户端同构）。
//!
//! 流程镜像 bridge/quic/client.rs：JNI 调用线程只注册占位句柄；DNS 解析、
//! socket 绑定与 KCP 建立全部在 runtime 任务内——任何同步阻塞都会冻结
//! 同一 Netty EventLoop。失败路径置 FAILED、上报并就地自清理。
//!
//! conv 语义：`connect_with_socket` 本地生成随机非零 conv，服务端按首包
//! 接纳（无握手模型）。CONNECTED 延迟到首个入站数据帧（存活判定）；
//! 出站不受门控——否则与服务端"按首包建会话"互等死锁。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use tokio_kcp::KcpStream;

use super::config::{build_config, KcpProfile};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::bridge::{ConnHandle, STATE_CONNECTING};

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
        let stream =
            match KcpStream::connect_with_socket(&build_config(profile), udp, addr).await {
                Ok(s) => s,
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
        // connect_with_socket 纯本地构建：CONNECTED 在读循环收到服务端
        // FRAME_PONG（会话建立应答）时置位——run_kcp_connection 首发的
        // FRAME_PROBE 触发服务端会话创建（无握手模型，免首次写入互等）。
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
