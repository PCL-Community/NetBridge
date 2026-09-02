//! 服务端 QUIC acceptor：endpoint 生命周期与连接 accept。

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::connection::run_connection;
use crate::error::{BridgeError, Transport};
use crate::registry::{allocate_id, remove_conn, runtime, servers};
use crate::server_ops::register_connection;
use crate::socket_util;
use crate::{STATE_FAILED, ServerHandle, TransportEndpoint, guarded, try_admit};

/// 启动服务端 QUIC acceptor（端口 0 表示由系统分配）。
pub fn start_server(
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let server_config = quinn_plaintext::server_config();
    let endpoint = {
        let _guard = rt.enter();
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
        let panicked = guarded("quic accept loop", async move {
            loop {
                let incoming = match accept_endpoint.accept().await {
                    Some(incoming) => incoming,
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
            servers().remove(&server_id);
        }
    });
    Ok(server_id)
}

/// 经 NativeContext 启动服务端 QUIC acceptor。
pub fn start_server_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
) -> Result<u64, BridgeError> {
    let server_config = quinn_plaintext::server_config();
    let endpoint = {
        let _guard = ctx.handle().enter();
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

    let server_id = ctx.allocate_id();
    let conn_count = Arc::new(AtomicUsize::new(0));
    let accept_endpoint = endpoint.clone();
    let accept_counter = Arc::clone(&conn_count);
    ctx.servers_map().insert(
        server_id,
        ServerHandle {
            endpoint: TransportEndpoint::Quic(endpoint),
            port: actual_port,
            max_connections,
            conn_count,
        },
    );

    let ctx_clone = Arc::clone(ctx);
    let ctx_cleanup = Arc::clone(ctx);
    ctx.handle().spawn(async move {
        let panicked = guarded("quic accept loop in context", async move {
            loop {
                let incoming = match accept_endpoint.accept().await {
                    Some(incoming) => incoming,
                    None => break,
                };
                if accept_counter.load(Ordering::Relaxed) >= max_connections {
                    drop(incoming);
                    continue;
                }
                let conn_counter = Arc::clone(&accept_counter);
                let c = Arc::clone(&ctx_clone);
                c.handle().spawn(serve_incoming_in_context(
                    c.clone(),
                    server_id,
                    incoming,
                    conn_counter,
                    max_connections,
                ));
            }
        })
        .await;
        if panicked {
            ctx_cleanup.servers_map().remove(&server_id);
        }
    });
    Ok(server_id)
}

async fn serve_incoming_in_context(
    ctx: Arc<crate::context::NativeContext>,
    server_id: u64,
    incoming: quinn::Incoming,
    conn_counter: Arc<AtomicUsize>,
    max_connections: usize,
) {
    let peer = incoming.remote_address();
    let Ok(conn) = incoming.await else {
        return;
    };
    if !try_admit(&conn_counter, max_connections) {
        return;
    }
    let (to_transport_tx, to_transport_rx) = tokio::sync::mpsc::channel::<crate::Command>(4096);
    let (to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<bytes::Bytes>(8192);
    let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
    let conn_id = ctx.allocate_id();
    ctx.conns().insert(
        conn_id,
        crate::ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            Some(server_id),
            Some(conn_counter),
            false,
            false,
            Some(peer),
        ),
    );
    // 触发 ACCEPTED 事件
    ctx.event_sink().on_event(
        crate::event::NB_EVENT_ACCEPTED,
        server_id,
        conn_id as i64,
        0,
    );

    match conn.accept_bi().await {
        Ok((send, recv)) => {
            let ctx_c = Arc::clone(&ctx);
            let panicked = guarded("quic connection task in context", async move {
                super::connection::run_connection_with_sink(
                    conn_id,
                    conn,
                    send,
                    recv,
                    to_transport_rx,
                    to_java_tx,
                    to_transport_tx,
                    state,
                    ctx_c.event_sink().clone(),
                    {
                        let c = Arc::clone(&ctx_c);
                        Box::new(move |id| {
                            c.remove_conn(id);
                        })
                    },
                )
                .await;
            })
            .await;
            if panicked {
                ctx.remove_conn(conn_id);
            }
        }
        Err(_) => {
            state.store(STATE_FAILED, Ordering::SeqCst);
            ctx.remove_conn(conn_id);
        }
    }
}

async fn serve_incoming(
    server_id: u64,
    incoming: quinn::Incoming,
    conn_counter: Arc<AtomicUsize>,
    max_connections: usize,
) {
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
            let panicked = guarded("quic connection task", async move {
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
