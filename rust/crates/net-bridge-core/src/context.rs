//! NativeContext：实例级运行时所有权根节点。

use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use dashmap::DashMap;
use tokio::runtime::{Builder, Runtime};

use crate::error::BridgeError;
use crate::event::{EventSink, NoopEventSink};
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::{
    Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_FAILED, ServerHandle,
    TransportEndpoint,
};

pub const CONTEXT_STATE_RUNNING: u8 = 0;
pub const CONTEXT_STATE_SHUTTING_DOWN: u8 = 1;
pub const CONTEXT_STATE_CLOSED: u8 = 2;

pub struct NativeContext {
    state: AtomicU8,
    runtime: Mutex<Option<Runtime>>,
    handle: tokio::runtime::Handle,
    connections: DashMap<u64, ConnHandle>,
    servers: DashMap<u64, ServerHandle>,
    next_id: AtomicU64,
    event_sink: Arc<dyn EventSink>,
}

impl NativeContext {
    /// 创建新的 NativeContext 实例。
    pub fn new(
        worker_threads: usize,
        event_sink: Option<Arc<dyn EventSink>>,
    ) -> Result<Arc<Self>, BridgeError> {
        let mut builder = Builder::new_multi_thread();
        builder.enable_all();
        builder.thread_name("net-bridge-native");
        if worker_threads > 0 {
            builder.worker_threads(worker_threads);
        }
        let rt = builder
            .build()
            .map_err(|_| BridgeError::RuntimeUnavailable)?;
        let handle = rt.handle().clone();

        Ok(Arc::new(Self {
            state: AtomicU8::new(CONTEXT_STATE_RUNNING),
            runtime: Mutex::new(Some(rt)),
            handle,
            connections: DashMap::new(),
            servers: DashMap::new(),
            next_id: AtomicU64::new(1),
            event_sink: event_sink.unwrap_or_else(|| Arc::new(NoopEventSink)),
        }))
    }

    pub fn handle(&self) -> &tokio::runtime::Handle {
        &self.handle
    }

    pub fn state(&self) -> u8 {
        self.state.load(Ordering::SeqCst)
    }

    pub fn is_running(&self) -> bool {
        self.state() == CONTEXT_STATE_RUNNING
    }

    pub fn allocate_id(&self) -> u64 {
        self.next_id.fetch_add(1, Ordering::Relaxed)
    }

    pub fn event_sink(&self) -> &Arc<dyn EventSink> {
        &self.event_sink
    }

    pub fn conns(&self) -> &DashMap<u64, ConnHandle> {
        &self.connections
    }

    pub fn servers_map(&self) -> &DashMap<u64, ServerHandle> {
        &self.servers
    }

    pub fn remove_conn(&self, conn_id: u64) -> Option<ConnHandle> {
        self.connections.remove(&conn_id).map(|(_, h)| {
            if let Some(count) = h.server_count.as_ref() {
                count.fetch_sub(1, Ordering::Relaxed);
            }
            h
        })
    }

    pub fn connection_state(&self, conn: u64) -> Option<u32> {
        self.connections
            .get(&conn)
            .map(|h| h.state.load(Ordering::SeqCst))
    }

    pub fn connection_remote_addr(&self, conn: u64) -> Option<SocketAddr> {
        self.connections.get(&conn).and_then(|h| h.remote_addr)
    }

    pub fn close_connection(&self, conn: u64) -> bool {
        let Some(handle) = self.connections.get(&conn) else {
            return false;
        };
        let state = handle.state.clone();
        let to_transport = handle.to_transport.clone();
        drop(handle);
        let was = state.swap(STATE_CLOSED, Ordering::SeqCst);
        let ok = to_transport.try_send(Command::Close).is_ok();
        if was == STATE_FAILED || was == crate::STATE_CONNECTING {
            self.remove_conn(conn);
        }
        ok
    }

    pub fn write_chunk(&self, conn: u64, data: Bytes) -> Result<usize, BridgeError> {
        if data.is_empty() {
            return Ok(0);
        }
        let len = data.len();
        let Some(handle) = self.connections.get(&conn) else {
            return Err(BridgeError::NoSuchConnection);
        };
        let writable = handle.state.load(Ordering::SeqCst) == STATE_CONNECTED || handle.early_write;
        let to_transport = handle.to_transport.clone();
        drop(handle);
        if !writable {
            return Ok(0);
        }
        match to_transport.try_send(Command::Write(data)) {
            Ok(()) => Ok(len),
            Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => Ok(0),
            Err(_) => Err(BridgeError::ConnectionClosed),
        }
    }

