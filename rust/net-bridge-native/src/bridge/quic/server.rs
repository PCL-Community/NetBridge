//! 服务端 QUIC acceptor：endpoint 生命周期与连接 accept。
//!
//! 实例级的端口查询/停止与新连接上报原语（`server_port`/`stop_server`/
//! `accept_connections`）与连接登记器（`register_connection`）是传输无关的
//! 注册表操作，见 [`crate::bridge::server_ops`]；本模块仅保留 QUIC 特有的
//! accept 循环与连接注册时机（明文握手完成后）。

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::connection::run_connection;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, remove_conn, runtime, servers};
use crate::bridge::server_ops::register_connection;
use crate::bridge::socket_util;
use crate::bridge::{STATE_FAILED, ServerHandle, TransportEndpoint, try_admit};

/// 启动服务端 QUIC acceptor（端口 0 表示由系统分配）。
///
/// `max_connections` 为本实例活跃连接上限：accept 阶段超限即丢弃
/// Incoming（quinn 回 CONNECTION_REFUSED）。软限制——并发 accept 间
/// 存在少量超发窗口，但足以阻断连接洪泛的资源耗尽。
///
/// `bind` 指定监听地址：`None` 为默认——优先 IPv6 双栈 `[::]:port`
/// （IPV6_V6ONLY=false，同时接受 IPv4 v4-mapped 连接），系统禁用双栈
/// 时回退 IPv4 `0.0.0.0:port`；`Some(ip)` 则仅绑定该地址（如
/// `server-ip` 指定的内网地址），失败即返回错误。
pub fn start_server(
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        // 日志由 JNI 导出层对 Err 统一上报，此处不重复。
        return Err(BridgeError::RuntimeUnavailable);
    };
    let server_config = quinn_plaintext::server_config();
    let endpoint = {
        let _guard = rt.enter();
        // socket2 统一底座：显式双栈 + 4MB 缓冲 + REUSEADDR；
        // bind=None 时 v6 双栈失败自动回退 IPv4-only，错误信息含两个原因。
        let (socket, _local) =
            socket_util::bind_server(port, bind).map_err(|source| BridgeError::Bind {
                transport: Transport::Quic,
                port,
                source,
            })?;
        quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            Some(server_config),
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(|source| BridgeError::Setup {
            transport: Transport::Quic,
            stage: "endpoint",
            source,
        })?
    };
    let actual_port = endpoint
        .local_addr()
        .map_err(|source| BridgeError::Setup {
            transport: Transport::Quic,
            stage: "local addr",
            source,
        })?
        .port();

    let server_id = allocate_id();
    let conn_count = Arc::new(AtomicUsize::new(0));
    let accept_endpoint = endpoint.clone();
    let accept_counter = Arc::clone(&conn_count);
    servers().insert(
        server_id,
        ServerHandle {
            endpoint: TransportEndpoint::Quic(endpoint),
            port: actual_port,
            max_connections,
            conn_count,
        },
    );

    rt.spawn(async move {
        let panicked = crate::bridge::guarded("quic accept loop", async move {
            loop {
                let incoming = match accept_endpoint.accept().await {
                    Some(incoming) => incoming,
                    // endpoint 被 stopServer 关闭。
                    None => break,
                };
                if accept_counter.load(Ordering::Relaxed) >= max_connections {
                    drop(incoming);
                    continue;
                }
                let conn_counter = Arc::clone(&accept_counter);
                rt.spawn(serve_incoming(
                    server_id,
                    incoming,
                    conn_counter,
                    max_connections,
                ));
            }
        })
        .await;
        if panicked {
            // acceptor 死亡：注销服务端句柄，避免 SERVERS 残留。
            servers().remove(&server_id);
        }
    });
    Ok(server_id)
}

/// 服务一条 incoming：握手 → 登记 → 双向流 → 数据循环；任何失败就地清理。
async fn serve_incoming(
    server_id: u64,
    incoming: quinn::Incoming,
    conn_counter: Arc<AtomicUsize>,
    max_connections: usize,
) {
    // 取对端地址（Java 侧 IP 管控依赖）。
    let peer = incoming.remote_address();
    let Ok(conn) = incoming.await else {
        return;
    };
    if !try_admit(&conn_counter, max_connections) {
        return;
    }
    let reg = register_connection(server_id, peer, conn_counter);
    match conn.accept_bi().await {
        Ok((send, recv)) => {
            let conn_id = reg.conn_id;
            let panicked = crate::bridge::guarded("quic connection task", async move {
                run_connection(
                    conn_id,
                    conn,
                    send,
                    recv,
                    reg.to_transport_rx,
                    reg.to_java_tx,
                    reg.to_transport_tx,
                    reg.state,
                )
                .await;
            })
            .await;
            if panicked {
                remove_conn(conn_id);
            }
        }
        Err(_) => {
            reg.state.store(STATE_FAILED, Ordering::SeqCst);
            remove_conn(reg.conn_id);
        }
    }
}
