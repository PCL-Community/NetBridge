//! 单连接数据面：状态查询、批量读写、优雅关闭与读写循环。
//!
//! 零拷贝约定：读侧经 quinn `read_chunk` 直取 `Bytes`，单块满足时零
//! 重组直返 JNI 层；超长块用 `slice` 共享视图切分；仅多块拼接时才
//! 复制进 `BytesMut`。写侧由 JNI 拷出的 `Vec<u8>` 零成本转 `Bytes`。

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use tokio::sync::mpsc;

use super::Command;
use super::registry::{conns, remove_conn};

/// 查询连接状态；不存在返回 None（Java 映射为 UNKNOWN）。
pub fn connection_state(conn: u64) -> Option<u32> {
    conns().get(&conn).map(|h| h.state.load(Ordering::SeqCst))
}

/// 关闭连接（优雅结束发送侧，等待 QUIC 任务收尾）。
pub fn close_connection(conn: u64) -> bool {
    let Some(handle) = conns().get(&conn) else {
        return false;
    };
    let state = handle.state.clone();
    let to_quic = handle.to_quic.clone();
    drop(handle);
    let was = state.swap(super::STATE_CLOSED, Ordering::SeqCst);
    let ok = to_quic.try_send(Command::Close).is_ok();
    // 防御性幂等清理：正常路径已由任务收尾/失败回调自清理。
    if was == super::STATE_FAILED {
        remove_conn(conn);
    }
    ok
}

/// 写入一段字节到 QUIC 流。返回实际入队字节数：
/// - 满队列返回 0（Java 侧应做背压缓冲，不可丢弃）；
/// - 连接未就绪返回 0；
/// - 连接已关闭返回 Err。
pub fn write_chunk(conn: u64, data: Bytes) -> Result<usize, String> {
    if data.is_empty() {
        return Ok(0);
    }
    let len = data.len();
    let Some(handle) = conns().get(&conn) else {
        return Err("no such connection".to_string());
    };
    let connected = handle.state.load(Ordering::SeqCst) == super::STATE_CONNECTED;
    let to_quic = handle.to_quic.clone();
    drop(handle);
    if !connected {
        return Ok(0);
    }
    match to_quic.try_send(Command::Write(data)) {
        Ok(()) => Ok(len),
        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => Ok(0),
        Err(_) => Err("connection closed".to_string()),
    }
}

/// 从队列取下一块：残留块优先，其后为 channel。
fn pull(rx: &mut mpsc::Receiver<Bytes>, pending: &mut VecDeque<Bytes>) -> Option<Bytes> {
    pending.pop_front().or_else(|| rx.try_recv().ok())
}

/// 多块拼接：唯一不可避免的重组拷贝（JNI 边界需要连续内存）。
/// 超出 max_bytes 的尾部以共享视图切回 pending。
fn reassemble(
    first: &Bytes,
    mut next: Option<Bytes>,
    rx: &mut mpsc::Receiver<Bytes>,
    pending: &mut VecDeque<Bytes>,
    max_bytes: usize,
) -> Bytes {
    let mut out = BytesMut::with_capacity(max_bytes);
    out.extend_from_slice(first);
    while out.len() < max_bytes {
        let Some(mut chunk) = next.take().or_else(|| pull(rx, pending)) else {
            break;
        };
        if out.len() + chunk.len() > max_bytes {
            let cut = max_bytes - out.len();
            pending.push_front(chunk.slice(cut..));
            chunk = chunk.slice(..cut);
        }
        out.extend_from_slice(&chunk);
    }
    out.freeze()
}

/// 读取最多 max_bytes 字节；无数据时返回空 Bytes。
///
/// 零拷贝策略：首块即满足（含恰好等于）直接移交；超长用 `slice`
/// 共享视图切分、尾部回队；仅多块拼接时复制进 `BytesMut`（JNI 边界
/// 需要连续内存，此为唯一不可避免的重组拷贝）。
pub fn read_chunk(conn: u64, max_bytes: usize) -> Result<Bytes, String> {
    let Some(handle) = conns().get(&conn) else {
        return Err("no such connection".to_string());
    };
    let to_java = handle.to_java.clone();
    drop(handle);
    // 队列锁无不变量，中毒后接管继续，避免 JNI 边界 abort。
    let mut guard = match to_java.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let (rx, pending) = &mut *guard;

    let first = match pull(rx, pending) {
        Some(b) => b,
        None => return Ok(Bytes::new()),
    };
    if first.len() > max_bytes {
        pending.push_front(first.slice(max_bytes..));
        return Ok(first.slice(..max_bytes));
    }

    match pull(rx, pending) {
        // 仅此一块且不超长：整块直返，零重组。
        None => Ok(first),
        Some(second) => Ok(reassemble(&first, Some(second), rx, pending, max_bytes)),
    }
}

/// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
///
/// 关闭传播：reader 检测到对端关闭（流结束/错误）后 drop 自己持有的
/// `to_quic_tx` 克隆，使主循环 `to_quic_rx.recv()` 返回 None 而收尾
/// （置 CLOSED、清理 registry）。此后 Java 侧 writeChunk 也会因 channel
/// 关闭得到错误——读写两条路径都能感知连接死亡，客户端不会挂起。
// 参数均为独立原语（流/channel/状态），强行打包成结构体反增间接层。
#[allow(clippy::too_many_arguments)]
pub async fn run_connection(
    conn_id: u64,
    conn: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_quic_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    to_quic_tx: mpsc::Sender<Command>,
    state: Arc<AtomicU32>,
) {
    let reader = {
        let to_quic_tx = to_quic_tx.clone();
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
                    }
                    Ok(None) => break,
                    Err(_) => break,
                }
            }
            drop(to_quic_tx);
        })
    };

    loop {
        // Close 命令可能因写队列满丢失，或状态已由外部置 CLOSED；此处兜底收尾。
        if state.load(Ordering::SeqCst) == super::STATE_CLOSED {
            let _ = send.finish();
            break;
        }
        tokio::select! {
            cmd = to_quic_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    if send.write_all(&bytes).await.is_err() {
                        state.store(super::STATE_FAILED, Ordering::SeqCst);
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
        super::STATE_CONNECTING | super::STATE_CONNECTED => {
            state.store(super::STATE_CLOSED, Ordering::SeqCst);
        }
        _ => {}
    }
    reader.abort();
    let _ = reader.await;
    conn.close(0u32.into(), b"qmc close");
    remove_conn(conn_id);
}
