//! KCP 单连接数据面：控制字帧读写循环与关闭传播。
//!
//! 结构镜像 QUIC 的 `run_connection`（bridge/connection.rs）：经
//! [`tokio::io::split`] 拆出读写两半——读任务推 Java 队列，主循环消费
//! Java 命令并 select 读任务句柄感知对端关闭。关闭语义：本端 Close 发送
//! FRAME_CLOSE 控制帧后收尾；对端读侧据此优雅置 CLOSED（替代死等超时）。

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::io::{AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

use super::fec_stream::FecStream;
use super::frame;
use crate::bridge::registry::remove_conn;
use crate::bridge::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, STATE_FAILED};

type KcpFec = FecStream<tokio_kcp::KcpStream>;

/// KCP 会话主循环：`fec` 为已建立的纠错流。
///
/// 建连（无握手模型）：`client_side=true` 首发 FRAME_PROBE 探测帧，服务端
/// （false）接纳后立即回发 FRAME_PONG——客户端以此置位 CONNECTED，打破
/// 「服务端按首包建会话 / 客户端等首帧才回」互等死锁。
///
/// 关闭传播：
/// - 本端 `Command::Close` → FRAME_CLOSE + shutdown → CLOSED；
/// - 对端 FRAME_CLOSE / 防御性 EOF → 读任务退出 → 主循环 channel 关闭收尾；
/// - IO 错误（解码超限/截断等）→ FAILED。
pub async fn run_kcp_connection(
    conn_id: u64,
    fec: KcpFec,
    mut to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    client_side: bool,
) {
    let (mut fec_r, mut fec_w) = tokio::io::split(fec);
    // 建连破环帧：写失败不致命（KCP 重传保证最终送达）；对端无存活则由
    // 看门狗兜底，此帧仅加速双向可达判定。
    let first = if client_side {
        frame::FRAME_PROBE
    } else {
        frame::FRAME_PONG
    };
    let _ = frame::write_frame(&mut fec_w, first, b"").await;

    // 读任务退出信号：JoinHandle 不能在完成后再次 poll（会 panic），
    // 故以 channel 通知主循环，句柄仅用于超时兜底 abort。
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<()>(1);
    let reader_state = Arc::clone(&state);
    let reader = tokio::spawn(async move {
        let reader_state = reader_state;
        let mut payload = Vec::with_capacity(4096);
        loop {
            let readed = frame::read_frame(&mut fec_r, &mut payload).await;
            match readed {
                Ok((frame::FRAME_DATA, len)) => {
                    if len == 0 {
                        continue; // 空数据帧合法但无意义
                    }
                    // 首个有效数据帧送达 Java 队列即视为连接存活
                    // （客户端 CONNECTED 判定；服务端注册时已为 CONNECTED）。
                    let _ = reader_state.compare_exchange(
                        STATE_CONNECTING,
                        STATE_CONNECTED,
                        Ordering::SeqCst,
                        Ordering::SeqCst,
                    );
                    // Vec → Bytes 零拷贝接管后换新缓冲，避免每帧堆分配。
                    let chunk: Bytes = std::mem::take(&mut payload).into();
                    payload = Vec::with_capacity(4096.max(chunk.len()));
                    if to_java_tx.send(chunk).await.is_err() {
                        break;
                    }
                }
                Ok((frame::FRAME_CLOSE, _)) => break,
                Ok((frame::FRAME_PROBE, _)) => {
                    continue; // 探测帧无数据语义：服务端侧忽略。
                }
                Ok((frame::FRAME_PONG, _)) => {
                    // 存活应答：客户端以此置位 CONNECTED（服务端侧防御性忽略）。
                    let _ = reader_state.compare_exchange(
                        STATE_CONNECTING,
                        STATE_CONNECTED,
                        Ordering::SeqCst,
                        Ordering::SeqCst,
                    );
                    continue;
                }
                Ok((other, _)) => {
                    // 未知类型：协议不匹配，连接不可信。
                    let _ = other;
                    reader_state.store(STATE_FAILED, Ordering::SeqCst);
                    break;
                }
                Err(_) => {
                    // 解码超限/截断：数据不可信。FAILED 由收尾规则保留，
                    // 不被 CLOSED 覆盖（与 QUIC 一致，Java poll 依赖区分）。
                    reader_state.store(STATE_FAILED, Ordering::SeqCst);
                    break;
                }
            }
        }
        let _ = reader_done_tx.send(()).await;
    });

    loop {
        // Close 命令可能因队列满丢失，或状态已由外部置 CLOSED；兜底收尾。
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            graceful_close(&mut fec_w).await;
            break;
        }
        tokio::select! {
            biased;

            _ = reader_done_rx.recv() => {
                // 读侧先行终止：对端关闭（CLOSE 帧/防御性 EOF）或不可信
                // 错误。FAILED 已由读任务写入；此处推进本地收尾。
                graceful_close(&mut fec_w).await;
                break;
            }
            cmd = to_kcp_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    if write_data_frame(&mut fec_w, &bytes).await.is_err() {
                        state.store(STATE_FAILED, Ordering::SeqCst);
                        break;
                    }
                }
                Some(Command::Close) => {
                    graceful_close(&mut fec_w).await;
                    break;
                }
                None => {
                    // 全部发送端关闭（外部句柄释放）→ 收尾。
                    graceful_close(&mut fec_w).await;
                    break;
                }
            },
        }
    }

    // 与 QUIC 一致的状态迁移：FAILED 优先保留。
    match state.load(Ordering::SeqCst) {
        STATE_CONNECTING | STATE_CONNECTED => {
            state.store(STATE_CLOSED, Ordering::SeqCst);
        }
        _ => {}
    }
    reader.abort();
    remove_conn(conn_id);
}

/// 尽力而为的优雅关闭：发 CLOSE 帧并 shutdown 底层流；
/// 对端能否收到不影响本地状态机推进。
async fn graceful_close(fec_w: &mut (impl AsyncWrite + Unpin)) {
    let _ = frame::write_frame(fec_w, frame::FRAME_CLOSE, b"").await;
    let _ = AsyncWriteExt::shutdown(fec_w).await;
}

/// 写一帧数据；>u16 的块切分（防御路径；JNI 层块 ≤64KiB）。
/// 空块跳过——空数据帧无接收方语义。
async fn write_data_frame(fec_w: &mut (impl AsyncWrite + Unpin), data: &Bytes) -> io::Result<()> {
    for chunk in data.chunks(u16::MAX as usize) {
        frame::write_frame(fec_w, frame::FRAME_DATA, chunk).await?;
    }
    Ok(())
}
