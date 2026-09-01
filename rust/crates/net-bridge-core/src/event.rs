//! 事件系统与 EventSink trait。

use std::sync::{Arc, OnceLock};

pub const NB_EVENT_CONNECTION_STATE: u32 = 1;
pub const NB_EVENT_DATA_AVAILABLE: u32 = 2;
pub const NB_EVENT_WRITABLE: u32 = 3;
pub const NB_EVENT_ACCEPTED: u32 = 4;
pub const NB_EVENT_SERVER_STATE: u32 = 5;

/// 事件回调接收器 trait。
pub trait EventSink: Send + Sync + 'static {
    fn on_event(&self, event_kind: u32, object_id: u64, arg0: i64, arg1: i64);
}

/// 默认空操作 EventSink。
pub struct NoopEventSink;

impl EventSink for NoopEventSink {
    fn on_event(&self, _event_kind: u32, _object_id: u64, _arg0: i64, _arg1: i64) {}
}

static EVENT_SINK: OnceLock<Arc<dyn EventSink>> = OnceLock::new();
static DEFAULT_SINK: OnceLock<Arc<dyn EventSink>> = OnceLock::new();

/// 设置全局 EventSink。
pub fn set_event_sink(sink: Arc<dyn EventSink>) -> Result<(), Arc<dyn EventSink>> {
    EVENT_SINK.set(sink)
}

/// 获取当前 EventSink。
pub fn get_event_sink() -> &'static Arc<dyn EventSink> {
    EVENT_SINK
        .get()
        .unwrap_or_else(|| DEFAULT_SINK.get_or_init(|| Arc::new(NoopEventSink)))
}

/// 触发数据到达通知。
pub fn notify_data(conn_id: u64) {
    get_event_sink().on_event(NB_EVENT_DATA_AVAILABLE, conn_id, 0, 0);
}
