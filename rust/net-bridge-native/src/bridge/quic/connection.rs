//! 单连接 QUIC 数据面：读写循环与关闭传播。
//!
//! 连接粒度的状态查询与批量读写原语（`connection_state`/`read_chunk`/
//! `write_chunk`/`close_connection`）是传输无关的注册表操作，见
//! [`crate::bridge::dataplane`]；本模块仅保留 QUIC 特有的数据循环。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use crate::bridge::registry::remove_conn;
use crate::bridge::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, STATE_FAILED};

/// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
///
/// 关闭传播：reader 检测到对端关闭（流结束/错误）后 drop 自己持有的
/// `to_transport_tx` 克隆，使主循环 `to_transport_rx.recv()` 返回 None 而收尾
/// （置 CLOSED、清理 registry）。此后 Java 侧 writeChunk 也会因 channel
/// 关闭得到错误——读写两条路径都能感知连接死亡，客户端不会挂起。
#[allow(clippy::too_many_arguments)]
pub async fn run_connection(
    conn_id: u64,
    conn: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    to_transport_tx: mpsc::Sender<Command>,
    state: Arc<AtomicU32>,
) {
    // 读任务退出信号：JoinHandle 不能在完成后再次 poll（会 panic），
    // 故以 channel 通知主循环，句柄仅用于超时兜底 abort。
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<()>(1);
    let reader = {
        let to_transport_tx = to_transport_tx.clone();
        tokio::spawn(async move {
            // 防护：quinn 不应产出空块；连续异常则终止读循环交由收尾。
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
                        // 数据已入 Java 队列：反向通知唤醒 EventLoop 立即读，免轮询等待。
                        crate::notify_data(conn_id);
                    }
                    Ok(None) => break,
                    Err(_) => break,
                }
            }
            drop(to_transport_tx);
            let _ = reader_done_tx.send(()).await;
        })
    };

    loop {
        // Close 命令可能因写队列满丢失，或状态已由外部置 CLOSED；此处兜底收尾。
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            let _ = send.finish();
            break;
        }
        tokio::select! {
            // 读侧先行终止：对端关闭/读错误。FAILED 若有已由读路径写入。
            _ = reader_done_rx.recv() => break,
            cmd = to_transport_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    if send.write_all(&bytes).await.is_err() {
                        state.store(STATE_FAILED, Ordering::SeqCst);
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

    // 仅从活跃态迁移到 CLOSED；写失败已置的 FAILED 保留
    // （Java poll 依赖 FAILED/CLOSED 区分异常类型与 lastError）。
    match state.load(Ordering::SeqCst) {
        STATE_CONNECTING | STATE_CONNECTED => {
            state.store(STATE_CLOSED, Ordering::SeqCst);
        }
        _ => {}
    }
    reader.abort();
    conn.close(0u32.into(), b"net-bridge close");
    remove_conn(conn_id);
}
