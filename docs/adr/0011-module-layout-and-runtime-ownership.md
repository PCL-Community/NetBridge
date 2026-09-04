# ADR-0011: 模块布局与 Java 运行时所有权

状态：已接受 · 日期：2026-09-04 · 取代：ADR-0007（模块划分与 JNI 命名去协议化）

## 背景

ADR-0007 时代模块以 common + 双 loader 副本为主体：`NativeClientTransport`、
`NativeServerTransport` 与 7 个 mixin 在 fabric/neoforge 各维护一份由
`checkSyncedCopies` 强制同步的副本；`ConnectStatus`/`ConnectionDisplay`/
`TransportSelector`/`FallbackTracker`/`NativeAcceptor` 等为静态可变状态；
`NetBridgeServices` 是三个独立静态服务加隐式默认自举的"static service bag"。

## 决策

### 模块布局

```text
common      纯 Java domain/runtime/native 抽象/config（禁止 import net.minecraft、loader API）
minecraft   共享 Minecraft-aware 层：NativeClientTransport、NativeServerTransport、
            7 个共享 mixin（top.tangge233.netbridge.mc / .mixin，禁止 import loader API）
fabric      仅 Fabric bootstrap / 生命周期 glue（MinecraftServerLifecycleMixin）/ metadata
neoforge    仅 NeoForge bootstrap / 事件 glue / metadata
rust        workspace：net-bridge-core（纯传输核心，forbid unsafe）+ net-bridge-native（C ABI shell）
```

共享层以 source set 形式编译进两个 loader（双方均基于 Mojmap；fabric 由 loom remap 到
intermediary，NeoForge 1.21+ 运行时即 mojmap），`checkSyncedCopies`
随之删除——同步由架构而非流程保证。loader module 只保留 bootstrap/lifecycle glue。

### Java 运行时所有权

- **单一 composition root**：`NetBridgeServices` 仅持有一个 `static @Nullable
  NetBridgeRuntime runtime`；未 bootstrap 一律 `IllegalStateException`，删除隐式
  `config/net-bridge` 默认自举。
- **`NetBridgeRuntime`（AutoCloseable）**持有 config 服务、native backend（可用或
  `UnavailableNativeTransportBackend`）、`ClientRuntime` 与 `ServerRuntime`； native 加载失败不阻断
  runtime，客户端 planner 自动 TCP、服务端不发布 accelerated。
- **实例化状态**：客户端 `ClientRuntime`（capabilities/success caches、planner、executor、state
  store）与服务端 `ServerRuntime`/`ServerTransportManager`
  （session-scoped，事务式 start/stop，支持 integrated server 重启）各自持有；生产代码静态可变状态仅允许
  root locator（白名单：`NetBridgeServices`、 mixin 层 ThreadLocal 守卫、`StatusNetworksCapture` 捕获槽）。
- **架构守卫**：`verifyArchitecture` Gradle 任务固化上述规则（FFM import 白名单、 JNI 归零、层纯净性、package
  @NullMarked 覆盖、副本目录不存在、Rust core 无 FFI/全局注册表）；`verifyNativeSymbols` 保证 cdylib
  业务导出仅
  `netbridge_get_api`。

## 后果

- 一份 Minecraft 源码，两 loader smoke 均绿；新增平台功能不再双写。
- 状态均有实例 owner，bootstrap 顺序显式，native unavailable 仍提供完整 runtime + TCP。
- 守卫任务进入 `assembleAll` 依赖链，回归即失败。
