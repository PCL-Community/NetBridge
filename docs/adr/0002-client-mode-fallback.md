# ADR-0002: 客户端传输模式与 TCP 降级

状态：已接受 · 日期：2026-08-25

## 背景

客户端需在 tcp / quic / kcp 间选择。非 TCP 传输可能被防火墙/ISP 掐断 UDP（黑洞），
必须定义可预期的降级行为，否则玩家面对无限连接失败。

## 决策

- **三档模式**：`tcp` / `quic` / `kcp`。不存在独立的 *-fallback-tcp 档：
  quic/kcp 天生内置 TCP 降级；tcp 即纯 TCP，无降级概念。
- **尝试序列**（单次连接流程内）：目标传输至多尝试 **2 次**——第 1 次握手超时 **10 s**
  （冷启动），第 2 次 **20 s**；两次均失败 ⇒ 本次连接改走 TCP。native 报 FAILED/CLOSED
  视同当次立即失败，不等超时。tcp 直连不走此序列。
- **超时实现**：Java `HandshakeWatchdog` 定时任务竞速 connect promise——两栈均无可用原生
  握手超时，此为唯一可行方案（证据与约束见 ADR-0008）。
- **降级记忆优先于尝试序列**：`FallbackTracker` 命中（该服务器 5 min 内发生过降级）⇒
  直接走 TCP，不发起任何加速尝试；TTL 过期后重新执行完整序列。
- GUI tooltip 固定说明"握手失败 2 次后自动降级到 TCP"。

## 后果

- `TransportMode` 收敛为 TCP/QUIC/KCP 三值。
- 需要 Java 侧握手表计时器（10s/20s），不能裸等 native `connectionState`。

## 补充决策（第二轮拷问定稿）

- **mode 与宣告不匹配**：所选传输未被服务端宣告（或 protocol 不在支持集）→ **直接走 TCP**，
  不尝试其他加速传输；优先级协商不存在。
