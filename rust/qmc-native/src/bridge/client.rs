//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

use bytes::Bytes;
use tokio::sync::mpsc;

use super::connection::run_connection;
use super::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use super::{ConnHandle, STATE_CONNECTING};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
///
/// DNS 解析、UDP 绑定与握手全部在 runtime 任务内进行：本函数由 JNI 从
/// Netty 事件循环线程调用，任何同步阻塞（DNS 可达数秒）都会冻结同一
/// EventLoop 上的全部 channel。失败路径置 FAILED、上报日志并就地移除
/// 注册表条目（不依赖 Java close 兜底，消除 channel 早亡时的泄漏）。
pub fn connect(host: &str, port: u16) -> Result<u64, String> {
    let rt = runtime();
    let (to_quic_tx, to_quic_rx) = mpsc::channel::<super::Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(state.clone(), to_java_rx, to_quic_tx.clone(), None, true),
    );

    let host = host.to_string();
    rt.spawn(async move {
        // 先解析目标地址，按其地址族选择本地绑定（IPv4 目标绑 0.0.0.0，
        // IPv6 绑 [::]，否则 quinn 会报 invalid remote address）。
        let addr = match tokio::net::lookup_host((host.as_str(), port)).await {
            Ok(mut addrs) => match addrs.next() {
                Some(addr) => addr,
                None => {
                    fail(conn_id, &state, format!("dns resolve failed: no address for {host}:{port}"));
                    return;
                }
            },
            Err(e) => {
                fail(conn_id, &state, format!("dns resolve failed: {host}:{port}: {e}"));
                return;
            }
        };
        let bind_addr: SocketAddr = if addr.is_ipv6() {
            "[::]:0".parse().unwrap()
        } else {
            "0.0.0.0:0".parse().unwrap()
        };
        let mut endpoint = match quinn::Endpoint::client(bind_addr) {
            Ok(ep) => ep,
            Err(e) => {
                fail(conn_id, &state, format!("udp client: {e}"));
                return;
            }
        };
        endpoint.set_default_client_config(quinn_plaintext::client_config());

        let conn = match endpoint.connect(addr, "plaintext.test") {
            Ok(connecting) => match connecting.await {
                Ok(conn) => conn,
                Err(e) => {
                    fail(conn_id, &state, format!("quic handshake to {addr}: {e}"));
                    return;
                }
            },
            Err(e) => {
                fail(conn_id, &state, format!("quic connect to {addr}: {e}"));
                return;
            }
        };
        let (send, recv) = match conn.open_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                fail(conn_id, &state, format!("open_bi to {addr}: {e}"));
                return;
            }
        };
        state.store(super::STATE_CONNECTED, Ordering::SeqCst);
        run_connection(
            conn_id, conn, send, recv, to_quic_rx, to_java_tx, to_quic_tx, state,
        )
        .await;
    });
    Ok(conn_id)
}

/// 标记连接失败、上报日志并就地移除注册表条目（自清理，无泄漏窗口）。
/// Java poll 随后观察到 UNKNOWN 并按连接不存在收尾。
fn fail(conn_id: u64, state: &Arc<AtomicU32>, msg: String) {
    state.store(super::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(msg);
}
