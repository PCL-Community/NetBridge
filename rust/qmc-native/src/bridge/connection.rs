//! 单连接数据面：状态查询、批量读写、优雅关闭与读写循环。

use std::sync::atomic::Ordering;

use super::Command;
use super::registry::registry;

/// 查询连接状态；不存在返回 None（Java 映射为 UNKNOWN）。
pub fn connection_state(conn: u64) -> Option<u32> {
    registry()
        .lock()
        .unwrap()
        .conns
        .get(&conn)
        .map(|h| h.state.load(Ordering::SeqCst))
}

/// 关闭连接（优雅结束发送侧，等待 QUIC 任务收尾）。
pub fn close_connection(conn: u64) -> bool {
    let reg = registry().lock().unwrap();
    let Some(handle) = reg.conns.get(&conn) else {
        return false;
    };
    handle.state.store(super::STATE_CLOSED, Ordering::SeqCst);
    handle.to_quic.try_send(Command::Close).is_ok()
}

/// 写入一段字节到 QUIC 流。返回实际入队字节数：
/// - 满队列返回 0（Java 侧应做背压缓冲，不可丢弃）；
/// - 连接未就绪返回 0；
/// - 连接已关闭返回 Err。
pub fn write_chunk(conn: u64, data: &[u8]) -> Result<usize, String> {
    if data.is_empty() {
        return Ok(0);
    }
    let reg = registry().lock().unwrap();
    let handle = reg
        .conns
        .get(&conn)
        .ok_or_else(|| "no such connection".to_string())?;
    if handle.state.load(Ordering::SeqCst) != super::STATE_CONNECTED {
        return Ok(0);
    }
    match handle.to_quic.try_send(Command::Write(data.to_vec())) {
        Ok(()) => Ok(data.len()),
        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => Ok(0),
        Err(_) => Err("connection closed".to_string()),
    }
}

/// 读取最多 max_bytes 字节；无数据时返回空 Vec。
pub fn read_chunk(conn: u64, max_bytes: usize) -> Result<Vec<u8>, String> {
    let reg = registry().lock().unwrap();
    let handle = reg
        .conns
        .get(&conn)
        .ok_or_else(|| "no such connection".to_string())?;
    let mut guard = handle.to_java.lock().unwrap();
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
pub async fn run_connection(
    conn_id: u64,
    conn: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_quic_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Vec<u8>>,
    state: std::sync::Arc<std::sync::atomic::AtomicU32>,
) {
    let reader = tokio::spawn(async move {
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
    });

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

    state.store(super::STATE_CLOSED, Ordering::SeqCst);
    reader.abort();
    let _ = reader.await;
    conn.close(0u32.into(), b"qmc close");
    registry().lock().unwrap().conns.remove(&conn_id);
}
