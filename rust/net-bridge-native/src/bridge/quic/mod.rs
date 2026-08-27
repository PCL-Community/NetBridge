//! QUIC 传输实现（quinn-plaintext）：客户端、服务端 acceptor 与单连接数据面。
//!
//! 数据面原语（state/read/write/close）与服务端句柄原语
//! （port/stop/accept/register）是传输无关的注册表操作，见
//! [`super::dataplane`] 与 [`super::server_ops`]；本模块只负责 QUIC
//! 特有的建连、accept 与连接数据循环。

mod client;
pub(crate) mod connection;
pub(crate) mod server;

pub use client::connect;
pub use server::start_server;

#[cfg(test)]
mod tests;
