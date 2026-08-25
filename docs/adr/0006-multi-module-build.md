# ADR-0006: 多模块构建架构（common / neoforge / fabric）

- 状态：已接受
- 日期：2026-08-21
- 关联：ADR-0001、ADR-0005

## 背景

原构建为单 Gradle 项目 + 多 sourceSet（main/mc/neoforge/fabric）。存在两个致命问题：

1. **Fabric 产物未重映射**：所有源码以 Mojang 映射编译，直接打进 `jarFabric`。
   Fabric 运行时类名是 intermediary（如 `net.minecraft.class_2535`），
   未 remap 的 mod 在 Fabric Loader 上必然 `NoClassDefFoundError` / mixin 解析失败。
2. **共享 sourceSet 跨映射体系**：`mc` sourceSet 同时被两个平台的 jar 打包，
   但两平台对 Minecraft 类的运行时命名不同，一份 class 无法同时服务两者。

## 决策

采用 **Gradle 多模块构建**（单构建、`settings.gradle` include）：

| 模块 | 内容 | 构建插件 |
|------|------|----------|
| `:common` | JNI 桥、QUIC Channel 适配器、传输决策、QuicServer——**零 Minecraft 依赖**（仅 Netty/gson） | `java-library` |
| `:neoforge` | NeoForge 入口、mixin（mojmap 编写）、从原 `qmc-mc` 下沉的客户端传输 glue（mojmap 编写） | ModDevGradle |
| `:fabric` | Fabric 入口、mixin（mojmap 编写）、从原 `qmc-mc` 下沉的客户端传输 glue（mojmap 编写） | Fabric Loom |

要点：

1. **共享逻辑按平台复制而非共享字节码**（"下沉到各自平台模块"）：
   原 `qmc-mc` 中引用 Minecraft 类的代码（`QuicClientTransport`、
   `ConnectionMixin`、status mixin 等）在 `:neoforge` 与 `:fabric`
   各有一份源码副本（包名分别为 `top.tangge233.netbridge.neoforge.mc` /
   `top.tangge233.netbridge.fabric.mc`）。两者均以 Mojang 映射编写；
   Fabric 侧由 Loom 在 `remapJar` 时自动重映射到 intermediary。
   不追求单一共享源码集——跨映射体系的字节码共享是本次重构要消灭的问题本身。
2. **`:fabric` 使用完整 Fabric Loom**：提供 minecraft 依赖、
   `remapJar`、`runClient` 开发环境。Loom 与 ModDevGradle 并存于同一
   多模块构建，但各只应用于自己的子项目。
3. **Rust cdylib 构建留在根项目**（`buildCdylib` 任务），
   `:common` 的测试与两个平台 jar 均依赖其产物。
4. **产物聚合**：根任务 `assembleAll` 将 `net-bridge-neoforge-<v>.jar` 与
   `net-bridge-fabric-<v>.jar` 收集到根 `build/libs/`，保持分发习惯不变。
5. **版本锁定约定**：NeoForge（`neoForge.version = 21.1.183`，即 MC 1.21.1）
   与 Fabric（`minecraft_version=1.21.1`）必须锁定同一 Minecraft 版本。
   升级 MC 时需同步修改 `gradle.properties` 中两处版本并回归测试双平台。

## 后果

- 正面：Fabric 产物经官方 remap 管线，可被 Fabric Loader 正常加载；
  两平台 mixin 可分别演进；`:common` 保持纯净可独立测试。
- 负面：引用 MC 类的逻辑存在两份源码副本，修改需同步两处
  （以 diff 工具或后续 codegen 缓解）；构建引入 Loom 后首次配置时间变长。
- 中性：`java/qmc-*` 目录迁移为顶层 `common/`、`neoforge/`、`fabric/` 目录。
