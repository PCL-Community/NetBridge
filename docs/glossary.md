# quic-mc 术语表（Glossary）

本术语表服务于 quic-mc 的 ADR 与实现，随文档持续更新。
（当前方向：无 TLS、无 zstd、无指纹，仅传输能力识别 + QUIC 管道替换。）

## 通用

- **TCP（原版连接）**：Minecraft 默认的字节流传输。quic-mc 保留其作为兼容与回退路径。
- **QUIC（连接）**：基于 UDP 的 IETF 传输协议（由 Rust `quinn-proto`/`quinn-plaintext` 实现），提供多路复用、低延迟、抗队头阻塞、连接迁移；本项目以**明文管道**形式使用（无加密层）。
- **QUIC-with-TCP-fallback（智能回退）**：客户端模式之一：优先 QUIC，在握手失败/UDP 被 NAT 阻断/能力不匹配时自动回退 TCP。
- **quic-raw**：quic-mc 对 QUIC 连接的特性标记，出现于 Ping 响应 `networks` 字段中，表示该服务端可提供 raw（明文）QUIC 传输。
- **管道路径 / 字节流语义**：MC 应用层（`Connection`/Netty）所见的帧流。quic-mc 保持与 TCP 完全一致的字节流语义（帧长度、顺序、压缩、加密状态均不变），以达成对其它 mod 透明。
- **明文 QUIC（Plaintext QUIC）**：使用 `quinn-plaintext`（无 TLS/无证书）的 QUIC 管道。**不提供**机密性、完整性、认证；仅限可信/隔离网络或底层已有加密（如 WireGuard）场景。0.2.0+ 自带 checksum（仅防随机损坏，非认证 MAC）。
- **JNI 桥（JNI Bridge）**：Java ⇄ Rust 的同步批量字节队列接口，Java 通过 `writeChunk/readChunk` 与 Rust QUIC 传输交换数据，避免逐包跨 JNI。
- **Channel 适配器（QUIC Channel Adapter）**：Java 侧包装 QUIC 流的 Netty `Channel`，使原版 `Connection` 以一致接口读写，原版握手/登录/游玩逻辑不变。
- **传输能力识别（Transport Capability Advertise）**：服务端在 Ping 响应 `networks` 字段声明 QUIC 能力（`quic-raw`、端口、`protocol: "quic-mc/1"`）；客户端据此选择 TCP/QUIC/fallback。**不做登录期能力协商**（无 `encryption_skip`/`zstd_stream` 等 flag）。

## Minecraft 相关

- **Mojang 映射（Mojang Mappings）**：Minecraft 官方反混淆映射，quic-mc 核心使用它，以避免 Fabric 中间层/NeoForge 特有命名差异。
- **NeoForge**：Minecraft 1.21.1 的 modding 平台之一（基于 Forge 的后续分支），提供事件总线与 payload 注册。
- **Fabric**：另一 modding 平台，提供 `ServerLoginNetworking`/`ClientLoginNetworking` 登录期查询 API。
- **原版密钥交换 / 认证仪式**：登录时客户端生成随机会话密钥、以服务器公钥加密后发送，并由服务器派生 `serverId`、调用 `hasJoinedServer` 完成在线模式认证的流程。**本项目不修改它**（ADR-0003 已否决跳过）。
- **AES/CFB8（会话流加密）**：原版登录后安装的无认证流式加密（CFB8）。**在本项目中原版行为不变**（QUIC 流之上照常运行）。
- **`setEncryption`**：原版连接认证后安装 AES cipher 的环节。**本项目不跳过、不修改**。
- **`hasJoinedServer`**：Mojang 会话服务接口，服务器调用它以验证玩家是否已通过认证（在线模式）。**本项目不触碰**。
- **`RegisterPayloadHandlersEvent`**：NeoForge 注册自定义 payload 的入口（本项目仅用于 play/configuration 阶段，不扩展 login 阶段）。
- **`ServerLoginNetworking` / `ClientLoginNetworking`**：Fabric 登录期查询 API。**本项目不依赖它**（无登录期协商）。
- **Ping（服务器列表）**：客户端通过 TCP 向服务器（status 协议）发送的服务器信息查询，响应 JSON 含版本、玩家、描述、favicon；quic-mc 扩展 `networks` 字段做传输能力识别。
- **`networks` 字段**：Ping 响应 JSON 中 quic-mc 新增的顶层字段，声明 `quic` 能力、端口、协议版本（`protocol: "quic-mc/1"`）与特性列表（`["quic-raw"]`）。
- **原版 zlib 压缩（`SetCompression`）**：Minecraft 原版的包压缩。**本项目不修改、不叠加 zstd**（ADR-0004 已否决）。

