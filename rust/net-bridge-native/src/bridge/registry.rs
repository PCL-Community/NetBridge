//! 全局注册表：连接/服务端句柄、id 分配与错误上报。
//!
//! 并发模型：`CONNS`/`SERVERS` 为分片锁 DashMap（跨连接读写互不阻塞）；
//! 约定 clone-out——从 map 取到句柄字段（Arc/Sender）后立即克隆并 drop
//! guard，禁止持 `Ref`/`RefMut` 做第二次 map 操作或 channel/IO。

use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock};

use dashmap::DashMap;
use tokio::runtime::Runtime;

use super::{ConnHandle, ServerHandle};

// fallible 初始化：runtime 创建失败（线程/资源耗尽）缓存为 None，
// 调用方返回错误而非 panic——panic 穿越 extern "system" 边界会 abort 宿主 JVM。
static RUNTIME: LazyLock<Option<Arc<Runtime>>> =
    LazyLock::new(|| Runtime::new().ok().map(Arc::new));
static CONNS: LazyLock<DashMap<u64, ConnHandle>> = LazyLock::new(DashMap::new);
static SERVERS: LazyLock<DashMap<u64, ServerHandle>> = LazyLock::new(DashMap::new);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// 服务端当前活跃连接数（客户端连接不计数），用于并发上限防护。
static ACTIVE_SERVER_CONNS: AtomicUsize = AtomicUsize::new(0);

pub fn runtime() -> Option<&'static Arc<Runtime>> {
    RUNTIME.as_ref()
}

pub fn conns() -> &'static DashMap<u64, ConnHandle> {
    &CONNS
}

pub fn servers() -> &'static DashMap<u64, ServerHandle> {
    &SERVERS
}

/// 分配全局唯一 id（自 1 递增）。
pub fn allocate_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

pub fn active_server_conns() -> usize {
    ACTIVE_SERVER_CONNS.load(Ordering::Relaxed)
}

/// 服务端注册新活跃连接；返回是否仍在上限内（超限调用方应拒绝）。
pub fn track_server_conn_added(max: usize) -> bool {
    let prev = ACTIVE_SERVER_CONNS.fetch_add(1, Ordering::Relaxed);
    if prev >= max {
        ACTIVE_SERVER_CONNS.fetch_sub(1, Ordering::Relaxed);
        return false;
    }
    true
}

/// 移除连接条目；服务端连接同步递减活跃计数。
///
/// 所有从注册表移除连接的路径都必须经过这里，以保证计数一致。
pub fn remove_conn(conn_id: u64) -> Option<ConnHandle> {
    conns().remove(&conn_id).map(|(_, h)| {
        if h.server_id.is_some() {
            ACTIVE_SERVER_CONNS.fetch_sub(1, Ordering::Relaxed);
        }
        h
    })
}

/// 查询连接的对端地址（"ip:port"）；客户端连接或不存在返回 None。
pub fn conn_remote_addr(conn: u64) -> Option<String> {
    conns()
        .get(&conn)
        .and_then(|h| h.remote_addr)
        .map(|a| a.to_string())
}

/// 错误即时上报：stderr 由 Minecraft 启动器重定向进 logs/latest.log。
pub fn report_error(msg: String) {
    eprintln!("[net-bridge-native] error: {msg}");
}
