# net-bridge 术语表

随 grilling 进展更新。标 ⏳ 的条目仍在拷问中，定稿后移除标记。
ADR 序列自重构起重新编号（docs/adr/0001 起）；旧代码注释中 ADR-0001~0007 引用已弃用，重构时随手清理。

## 架构总览

- **net-bridge**：Minecraft mod 总体。`common`（平台无关逻辑）+ `fabric` / `neoforge`（loader 适配层）。
- **net-bridge-native**：Rust crate，经 JNI 向 Java 暴露传输原语。持有 tokio runtime 与 endpoint。
- **JNI 桥**：Java `top.tangge233.netbridge.jni.NativeBridge`（原 `QuicNative`，ADR-0007 去协议化改名）
  ↔ Rust 导出函数。句柄式注册表（传输无关 `long` id）。ABI `0.2.0`，不匹配即拒绝加载。
- **Transport trait**（Rust，ADR-0007）：connect/accept/state/read/write/close 统一抽象；
  quic 与 kcp 各自实现，JNI 层经 `dyn Transport` 分派。
- **双 loader 源码副本**：mixin 与 transport 类在 fabric/neoforge 各一份源码副本；mixin 仅挂载点，
  逻辑下沉 common。

## 传输协议

- **QUIC 明文（quic-plaintext）**：QUIC 传输但关闭 TLS 加密；安全性由 Minecraft 自带加密流保证。动机：省一次加密握手开销。
- **KCP 栈**（自外向内）：**FEC(RS) → KCP → smux**。
  - **FEC / RS 码**：Reed-Solomon 前向纠错，最外层 UDP 包保护。实现在 `bridge/kcp/fec_stream.rs`。
  - **KCP**：可靠低延迟 ARQ，stream 模式（字节流管道），kcp-rs 实现（内建 SYN 握手）。
  - **smux**：多路流控层（原 `bridge/kcp/frame.rs` 控制字层已移除），滑动窗口 token 流控 + FIN 关闭语义；单条 MC 字节流占用一个 smux 流。
- **KCP profile**：预设参数档，仅二档不支持自定义。配置串规范 `balance` / `aggressive`
  （Rust 解析兼容别名 `balanced`）：
  - **balance**：nodelay=0/interval=40/resend=0/nc=0，mtu=1300，wnd=(256,256)，stream=true。
  - **aggressive**：nodelay=1/interval=10/resend=2/nc=1，其余同上。

## 能力发现

- **networks 能力**：服务端在列表 ping 响应 JSON 注入的顶层 `networks` 对象，每传输一个条目
  `{enable, host, port, protocol}`（ADR-0001）。`enable` 缺失 = false；`host` 缺失/null = 跟随服务器地址；`features` 字段已废除。
- **protocol 版本串**：`net-bri-quic/1`、`net-bri-kcp/1`。客户端精确比对自身支持集，
  不支持的协议 → 该传输本地禁用（ADR-0001）。版本演进只看 protocol 串。

## 客户端行为

- **TransportMode**：三档 `tcp` / `quic` / `kcp`（ADR-0002）。quic/kcp 内置 TCP 降级，tcp 无降级概念。
- **fallback（降级）**：单次连接流程内目标传输至多尝试 2 次（第 1 次超时 10 s、第 2 次 20 s，
  native FAILED 立即计败），两败后本次连接改走 TCP（ADR-0002）。
  所选传输未宣告/协议不支持 → 直接走 TCP，不尝试其他加速传输（ADR-0002 补充）。
- **降级记忆**：按服务器地址记忆 fallback 结果，TTL 5 分钟；**命中即直接走 TCP、跳过全部
  加速尝试**，过期后重新执行完整尝试序列（ADR-0002）。
- **握手存活判定**：Java `HandshakeWatchdog` 竞速 connect promise（10s/20s）——quinn 黑洞下永不
  自行失败（ADR-0008）。QUIC 的 CONNECTED = 明文握手完成；**KCP 的 CONNECTED = kcp-rs SYN
  握手完成**（`connect_timeout` 8s 内无应答直接 FAILED，watchdog 兜底）。
- **连接提示**：ConnectScreen 另起一行显示实际状态机文案——"正在建立 QUIC/KCP/TCP 连接"、
  降级时"正在回退 TCP 连接"（ADR-0005）。服务器列表 tooltip 说明降级策略。
- **F3 协议行**：单行 `[net-bridge] <QUIC|KCP|TCP> <addr>`，显示实际生效协议与连接地址（ADR-0005）。

## 配置

- **nightconfig**：MC 生态 TOML 配置库。服务端 `config/net-bridge/server.toml`；客户端同目录 `config/net-bridge/client.toml`。
- **游戏内切换按钮**：保留（多人游戏屏幕底部），与配置文件双向同步（读文件初始值、切换即写回）。
- **服务端 `[quic]`/`[kcp]` 段**（字段一致）：`enable`（**quic 默认 true，kcp 默认 false**）/ `bind`（默认 0.0.0.0）/ `host`（null=跟随服务器地址）/ `port`（-1 跟随 MC 端口，**kcp 为 MC 端口+1**；0 随机；越界或 bind 失败→日志报错并禁用该传输）/ `max_connection`（默认 256，达限静默丢弃新客户端——防 UDP 反射，quic/kcp 独立计数）（ADR-0003）。ping 条目恒下发**解析后的具体端口**，-1/0 不上 wire（ADR-0001）。
- **客户端 `mode`**：tcp（默认）/ quic / kcp；`[kcp] profile` = balance（默认）/ aggressive。系统属性 `netbridge.transport` 同名覆盖配置文件；旧取值（`quic-fallback` 等）废弃。无迁移（Alpha 硬切）。

## JNI 数据面

- **批量桥接口**：同步 `writeChunk(byte[], length)` / `readChunk(maxBytes)` / `readChunkInto(direct ByteBuffer)`
  / `connectionState(conn)`；句柄式注册表。
- **边界策略**（ADR-0004）：零拷贝仅限各语言内部（Rust 用 `Bytes`）；JNI 边界显式允许拷贝换内存安全。
  `readChunkInto` 为唯一豁免的直接内存路径（SAFETY 论证完备，保留）；不新增其他直接内存变体。
- **线程模型**（ADR-0006）：Netty EventLoop 自适应轮询——连接期固定 5ms；活跃期 5ms，
  连续空转步进退避至 40ms 上限；拒绝回调/wakeup fd/每连接阻塞线程三方案。
- 返回约定：入队字节数（0=队列满须重试）、读取数（0=暂无）、-1/null=非法或连接失效。