## 构建与工具

- **Gradle 多模块构建**：单 Gradle 构建内 include `:common` / `:neoforge` / `:fabric` 三个子项目（ADR-0006）。根项目负责 Rust cdylib 与产物聚合。
- **ModDevGradle**：NeoForge 官方 Gradle 插件，仅应用于 `:neoforge`，提供 mojmap 命名的 Minecraft 依赖。
- **Fabric Loom**：Fabric 官方 Gradle 插件，仅应用于 `:fabric`，提供 minecraft 依赖、`remapJar`（mojmap→intermediary）与 `runClient` 开发环境。
- **Intermediary 映射**：Fabric 运行时的中性类命名（如 `net.minecraft.class_2535`）。Fabric mod 必须在打包时由 Loom 从 Mojang 映射重映射到 intermediary，否则无法加载。
- **remapJar**：Loom 的重映射打包任务，产出可在 Fabric Loader 加载的 jar。
- **mise**：工具链版本管理器，管理 JDK 21、Gradle 等版本。
- **cdylib（.so）**：Rust 编译产出的共享库，通过 JNI 被 Java 加载。
- **rules_rust**：Bazel 官方 Rust 规则集，用于 `rust_shared_library` 构建 cdylib。
- **quinn-plaintext**：基于 quinn-proto 的明文化传输插件（无 TLS），用于本项目的 QUIC 管道。
- **quinn-proto**：QUIC 传输协议实现（状态机、拥塞控制等），quinn-plaintext 以其为基础。

## 架构层（按数据流）

1. **Java / MC 协议层**：原版 `Connection` + 网络事件，生成/消费 MC 协议帧。
2. **QUIC Channel 适配器**：把 QUIC 流暴露为 Netty `Channel`，接入原版连接管道。
3. **JNI 桥**：批量字节队列，Java ⇄ Rust 数据交换。
4. **Rust QUIC 传输层**：quinn-plaintext endpoint、QUIC 流、UDP socket、tokio runtime（**无 TLS/无压缩**）。

## 版本锁定约定

- **Minecraft 1.21.1 双平台锁定**：`:neoforge`（NeoForge 21.1.183）与 `:fabric`（`minecraft_version=1.21.1`）必须锁定同一 MC 版本；升级时同步修改 `gradle.properties` 并双平台回归（ADR-0006）。

## 决策记录索引

- ADR-0001：传输架构（TCP 保留 + QUIC 可选，quinn-plaintext 管道，无 TLS/无 zstd/无指纹）
- ADR-0002：传输能力识别（`networks` 字段 + 模式选择，无登录期协商）
- ADR-0003：认证与加密（**已否决**——无认证解耦/无指纹，原版认证与加密不变）
- ADR-0004：zstd 流压缩（**已否决**——不引入压缩层）
- ADR-0005：对其它模组的透明性边界（普通 mod 透明；传输层独占 mod 不承诺共存）
- ADR-0006：多模块构建架构（common/neoforge/fabric 三子项目；共享 MC 逻辑按平台复制源码；Fabric 走 Loom remapJar）
