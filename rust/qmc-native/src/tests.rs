//! JNI 桥集成测试：真实 QUIC 回环、关闭传播与 ABI 常量。

use super::bridge::*;
use bytes::Bytes;
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
            // Edition 2024 let-chain：读取满足长度即返回。
            if let Ok(data) = read_chunk(conn, 65536)
                && data.len() >= want
            {
                return data.to_vec();
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
    let server = start_server(0, 256).expect("start server");
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
        write_chunk(client, Bytes::copy_from_slice(payload)).expect("client write"),
        payload.len()
    );
    assert_eq!(wait_read(server_conn, payload.len()), payload);

    // server -> client
    let reply = b"pong from server";
    assert_eq!(
        write_chunk(server_conn, Bytes::copy_from_slice(reply)).expect("server write"),
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
    let server = start_server(0, 256).expect("start server");
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
