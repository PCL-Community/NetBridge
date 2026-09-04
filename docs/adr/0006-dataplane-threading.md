# ADR-0006: 数据面线程模型——EventLoop 自适应轮询

状态：已被 ADR-0010 取代 · 日期：2026-08-25 · 取代：pre-refactor QuicChannel 固定 5ms 轮询注释中的
ADR-0001 表述

## 背景

native 侧（tokio）异步产生/消费字节，Java 侧 Netty EventLoop 消费。候选方案调研结论：

| 方案                                               | 结论                                                                                                                                                   |
|----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| Rust→Java 回调（AttachCurrentThread 常驻派发线程） | 拒绝。每次 attach/detach ~50–100μs；常驻附着需自管线程生命周期、DeleteLocalRef、bootstrap classloader 限制、关闭期死锁风险；收益仅为消除 ≤5ms 轮询延迟 |
| wakeup fd 注册进 NioEventLoop                      | 拒绝。把 native fd 包装为 SelectableChannel 依赖 `sun.nio.ch` 内部 API，跨启动器/JVM 发行版不可移植                                                    |
| 每连接阻塞读专用线程                               | 拒绝。线程数随连接数线性膨胀，且数据仍需 marshal 回 EventLoop                                                                                          |
| EventLoop 定时轮询                                 | **采用**。现状每轮 2–3 次 JNI 调用（亚微秒），固定 5ms 已实测可行；仅需自适应化                                                                        |

## 决策

沿用 `AbstractChannel` + `scheduleAtFixedRate` 轮询架构，参数改为 **步进退避**：

- **握手/连接期**：固定 5ms（快速感知 STATE_CONNECTED/FAILED/CLOSED，支撑 10s/20s 超时判定）。
- **已连接期**：活跃（本轮有数据或写队列非空）保持 5ms；连续空转按 5→10→20→40ms 步进退避， 上限 40ms（一个
  MC tick）；任何数据到达或写排队立即复位 5ms。
- 读路径维持现状：池化 direct buffer + `readChunkInto`，单轮最多 16 次读防独占 EventLoop。
- 写路径维持现状：FastThreadLocal 复用 scratch `byte[]` + `writeChunk`，队列满靠下一轮 flush 重试。

## 后果

- 空闲连接 CPU 开销趋零（最坏 25 次 JNI 调用/秒/连接）；活跃延迟上限仍 5ms。
- 若 profile 实测 5ms 成为瓶颈，再评估 wakeup fd 方案（届时可考虑 Netty epoll transport 的 native
  扩展点），不在本期范围。
