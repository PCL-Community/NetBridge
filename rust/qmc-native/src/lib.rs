//! qmc-native: QUIC（quinn-plaintext）传输的 JNI 桥。
//!
//! 架构（ADR-0001）：
//! - `bridge` 模块持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//!   提供同步的 server/client/state/read/write 原语；
//! - JNI 导出把这些原语暴露给 Java（`top.tangge233.qmc.jni.QuicNative`），
//!   每条 QUIC 连接 = 一个双向流，承载整个 MC 会话字节流。

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jstring};
use jni::JNIEnv;
use quinn::{Connection, Endpoint, RecvStream, SendStream};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

pub const QMC_ABI_VERSION: &str = "0.1.0";
pub const QMC_RAW_FEATURE: &str = "quic-raw";

/// 连接状态（与 Java `QuicConnectionState` 一致）。
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

/// QUIC 传输桥核心：可脱离 JNI 单独测试。
pub mod bridge {
    use super::*;

    /// 发往 QUIC 写任务的控制命令。
    enum Command {
        Write(Vec<u8>),
        Close,
    }

    pub struct ConnHandle {
        state: Arc<AtomicU32>,
        /// Java 读侧队列 + 未取走的残留字节（防 chunk 被截断丢弃）。
        to_java: Mutex<(mpsc::Receiver<Vec<u8>>, Vec<u8>)>,
        to_quic: mpsc::Sender<Command>,
        server_id: Option<u64>,
        /// 服务端连接是否已被 Java 通过 acceptConnections 取走。
        reported: AtomicBool,
    }

    struct ServerHandle {
        endpoint: Endpoint,
        port: u16,
    }

    struct Registry {
        next_id: u64,
        conns: HashMap<u64, ConnHandle>,
        servers: HashMap<u64, ServerHandle>,
        last_error: Option<String>,
    }

    static RUNTIME: OnceLock<Arc<Runtime>> = OnceLock::new();
    static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();

