//! KCP 传输（kcp-rs + FECStream + smux）。
//!
//! 分层：UDP → kcp-rs（可靠流，stream 模式，SYN 握手内建）→
//! [`fec_stream::FecStream`]（RS 块纠错）→ smux（流控/关闭语义，单条
//! MC 字节流）→ Java 字节队列。
//!
//! 职责边界：kcp-rs 负责可靠投递与建连握手；FEC 兜底处理绕过/UDP
//! 校验和未覆盖的静默数据损坏；smux 替代原 frame.rs 控制字层——流控
//! 用滑动窗口 token 池，关闭用 FIN 帧，无需自定义应用帧编解码。

pub mod client;
pub mod config;
pub mod connection;
pub mod fec_stream;
pub mod server;

pub use client::connect;
pub use config::{build_config, KcpProfile};
pub use server::start_server;

#[cfg(test)]
mod tests;
