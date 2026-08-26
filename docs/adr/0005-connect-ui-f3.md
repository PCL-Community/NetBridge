# ADR-0005: 连接界面文案与 F3 调试行

状态：已接受 · 日期：2026-08-25

## 背景

玩家需要知道当前连接走的传输协议；降级发生时也需可见反馈。要求：GUI 只标注协议，
tooltip 说明降级策略；F3 显示实际生效协议。

## 决策

### 连接界面（ConnectScreen）

- 文案**另起一行**，不改动原版"连接服务器"标题行。
- 文案由**实际状态机驱动**（非配置回显），连接过程中实时更新：
  - 握手中：`正在建立 QUIC 连接` / `正在建立 KCP 连接` / `正在建立 TCP 连接`
  - 两次尝试期间文案不变（不显示重试计数）
  - 降级中（2 次失败后）：`正在回退 TCP 连接`
- i18n：key 形如 `connect.netbridge.quic` / `connect.netbridge.kcp` / `connect.netbridge.tcp` /
  `connect.netbridge.fallback`，zh_cn 与 en_us 双语资源。
- 服务器列表 tooltip：说明"握手失败 2 次后自动降级到 TCP"。

### F3 调试屏

单行，内容 = 当前实际协议 + 实际使用的传输端点地址：

```
[net-bridge] QUIC 1.2.3.4:25565
```

- 协议名用枚举大写（QUIC/KCP/TCP），不用 protocol 版本串（那是 wire 层概念）。
- 地址：quic/kcp 显示宣告解析后的 `host:port`；TCP 显示原服务器地址。降级后随协议切换更新。
- 经 mixin 注入网络调试区块；TCP 直连时同样显示（明确"当前走 TCP"）。

## 后果

- 需要客户端侧连接状态机的可查询句柄（供 ConnectScreen mixin 与 F3 mixin 共读）。

## 补充（第三轮拷问定稿）

- **游戏内切换按钮保留**：多人游戏屏幕底部入口与 `client.toml` 双向同步
  （启动读文件为初值，切换即写回）；按钮 tooltip 同样说明降级策略。
- **en_us 文案**：`Connecting via QUIC…` / `Connecting via KCP…` / `Connecting via TCP…` /
  `Falling back to TCP…`——清晰简短为准。
