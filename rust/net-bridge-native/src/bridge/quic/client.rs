//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use super::connection::run_connection;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::bridge::{ConnHandle, STATE_CONNECTING};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
///
/// DNS 解析、UDP 绑定与握手全部在 runtime 任务内进行：本函数由 JNI 从
/// Netty 事件循环线程调用，任何同步阻塞（DNS 可达数秒）都会冻结同一
/// EventLoop 上的全部 channel。失败路径置 FAILED、上报日志并就地移除
/// 注册表条目（不依赖 Java close 兜底，消除 channel 早亡时的泄漏）。
pub fn connect(host: &str, port: u16) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        // 日志由 JNI 导出层对 Err 统一上报，此处不重复。
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
            false,
            true,
            None,
        ),
    );

    let host = host.to_string();
    rt.spawn(async move {
        // 先解析目标地址，按其地址族选择本地绑定（IPv4 目标绑 0.0.0.0，
        // IPv6 绑 [::]，否则 quinn 会报 invalid remote address）。
        let addr = match tokio::net::lookup_host((host.as_str(), port)).await {
            Ok(mut addrs) => match addrs.next() {
                Some(addr) => addr,
                None => {
                    let e = BridgeError::Dns {
                        host: host.clone(),
                        port,
                        source: io_no_address(),
                    };
                    fail(conn_id, &state, e);
                    return;
                }
            },
            Err(source) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Dns {
                        host: host.clone(),
                        port,
                        source,
                    },
                );
                return;
            }
        };
        // socket2 统一底座：按目标地址族绑定对应未指定地址，显式缓冲区
        // 设置；v6 目标尝试双栈（失败降级 v6-only，见助手实现）。
        let socket = match crate::bridge::socket_util::bind_client(addr.is_ipv6()) {
            Ok(s) => s,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Setup {
                        transport: Transport::Quic,
                        stage: "client bind",
                        source: e,
                    },
                );
                return;
            }
        };
        let mut endpoint = match quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            None,
            socket,
            Arc::new(quinn::TokioRuntime),
        ) {
            Ok(ep) => ep,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Setup {
                        transport: Transport::Quic,
                        stage: "endpoint",
                        source: e,
                    },
                );
                return;
            }
        };
        endpoint.set_default_client_config(quinn_plaintext::client_config());

        let conn = match endpoint.connect(addr, "plaintext.test") {
            Ok(connecting) => match connecting.await {
                Ok(conn) => conn,
                Err(e) => {
                    fail(
                        conn_id,
                        &state,
                        BridgeError::Connect {
                            transport: Transport::Quic,
                            addr,
                            source: Box::new(e),
                        },
                    );
                    return;
                }
            },
            Err(e) => {
                // ConnectError → io::Error：quinn 提供 From 实现。
                fail(
                    conn_id,
                    &state,
                    BridgeError::Connect {
                        transport: Transport::Quic,
                        addr,
                        source: Box::new(e),
                    },
                );
                return;
            }
        };
        let (send, recv) = match conn.open_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                fail(
                    conn_id,
                    &state,
                    BridgeError::Connect {
                        transport: Transport::Quic,
                        addr,
                        source: Box::new(e),
                    },
                );
                return;
            }
        };
        state.store(crate::bridge::STATE_CONNECTED, Ordering::SeqCst);
        run_connection(
            conn_id,
            conn,
            send,
            recv,
            to_transport_rx,
            to_java_tx,
            to_transport_tx,
            state,
        )
        .await;
    });
    Ok(conn_id)
}

/// "解析成功但无地址"的合成错误（lookup_host 空迭代无独立错误类型）。
fn io_no_address() -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::NotFound, "no address resolved")
}

/// 标记连接失败、上报日志并就地移除注册表条目（自清理，无泄漏窗口）。
/// Java poll 随后观察到 UNKNOWN 并按连接不存在收尾。
fn fail(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) {
    state.store(crate::bridge::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
}
