//! net-bridge-native：QUIC / KCP 传输的 C ABI v1 层。

pub mod abi;

pub use abi::netbridge_get_api;

pub const NET_BRIDGE_ABI_VERSION: &str = net_bridge_core::NET_BRIDGE_ABI_VERSION;
