//! 全局注册表：连接/服务端句柄、id 分配与错误上报。
//!
//! 并发模型：`CONNS`/`SERVERS` 为分片锁 DashMap（跨连接读写互不阻塞）；
//! 约定 clone-out——从 map 取到句柄字段（Arc/Sender）后立即克隆并 drop
//! guard，禁止持 `Ref`/`RefMut` 做第二次 map 操作或 channel/IO。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};

use dashmap::DashMap;
use tokio::runtime::Runtime;

use super::ConnHandle;

// fallible 初始化：runtime 创建失败（线程/资源耗尽）缓存为 None，
// 调用方返回错误而非 panic——panic 穿越 extern "system" 边界会 abort 宿主 JVM。
static RUNTIME: LazyLock<Option<Arc<Runtime>>> =
    LazyLock::new(|| Runtime::new().ok().map(Arc::new));
static CONNS: LazyLock<DashMap<u64, ConnHandle>> = LazyLock::new(DashMap::new);
static SERVERS: LazyLock<DashMap<u64, super::ServerHandle>> = LazyLock::new(DashMap::new);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

pub fn runtime() -> Option<&'static Arc<Runtime>> {
    RUNTIME.as_ref()
}

pub fn conns() -> &'static DashMap<u64, ConnHandle> {
    &CONNS
}

pub fn servers() -> &'static DashMap<u64, super::ServerHandle> {
    &SERVERS
}

/// 分配全局唯一 id（自 1 递增）。
pub fn allocate_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

/// 移除连接条目；服务端连接同步递减所属实例的活跃计数。
///
/// 所有从注册表移除连接的路径都必须经过这里，以保证计数一致。
pub fn remove_conn(conn_id: u64) -> Option<ConnHandle> {
    conns().remove(&conn_id).map(|(_, h)| {
        if let Some(count) = h.server_count.as_ref() {
            count.fetch_sub(1, Ordering::Relaxed);
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
