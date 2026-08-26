//! 数据面原语：连接粒度的状态查询、批量字节读写与关闭（传输无关）。
//!
//! 每个 native 传输（QUIC/KCP）的数据循环都按同一规约写入注册表句柄
//! （[`crate::bridge::ConnHandle`]）：读侧任务把字节推入 `to_java` 队列，
//! 写侧任务消费 `to_transport` 命令。本模块只理解这两条通道与状态原子，
//! 不依赖任何传输实现——QUIC（[`super::quic`]）与 KCP（[`super::kcp`]）共用。
//!
//! 零拷贝约定：读侧经 `read_chunk` 直取 `Bytes`，单块满足时零重组直返
//! JNI 层；超长块用 `slice` 共享视图切分；仅多块拼接时才复制进
//! `BytesMut`。写侧由 JNI 拷出的 `Vec<u8>` 零成本转 `Bytes`。

use std::collections::VecDeque;
use std::sync::atomic::Ordering;

use bytes::{Bytes, BytesMut};
use tokio::sync::mpsc;

use crate::bridge::error::BridgeError;
use crate::bridge::registry::{conns, remove_conn};
use crate::bridge::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_FAILED};

/// 查询连接状态；不存在返回 None（Java 映射为 UNKNOWN）。
pub fn connection_state(conn: u64) -> Option<u32> {
    conns().get(&conn).map(|h| h.state.load(Ordering::SeqCst))
}

/// 关闭连接（优雅结束发送侧，等待传输任务收尾）。
pub fn close_connection(conn: u64) -> bool {
    let Some(handle) = conns().get(&conn) else {
        return false;
    };
    let state = handle.state.clone();
    let to_transport = handle.to_transport.clone();
    drop(handle);
    let was = state.swap(STATE_CLOSED, Ordering::SeqCst);
    let ok = to_transport.try_send(Command::Close).is_ok();
    // 防御性幂等清理：正常路径已由任务收尾/失败回调自清理。
    if was == STATE_FAILED {
        remove_conn(conn);
    }
    ok
}

/// 写入一段字节到连接。返回实际入队字节数：
/// - 满队列返回 0（Java 侧应做背压缓冲，不可丢弃）；
/// - 连接未就绪返回 0；
/// - 连接已关闭返回 Err。
pub fn write_chunk(conn: u64, data: Bytes) -> Result<usize, BridgeError> {
    if data.is_empty() {
        return Ok(0);
    }
    let len = data.len();
    let Some(handle) = conns().get(&conn) else {
        return Err(BridgeError::NoSuchConnection);
    };
    // early_write：KCP 客户端连接期即可写（首帧入站才置 CONNECTED，
    // 出站若同门控会与服务端"按首包建会话"互等死锁）。
    let writable =
        handle.state.load(Ordering::SeqCst) == STATE_CONNECTED || handle.early_write;
    let to_transport = handle.to_transport.clone();
    drop(handle);
    if !writable {
        return Ok(0);
    }
    match to_transport.try_send(Command::Write(data)) {
        Ok(()) => Ok(len),
        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => Ok(0),
        Err(_) => Err(BridgeError::ConnectionClosed),
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
pub fn read_chunk(conn: u64, max_bytes: usize) -> Result<Bytes, BridgeError> {
    let Some(handle) = conns().get(&conn) else {
        return Err(BridgeError::NoSuchConnection);
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
