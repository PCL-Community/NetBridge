//! KCP 门面集成测试：真实 KCP 往返、关闭传播（含探测帧路径）。

use bytes::Bytes;
use std::time::{Duration, Instant};

use crate::bridge::kcp::config::KcpProfile;
use crate::bridge::*;
use crate::transport::TransportKind;

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
fn kcp_loopback_roundtrip() {
    let server = start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = server_port(server).expect("kcp server port");
    let client =
        connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced).expect("kcp connect");

    // kcp-rs 内建握手：服务端 SYN 确认后 accept 返回；连接期即可写
    // （early_write），无需先写再等 accept。
    let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(
        write_chunk(client, Bytes::from(payload.clone())).expect("client write"),
        payload.len(),
        "kcp client must accept writes while connecting"
    );

    let deadline = Instant::now() + Duration::from_secs(10);
    let accepted = loop {
        let a = accept_connections(server);
        if !a.is_empty() {
            break a;
        }
        assert!(Instant::now() < deadline, "timeout waiting for kcp accept");
        std::thread::sleep(Duration::from_millis(5));
    };
    assert_eq!(accepted.len(), 1, "kcp server should see one connection");
    let server_conn = accepted[0];

    // client -> server：跨 FEC 块边界的中等负载。
    let mut got = Vec::with_capacity(payload.len());
    while got.len() < payload.len() {
        got.extend_from_slice(&wait_read(server_conn, 1));
    }
    assert_eq!(got, payload);

    // server -> client：首个入站帧置位 CONNECTED。
    let reply = b"pong over kcp";
    assert_eq!(
        write_chunk(server_conn, Bytes::copy_from_slice(reply)).expect("server write"),
        reply.len()
    );
    assert_eq!(wait_read(client, reply.len()), reply);
    wait_state(client, STATE_CONNECTED);

    close_connection(client);
    stop_server(server);
}

/// 对端关闭传播（smux 路径）：服务端 close → FIN → 客户端读侧 EOF 感知。
#[test]
fn kcp_peer_close_propagates_to_client() {
    let server = start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = server_port(server).expect("kcp server port");
    let client =
        connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced).expect("kcp connect");

    // 先写：确保会话建立后立即有活动流量（握手由 kcp-rs 完成）。
    assert_eq!(
        write_chunk(client, Bytes::copy_from_slice(b"warm-up")).expect("warm-up write"),
        7
    );

    let deadline = Instant::now() + Duration::from_secs(10);
    let accepted = loop {
        let a = accept_connections(server);
        if !a.is_empty() {
            break a;
        }
        assert!(Instant::now() < deadline, "timeout waiting for kcp accept");
        std::thread::sleep(Duration::from_millis(5));
    };
    let server_conn = accepted[0];

    std::thread::sleep(Duration::from_millis(1));
    // 服务端主动关闭：客户端应快速感知（CLOSED 或 registry 已移除）。
    assert!(close_connection(server_conn));
    wait_disconnected(client);

    close_connection(client);
    stop_server(server);
}
