//! 单连接 QUIC 数据面：读写循环与关闭传播。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use crate::event::{EventSink, NB_EVENT_CONNECTION_STATE, NB_EVENT_DATA_AVAILABLE};
use crate::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, STATE_FAILED, guarded};

/// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
#[allow(clippy::too_many_arguments)]
pub async fn run_connection_with_sink(
    conn_id: u64,
    conn: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    to_transport_tx: mpsc::Sender<Command>,
    state: Arc<AtomicU32>,
    event_sink: Arc<dyn EventSink>,
    cleanup: Box<dyn Fn(u64) + Send + Sync>,
) {
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<()>(1);
    let reader = {
        let to_transport_tx = to_transport_tx.clone();
        let sink = Arc::clone(&event_sink);
        tokio::spawn(async move {
            let panicked = guarded("quic reader task", async move {
                let mut empty_streak = 0u32;
                loop {
                    match recv.read_chunk(65536, true).await {
                        Ok(Some(chunk)) => {
                            if chunk.bytes.is_empty() {
                                empty_streak = empty_streak.saturating_add(1);
                                if empty_streak >= 16 {
                                    break;
                                }
                                continue;
                            }
                            empty_streak = 0;
                            if to_java_tx.send(chunk.bytes).await.is_err() {
                                break;
                            }
                            sink.on_event(NB_EVENT_DATA_AVAILABLE, conn_id, 0, 0);
                        }
                        Ok(None) => break,
                        Err(_) => break,
                    }
                }
            })
            .await;
            let _ = panicked;
            drop(to_transport_tx);
            let _ = reader_done_tx.send(()).await;
        })
    };

    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            let _ = send.finish();
            break;
        }
        tokio::select! {
            _ = reader_done_rx.recv() => break,
            cmd = to_transport_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    if send.write_all(&bytes).await.is_err() {
                        state.store(STATE_FAILED, Ordering::SeqCst);
                        event_sink.on_event(
                            NB_EVENT_CONNECTION_STATE,
                            conn_id,
                            crate::event::abi_connection_state(STATE_FAILED) as i64,
                            0,
                        );
                        break;
                    }
                }
                Some(Command::Close) => {
                    let _ = send.finish();
                    break;
                }
                None => break,
            },
            _ = conn.closed() => break,
        }
    }

    match state.load(Ordering::SeqCst) {
        STATE_CONNECTING | STATE_CONNECTED => {
            state.store(STATE_CLOSED, Ordering::SeqCst);
            event_sink.on_event(
                NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(STATE_CLOSED) as i64,
                0,
            );
        }
        _ => {}
    }
    reader.abort();
    conn.close(0u32.into(), b"net-bridge close");
    cleanup(conn_id);
}
