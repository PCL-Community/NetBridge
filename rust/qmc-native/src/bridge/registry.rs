//! 全局注册表：连接/服务端句柄与最近错误。
//!
//! 并发模型：`conns`/`servers` 为分片锁 DashMap（跨连接读写互不阻塞）；
//! 约定 clone-out——从 map 取到句柄字段（Arc/Sender）后立即克隆并 drop
//! guard，禁止持 `Ref`/`RefMut` 做第二次 map 操作或 channel/IO。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use dashmap::DashMap;

use super::{ConnHandle, ServerHandle};

static RUNTIME: OnceLock<std::sync::Arc<tokio::runtime::Runtime>> = OnceLock::new();
static CONNS: OnceLock<DashMap<u64, ConnHandle>> = OnceLock::new();
static SERVERS: OnceLock<DashMap<u64, ServerHandle>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static LAST_ERROR: OnceLock<Mutex<Option<String>>> = OnceLock::new();

pub fn runtime() -> &'static std::sync::Arc<tokio::runtime::Runtime> {
    RUNTIME.get_or_init(|| {
        std::sync::Arc::new(tokio::runtime::Runtime::new().expect("failed to create tokio runtime"))
    })
}

pub fn conns() -> &'static DashMap<u64, ConnHandle> {
    CONNS.get_or_init(DashMap::new)
}

pub fn servers() -> &'static DashMap<u64, ServerHandle> {
    SERVERS.get_or_init(DashMap::new)
}

/// 分配全局唯一 id（自 1 递增）。
pub fn allocate_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

fn last_error_cell() -> &'static Mutex<Option<String>> {
    LAST_ERROR.get_or_init(|| Mutex::new(None))
}

/// 记录最近一次错误（JNI 侧读取并清空）。毒锁容忍：接管继续，避免
/// JNI 边界 unwind 导致宿主进程 abort。
pub fn last_error() -> Option<String> {
    match last_error_cell().lock() {
        Ok(mut cell) => cell.take(),
        Err(poisoned) => poisoned.into_inner().take(),
    }
}

pub fn set_last_error(msg: String) {
    let mut cell = match last_error_cell().lock() {
        Ok(cell) => cell,
        Err(poisoned) => poisoned.into_inner(),
    };
    *cell = Some(msg);
}