    pub fn read_chunk(&self, conn: u64, max_bytes: usize) -> Result<Bytes, BridgeError> {
        let Some(handle) = self.connections.get(&conn) else {
            return Err(BridgeError::NoSuchConnection);
        };
        let to_java = handle.to_java.clone();
        drop(handle);
        let mut guard = match to_java.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let (rx, pending) = &mut *guard;

        let first = match pending.pop_front().or_else(|| rx.try_recv().ok()) {
            Some(b) => b,
            None => return Ok(Bytes::new()),
        };
        if first.len() > max_bytes {
            pending.push_front(first.slice(max_bytes..));
            return Ok(first.slice(..max_bytes));
        }

        match pending.pop_front().or_else(|| rx.try_recv().ok()) {
            None => Ok(first),
            Some(second) => {
                let mut out = bytes::BytesMut::with_capacity(max_bytes);
                out.extend_from_slice(&first);
                let mut next = Some(second);
                while out.len() < max_bytes {
                    let Some(mut chunk) = next
                        .take()
                        .or_else(|| pending.pop_front().or_else(|| rx.try_recv().ok()))
                    else {
                        break;
                    };
                    if out.len() + chunk.len() > max_bytes {
                        let cut = max_bytes - out.len();
                        pending.push_front(chunk.slice(cut..));
                        chunk = chunk.slice(..cut);
                    }
                    out.extend_from_slice(&chunk);
                }
                Ok(out.freeze())
            }
        }
    }

    pub fn server_port(&self, server: u64) -> Option<u16> {
        self.servers.get(&server).map(|h| h.port)
    }

    pub fn stop_server(&self, server: u64) -> bool {
        let Some((_, handle)) = self.servers.remove(&server) else {
            return false;
        };
        match handle.endpoint {
            TransportEndpoint::Quic(endpoint) => endpoint.close(0u32.into(), b"net-bridge stop"),
            TransportEndpoint::Kcp(stop_tx) => {
                let _ = stop_tx.try_send(());
            }
        }
        let conn_ids: Vec<u64> = self
            .connections
            .iter()
            .filter(|e| e.server_id == Some(server))
            .map(|e| *e.key())
            .collect();
        for id in conn_ids {
            if let Some(h) = self.connections.get_mut(&id) {
                h.state.store(STATE_CLOSED, Ordering::SeqCst);
                let _ = h.to_transport.try_send(Command::Close);
            }
        }
        true
    }

    pub fn accept_connections(&self, server: u64) -> Vec<u64> {
        let mut out = Vec::new();
        for e in self.connections.iter_mut() {
            if e.server_id == Some(server) && !e.reported.swap(true, Ordering::SeqCst) {
                out.push(*e.key());
            }
        }
        out
    }

    pub fn connect(
        self: &Arc<Self>,
        kind: TransportKind,
        host: &str,
        port: u16,
        profile: KcpProfile,
    ) -> Result<u64, BridgeError> {
        if !self.is_running() {
            return Err(BridgeError::RuntimeUnavailable);
        }
        match kind {
            TransportKind::Quic => crate::transport::quic::connect_in_context(self, host, port),
            TransportKind::Kcp => {
                crate::transport::kcp::connect_in_context(self, host, port, profile)
            }
        }
    }

    pub fn start_server(
        self: &Arc<Self>,
        kind: TransportKind,
        port: u16,
        max_connections: usize,
        bind: Option<IpAddr>,
        profile: KcpProfile,
    ) -> Result<u64, BridgeError> {
        if !self.is_running() {
            return Err(BridgeError::RuntimeUnavailable);
        }
        match kind {
            TransportKind::Quic => {
                crate::transport::quic::start_server_in_context(self, port, max_connections, bind)
            }
            TransportKind::Kcp => crate::transport::kcp::start_server_in_context(
                self,
                port,
                max_connections,
                bind,
                profile,
            ),
        }
    }

