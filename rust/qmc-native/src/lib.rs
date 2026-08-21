//! qmc-native: QUIC（quinn-plaintext）传输的 JNI 桥。
//!
//! 架构（ADR-0001）：
//! - `bridge` 模块持有真实 quinn-plaintext endpoint / 连接 / 批量字节队列，
//!   提供同步的 server/client/state/read/write 原语；
//! - JNI 导出把这些原语暴露给 Java（`top.tangge233.qmc.jni.QuicNative`），
//!   每条 QUIC 连接 = 一个双向流，承载整个 MC 会话字节流。

pub mod bridge;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jstring};

pub const QMC_ABI_VERSION: &str = "0.1.0";
pub const QMC_RAW_FEATURE: &str = "quic-raw";

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
    bridge::server_port(server as u64)
        .map(|p| p as jint)
        .unwrap_or(-1)
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
    bridge::connection_state(conn as u64)
        .map(|s| s as jint)
        .unwrap_or(-1)
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
    use std::time::{Duration, Instant};

    fn wait_state(conn: u64, want: u32) {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if connection_state(conn) == Some(want) {
                return;
            }
            assert!(
                Instant::now() < deadline,
                "timeout waiting for state {want}"
            );
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    /// 等待连接脱离 CONNECTED（CLOSED 或已被 registry 移除→UNKNOWN）。
    /// 注意：收尾时 store(CLOSED) 与 remove 几乎同时发生，CLOSED 窗口
    /// 极短，轮询通常直接观察到 UNKNOWN——两者均视为“已感知关闭”
    /// （与 Java QuicChannel.poll 的判定一致）。
    ///
    /// deadline 必须明显大于 quinn 默认 max_idle_timeout（30s）：对端
    /// CONNECTION_CLOSE 未及时到达时，客户端靠 idle 超时感知关闭，
    /// 恰好 30s 的 deadline 会与超时点竞态导致偶发失败。
    fn wait_disconnected(conn: u64) {
        let deadline = Instant::now() + Duration::from_secs(40);
        loop {
            match connection_state(conn) {
                Some(STATE_CONNECTED) => {}
                _ => return,
            }
            assert!(Instant::now() < deadline, "timeout waiting for disconnect");
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
        assert_eq!(STATE_CONNECTED, 1);
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
            assert!(
                Instant::now() < deadline,
                "timeout waiting for server accept"
            );
            std::thread::sleep(Duration::from_millis(5));
        };
        assert_eq!(accepted.len(), 1, "server should see one connection");
        let server_conn = accepted[0];
        assert_eq!(connection_state(server_conn), Some(STATE_CONNECTED));

        // client -> server
        let payload = b"quic-mc hello over bridge";
        assert_eq!(
            write_chunk(client, payload).expect("client write"),
            payload.len()
        );
        assert_eq!(wait_read(server_conn, payload.len()), payload);

        // server -> client
        let reply = b"pong from server";
        assert_eq!(
            write_chunk(server_conn, reply).expect("server write"),
            reply.len()
        );
        assert_eq!(wait_read(client, reply.len()), reply);

        close_connection(client);
        stop_server(server);
        wait_state(client, STATE_CLOSED);
        assert_eq!(connection_state(client), Some(STATE_CLOSED));
    }

    /// 对端关闭传播：服务端主动关闭后，客户端应感知到 CLOSED。
    /// （回归保护：生产中服务端拒绝/异常关闭连接时客户端不能挂起。）
    #[test]
    fn peer_close_propagates_to_client() {
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
            assert!(
                Instant::now() < deadline,
                "timeout waiting for server accept"
            );
            std::thread::sleep(Duration::from_millis(5));
        };
        let server_conn = accepted[0];

        // 服务端主动关闭 → 客户端应在超时内感知（CLOSED 或 UNKNOWN）。
        assert!(close_connection(server_conn));
        wait_disconnected(client);

        close_connection(client);
        stop_server(server);
    }

    #[tokio::test]
    async fn plaintext_bidi_echo() {
        use quinn::Endpoint;

        let server_config = quinn_plaintext::server_config();
        let server = Endpoint::server(server_config, "127.0.0.1:0".parse().unwrap())
            .expect("server endpoint");
        let server_addr = server.local_addr().expect("server local addr");

        let mut client = Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client.set_default_client_config(quinn_plaintext::client_config());

        let server_task = tokio::spawn(async move {
            let conn = server
                .accept()
                .await
                .expect("accept incoming")
                .await
                .expect("connect");
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
        server_task.await.unwrap();
    }
}
