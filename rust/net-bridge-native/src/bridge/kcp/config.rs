//! KCP 参数预设：balanced / aggressive 二档，不支持自定义。
//!
//! 公共固定：`mtu=1300`、`wnd_size=(256,256)`、`stream=true`（字节流管道
//! 语义）`flush_write=true`（写后立即冲刷，MC 小包低延迟）、
//! `session_expire=90s`（对端消失的服务端侧回收）。

use std::time::Duration;

use tokio_kcp::{KcpConfig, KcpNoDelayConfig};

/// 传输预设：平衡（默认）与激进。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum KcpProfile {
    /// nodelay=0/interval=40/resend=0/nc=0：标准 KCP，带宽友好。
    #[default]
    Balanced,
    /// nodelay=1/interval=10/resend=2/nc=1：极速模式，高丢包链路换延迟。
    Aggressive,
}

impl KcpProfile {
    /// 解析配置中的 `profile` 字段；非法值返回 None（上层告警回退默认）。
    /// 规范名 `balance`，历史别名 `balanced` 兼容接受。
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "balance" | "balanced" => Some(Self::Balanced),
            "aggressive" => Some(Self::Aggressive),
            _ => None,
        }
    }

    fn no_delay(self) -> KcpNoDelayConfig {
        match self {
            Self::Balanced => KcpNoDelayConfig::normal(),
            Self::Aggressive => KcpNoDelayConfig::fastest(),
        }
    }
}

/// 构建两端共用的 KCP 配置。参数为两端锁定的预设值，不支持自定义。
pub fn build_config(profile: KcpProfile) -> KcpConfig {
    KcpConfig {
        mtu: 1300,
        nodelay: profile.no_delay(),
        wnd_size: (256, 256),
        session_expire: Duration::from_secs(90),
        flush_write: true,
        flush_acks_input: false,
        stream: true,
        allow_recv_empty_packet: false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_profiles() {
        assert_eq!(KcpProfile::parse("balanced"), Some(KcpProfile::Balanced));
        assert_eq!(
            KcpProfile::parse(" Aggressive "),
            Some(KcpProfile::Aggressive)
        );
        assert_eq!(KcpProfile::parse("turbo"), None);
    }

    #[test]
    fn presets_match_adr() {
        let balanced = build_config(KcpProfile::Balanced);
        assert_eq!(balanced.mtu, 1300);
        assert!(balanced.stream, "stream 模式必须开启");
        assert!(balanced.flush_write);
        assert_eq!(balanced.wnd_size, (256, 256));
        assert!(!balanced.nodelay.nodelay);

        let aggressive = build_config(KcpProfile::Aggressive);
        assert!(aggressive.nodelay.nodelay);
        assert_eq!(aggressive.nodelay.interval, 10);
        assert_eq!(aggressive.nodelay.resend, 2);
        assert!(aggressive.nodelay.nc);
    }
}
