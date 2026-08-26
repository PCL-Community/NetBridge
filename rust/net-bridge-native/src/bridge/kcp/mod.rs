//! KCP 传输（tokio-kcp + FECStream。
//!
//! 分层：UDP → tokio_kcp（可靠流，stream 模式）→ [`fec_stream::FecStream`]
//! （RS 块纠错）→ [`frame`] 控制字帧层 → Java 字节队列。
//!
//! 职责边界：KCP 重传负责丢包；FEC 只处理穿透 UDP 校验和的静默数据损坏。

pub mod client;
pub mod config;
pub mod connection;
pub mod fec_stream;
pub mod frame;
pub mod server;

pub use client::connect;
pub use config::{build_config, KcpProfile};
pub use server::start_server;

#[cfg(test)]
mod tests;
