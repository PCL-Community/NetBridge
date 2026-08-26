# ADR-0008: 传输握手超时与存活判定

状态：已接受 · 日期：2026-08-26 · 依赖：ADR-0002（watchdog 定义）

## 背景

ADR-0002 规定握手失败判定为 Java 侧 10s/20s 超时。调查三个依赖库能否原生承担该职责
（本地锁定版本：quinn 0.11.11 / quinn-proto 0.11.17 / quinn-plaintext 0.3.0 / tokio_kcp 0.9.8）。

### QUIC 调查结论

- quinn-plaintext 仅实现 `crypto::Session`/`ClientConfig`/`ServerConfig` 明文替换，
  不涉及任何超时机制；quinn 全部传输配置可用但与本问题无关。
- quinn-proto 的 `Timer::Idle`（触发 `ConnectionError::TimedOut`）仅在
  `on_packet_authenticated → reset_idle_timeout` 布防——**只在收到包时重置，连接创建时不设初值**。
- 黑洞场景（零响应）：loss detection 的 PTO 探针无限重传（`pto_count` 无上限、无放弃逻辑），
  Idle 永不触发 ⇒ `connecting.await` **永久悬挂**。
- 有响应后断流：需等 `max(协商 idle timeout 默认 30s, 3×PTO)` 才报 TimedOut，远超目标。

结论：quinn 无法在 10s/20s 内自行终结黑洞握手。

### KCP 调查结论

- `KcpStream::connect*` 为纯本地构造（`KcpSocket::new` + 随机 conv），无网络交互、无握手应答，
  对死对端同样"成功"——不存在原生连接超时的概念。
- 唯一相关参数 `session_expire`（默认 90s）是服务端闲置回收，与建连无关。
- 现实现 connect 后立即置 CONNECTED：黑洞下客户端误判已连成，挂载管线后失去降级窗口。

结论：KCP 无握手可言，native 层无从判定可达性。

## 决策

1. **统一由 Java `HandshakeWatchdog` 承担握手超时**（首次 10s、后续 20s，竞速 connect promise）。
   这是两栈约束下的唯一可行方案，非偏好选择。
2. **KCP 存活判定重定义**：STATE_CONNECTED 由"connect 返回"改为"**首个有效数据帧送达
   Java 队列**"——reader 从 FEC/KCP 栈解出首个 ExtendedCmd 数据帧、payload 进入 `to_java`
   队列时置位；FEC 解码失败的杂散 UDP 包不计。
   **配套：KCP 客户端出站不受 CONNECTED 门控**（native 写路径与 Java channel 均放行
   连接期写入）。否则死锁：tokio_kcp 服务端按首包建会话，客户端等首帧才发、
   服务端等首包才回，双向互等。
   依据：MC 连接建立后立即发送协议握手包、服务端必有响应，首帧即可证明双向可达；
   零 wire 改动，保住降级窗口（管线挂载仍延迟到 CONNECTED 后）。
3. **QUIC 维持现状**：明文握手双向交换 transport params，CONNECTED 即真实可达证明；
   watchdog 超时后关闭连接句柄即可。
4. 两栈其余参数保持默认：`max_idle_timeout`(30s) 管会话期存活，`ReadTimeoutHandler(30)` 管
   应用层空闲，三者正交不混用。

## 后果

- watchdog 超时路径必须主动 `closeConnection(connId)` 清理 native 句柄（QUIC 黑洞下
  native 任务仍在 PTO 空转，不清理则泄漏）。
- KCP 首字节判定使握手耗时含 RTT；10s 预算在高延迟链路依然充足（KCP aggressive 档
  interval=10ms）。
