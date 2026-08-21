# quic-mc 目录/构建约定（2026-08-21 同步）

- 根 Gradle 单工程多 sourceSet：
  - `java/qmc-common`：纯 common，无 mod loader 依赖。
  - `java/qmc-mc`：MC 相关共享代码（客户端 QUIC glue + 共享 mixin），
    打包进 fabric/neoforge 两个 jar；依赖 merged jar（Mojang 映射）+ sponge-mixin。
  - `java/qmc-neoforge`：NeoForge 1.21.1 mod（入口、META-INF/neoforge.mods.toml、后续 network mixin/at）。
  - `java/qmc-fabric`：Fabric 1.21.1 mod（入口、fabric.mod.json、后续 mixins）。
  - Rust cdylib：`rust/qmc-native`，cargo 构建后复制到 `build/native/`。
- 包名：`top.tangge233.qmc.*`（JNI 类 `top.tangge233.qmc.jni.QuicNative`）。
- 客户端 QUIC 接入（2026-08-21）：
  - `QuicChannel`（common，Netty AbstractChannel 适配器）包装 JNI QUIC 流，
    纯 Netty 回环集成测试通过。
  - `qmc-mc` 共享 mixin：`ConnectionMixin`（connect 时按能力/模式换 QUIC 或回退 TCP）、
    `ClientboundStatusResponsePacketMixin`（解码时捕获 networks 原始 JSON）、
    `ServerStatusPingerResponseMixin`（把 networks 绑定到服务器地址）。
  - 客户端开关：系统属性 `qmc.transport` = tcp（默认）/ quic / quic_fallback。
- 用户要求：不要触碰 .minecraft/游戏目录；mise 管理 Java/Gradle，Rust 不交 mise；国内镜像；命令先让用户/沙箱外运行。
