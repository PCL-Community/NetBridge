//! KCP 单连接数据面：smux 会话内的 MC 字节流读写循环与关闭传播。
//!
//! 结构与 QUIC 的 `run_connection`（bridge/quic/connection.rs）同构：经
//! [`tokio::io::split`] 拆出读写两半——读任务推 Java 队列，主循环消费
//! Java 命令并 select 读任务句柄感知对端关闭。
//!
//! 流控与关闭语义（原 frame.rs 控制字层已移除）：
//! - 流控：smux 会话内置滑动窗口 token 池（`max_receive_buffer`），Java
//!   侧慢消费时 token 不归还、读任务阻塞，背压经 KCP 天然回传；
//! - 关闭：本端 Close → FIN 关流 + 会话关闭；对端 FIN → 读侧干净 EOF
//!   （CLOSED）；对端会话死亡 → 读侧 BrokenPipe（FAILED，与 QUIC 一致）。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

use super::fec_stream::FecStream;
use crate::bridge::registry::remove_conn;
use crate::bridge::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING, STATE_FAILED};

type KcpFec = FecStream<kcp::KcpStream>;

/// smux 会话配置：帧上限 64KiB（与 Java 侧块上限一致），其余取默认
/// （流量窗口 4MB、keep-alive 10s/30s）。
fn smux_config() -> Config {
    ConfigBuilder::new()
        .max_frame_size(64 * 1024)
        .build()
        .expect("static smux config")
}

/// KCP 会话主循环：`fec` 为已建立的纠错流（承载 smux 会话）。
///
/// 建连（kcp-rs 握手模型）：`connect`/`accept` 已完成 SYN 交换，此函数不再
/// 发送探测帧；CONNECTED 由调用方在握手完成后置位。
///
/// 关闭传播：
/// - 本端 `Command::Close` → FIN + 会话关闭 → CLOSED；
/// - 对端 FIN → 读侧 EOF → 主循环收尾 → CLOSED；
/// - 对端会话死亡 / 传输错误 → 读侧错误 → FAILED（优先级保留）。
pub async fn run_kcp_connection(
    conn_id: u64,
    fec: KcpFec,
    mut to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    client_side: bool,
) {
    // 建立 smux 会话：每条 KCP 连接携带单条 MC 字节流。
    let session = match if client_side {
        Session::client(fec, smux_config()).await
    } else {
        Session::server(fec, smux_config()).await
    } {
        Ok(s) => s,
        Err(_) => {
            state.store(STATE_FAILED, Ordering::SeqCst);
            remove_conn(conn_id);
            return;
        }
    };
    // 客户端主动开流、服务端被动接受；流同步由 smux SYN 帧完成。
    let stream = match if client_side {
        session.open_stream().await
    } else {
        session.accept_stream().await
    } {
        Ok(s) => s,
        Err(_) => {
            state.store(STATE_FAILED, Ordering::SeqCst);
            remove_conn(conn_id);
            return;
        }
    };

    let (mut stream_r, mut stream_w) = tokio::io::split(stream);
    // 读任务退出信号：先发 true=对端 FIN（干净 EOF），false=错误（FAILED）。
    // JoinHandle 不能在完成后再次 poll（会 panic），故以 channel 通知主循环，
    // 句柄仅用于超时兜底 abort。读任务不写状态原子——FAILED/CLOSED 归属主循环。
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<bool>(1);
    let reader = tokio::spawn(async move {
        let mut payload = BytesMut::with_capacity(64 * 1024);
        let clean = loop {
            payload.clear();
            match stream_r.read_buf(&mut payload).await {
                Ok(0) => break true, // 对端 FIN：流关闭，无错误。
                Ok(_) => {
                    // BytesMut → Bytes 零拷贝接管；被 Java 消费后不再持有缓冲。
                    if to_java_tx.send(payload.split().freeze()).await.is_err() {
                        break true;
                    }
                }
                Err(_) => break false, // 对端会话死亡/传输错误：FAILED。
            }
        };
        let _ = reader_done_tx.send(clean).await;
    });

    loop {
        // Close 命令可能因队列满丢失，或状态已由外部置 CLOSED；兜底收尾。
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            graceful_close(&mut stream_w, &session).await;
            break;
        }
        tokio::select! {
            biased;

            clean = reader_done_rx.recv() => {
                // 对端 FIN（clean=true）：正常收尾，状态最终置 CLOSED；
                // 对端会话死亡/传输错误（clean=false）：FAILED 由收尾规则
                // 保留，不被 CLOSED 覆盖（与 QUIC 一致，Java poll 依赖区分）。
                if clean == Some(false) {
                    state.store(STATE_FAILED, Ordering::SeqCst);
                }
                graceful_close(&mut stream_w, &session).await;
                break;
            }
            cmd = to_kcp_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    if bytes.is_empty() {
                        continue;
                    }
                    if stream_w.write_all(&bytes).await.is_err() {
                        state.store(STATE_FAILED, Ordering::SeqCst);
                        break;
                    }
                }
                Some(Command::Close) => {
                    graceful_close(&mut stream_w, &session).await;
                    break;
                }
                None => {
                    // 全部发送端关闭（外部句柄释放）→ 收尾。
                    graceful_close(&mut stream_w, &session).await;
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

/// 尽力而为的优雅关闭：FIN 关流 + 无条件关闭会话；对端能否收到不影响
/// 本地状态机推进。
async fn graceful_close(stream_w: &mut (impl AsyncWrite + Unpin), session: &Session) {
    let _ = stream_w.shutdown().await;
    let _ = session.close().await;
}
