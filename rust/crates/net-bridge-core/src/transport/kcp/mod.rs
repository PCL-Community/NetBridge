//! KCP 传输（kcp-rs + FECStream + smux）。

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
