//! KCP 单连接数据面：smux 会话承载 MC 字节流，读写循环与关闭传播。
//!
//! 结构镜像 QUIC 的 `run_connection`：读任务推 Java 队列，主循环消费 Java
//! 命令并感知对端关闭。流控由 smux 滑动窗口 token 承担；关闭经 FIN（对端
//! 见干净 EOF）+ 会话撤销实现。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use kcp::KcpStream;
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::sync::mpsc;

use super::fec_stream::FecStream;
use crate::bridge::registry::remove_conn;
use crate::bridge::{Command, STATE_CLOSED, STATE_FAILED, report_error};

type KcpFec = FecStream<KcpStream>;

/// smux 配置：帧上限与 Java 侧块上限对齐，其余取默认值。
fn smux_config() -> Config {
    ConfigBuilder::new()
        .max_frame_size(64 * 1024)
        .build()
        .expect("static smux config")
}

/// KCP 会话主循环：建会话 → 建流 → 读写驱动 → 收尾。
pub async fn run_kcp_connection(
    conn_id: u64,
    fec: KcpFec,
    to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    client_side: bool,
) {
    let Some(session) = create_session(conn_id, fec, client_side, &state).await else {
        return;
    };
    let Some(stream) = open_mc_stream(conn_id, &session, client_side, &state).await else {
        return;
    };

    let (stream_r, stream_w) = tokio::io::split(stream);
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<bool>(1);
    let reader = tokio::spawn(reader_loop(
        conn_id,
        stream_r,
        to_java_tx,
        state.clone(),
        reader_done_tx,
    ));

    drive(
        conn_id,
        stream_w,
        &session,
        to_kcp_rx,
        &mut reader_done_rx,
        &state,
    )
    .await;

    // 兜底：正常路径 reader 已自行退出并发出 done 信号。
    reader.abort();
    remove_conn(conn_id);
}

/// 建立 smux 会话；失败置 FAILED 并注销连接，返回 None。
async fn create_session(
    conn_id: u64,
    fec: KcpFec,
    client_side: bool,
    state: &AtomicU32,
) -> Option<Session> {
    let result = if client_side {
        Session::client(fec, smux_config()).await
    } else {
        Session::server(fec, smux_config()).await
    };
    request_fail(
        conn_id,
        state,
        result.map_err(|e| format!("smux session setup failed: {e}")),
    )
}

/// 打开（客户端）或接受（服务端）本连接的 MC 字节流。
async fn open_mc_stream(
    conn_id: u64,
    session: &Session,
    client_side: bool,
    state: &AtomicU32,
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
    )
}

/// Result 失败收尾：上报并置 FAILED；成功原样返回。
fn request_fail<T>(conn_id: u64, state: &AtomicU32, result: Result<T, String>) -> Option<T> {
    match result {
        Ok(value) => Some(value),
        Err(msg) => {
            report_error(format!("kcp conn {conn_id}: {msg}"));
            state.store(STATE_FAILED, Ordering::SeqCst);
            remove_conn(conn_id);
            None
        }
    }
}

/// 读循环：字节入 Java 队列。
///
/// 结束信号为 bool：`true` 表示干净收尾（对端 FIN，或本端已先置 CLOSED 的
/// 自关闭竞态），`false` 表示真实错误（对端会话死亡/传输错误）。
async fn reader_loop(
    conn_id: u64,
    mut stream_r: ReadHalf<smux::Stream>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    done_tx: mpsc::Sender<bool>,
) {
    let mut payload = BytesMut::with_capacity(64 * 1024);
    let clean = loop {
        payload.clear();
        match stream_r.read_buf(&mut payload).await {
            Ok(0) => break true, // 对端 FIN。
            Ok(_) => {
                // BytesMut → Bytes 零拷贝接管。
                if to_java_tx.send(payload.split().freeze()).await.is_err() {
                    break true;
                }
            }
            Err(_) if state.load(Ordering::SeqCst) == STATE_CLOSED => break true,
            Err(e) => {
                report_error(format!("kcp conn {conn_id}: read error: {e}"));
                break false;
            }
        }
    };
    let _ = done_tx.send(clean).await;
}

/// 主循环：命令下发 + 关闭感知；退出前统一优雅收尾。
async fn drive(
    conn_id: u64,
    mut stream_w: WriteHalf<smux::Stream>,
    session: &Session,
    mut cmds: mpsc::Receiver<Command>,
    reader_done: &mut mpsc::Receiver<bool>,
    state: &AtomicU32,
) {
    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            break; // 外部已置 CLOSED（close_connection/stop_server）。
        }
        tokio::select! {
            biased;

            done = reader_done.recv() => {
                if done == Some(false) {
                    state.store(STATE_FAILED, Ordering::SeqCst);
                }
                break;
            }
            cmd = cmds.recv() => {
                let closed = match cmd {
                    Some(Command::Write(bytes)) if !bytes.is_empty() => {
                        if let Err(e) = stream_w.write_all(&bytes).await {
                            report_error(format!("kcp conn {conn_id}: write error: {e}"));
                            state.store(STATE_FAILED, Ordering::SeqCst);
                            true
                        } else {
                            false
                        }
                    }
                    Some(Command::Close) | None => true, // 关停，或全部发送端释放。
                    _ => false,                          // 空块无语义，跳过。
                };
                if closed {
                    break;
                }
            }
        }
    }
    if state.load(Ordering::SeqCst) != STATE_FAILED {
        // 自关闭：先置 CLOSED 再关会话，reader 据此区分收尾与真错（FAILED 优先保留）。
        state.store(STATE_CLOSED, Ordering::SeqCst);
    }
    graceful_close(&mut stream_w, session).await;
}

/// 优雅关闭：FIN 关流对端先见干净 EOF，最后整体撤销会话。
async fn graceful_close(stream_w: &mut (impl AsyncWrite + Unpin), session: &Session) {
    let _ = stream_w.shutdown().await;
    let _ = session.close().await;
}
