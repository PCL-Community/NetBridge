//! QUIC 门面集成测试：真实 QUIC 往返、关闭传播与 quinn 原生回环。

use bytes::Bytes;
use std::time::{Duration, Instant};

use crate::transport::TransportKind;
use crate::*;

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

fn wait_terminal(conn: u64) {
    match connection_state(conn) {
        Some(STATE_CLOSED) | None => {}
        other => panic!("unexpected terminal state {other:?}"),
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
fn quic_loopback_roundtrip() {
    let server =
        start_server(TransportKind::Quic, 0, 256, None, Default::default()).expect("start server");
    let port = server_port(server).expect("server port");
    let client =
        connect(TransportKind::Quic, "127.0.0.1", port, Default::default()).expect("connect");
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
    let payload = b"net-bridge hello over bridge";
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
    wait_terminal(client);
}

#[test]
fn quic_peer_close_propagates_to_client() {
    let server =
        start_server(TransportKind::Quic, 0, 256, None, Default::default()).expect("start server");
    let port = server_port(server).expect("server port");
    let client =
        connect(TransportKind::Quic, "127.0.0.1", port, Default::default()).expect("connect");
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

    assert!(close_connection(server_conn));
    wait_disconnected(client);

    close_connection(client);
    stop_server(server);
}

#[tokio::test]
async fn plaintext_bidi_echo() {
    use quinn::Endpoint;

    let server_config = quinn_plaintext::server_config();
    let server =
        Endpoint::server(server_config, "127.0.0.1:0".parse().unwrap()).expect("server endpoint");
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
    let request = b"net-bridge hello";
    send.write_all(request).await.expect("write");
    send.finish().unwrap();
    let response = recv.read_to_end(1024).await.expect("read response");

    assert_eq!(response, request, "echo mismatch");
    conn.close(0u32.into(), b"done");
    server_task.await.unwrap();
}
