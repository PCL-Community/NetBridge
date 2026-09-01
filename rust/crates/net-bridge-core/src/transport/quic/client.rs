//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use super::connection::run_connection;
use crate::error::{BridgeError, Transport};
use crate::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::socket_util;
use crate::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, guarded};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
pub fn connect(host: &str, port: u16) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        return Err(BridgeError::RuntimeUnavailable);
    };
    let (conn_id, state, to_java_tx, mut to_transport_rx, to_transport_tx) = register_client();
    let host = host.to_string();
    rt.spawn(async move {
        let panicked = guarded("quic connect task", async move {
            if let Some((conn, send, recv)) =
                establish(&host, port, conn_id, &state, &mut to_transport_rx).await
            {
                state.store(STATE_CONNECTED, Ordering::SeqCst);
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
            }
        })
        .await;
        if panicked {
            remove_conn(conn_id);
        }
    });
    Ok(conn_id)
}

/// 注册占位句柄（状态 CONNECTING）并返回建连任务与数据循环所需各端。
fn register_client() -> (
    u64,
    Arc<AtomicU32>,
    mpsc::Sender<Bytes>,
    mpsc::Receiver<Command>,
    mpsc::Sender<Command>,
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
            to_transport_tx.clone(),
            None,
            None,
            false,
            true,
            None,
        ),
    );
    (conn_id, state, to_java_tx, to_transport_rx, to_transport_tx)
}

/// 建立连接：解析 → 绑定 → endpoint → 取消感知握手 → 双向流。
async fn establish(
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    to_transport_rx: &mut mpsc::Receiver<Command>,
) -> Option<(quinn::Connection, quinn::SendStream, quinn::RecvStream)> {
    let addr = resolve_addr(host, port, conn_id, state).await?;
    let socket = match socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            return fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "client bind",
                    source: e,
                },
            );
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
            return fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "endpoint",
                    source: e,
                },
            );
        }
    };
    endpoint.set_default_client_config(quinn_plaintext::client_config());

    let connecting = match endpoint.connect(addr, "plaintext.test") {
        Ok(connecting) => connecting,
        Err(e) => return fail(conn_id, state, connect_error(addr, e)),
    };
    let conn = tokio::select! {
        result = connecting => match result {
            Ok(conn) => conn,
            Err(e) => return fail(conn_id, state, connect_error(addr, e)),
        },
        _ = to_transport_rx.recv() => {
            cancel(conn_id, state);
            return None;
        }
    };
    let (send, recv) = match conn.open_bi().await {
        Ok(pair) => pair,
        Err(e) => return fail(conn_id, state, connect_error(addr, e)),
    };
    if state.load(Ordering::SeqCst) == STATE_CLOSED {
        cancel(conn_id, state);
        return None;
    }
    Some((conn, send, recv))
}

async fn resolve_addr(
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
) -> Option<SocketAddr> {
    match tokio::net::lookup_host((host, port)).await {
        Ok(mut addrs) => addrs.next().or_else(|| {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source: io_no_address(),
                },
            )
        }),
        Err(source) => fail(
            conn_id,
            state,
            BridgeError::Dns {
                host: host.to_owned(),
                port,
                source,
            },
        ),
    }
}

fn connect_error(
    addr: SocketAddr,
    source: impl std::error::Error + Send + Sync + 'static,
) -> BridgeError {
    BridgeError::Connect {
        transport: Transport::Quic,
        addr,
        source: Box::new(source),
    }
}

fn io_no_address() -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::NotFound, "no address resolved")
}

fn fail<T>(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) -> Option<T> {
    state.store(crate::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
    None
}

fn cancel(conn_id: u64, state: &Arc<AtomicU32>) {
    state.store(STATE_CLOSED, Ordering::SeqCst);
    remove_conn(conn_id);
}
