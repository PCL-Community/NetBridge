//! KCP 服务端：acceptor 生命周期与连接接纳（kcp-rs 内建握手）。

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use kcp::{KcpConfig, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::socket_util;
use crate::{ServerHandle, TransportEndpoint, guarded, try_admit};

/// 经 NativeContext 启动 KCP 服务端。
pub fn start_server_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
    profile: KcpProfile,
) -> Result<u64, BridgeError> {
    let config = build_config(profile);
    let server_id = ctx.allocate_id();

    let (tx, rx) = std::sync::mpsc::channel::<Result<u16, BridgeError>>();
    let (stop_tx, stop_rx) = mpsc::channel::<()>(1);
    let ctx_clone = Arc::clone(ctx);
    let ctx_cleanup = Arc::clone(ctx);

    ctx.handle().spawn(async move {
        let c = Arc::clone(&ctx_clone);
        let panicked = guarded("kcp accept task in context", async move {
            server_task_in_context(
                c,
                server_id,
                port,
                bind,
                max_connections,
                config,
                tx,
                stop_tx,
                stop_rx,
            )
            .await;
        })
        .await;
        if panicked {
            ctx_cleanup.servers_map().remove(&server_id);
        }
    });

    match rx.recv_timeout(Duration::from_secs(5)) {
        Ok(Ok(_port)) => Ok(server_id),
        Ok(Err(msg)) => Err(msg),
        Err(_) => Err(BridgeError::Other("kcp listener setup timeout".into())),
    }
}

#[allow(clippy::too_many_arguments)]
async fn server_task_in_context(
    ctx: Arc<crate::context::NativeContext>,
    server_id: u64,
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: KcpConfig,
    tx: std::sync::mpsc::Sender<Result<u16, BridgeError>>,
    stop_tx: mpsc::Sender<()>,
    mut stop_rx: mpsc::Receiver<()>,
) {
    let (mut listener, local) = match bind_listener(port, bind, max_connections, &config).await {
        Ok(pair) => pair,
        Err(msg) => {
            let _ = tx.send(Err(msg));
            return;
        }
    };
    let conn_count = Arc::new(AtomicUsize::new(0));
    ctx.servers_map().insert(
        server_id,
        ServerHandle {
            endpoint: TransportEndpoint::Kcp(stop_tx),
            port: local.port(),
            max_connections,
            conn_count: Arc::clone(&conn_count),
        },
    );
    let _ = tx.send(Ok(local.port()));

    let ctx_clone = Arc::clone(&ctx);
    accept_loop_in_context(
        ctx_clone,
        &mut listener,
        &mut stop_rx,
        server_id,
        max_connections,
        conn_count,
    )
    .await;
    drop(listener);
    let _ = ctx.stop_server(server_id);
}

async fn accept_loop_in_context(
    ctx: Arc<crate::context::NativeContext>,
    listener: &mut KcpUdpStream,
    stop_rx: &mut mpsc::Receiver<()>,
    server_id: u64,
    max_connections: usize,
    conn_count: Arc<AtomicUsize>,
) {
    loop {
        let accepted = tokio::select! {
            _ = stop_rx.recv() => None,
            acc = listener.accept() => acc.ok(),
        };
        let Some((stream, peer)) = accepted else {
            break;
        };
        if !admit(&conn_count, max_connections) {
            continue;
        }
        let (to_transport_tx, to_transport_rx) = mpsc::channel::<crate::Command>(4096);
        let (to_java_tx, to_java_rx) = mpsc::channel::<bytes::Bytes>(8192);
        let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
        let conn_id = ctx.allocate_id();
        ctx.conns().insert(
            conn_id,
            crate::ConnHandle::new(
                state.clone(),
                to_java_rx,
                to_transport_tx,
                Some(server_id),
                Some(Arc::clone(&conn_count)),
                false,
                false,
                Some(peer),
            ),
        );
        ctx.event_sink().on_event(
            crate::event::NB_EVENT_ACCEPTED,
            server_id,
            conn_id as i64,
            0,
        );

        let ctx_c = Arc::clone(&ctx);
        ctx.handle().spawn(async move {
            let sink = ctx_c.event_sink().clone();
            let c = Arc::clone(&ctx_c);
            let panicked = guarded("kcp connection task in context", async move {
                super::connection::run_kcp_connection_with_sink(
                    conn_id,
                    FecStream::new(stream),
                    to_transport_rx,
                    to_java_tx,
                    state,
                    false,
                    sink,
                    Box::new(move |id| {
                        c.remove_conn(id);
                    }),
                )
                .await;
            })
            .await;
            if panicked {
                ctx_c.remove_conn(conn_id);
            }
        });
    }
}

async fn bind_listener(
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: &KcpConfig,
) -> Result<(KcpUdpStream, SocketAddr), BridgeError> {
    let (socket, _) = socket_util::bind_server(port, bind).map_err(|source| BridgeError::Bind {
        transport: Transport::Kcp,
        port,
        source,
    })?;
    let socket = nonblocking(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "set_nonblocking",
        source,
    })?;
    let udp = UdpSocket::from_std(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "from_std",
        source,
    })?;
    let local = udp.local_addr().map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "local addr",
        source,
    })?;
    let listener =
        KcpUdpStream::socket_listen(Arc::new(config.clone()), udp, max_connections.max(8), None)
            .map_err(|e| BridgeError::Other(format!("kcp listener: {e}")))?;
    Ok((listener, local))
}

fn admit(conn_count: &AtomicUsize, max: usize) -> bool {
    conn_count.load(Ordering::Relaxed) < max && try_admit(conn_count, max)
}

fn nonblocking(s: std::net::UdpSocket) -> std::io::Result<std::net::UdpSocket> {
    s.set_nonblocking(true)?;
    Ok(s)
}
