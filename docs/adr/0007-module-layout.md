# ADR-0007: 模块划分与 JNI 命名去协议化

状态：已接受 · 日期：2026-08-25 · 取代：pre-refactor 注释中 ADR-0006 的双副本约定表述（副本策略本身保留）

## 背景

KCP 加入后，`QuicNative` / `QuicChannel` 等 QUIC 专名名不副实；能力解析、模式决策、
降级状态机混在 `net` 单层包里。重构按职责重新划层。

## 决策

### Java（common）

```
top.tangge233.netbridge
├── jni/          NativeBridge（原 QuicNative）、NativeLoader、NativeConnState
├── ability/      NetworksAbility、NetworksEntry(enable/host/port/protocol)、
│                 StatusNetworksCodec(wire v2 注入/解析)、TransportProtocol(版本串常量+支持集比对)
├── transport/    TransportMode(tcp/quic/kcp)、ClientConfig(client.toml)、
│                 KcpProfile(balance/aggressive)、HandshakeWatchdog(10s/20s)、
│                 FallbackTracker(TTL 5min)、TransportSelector(决策入口)
├── channel/      NativeChannel(原 QuicChannel，协议无关)
└── server/       ServerConfig(server.toml [quic]/[kcp])、NativeAcceptor
```

### Rust（net-bridge-native）

```
src/
├── transport.rs      trait Transport { connect/accept/state/read/write/close }
├── bridge/mod.rs     句柄注册表（传输无关 id）、server/client 门面
├── bridge/quic/      quinn-plaintext 实现
└── bridge/kcp/       fec_stream + kcp-rs + smux（现结构保留）
```

JNI 导出层只做参数转换，业务在 `bridge` 门面后；两协议经 `dyn Transport` 分派。

### ABI

- 类更名 ⇒ 导出符号变 `Java_top_tangge233_netbridge_jni_NativeBridge_*`。
- ABI 版本 `0.1.0` → `0.2.0`；加载时不匹配即拒绝（Alpha 无兼容负担）。

### 平台层（fabric/neoforge）

双源码副本策略保留；mixin 仅做挂载点，逻辑全部下沉 common。

## 后果

- 新传输接入 = 实现 `Transport` trait + ability 条目 + TransportMode 枚举值，不动框架。