    fn runtime() -> &'static Arc<Runtime> {
        RUNTIME.get_or_init(|| {
            Arc::new(Runtime::new().expect("failed to create tokio runtime"))
        })
    }

    fn registry() -> &'static Mutex<Registry> {
        REGISTRY.get_or_init(|| {
            Mutex::new(Registry {
                next_id: 1,
                conns: HashMap::new(),
                servers: HashMap::new(),
                last_error: None,
            })
        })
    }

    fn allocate_id(reg: &mut Registry) -> u64 {
        let id = reg.next_id;
        reg.next_id += 1;
        id
    }

    /// 启动服务端 QUIC acceptor（端口 0 表示由系统分配）。
    pub fn start_server(port: u16) -> Result<u64, String> {
        let rt = runtime();
        let server_config = quinn_plaintext::server_config();
        let endpoint = {
            let _guard = rt.enter();
            Endpoint::server(server_config, ([0, 0, 0, 0], port).into())
                .map_err(|e| format!("bind udp/{port}: {e}"))?
        };
        let actual_port = endpoint
            .local_addr()
            .map_err(|e| format!("local addr: {e}"))?
            .port();

        let server_id = {
            let mut reg = registry().lock().unwrap();
            let id = allocate_id(&mut reg);
            reg.servers.insert(
                id,
                ServerHandle {
                    endpoint: endpoint.clone(),
                    port: actual_port,
                },
            );
            id
        };

        let accept_endpoint = endpoint.clone();
        rt.spawn(async move {
            loop {
                let incoming = match accept_endpoint.accept().await {
                    Some(incoming) => incoming,
                    // endpoint 被 stopServer 关闭。
                    None => break,
                };
                rt.spawn(async move {
                    match incoming.await {
                        Ok(conn) => {
                            let reg = register_connection(server_id);
                            match conn.accept_bi().await {
                                Ok((send, recv)) => {
                                    run_connection(
                                        reg.conn_id,
                                        conn,
                                        send,
                                        recv,
                                        reg.to_quic_rx,
                                        reg.to_java_tx,
                                        reg.state,
                                    )
                                    .await;
                                }
                                Err(_) => {
                                    reg.state.store(STATE_FAILED, Ordering::SeqCst);
                                    registry().lock().unwrap().conns.remove(&reg.conn_id);
                                }
                            }
                        }
                        Err(_) => {}
                    }
                });
            }
        });
        Ok(server_id)
    }

    /// 查询服务端实际绑定端口。
    pub fn server_port(server: u64) -> Option<u16> {
        registry()
            .lock()
            .unwrap()
            .servers
            .get(&server)
            .map(|h| h.port)
    }

    /// 停止服务端并关闭其全部连接。
    pub fn stop_server(server: u64) -> bool {
        let mut reg = registry().lock().unwrap();
        let Some(handle) = reg.servers.remove(&server) else {
            return false;
        };
        handle.endpoint.close(0u32.into(), b"qmc stop");
        let conn_ids: Vec<u64> = reg
            .conns
            .iter()
            .filter(|(_, h)| h.server_id == Some(server))
            .map(|(id, _)| *id)
            .collect();
        for id in conn_ids {
            if let Some(h) = reg.conns.get(&id) {
                h.state.store(STATE_CLOSED, Ordering::SeqCst);
                let _ = h.to_quic.try_send(Command::Close);
            }
        }
        true
    }

    /// 客户端发起 QUIC 连接（异步握手，立即返回连接 id）。
    pub fn connect(host: &str, port: u16) -> Result<u64, String> {
        let rt = runtime();
        let mut endpoint = {
            let _guard = rt.enter();
            Endpoint::client("0.0.0.0:0".parse().unwrap())
                .map_err(|e| format!("udp client: {e}"))?
        };
        endpoint.set_default_client_config(quinn_plaintext::client_config());

        let (to_quic_tx, to_quic_rx) = mpsc::channel::<Command>(4096);
        let (to_java_tx, to_java_rx) = mpsc::channel::<Vec<u8>>(8192);
        let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
        let conn_id = {
            let mut reg = registry().lock().unwrap();
            let id = allocate_id(&mut reg);
            reg.conns.insert(
                id,
                ConnHandle {
                    state: state.clone(),
                    to_java: Mutex::new((to_java_rx, Vec::new())),
                    to_quic: to_quic_tx,
                    server_id: None,
                    reported: AtomicBool::new(true),
                },
            );
            id
        };

        let host_owned = host.to_string();
        rt.spawn(async move {
            let addr = match tokio::net::lookup_host((host_owned.as_str(), port)).await {
                Ok(mut addrs) => addrs.next(),
                Err(_) => None,
            };
            let Some(addr) = addr else {
                state.store(STATE_FAILED, Ordering::SeqCst);
                return;
            };

            let conn = match endpoint.connect(addr, "plaintext.test") {
                Ok(connecting) => match connecting.await {
                    Ok(conn) => conn,
                    Err(_) => {
                        state.store(STATE_FAILED, Ordering::SeqCst);
                        return;
                    }
                },
                Err(_) => {
                    state.store(STATE_FAILED, Ordering::SeqCst);
                    return;
                }
            };
            let (send, recv) = match conn.open_bi().await {
                Ok(pair) => pair,
                Err(_) => {
                    state.store(STATE_FAILED, Ordering::SeqCst);
                    return;
                }
            };
            state.store(STATE_CONNECTED, Ordering::SeqCst);
            run_connection(conn_id, conn, send, recv, to_quic_rx, to_java_tx, state).await;
        });
        Ok(conn_id)
    }

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
        handle.state.store(STATE_CLOSED, Ordering::SeqCst);
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
        if handle.state.load(Ordering::SeqCst) != STATE_CONNECTED {
            return Ok(0);
        }
        match handle.to_quic.try_send(Command::Write(data.to_vec())) {
            Ok(()) => Ok(data.len()),
            Err(mpsc::error::TrySendError::Full(_)) => Ok(0),
            Err(mpsc::error::TrySendError::Closed(_)) => Err("connection closed".to_string()),
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

    /// 取回服务端尚未上报的新连接 id 列表。
    pub fn accept_connections(server: u64) -> Vec<u64> {
        let reg = registry().lock().unwrap();
        let mut out = Vec::new();
        for (id, handle) in reg.conns.iter() {
            if handle.server_id == Some(server) && !handle.reported.swap(true, Ordering::SeqCst) {
                out.push(*id);
            }
        }
        out
    }

    /// 记录最近一次错误（JNI 侧读取并清空）。
    pub fn last_error() -> Option<String> {
        registry().lock().unwrap().last_error.take()
    }

    pub fn set_last_error(msg: String) {
        registry().lock().unwrap().last_error = Some(msg);
    }

    /// 服务端握手完成后立即注册连接句柄（不等待首个流数据）。
    struct Registered {
        conn_id: u64,
        to_quic_rx: mpsc::Receiver<Command>,
        to_java_tx: mpsc::Sender<Vec<u8>>,
        state: Arc<AtomicU32>,
    }

    fn register_connection(server_id: u64) -> Registered {
        let (to_quic_tx, to_quic_rx) = mpsc::channel::<Command>(4096);
        let (to_java_tx, to_java_rx) = mpsc::channel::<Vec<u8>>(8192);
        let state = Arc::new(AtomicU32::new(STATE_CONNECTED));
        let conn_id = {
            let mut reg = registry().lock().unwrap();
            let id = allocate_id(&mut reg);
            reg.conns.insert(
                id,
                ConnHandle {
                    state: state.clone(),
                    to_java: Mutex::new((to_java_rx, Vec::new())),
                    to_quic: to_quic_tx,
                    server_id: Some(server_id),
                    reported: AtomicBool::new(false),
                },
            );
            id
        };
        Registered {
            conn_id,
            to_quic_rx,
            to_java_tx,
            state,
        }
    }

    /// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
    async fn run_connection(
        conn_id: u64,
        conn: Connection,
        mut send: SendStream,
        mut recv: RecvStream,
        mut to_quic_rx: mpsc::Receiver<Command>,
        to_java_tx: mpsc::Sender<Vec<u8>>,
        state: Arc<AtomicU32>,
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

        state.store(STATE_CLOSED, Ordering::SeqCst);
        reader.abort();
        let _ = reader.await;
        conn.close(0u32.into(), b"qmc close");
        registry().lock().unwrap().conns.remove(&conn_id);
    }
}

