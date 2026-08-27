//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use super::connection::run_connection;
use crate::bridge::error::{BridgeError, Transport};
use crate::bridge::registry::{allocate_id, conns, remove_conn, report_error, runtime};
use crate::bridge::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

/// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
///
/// DNS 解析、UDP 绑定与握手全部在 runtime 任务内进行：本函数由 JNI 从
/// Netty 事件循环线程调用，任何同步阻塞（DNS 可达数秒）都会冻结同一
/// EventLoop 上的全部 channel。失败路径置 FAILED、上报日志并就地移除
/// 注册表条目（不依赖 Java close 兜底，消除 channel 早亡时的泄漏）。
pub fn connect(host: &str, port: u16) -> Result<u64, BridgeError> {
    let Some(rt) = runtime() else {
        // 日志由 JNI 导出层对 Err 统一上报，此处不重复。
        return Err(BridgeError::RuntimeUnavailable);
    };
    let (conn_id, state, to_java_tx, mut to_transport_rx, to_transport_tx) = register_client();
    let host = host.to_string();
    rt.spawn(async move {
        let panicked = crate::bridge::guarded("quic connect task", async move {
            if let Some((conn, send, recv)) =
                establish(&host, port, conn_id, &state, &mut to_transport_rx).await
            {
                state.store(STATE_CONNECTED, Ordering::SeqCst);
                run_connection(
                    conn_id,
                    conn,
                    send,
                    recv,
                    to_transport_rx,
                    to_java_tx,
                    to_transport_tx,
                    state,
                )
                .await;
            }
        })
        .await;
        if panicked {
            remove_conn(conn_id);
        }
    });
    Ok(conn_id)
}

/// 注册占位句柄（状态 CONNECTING）并返回建连任务与数据循环所需各端。
fn register_client() -> (
    u64,
    Arc<AtomicU32>,
    mpsc::Sender<Bytes>,
    mpsc::Receiver<Command>,
    mpsc::Sender<Command>,
) {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let conn_id = allocate_id();
    conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            None,
            None,
            false,
            true,
            None,
        ),
    );
    (conn_id, state, to_java_tx, to_transport_rx, to_transport_tx)
}

/// 建立连接：解析 → 绑定 → endpoint → 取消感知握手 → 双向流。
///
/// 失败（错误或外部 Close 取消）均就地清理注册表条目并返回 None。
async fn establish(
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    to_transport_rx: &mut mpsc::Receiver<Command>,
) -> Option<(quinn::Connection, quinn::SendStream, quinn::RecvStream)> {
    // 先解析目标地址，按其地址族选择本地绑定（IPv4 目标绑 0.0.0.0，
    // IPv6 绑 [::]，否则 quinn 会报 invalid remote address）。
    let addr = resolve_addr(host, port, conn_id, state).await?;
    // socket2 统一底座：按目标地址族绑定对应未指定地址，显式缓冲区
    // 设置；v6 目标尝试双栈（失败降级 v6-only，见助手实现）。
    let socket = match crate::bridge::socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            return fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "client bind",
                    source: e,
                },
            );
        }
    };
    let mut endpoint = match quinn::Endpoint::new(
        quinn::EndpointConfig::default(),
        None,
        socket,
        Arc::new(quinn::TokioRuntime),
    ) {
        Ok(ep) => ep,
        Err(e) => {
            return fail(
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "endpoint",
                    source: e,
                },
            );
        }
    };
    endpoint.set_default_client_config(quinn_plaintext::client_config());

    let connecting = match endpoint.connect(addr, "plaintext.test") {
        Ok(connecting) => connecting,
        Err(e) => return fail(conn_id, state, connect_error(addr, e)),
    };
    // 黑洞下 connecting.await 永久悬挂（quinn PTO 无放弃逻辑）；Java watchdog
    // 超时经 closeConnection 发 Close 并置 CLOSED。select 命令通道：收到 Close
    // 即放弃握手（Drop Connecting 停止 PTO）并注销，否则任务、endpoint、
    // socket 与注册表条目随每次失败连接永存。
    let conn = tokio::select! {
        result = connecting => match result {
            Ok(conn) => conn,
            Err(e) => return fail(conn_id, state, connect_error(addr, e)),
        },
        // 外部关闭（watchdog/closeConnection）：Close 命令或通道关闭均视为取消。
        _ = to_transport_rx.recv() => {
            cancel(conn_id, state);
            return None;
        }
    };
    let (send, recv) = match conn.open_bi().await {
        Ok(pair) => pair,
        Err(e) => return fail(conn_id, state, connect_error(addr, e)),
    };
    // open_bi 期间仍可能被外部关闭：已 CLOSED 的连接不得复活为 CONNECTED。
    if state.load(Ordering::SeqCst) == STATE_CLOSED {
        cancel(conn_id, state);
        return None;
    }
    Some((conn, send, recv))
}

/// 解析目标地址；失败上报并注销连接，返回 None 由任务收尾。
async fn resolve_addr(
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
) -> Option<SocketAddr> {
    match tokio::net::lookup_host((host, port)).await {
        Ok(mut addrs) => addrs.next().or_else(|| {
            fail(
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source: io_no_address(),
                },
            )
        }),
        Err(source) => fail(
            conn_id,
            state,
            BridgeError::Dns {
                host: host.to_owned(),
                port,
                source,
            },
        ),
    }
}

/// 组装建连错误（quinn ConnectError 无 io::Error 转换，装箱承载）。
fn connect_error(
    addr: SocketAddr,
    source: impl std::error::Error + Send + Sync + 'static,
) -> BridgeError {
    BridgeError::Connect {
        transport: Transport::Quic,
        addr,
        source: Box::new(source),
    }
}

/// "解析成功但无地址"的合成错误（lookup_host 空迭代无独立错误类型）。
fn io_no_address() -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::NotFound, "no address resolved")
}

/// 标记连接失败、上报日志并移除注册表条目（自清理，无泄漏窗口）。
/// 返回 None 供 `?`/`return` 短路建连任务。
fn fail<T>(conn_id: u64, state: &Arc<AtomicU32>, err: BridgeError) -> Option<T> {
    state.store(crate::bridge::STATE_FAILED, Ordering::SeqCst);
    remove_conn(conn_id);
    report_error(err.message());
    None
}

/// 外部取消收尾：置 CLOSED 并移除注册表条目（任务随返回释放 endpoint 与 socket）。
fn cancel(conn_id: u64, state: &Arc<AtomicU32>) {
    state.store(STATE_CLOSED, Ordering::SeqCst);
    remove_conn(conn_id);
}
