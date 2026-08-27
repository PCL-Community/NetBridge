//! 传输类别：JNI 层选择 QUIC 或 KCP 实现的统一标签。
//!
//! 连接注册表与数据面原语（state/read/write/close）传输无关；
//! 本枚举只用于分派建连（connect）与服务端 acceptor（start_server）。

use crate::bridge::error::Transport;

/// 传输实现类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportKind {
    /// quinn-plaintext 明文 QUIC。
    Quic,
    /// kcp-rs + FEC + smux 多路流控 KCP。
    Kcp,
}

impl TransportKind {
    /// JNI 整型标签解析：0 = QUIC，1 = KCP；其余非法返回 None。
    pub fn from_jint(value: i32) -> Option<Self> {
        match value {
            0 => Some(Self::Quic),
            1 => Some(Self::Kcp),
            _ => None,
        }
    }

    /// 错误信息用的传输标签（与 [`BridgeError`] 的 Transport 对应）。
    pub fn label(self) -> Transport {
        match self {
            Self::Quic => Transport::Quic,
            Self::Kcp => Transport::Kcp,
        }
    }
}