// ---------------------------------------------------------------------------
// JNI 导出（同步批量桥）
// ---------------------------------------------------------------------------

macro_rules! jni_err {
    ($env:expr, $msg:expr, $default:expr) => {{
        bridge::set_last_error($msg.to_string());
        $default
    }};
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(QMC_ABI_VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_rawFeature(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(QMC_RAW_FEATURE) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_startServer(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jlong {
    match bridge::start_server(port.max(0) as u16) {
        Ok(id) => id as jlong,
        Err(msg) => jni_err!(_env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_serverPort(
    _env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jint {
    bridge::server_port(server as u64).map(|p| p as jint).unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_acceptConnections(
    env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jlongArray {
    let ids = bridge::accept_connections(server as u64);
    match env.new_long_array(ids.len() as jint) {
        Ok(arr) => {
            let raw: Vec<jlong> = ids.into_iter().map(|id| id as jlong).collect();
            if !raw.is_empty() {
                let _ = env.set_long_array_region(&arr, 0, &raw);
            }
            arr.as_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_stopServer(
    _env: JNIEnv,
    _class: JClass,
    server: jlong,
) -> jboolean {
    bridge::stop_server(server as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connect(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    port: jint,
) -> jlong {
    let host_str = match env.get_string(&host) {
        Ok(s) => String::from(s),
        Err(_) => return -1,
    };
    match bridge::connect(&host_str, port.max(0) as u16) {
        Ok(id) => id as jlong,
        Err(msg) => jni_err!(env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_connectionState(
    _env: JNIEnv,
    _class: JClass,
    conn: jlong,
) -> jint {
    bridge::connection_state(conn as u64).map(|s| s as jint).unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_closeConnection(
    _env: JNIEnv,
    _class: JClass,
    conn: jlong,
) -> jboolean {
    bridge::close_connection(conn as u64) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_writeChunk(
    env: JNIEnv,
    _class: JClass,
    conn: jlong,
    data: JByteArray,
) -> jint {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    match bridge::write_chunk(conn as u64, &bytes) {
        Ok(n) => n as jint,
        Err(msg) => jni_err!(env, msg, -1),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_readChunk(
    env: JNIEnv,
    _class: JClass,
    conn: jlong,
    max_bytes: jint,
) -> jbyteArray {
    let max = max_bytes.max(0) as usize;
    let bytes = match bridge::read_chunk(conn as u64, max) {
        Ok(b) => b,
        Err(msg) => {
            bridge::set_last_error(msg);
            return std::ptr::null_mut();
        }
    };
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.as_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_tangge233_qmc_jni_QuicNative_lastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match bridge::last_error() {
        Some(msg) => match env.new_string(&msg) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::bridge::*;
    use super::*;
    use std::time::{Duration, Instant};

    fn wait_state(conn: u64, want: u32) {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if connection_state(conn) == Some(want) {
                return;
            }
            assert!(Instant::now() < deadline, "timeout waiting for state {want}");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn wait_read(conn: u64, want: usize) -> Vec<u8> {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(data) = read_chunk(conn, 65536) {
                if data.len() >= want {
                    return data;
                }
            }
            assert!(Instant::now() < deadline, "timeout waiting for read");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn abi_constants() {
        assert_eq!(super::QMC_ABI_VERSION, "0.1.0");
        assert_eq!(super::QMC_RAW_FEATURE, "quic-raw");
        assert_eq!(super::STATE_CONNECTED, 1);
    }

    #[test]
    fn bridge_loopback_roundtrip() {
        let server = start_server(0).expect("start server");
        let port = server_port(server).expect("server port");
        let client = connect("127.0.0.1", port).expect("connect");
        wait_state(client, STATE_CONNECTED);

        let deadline = Instant::now() + Duration::from_secs(10);
        let accepted = loop {
            let a = accept_connections(server);
            if !a.is_empty() {
                break a;
            }
            assert!(Instant::now() < deadline, "timeout waiting for server accept");
            std::thread::sleep(Duration::from_millis(5));
        };
        assert_eq!(accepted.len(), 1, "server should see one connection");
        let server_conn = accepted[0];
        assert_eq!(connection_state(server_conn), Some(STATE_CONNECTED));

        // client -> server
        let payload = b"quic-mc hello over bridge";
        assert_eq!(write_chunk(client, payload).expect("client write"), payload.len());
        assert_eq!(wait_read(server_conn, payload.len()), payload);

        // server -> client
        let reply = b"pong from server";
        assert_eq!(write_chunk(server_conn, reply).expect("server write"), reply.len());
        assert_eq!(wait_read(client, reply.len()), reply);

        close_connection(client);
        stop_server(server);
        wait_state(client, STATE_CLOSED);
        assert_eq!(connection_state(client), Some(STATE_CLOSED));
    }
}

#[cfg(test)]
mod quinn_smoke {
    #[tokio::test]
    async fn plaintext_bidi_echo() {
        use quinn::Endpoint;

        let server_config = quinn_plaintext::server_config();
        let server = Endpoint::server(server_config, "127.0.0.1:0".parse().unwrap())
            .expect("server endpoint");
        let server_addr = server.local_addr().expect("server local addr");

        let mut client = Endpoint::client("127.0.0.1:0".parse().unwrap())
            .expect("client endpoint");
        client.set_default_client_config(quinn_plaintext::client_config());

        let server_task = tokio::spawn(async move {
            let conn = server.accept().await.expect("accept incoming").await.expect("connect");
            let (mut send, mut recv) = conn.accept_bi().await.expect("accept bi");
            let msg = recv.read_to_end(1024).await.expect("read request");
            send.write_all(&msg).await.expect("echo write");
            send.finish().unwrap();
            let _ = conn.closed().await;
        });

        let conn = client
            .connect(server_addr, "plaintext.test")
            .expect("connect")
            .await
            .expect("connect await");

        let (mut send, mut recv) = conn.open_bi().await.expect("open bi");
        let request = b"quic-mc hello";
        send.write_all(request).await.expect("write");
        send.finish().unwrap();
        let response = recv.read_to_end(1024).await.expect("read response");

        assert_eq!(response, request, "echo mismatch");
        conn.close(0u32.into(), b"done");
        server_task.await;
    }
}
