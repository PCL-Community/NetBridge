//! 服务端 QUIC acceptor：endpoint 生命周期与连接 accept。

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use crate::error::{BridgeError, Transport};
use crate::socket_util;
use crate::{ServerHandle, TransportEndpoint, try_admit};

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

    let server_id = ctx.allocate_id()?;
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
    ctx.spawn_server_task("quic accept loop in context", server_id, async move {
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
    let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
    let Ok(conn_id) = ctx.allocate_id() else {
        conn_counter.fetch_sub(1, Ordering::Relaxed);
        return;
    };
    if !ctx.servers_map().contains_key(&server_id) {
        conn_counter.fetch_sub(1, Ordering::Relaxed);
        return;
    }
    let (to_transport_tx, to_transport_rx) = tokio::sync::mpsc::channel::<crate::Command>(4096);
    let (to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<bytes::Bytes>(8192);
    ctx.conns().insert(
        conn_id,
        crate::ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            Some(server_id),
            Some(conn_counter),
            true,
            Some(peer),
        ),
    );
    ctx.set_conn_remote_addr(conn_id, peer);
    ctx.event_sink().on_event(
        crate::event::NB_EVENT_ACCEPTED,
        server_id,
        conn_id as i64,
        0,
    );
    let accept_ctx = Arc::clone(&ctx);
    let to_transport_tx_runner = to_transport_tx;
    ctx.spawn_connection_task("quic stream accept and drive", conn_id, async move {
        match conn.accept_bi().await {
            Ok((send, recv)) => {
                super::connection::run_connection_with_sink(
                    conn_id,
                    conn,
                    send,
                    recv,
                    to_transport_rx,
                    to_java_tx,
                    to_transport_tx_runner,
                    state,
                    accept_ctx,
                )
                .await;
            }
            Err(_) => {
                state.store(crate::STATE_FAILED, Ordering::SeqCst);
                accept_ctx.emit_terminal(conn_id);
            }
        }
    });
}