    pub fn shutdown(&self, timeout: Duration) -> Result<(), BridgeError> {
        if self
            .state
            .compare_exchange(
                CONTEXT_STATE_RUNNING,
                CONTEXT_STATE_SHUTTING_DOWN,
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_err()
            && self.state() != CONTEXT_STATE_SHUTTING_DOWN
        {
            return Ok(());
        }

        // 停止所有服务端
        let server_ids: Vec<u64> = self.servers.iter().map(|e| *e.key()).collect();
        for s_id in server_ids {
            self.stop_server(s_id);
        }

        // 关闭所有活跃连接
        let conn_ids: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in conn_ids {
            self.close_connection(c_id);
        }

        // runtime 即将销毁：任务清理回调不会再运行，此处强制回收注册表，
        // 保证 shutdown 后 registry 归零（无泄漏）。
        let remaining: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in remaining {
            self.remove_conn(c_id);
        }

        // 关闭 Tokio runtime
        let mut guard = self.runtime.lock().unwrap();
        if let Some(rt) = guard.take() {
            rt.shutdown_timeout(timeout);
        }

        self.state.store(CONTEXT_STATE_CLOSED, Ordering::SeqCst);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    struct TestEventSink {
        event_count: AtomicUsize,
    }

    impl EventSink for TestEventSink {
        fn on_event(&self, _event_kind: u32, _object_id: u64, _arg0: i64, _arg1: i64) {
            self.event_count.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn context_lifecycle_and_shutdown() {
        let sink = Arc::new(TestEventSink {
            event_count: AtomicUsize::new(0),
        });
        let ctx = NativeContext::new(2, Some(sink.clone())).expect("create context");
        assert!(ctx.is_running());
        assert_eq!(ctx.state(), CONTEXT_STATE_RUNNING);

        let server = ctx
            .start_server(TransportKind::Quic, 0, 64, None, KcpProfile::Balanced)
            .expect("start server in ctx");
        let port = ctx.server_port(server).expect("server port");
        assert_ne!(port, 0);

        let client = ctx
            .connect(TransportKind::Quic, "127.0.0.1", port, KcpProfile::Balanced)
            .expect("connect in ctx");

        // 等待连接建立并关停
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            if ctx.connection_state(client) == Some(STATE_CONNECTED) {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        ctx.shutdown(Duration::from_secs(3))
            .expect("shutdown context");
        assert_eq!(ctx.state(), CONTEXT_STATE_CLOSED);
        assert!(!ctx.is_running());
    }

    #[test]
    fn context_repeated_lifecycle_cycles_no_leak() {
        for cycle in 0..8u32 {
            let ctx = NativeContext::new(2, None).expect("create context");
            let server = ctx
                .start_server(TransportKind::Quic, 0, 16, None, KcpProfile::Balanced)
                .expect("start server");
            let port = ctx.server_port(server).expect("server port");

            let client = ctx
                .connect(TransportKind::Quic, "127.0.0.1", port, KcpProfile::Balanced)
                .expect("connect");
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while std::time::Instant::now() < deadline {
                if ctx.connection_state(client) == Some(STATE_CONNECTED) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            assert_eq!(
                ctx.connection_state(client),
                Some(STATE_CONNECTED),
                "cycle {cycle}: client not connected"
            );

            let accept_deadline = std::time::Instant::now() + Duration::from_secs(5);
            let accepted = loop {
                let a = ctx.accept_connections(server);
                if !a.is_empty() {
                    break a;
                }
                assert!(
                    std::time::Instant::now() < accept_deadline,
                    "cycle {cycle}: accept timeout"
                );
                std::thread::sleep(Duration::from_millis(10));
            };
            assert_eq!(accepted.len(), 1);
            let server_conn = accepted[0];

            let payload = format!("cycle-{cycle}-payload").into_bytes();
            assert_eq!(
                ctx.write_chunk(client, Bytes::copy_from_slice(&payload))
                    .expect("write"),
                payload.len()
            );
            let read_deadline = std::time::Instant::now() + Duration::from_secs(5);
            let mut got = 0usize;
            while got < payload.len() && std::time::Instant::now() < read_deadline {
                match ctx.read_chunk(server_conn, 65536) {
                    Ok(data) if !data.is_empty() => got += data.len(),
                    _ => std::thread::sleep(Duration::from_millis(10)),
                }
            }
            assert_eq!(got, payload.len(), "cycle {cycle}: roundtrip incomplete");

            ctx.close_connection(client);
            ctx.close_connection(server_conn);
            ctx.shutdown(Duration::from_secs(3)).expect("shutdown");
            assert_eq!(ctx.state(), CONTEXT_STATE_CLOSED);
            assert!(
                ctx.conns().is_empty(),
                "cycle {cycle}: leaked connections: {:?}",
                ctx.conns().iter().map(|e| *e.key()).collect::<Vec<_>>()
            );
            assert!(
                ctx.servers_map().is_empty(),
                "cycle {cycle}: leaked servers"
            );
        }
    }
}
