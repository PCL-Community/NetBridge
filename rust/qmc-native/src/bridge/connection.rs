//! 单连接数据面：状态查询、批量读写、优雅关闭与读写循环。

use std::sync::atomic::Ordering;

use super::Command;
use super::registry::conns;

/// 查询连接状态；不存在返回 None（Java 映射为 UNKNOWN）。
pub fn connection_state(conn: u64) -> Option<u32> {
    conns()
        .get(&conn)
        .map(|h| h.state.load(Ordering::SeqCst))
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
    // FAILED 且无存活任务收尾的连接（如客户端握手失败）：由 close 兜底
    // 移除注册表条目，否则泄漏；有任务时其收尾 remove 为幂等 no-op。
    if was == super::STATE_FAILED {
        conns().remove(&conn);
    }
    ok
}

/// 写入一段字节到 QUIC 流。返回实际入队字节数：
/// - 满队列返回 0（Java 侧应做背压缓冲，不可丢弃）；
/// - 连接未就绪返回 0；
/// - 连接已关闭返回 Err。
pub fn write_chunk(conn: u64, data: &[u8]) -> Result<usize, String> {
    if data.is_empty() {
        return Ok(0);
    }
    let Some(handle) = conns().get(&conn) else {
        return Err("no such connection".to_string());
    };
    let connected = handle.state.load(Ordering::SeqCst) == super::STATE_CONNECTED;
    let to_quic = handle.to_quic.clone();
    drop(handle);
    if !connected {
        return Ok(0);
    }
    match to_quic.try_send(Command::Write(data.to_vec())) {
        Ok(()) => Ok(data.len()),
        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => Ok(0),
        Err(_) => Err("connection closed".to_string()),
    }
}

/// 读取最多 max_bytes 字节；无数据时返回空 Vec。
pub fn read_chunk(conn: u64, max_bytes: usize) -> Result<Vec<u8>, String> {
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

    let mut out = Vec::new();
    if !pending.is_empty() {
        let take = max_bytes.min(pending.len());
        out.extend_from_slice(&pending[..take]);
        pending.drain(..take);
        if out.len() >= max_bytes {
            return Ok(out);
        }
    }
    while out.len() < max_bytes {
        match rx.try_recv() {
            Ok(chunk) => {
                let take = (max_bytes - out.len()).min(chunk.len());
                out.extend_from_slice(&chunk[..take]);
                if take < chunk.len() {
                    pending.extend_from_slice(&chunk[take..]);
                }
            }
            Err(_) => break,
        }
    }
    Ok(out)
}

use tokio::sync::mpsc;

/// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
///
/// 关闭传播：reader 检测到对端关闭（流结束/错误）后 drop 自己持有的
/// `to_quic_tx` 克隆，使主循环 `to_quic_rx.recv()` 返回 None 而收尾
/// （置 CLOSED、清理 registry）。此后 Java 侧 writeChunk 也会因 channel
/// 关闭得到错误——读写两条路径都能感知连接死亡，客户端不会挂起。
pub async fn run_connection(
    conn_id: u64,
    conn: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_quic_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Vec<u8>>,
    to_quic_tx: mpsc::Sender<Command>,
    state: std::sync::Arc<std::sync::atomic::AtomicU32>,
) {
    let reader = {
        let to_quic_tx = to_quic_tx.clone();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 65536];
            loop {
                match recv.read(&mut buf).await {
                    Ok(Some(n)) => {
                        if to_java_tx.send(buf[..n].to_vec()).await.is_err() {
                            break;
                        }
                    }
                    Ok(None) => break,
                    Err(_) => break,
                }
            }
            // 对端已关闭：释放 sender，令主循环的 recv() 返回 None。
            drop(to_quic_tx);
        })
    };

    loop {
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
    conns().remove(&conn_id);
}
