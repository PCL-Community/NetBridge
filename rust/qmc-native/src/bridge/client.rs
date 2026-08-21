//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use tokio::sync::mpsc;

use super::connection::run_connection;
use super::registry::{allocate_id, lock_registry, runtime};
use super::{ConnHandle, STATE_CONNECTING};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
///
/// DNS 解析、UDP 绑定与握手全部在 runtime 任务内进行：本函数由 JNI 从
/// Netty 事件循环线程调用，任何同步阻塞（DNS 可达数秒）都会冻结同一
/// EventLoop 上的全部 channel。失败路径置 FAILED 后返回，注册表条目由
/// close_connection 兜底清理。
pub fn connect(host: &str, port: u16) -> Result<u64, String> {
    let rt = runtime();
    let (to_quic_tx, to_quic_rx) = mpsc::channel::<super::Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Vec<u8>>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = {
        let mut reg = lock_registry();
        let id = allocate_id(&mut reg);
        reg.conns.insert(
            id,
            ConnHandle {
                state: state.clone(),
                to_java: Mutex::new((to_java_rx, Vec::new())),
                to_quic: to_quic_tx.clone(),
                server_id: None,
                reported: std::sync::atomic::AtomicBool::new(true),
            },
        );
        id
    };

    let host = host.to_string();
    rt.spawn(async move {
        // 先解析目标地址，按其地址族选择本地绑定（IPv4 目标绑 0.0.0.0，
        // IPv6 绑 [::]，否则 quinn 会报 invalid remote address）。
        let addr = match tokio::net::lookup_host((host.as_str(), port)).await {
            Ok(mut addrs) => match addrs.next() {
                Some(addr) => addr,
                None => {
                    fail(&state, format!("dns resolve failed: no address for {host}:{port}"));
                    return;
                }
            },
            Err(e) => {
                fail(&state, format!("dns resolve failed: {host}:{port}: {e}"));
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
                fail(&state, format!("udp client: {e}"));
                return;
            }
        };
        endpoint.set_default_client_config(quinn_plaintext::client_config());

        let conn = match endpoint.connect(addr, "plaintext.test") {
            Ok(connecting) => match connecting.await {
                Ok(conn) => conn,
                Err(e) => {
                    fail(&state, format!("quic handshake to {addr}: {e}"));
                    return;
                }
            },
            Err(e) => {
                fail(&state, format!("quic connect to {addr}: {e}"));
                return;
            }
        };
        let (send, recv) = match conn.open_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                fail(&state, format!("open_bi to {addr}: {e}"));
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

/// 标记连接失败并记录最近错误；条目留给 Java poll 观察 FAILED 后关闭清理。
fn fail(state: &Arc<AtomicU32>, msg: String) {
    state.store(super::STATE_FAILED, Ordering::SeqCst);
    super::registry::set_last_error(msg);
}
