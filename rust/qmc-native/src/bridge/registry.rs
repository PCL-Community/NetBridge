//! 全局注册表：连接/服务端句柄与最近错误。

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use super::{ConnHandle, ServerHandle};

pub struct Registry {
    pub next_id: u64,
    pub conns: HashMap<u64, ConnHandle>,
    pub servers: HashMap<u64, ServerHandle>,
    pub last_error: Option<String>,
}

static RUNTIME: OnceLock<std::sync::Arc<tokio::runtime::Runtime>> = OnceLock::new();
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();

pub fn runtime() -> &'static std::sync::Arc<tokio::runtime::Runtime> {
    RUNTIME.get_or_init(|| {
        std::sync::Arc::new(tokio::runtime::Runtime::new().expect("failed to create tokio runtime"))
    })
}

pub fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| {
        Mutex::new(Registry {
            next_id: 1,
            conns: HashMap::new(),
            servers: HashMap::new(),
            last_error: None,
        })
    })
}

/// 毒锁容忍的注册表访问。
///
/// 注册表不含需要回滚的不变量，持锁线程 panic 后直接接管数据继续运行；
/// 否则后续 JNI 调用会因 unwrap 中毒锁反复 panic，从 `extern "system"`
/// 边界 unwind 导致整个宿主进程（Minecraft）abort。
pub fn lock_registry() -> std::sync::MutexGuard<'static, Registry> {
    match registry().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

pub fn allocate_id(reg: &mut Registry) -> u64 {
    let id = reg.next_id;
    reg.next_id += 1;
    id
}

/// 记录最近一次错误（JNI 侧读取并清空）。
pub fn last_error() -> Option<String> {
    lock_registry().last_error.take()
}

pub fn set_last_error(msg: String) {
    lock_registry().last_error = Some(msg);
}
