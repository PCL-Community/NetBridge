//! QUIC 传输实现（quinn-plaintext）：客户端、服务端 acceptor 与单连接数据面。

mod client;
pub(crate) mod connection;
pub(crate) mod server;

pub use client::{connect, connect_in_context};
pub use server::{start_server, start_server_in_context};

#[cfg(test)]
mod tests;
