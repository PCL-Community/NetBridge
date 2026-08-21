//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use tokio::sync::mpsc;

use super::connection::run_connection;
use super::registry::{allocate_id, registry, runtime};
use super::{ConnHandle, STATE_CONNECTING};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
pub fn connect(host: &str, port: u16) -> Result<u64, String> {
    let rt = runtime();
    // 先解析目标地址，按其地址族选择本地绑定（IPv4 目标绑 0.0.0.0，IPv6 绑 [::]，
    // 否则 quinn 会报 invalid remote address）。
    let addr = match runtime().block_on(tokio::net::lookup_host((host, port))) {
        Ok(mut addrs) => addrs.next(),
        Err(_) => None,
    };
    let Some(addr) = addr else {
        return Err(format!("dns resolve failed: {host}:{port}"));
    };
    let bind_addr: std::net::SocketAddr = if addr.is_ipv6() {
        "[::]:0".parse().unwrap()
    } else {
        "0.0.0.0:0".parse().unwrap()
    };
    let mut endpoint = {
        let _guard = rt.enter();
        quinn::Endpoint::client(bind_addr).map_err(|e| format!("udp client: {e}"))?
    };
    endpoint.set_default_client_config(quinn_plaintext::client_config());

    let (to_quic_tx, to_quic_rx) = mpsc::channel::<super::Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Vec<u8>>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = {
        let mut reg = registry().lock().unwrap();
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

    rt.spawn(async move {
        let conn = match endpoint.connect(addr, "plaintext.test") {
            Ok(connecting) => match connecting.await {
                Ok(conn) => conn,
                Err(e) => {
                    state.store(super::STATE_FAILED, Ordering::SeqCst);
                    super::registry::set_last_error(format!("quic handshake to {addr}: {e}"));
                    return;
                }
            },
            Err(e) => {
                state.store(super::STATE_FAILED, Ordering::SeqCst);
                super::registry::set_last_error(format!("quic connect to {addr}: {e}"));
                return;
            }
        };
        let (send, recv) = match conn.open_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                state.store(super::STATE_FAILED, Ordering::SeqCst);
                super::registry::set_last_error(format!("open_bi to {addr}: {e}"));
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
