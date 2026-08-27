//! KCP 传输（kcp-rs + FECStream + smux）。
//!
//! 分层：UDP → kcp-rs（可靠流，SYN 握手内建）→ [`fec_stream::FecStream`]
//! （RS 块纠错）→ smux（流控/关闭语义）→ Java 字节队列。原 frame.rs
//! 控制字层已移除，流控改由 smux 滑动窗口 token 承担。

pub mod client;
pub mod config;
pub mod connection;
pub mod fec_stream;
pub mod server;

pub use client::connect;
pub use config::{KcpProfile, build_config};
pub use server::start_server;

#[cfg(test)]
mod tests;
