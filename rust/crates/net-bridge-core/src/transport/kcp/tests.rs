//! KCP 门面集成测试：真实 KCP 往返、关闭传播（含探测帧路径）。

use bytes::Bytes;
use std::time::{Duration, Instant};

use crate::context::NativeContext;
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::*;

use std::sync::Arc;

fn wait_state(ctx: &NativeContext, conn: u64, want: u32) {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if ctx.connection_state(conn) == Some(want) {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timeout waiting for state {want}"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn wait_disconnected(ctx: &NativeContext, conn: u64) {
    let deadline = Instant::now() + Duration::from_secs(40);
    loop {
        match ctx.connection_state(conn) {
            Some(STATE_CONNECTED) => {}
            _ => return,
        }
        assert!(Instant::now() < deadline, "timeout waiting for disconnect");
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn wait_read(ctx: &NativeContext, conn: u64, want: usize) -> Vec<u8> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(data) = ctx.read_chunk(conn, 65536)
            && data.len() >= want
        {
            return data.to_vec();
        }
        assert!(Instant::now() < deadline, "timeout waiting for read");
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn test_ctx() -> Arc<NativeContext> {
    NativeContext::new(2, None).expect("native context")
}

#[test]
fn kcp_loopback_roundtrip() {
    let ctx = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(
        ctx.write_chunk(client, Bytes::from(payload.clone()))
            .expect("client write"),
        payload.len(),
        "kcp client must accept writes while connecting"
    );

    let deadline = Instant::now() + Duration::from_secs(10);
    let accepted = loop {
        let a = ctx.accept_connections(server);
        if !a.is_empty() {
            break a;
        }
        assert!(Instant::now() < deadline, "timeout waiting for kcp accept");
        std::thread::sleep(Duration::from_millis(5));
    };
    assert_eq!(accepted.len(), 1, "kcp server should see one connection");
    let server_conn = accepted[0];

    // client -> server
    let mut got = Vec::with_capacity(payload.len());
    while got.len() < payload.len() {
        got.extend_from_slice(&wait_read(&ctx, server_conn, 1));
    }
    assert_eq!(got, payload);

    // server -> client
    let reply = b"pong over kcp";
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(reply))
            .expect("server write"),
        reply.len()
    );
    assert_eq!(wait_read(&ctx, client, reply.len()), reply);
    wait_state(&ctx, client, STATE_CONNECTED);

    ctx.close_connection(client);
    ctx.stop_server(server);
}

#[test]
fn kcp_sustained_and_idle_phase() {
    let ctx = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(
        ctx.write_chunk(client, Bytes::from(payload.clone()))
            .expect("client write"),
        payload.len()
    );
    let deadline = Instant::now() + Duration::from_secs(10);
    let accepted = loop {
        let a = ctx.accept_connections(server);
        if !a.is_empty() {
            break a;
        }
        assert!(Instant::now() < deadline, "timeout waiting for kcp accept");
        std::thread::sleep(Duration::from_millis(5));
    };
    let server_conn = accepted[0];

    let mut round = 0u32;
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(15) {
        std::thread::sleep(Duration::from_millis(1000));
        round += 1;
        let msg = format!("active-{round}").into_bytes();
        assert_eq!(
            ctx.write_chunk(client, Bytes::copy_from_slice(&msg))
                .expect("cli write"),
            msg.len()
        );
        wait_read(&ctx, server_conn, msg.len());
        assert_eq!(
            ctx.write_chunk(server_conn, Bytes::copy_from_slice(&msg))
                .expect("srv write"),
            msg.len()
        );
        wait_read(&ctx, client, msg.len());
        assert_eq!(
            ctx.connection_state(client),
            Some(STATE_CONNECTED),
            "client died at round {round}"
        );
        assert_eq!(
            ctx.connection_state(server_conn),
            Some(STATE_CONNECTED),
            "server died at round {round}"
        );
    }

    std::thread::sleep(Duration::from_secs(45));
    assert_eq!(
        ctx.connection_state(client),
        Some(STATE_CONNECTED),
        "client died during idle phase"
    );
    assert_eq!(
        ctx.connection_state(server_conn),
        Some(STATE_CONNECTED),
        "server died during idle phase"
    );

    let msg = b"resume-after-idle";
    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(msg))
            .expect("cli resume"),
        msg.len()
    );
    wait_read(&ctx, server_conn, msg.len());
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(msg))
            .expect("srv resume"),
        msg.len()
    );
    wait_read(&ctx, client, msg.len());

    ctx.close_connection(client);
    ctx.stop_server(server);
}

#[test]
fn kcp_peer_close_propagates_to_client() {
    let ctx = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(b"warm-up"))
            .expect("warm-up write"),
        7
    );

    let deadline = Instant::now() + Duration::from_secs(10);
    let accepted = loop {
        let a = ctx.accept_connections(server);
        if !a.is_empty() {
            break a;
        }
        assert!(Instant::now() < deadline, "timeout waiting for kcp accept");
        std::thread::sleep(Duration::from_millis(5));
    };
    let server_conn = accepted[0];

    std::thread::sleep(Duration::from_millis(1));
    assert!(ctx.close_connection(server_conn));
    wait_disconnected(&ctx, client);

    ctx.close_connection(client);
    ctx.stop_server(server);
}
