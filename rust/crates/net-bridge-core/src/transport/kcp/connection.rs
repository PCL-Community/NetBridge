//! KCP 单连接数据面：smux 会话承载 MC 字节流，读写循环与关闭传播。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use kcp::KcpStream;
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::sync::mpsc;

use super::fec_stream::FecStream;
use crate::event::{EventSink, NB_EVENT_CONNECTION_STATE, NB_EVENT_DATA_AVAILABLE};
use crate::report_error;
use crate::{Command, STATE_CLOSED, STATE_FAILED, guarded};

type KcpFec = FecStream<KcpStream>;

fn smux_config() -> Result<Config, String> {
    ConfigBuilder::new()
        .max_frame_size(64 * 1024)
        .build()
        .map_err(|e| format!("smux config build failed: {e}"))
}

#[allow(clippy::too_many_arguments)]
pub async fn run_kcp_connection_with_sink(
    conn_id: u64,
    fec: KcpFec,
    to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    client_side: bool,
    event_sink: Arc<dyn EventSink>,
    cleanup: Box<dyn Fn(u64) + Send + Sync>,
) {
    let Some(session) =
        create_session(conn_id, fec, client_side, &state, &event_sink, &cleanup).await
    else {
        return;
    };
    let Some(stream) = open_mc_stream(
        conn_id,
        &session,
        client_side,
        &state,
        &event_sink,
        &cleanup,
    )
    .await
    else {
        return;
    };

    let (stream_r, stream_w) = tokio::io::split(stream);
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<bool>(1);
    let done_guard = reader_done_tx.clone();
    let reader_state = state.clone();
    let sink = Arc::clone(&event_sink);
    let reader = tokio::spawn(async move {
        let panicked = guarded("kcp reader task", async move {
            reader_loop(
                conn_id,
                stream_r,
                to_java_tx,
                reader_state,
                reader_done_tx,
                sink,
            )
            .await;
        })
        .await;
        if panicked {
            let _ = done_guard.try_send(true);
        }
    });

    drive(
        conn_id,
        stream_w,
        &session,
        to_kcp_rx,
        &mut reader_done_rx,
        &state,
        &event_sink,
    )
    .await;

    reader.abort();
    cleanup(conn_id);
}

async fn create_session(
    conn_id: u64,
    fec: KcpFec,
    client_side: bool,
    state: &AtomicU32,
    event_sink: &Arc<dyn EventSink>,
    cleanup: &(dyn Fn(u64) + Send + Sync),
) -> Option<Session> {
    let config = match smux_config() {
        Ok(config) => config,
        Err(msg) => {
            return request_fail(conn_id, state, Err(msg), event_sink, cleanup);
        }
    };
    let result = if client_side {
        Session::client(fec, config).await
    } else {
        Session::server(fec, config).await
    };
    request_fail(
        conn_id,
        state,
        result.map_err(|e| format!("smux session setup failed: {e}")),
        event_sink,
        cleanup,
    )
}

async fn open_mc_stream(
    conn_id: u64,
    session: &Session,
    client_side: bool,
    state: &AtomicU32,
    event_sink: &Arc<dyn EventSink>,
    cleanup: &(dyn Fn(u64) + Send + Sync),
) -> Option<smux::Stream> {
    let result = if client_side {
        session.open_stream().await
    } else {
        session.accept_stream().await
    };
    request_fail(
        conn_id,
        state,
        result.map_err(|e| format!("smux stream setup failed: {e}")),
        event_sink,
        cleanup,
    )
}

fn request_fail<T>(
    conn_id: u64,
    state: &AtomicU32,
    result: Result<T, String>,
    event_sink: &Arc<dyn EventSink>,
    cleanup: &(dyn Fn(u64) + Send + Sync),
) -> Option<T> {
    match result {
        Ok(value) => Some(value),
        Err(msg) => {
            report_error(format!("kcp conn {conn_id}: {msg}"));
            state.store(STATE_FAILED, Ordering::SeqCst);
            event_sink.on_event(
                NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(STATE_FAILED) as i64,
                0,
            );
            cleanup(conn_id);
            None
        }
    }
}

async fn reader_loop(
    conn_id: u64,
    mut stream_r: ReadHalf<smux::Stream>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    done_tx: mpsc::Sender<bool>,
    event_sink: Arc<dyn EventSink>,
) {
    let mut payload = BytesMut::with_capacity(64 * 1024);
    let clean = loop {
        payload.clear();
        match stream_r.read_buf(&mut payload).await {
            Ok(0) => break true,
            Ok(_) => {
                if to_java_tx.send(payload.split().freeze()).await.is_err() {
                    break true;
                }
                event_sink.on_event(NB_EVENT_DATA_AVAILABLE, conn_id, 0, 0);
            }
            Err(_) if state.load(Ordering::SeqCst) == STATE_CLOSED => break true,
            Err(e) if is_session_closed(&e) => break true,
            Err(e) => {
                report_error(format!("kcp conn {conn_id}: read error: {e}"));
                break false;
            }
        }
    };
    let _ = done_tx.send(clean).await;
}

async fn drive(
    conn_id: u64,
    mut stream_w: WriteHalf<smux::Stream>,
    session: &Session,
    mut cmds: mpsc::Receiver<Command>,
    reader_done: &mut mpsc::Receiver<bool>,
    state: &AtomicU32,
    event_sink: &Arc<dyn EventSink>,
) {
    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            break;
        }
        tokio::select! {
            biased;

            done = reader_done.recv() => {
                if done == Some(false) {
                    state.store(STATE_FAILED, Ordering::SeqCst);
                    event_sink.on_event(
                        NB_EVENT_CONNECTION_STATE,
                        conn_id,
                        crate::event::abi_connection_state(STATE_FAILED) as i64,
                        0,
                    );
                }
                break;
            }
            cmd = cmds.recv() => {
                let closed = match cmd {
                    Some(Command::Write(bytes)) if !bytes.is_empty() => {
                        if let Err(e) = stream_w.write_all(&bytes).await {
                            if is_session_closed(&e) {
                                true
                            } else {
                                report_error(format!("kcp conn {conn_id}: write error: {e}"));
                                state.store(STATE_FAILED, Ordering::SeqCst);
                                event_sink.on_event(
                                    NB_EVENT_CONNECTION_STATE,
                                    conn_id,
                                    crate::event::abi_connection_state(STATE_FAILED) as i64,
                                    0,
                                );
                                true
                            }
                        } else {
                            false
                        }
                    }
                    Some(Command::Close) | None => true,
                    _ => false,
                };
                if closed {
                    break;
                }
            }
        }
    }
    if state.load(Ordering::SeqCst) != STATE_FAILED {
        state.store(STATE_CLOSED, Ordering::SeqCst);
        event_sink.on_event(
            NB_EVENT_CONNECTION_STATE,
            conn_id,
            crate::event::abi_connection_state(STATE_CLOSED) as i64,
            0,
        );
    }
    graceful_close(&mut stream_w, session).await;
}

async fn graceful_close(stream_w: &mut (impl AsyncWrite + Unpin), session: &Session) {
    let _ = stream_w.shutdown().await;
    let _ = session.close().await;
}

fn is_session_closed(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::BrokenPipe
}
