//! 服务端 QUIC acceptor：endpoint 生命周期与连接 accept。

use std::sync::atomic::Ordering;

use super::client;
use super::connection::run_connection;
use super::registry::{allocate_id, registry, runtime};
use super::{Command, ConnHandle, STATE_CONNECTED, STATE_FAILED, ServerHandle};

/// 启动服务端 QUIC acceptor（端口 0 表示由系统分配）。
///
/// 优先绑定 IPv6 双栈 `[::]:port`（IPV6_V6ONLY=false，同时接受 IPv4
/// v4-mapped 连接）；系统禁用双栈时回退 IPv4 `0.0.0.0:port`。
pub fn start_server(port: u16) -> Result<u64, String> {
    let rt = runtime();
    let server_config = quinn_plaintext::server_config();
    let (endpoint, actual_port) = {
        let _guard = rt.enter();
        // 优先 IPv6 双栈（接受 v4-mapped 连接）；失败回退 IPv4-only。
        let v6_addr: std::net::SocketAddr = format!("[::]:{port}").parse().unwrap();
        match quinn::Endpoint::server(server_config.clone(), v6_addr) {
            Ok(ep) => {
                let p = ep
                    .local_addr()
                    .map_err(|e| format!("local addr: {e}"))?
                    .port();
                (ep, p)
            }
            Err(v6_err) => {
                let ep = quinn::Endpoint::server(server_config, ([0, 0, 0, 0], port).into())
                    .map_err(|e| format!("bind udp/{port}: v6: {v6_err}; v4: {e}"))?;
                let p = ep
                    .local_addr()
                    .map_err(|e| format!("local addr: {e}"))?
                    .port();
                (ep, p)
            }
        }
    };

    let server_id = {
        let mut reg = registry().lock().unwrap();
        let id = allocate_id(&mut reg);
        reg.servers.insert(
            id,
            ServerHandle {
                endpoint: endpoint.clone(),
                port: actual_port,
            },
        );
        id
    };

    let accept_endpoint = endpoint.clone();
    rt.spawn(async move {
        loop {
            let incoming = match accept_endpoint.accept().await {
                Some(incoming) => incoming,
                // endpoint 被 stopServer 关闭。
                None => break,
            };
            rt.spawn(async move {
                if let Ok(conn) = incoming.await {
                    let reg = register_connection(server_id);
                    match conn.accept_bi().await {
                        Ok((send, recv)) => {
                            run_connection(
                                reg.conn_id,
                                conn,
                                send,
                                recv,
                                reg.to_quic_rx,
                                reg.to_java_tx,
                                reg.state,
                            )
                            .await;
                        }
                        Err(_) => {
                            reg.state.store(STATE_FAILED, Ordering::SeqCst);
                            registry().lock().unwrap().conns.remove(&reg.conn_id);
                        }
                    }
                }
            });
        }
    });
    Ok(server_id)
}

/// 查询服务端实际绑定端口。
pub fn server_port(server: u64) -> Option<u16> {
    registry()
        .lock()
        .unwrap()
        .servers
        .get(&server)
        .map(|h| h.port)
}

/// 停止服务端并关闭其全部连接。
pub fn stop_server(server: u64) -> bool {
    let mut reg = registry().lock().unwrap();
    let Some(handle) = reg.servers.remove(&server) else {
        return false;
    };
    handle.endpoint.close(0u32.into(), b"qmc stop");
    let conn_ids: Vec<u64> = reg
        .conns
        .iter()
        .filter(|(_, h)| h.server_id == Some(server))
        .map(|(id, _)| *id)
        .collect();
    for id in conn_ids {
        if let Some(h) = reg.conns.get(&id) {
            h.state.store(super::STATE_CLOSED, Ordering::SeqCst);
            let _ = h.to_quic.try_send(Command::Close);
        }
    }
    true
}

/// 取回服务端尚未上报的新连接 id 列表。
pub fn accept_connections(server: u64) -> Vec<u64> {
    let reg = registry().lock().unwrap();
    let mut out = Vec::new();
    for (id, handle) in reg.conns.iter() {
        if handle.server_id == Some(server) && !handle.reported.swap(true, Ordering::SeqCst) {
            out.push(*id);
        }
    }
    out
}

/// 服务端握手完成后立即注册连接句柄（不等待首个流数据）。
pub struct Registered {
    pub conn_id: u64,
    pub to_quic_rx: tokio::sync::mpsc::Receiver<Command>,
    pub to_java_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    pub state: std::sync::Arc<std::sync::atomic::AtomicU32>,
}

fn register_connection(server_id: u64) -> Registered {
    let (to_quic_tx, to_quic_rx) = tokio::sync::mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(8192);
    let state = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(STATE_CONNECTED));
    let conn_id = {
        let mut reg = registry().lock().unwrap();
        let id = allocate_id(&mut reg);
        reg.conns.insert(
            id,
            ConnHandle {
                state: state.clone(),
                to_java: std::sync::Mutex::new((to_java_rx, Vec::new())),
                to_quic: to_quic_tx,
                server_id: Some(server_id),
                reported: std::sync::atomic::AtomicBool::new(false),
            },
        );
        id
    };
    Registered {
        conn_id,
        to_quic_rx,
        to_java_tx,
        state,
    }
}

// 保持 client 模块被引用（测试用其常量）；实际导出见 client.rs。
#[allow(unused_imports)]
use client as _client_marker;
