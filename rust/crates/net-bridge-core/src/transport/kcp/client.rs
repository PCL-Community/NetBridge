//! KCP 客户端：异步建连（kcp-rs 内建 SYN 握手），立即返回连接 id。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use kcp::{KcpStream, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::connection::run_kcp_connection;
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::socket_util;
use crate::{
    Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, STATE_FAILED, guarded,
};

/// 发起 KCP 连接（异步建立，立即返回连接 id）。
pub fn connect(host: &str, port: u16, profile: KcpProfile) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let (conn_id, state, to_java_tx, to_transport_rx) = register_client();
    let host = host.to_string();
    rt.spawn(async move {
        let panicked = guarded("kcp connect task", async move {
            connect_task(
                conn_id,
                state,
                host,
                port,
                profile,
                to_transport_rx,
                to_java_tx,
            )
            .await;
        })
        .await;
        if panicked {
            remove_conn(conn_id);
        }
    });
    Ok(conn_id)
}

/// 经 NativeContext 发起 KCP 客户端连接。
pub fn connect_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    host: &str,
    port: u16,
    profile: KcpProfile,
) -> Result<u64, BridgeError> {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = ctx.allocate_id();
    ctx.conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            None,
            None,
            true,
            true,
            None,
        ),
    );
    let host = host.to_string();
    let ctx_clone = Arc::clone(ctx);
    let ctx_cleanup = Arc::clone(ctx);
    let connected_ok = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let ok_task = Arc::clone(&connected_ok);
    let state_outer = Arc::clone(&state);
    ctx.handle().spawn(async move {
        let panicked = guarded("kcp connect task in context", async move {
            let Some(addr) = resolve_host(conn_id, &state, &host, port).await else {
                return;
            };
            let Some(udp) = client_udp(conn_id, &state, addr) else {
                return;
            };
            let Some(stream) = establish_kcp(conn_id, &state, addr, udp, profile).await else {
                return;
            };
            ok_task.store(true, Ordering::SeqCst);
            if state.load(Ordering::SeqCst) != STATE_CLOSED {
                state.store(STATE_CONNECTED, Ordering::SeqCst);
                ctx_clone.event_sink().on_event(
                    crate::event::NB_EVENT_CONNECTION_STATE,
                    conn_id,
                    crate::event::abi_connection_state(STATE_CONNECTED) as i64,
                    0,
                );
            }
            super::connection::run_kcp_connection_with_sink(
                conn_id,
                FecStream::new(stream),
                to_transport_rx,
                to_java_tx,
                state,
                true,
                ctx_clone.event_sink().clone(),
                {
                    let c = Arc::clone(&ctx_clone);
                    Box::new(move |id| {
                        c.remove_conn(id);
                    })
                },
            )
            .await;
        })
        .await;
        if panicked {
            ctx_cleanup.remove_conn(conn_id);
        } else if !connected_ok.load(Ordering::SeqCst) {
            let st = state_outer.load(Ordering::SeqCst);
            ctx_cleanup.event_sink().on_event(
                crate::event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(if st == STATE_FAILED {
                    STATE_FAILED
                } else {
                    STATE_CLOSED
                }) as i64,
                0,
            );
        }
    });
    Ok(conn_id)
}

fn register_client() -> (
    u64,
    Arc<AtomicU32>,
    mpsc::Sender<Bytes>,
    mpsc::Receiver<Command>,
) {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            None,
            None,
            true,
            true,
            None,
        ),
    );
    (conn_id, state, to_java_tx, to_transport_rx)
}

async fn connect_task(
    conn_id: u64,
    state: Arc<AtomicU32>,
    host: String,
    port: u16,
    profile: KcpProfile,
    to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
) {
    let Some(addr) = resolve_host(conn_id, &state, &host, port).await else {
        return;
    };
    let Some(udp) = client_udp(conn_id, &state, addr) else {
        return;
    };
    let Some(stream) = establish_kcp(conn_id, &state, addr, udp, profile).await else {
        return;
    };
    if state.load(Ordering::SeqCst) != STATE_CLOSED {
        state.store(STATE_CONNECTED, Ordering::SeqCst);
    }
    run_kcp_connection(
        conn_id,
        FecStream::new(stream),
        to_transport_rx,
        to_java_tx,
        state,
        true,
    )
    .await;
}

async fn resolve_host(
    conn_id: u64,
    state: &Arc<AtomicU32>,
    host: &str,
    port: u16,
) -> Option<SocketAddr> {
    match tokio::net::lookup_host((host, port)).await {
        Ok(mut addrs) => addrs.next().or_else(|| {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source: std::io::Error::new(
                        std::io::ErrorKind::NotFound,
                        "no address resolved",
                    ),
                },
            );
            None
        }),
        Err(source) => {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source,
                },
            );
            None
        }
    }
}

fn client_udp(conn_id: u64, state: &Arc<AtomicU32>, addr: SocketAddr) -> Option<UdpSocket> {
    let socket = match socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "client bind",
                    source: e,
                },
            );
            return None;
        }
    };
    match socket
        .set_nonblocking(true)
        .and_then(|()| UdpSocket::from_std(socket))
    {
        Ok(udp) => Some(udp),
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "from_std",
                    source: e,
                },
            );
            None
        }
    }
}

async fn establish_kcp(
    conn_id: u64,
    state: &Arc<AtomicU32>,
    addr: SocketAddr,
    udp: UdpSocket,
    profile: KcpProfile,
) -> Option<KcpStream> {
    let config = Arc::new(build_config(profile));
    match KcpUdpStream::socket_connect(config, addr, udp).await {
        Ok((stream, _)) => Some(stream),
        Err(e) => {
            fail(
                conn_id,
                state,
                BridgeError::Connect {
                    transport: Transport::Kcp,
                    addr,
                    source: Box::new(e),
                },
            );
            None
        }
    }
}

fn fail(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) {
    state.store(STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
}
