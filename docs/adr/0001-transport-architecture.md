# ADR-0001: 传输架构（TCP 保留 + QUIC 可选，quinn-plaintext 管道）

日期：2026-08-20  
状态：已接受（用户重写：无 TLS、无 zstd、无指纹；仅传输能力识别 + QUIC 管道替换）  
影响范围：全部模块

## 背景与目标

Minecraft 客户端与服务端默认使用 TCP。我们希望：
- 客户端具备 QUIC 连接能力、服务端具备 QUIC 接受能力；
- **QUIC 仅作为 TCP 的“管道替换”**，对其它 mod 透明；
- 保留原版 TCP 作为兼容与回退路径；
- 三种模式：TCP / QUIC / QUIC-with-TCP-fallback。

本项目初期的重点是**可行性验证与透明性**。因此移除上一版引入的 TLS/TOFU/zstd/认证解耦：**没有 zstd、没有 TLS，只有“传输能力识别”和 QUIC 管道**。

## 决策

- 传输层使用 Rust `quinn-plaintext`（基于 quinn-proto 0.11.8，纯明文、无加密），通过 JNI 暴露给 Java。
- `quinn-plaintext` 特性确认：
  - 无 TLS、无证书、无 TOFU、无指纹；
  - 0.2.0+ 自带 checksum（防随机损坏，**非**认证 MAC）；
  - **不与我们之外的 QUIC 实现互通**——仅 quic-mc 两端可用，符合定位；
  - 官方注明“仅当底层已有加密（如 WireGuard）时推荐”——本项目在初期接受该风险（见安全边界）。
- 客户端：先经 TCP Ping（ADR-0002）识别服务器是否支持 QUIC，再按模式选择 TCP/QUIC/fallback。
- 服务端：保留原版 TCP acceptor（Ping/登录/原版客户端），另开 UDP QUIC acceptor 并行接受。
- QUIC 流之上承载原版 Minecraft 协议帧，原版握手/登录/游玩逻辑**不变**。
- Rust 自持 UDP socket 与 tokio runtime；Java 通过同步 JNI 批量桥（块读写）传输，不逐包跨 JNI。
- 每 QUIC 连接 = 一个双向流，承载整个 MC 会话字节流。

## 字节流模型与对 mod 透明

- Java 侧原版 `Connection` 连接到一个包装 QUIC 流的 Netty `Channel` 适配器，保持原版握手/登录/游玩逻辑不变。
- 对其它 mod：字节流语义不变（无额外压缩层、无加密状态变化、无新增帧/长度/顺序变化）——这是“透明”的核心（见 ADR-0005）。
- 仅“传输能力识别”（Ping `networks` 字段）是可见的扩展，且向后兼容。

## 安全边界（重要）

- quinn-plaintext **不提供**机密性、完整性、认证。
- 后果：QUIC 会话明文可被网络中间人读取/篡改（checksum 仅防随机损坏）。**仅适用于隔离/可信网络或底层已有加密**（如 WireGuard/IPsec/VPN）。
- 因此：
  - 默认模式建议为 TCP 或 QUIC-with-TCP-fallback（用户显式选择 QUIC 方承担该风险）；
  - 文档与 UI 明确标注“明文 QUIC”；
  - 不承诺适用于公网明文场景。
- 未来若需加密，可作为独立 ADR 追加（例如 quinn TLS/TOFU 或底层 WireGuard），不在本项目初期范围。

## 备选方案

- quinn TLS + TOFU（上一版）：引入证书/指纹/UI 复杂度，初期否决。
- Java/Netty 持有 UDP socket：JNI 边界频繁、与 Netty event loop 争抢，否决。
- quinn-plaintext + 保留原版 AES：会破坏“对 mod 透明”目标（AES 字节流可观察），否决——初期不引入任何额外加密/压缩层。

## 后果

- 正向：可行性快、对普通 mod 透明、保留原版互操作、QUIC 收益（多路复用/抗队头阻塞/连接迁移）仍可得。
- 负向：明文会话仅限可信网络；QUIC 仅两端都安装 quic-mc 时可用；无加密/压缩能力。

## 待办

- Bazel（rules_rust）构建 Rust cdylib（平台 .so），mise 管理工具链（Bazelisk、JDK 21、Rust）。
- JNI 桥（`writeChunk`/`readChunk` 批量接口；实现另含直写池化直接缓冲区的零分配变体 `readChunkInto`，见 `QuicNative`/`QuicChannel`）。
- QUIC Channel 适配器 + Netty 集成。
- Ping `networks` 识别（ADR-0002）。
