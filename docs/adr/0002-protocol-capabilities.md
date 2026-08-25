# ADR-0002: 传输能力识别（`networks` 字段 + QUIC 模式选择）

日期：2026-08-20  
状态：已接受（重写：去掉登录期 flag 协商，仅保留 Ping `networks` 识别）  
影响范围：服务端 Ping、客户端智能选择

## 背景与目标

服务端需要告知客户端自己支持哪些传输（TCP / QUIC），且 QUIC 具有 `quic-raw` 特性；客户端据此智能选择。**只做“传输能力识别”，不做能力协商**（无 `encryption_skip`、无 `zstd_stream` 等登录期 flag）。

## 决策

### Ping 阶段（仅 TCP）——`networks` 字段

- 服务端列表 Ping 仍只走 TCP（原版 status listener），响应 JSON 增加顶层字段：
  ```json
  {
    "version": { "name": "1.21.1", "protocol": 767 },
    "players": { ... },
    "description": "...",
    "favicon": "...",
    "networks": {
      "quic": {
        "features": ["quic-raw"],
        "port": 25565,
        "protocol": "net-bridge/1"
      }
    }
  }
  ```
- 原版客户端忽略未知字段，向后兼容（ADR-0005）。
- 客户端发现 `networks.quic.features` 含 `quic-raw` 后，才将 QUIC 作为候选；否则走 TCP。

### 模式选择（客户端设置）

- `TCP`：始终 TCP。
- `QUIC`：仅当 Ping 显示支持且用户显式选择时使用；失败则**不自动**回退（用户需手动切换）——因为 QUIC 为明文管道，自动切换可能掩盖网络问题。
- `QUIC-with-TCP-fallback`：优先 QUIC；握手失败/UDP 被 NAT 阻断/超时 → 自动回退 TCP。
- 默认推荐 `TCP` 或 `QUIC-with-TCP-fallback`（用户显式选择 QUIC 承担明文风险，见 ADR-0001 安全边界）。

## 失败模式

- Ping 无 `networks` 或 `features` 不含 `quic-raw` → 视作不支持 QUIC，走 TCP。
- QUIC 握手失败/超时（如 5s）→ QUIC-with-fallback 回退 TCP；QUIC-only 显示错误。
- 协议版本不匹配（`protocol != "net-bridge/1"`）→ 降级 TCP。

## 后果

- 正向：透明（未扩展登录期协商）、向后兼容、可演进。
- 负向：客户端需缓存 Ping 结果以驱动模式选择；QUIC 能力信息仅靠 TCP Ping（若 Ping 被劫持，QUIC 识别信息可信度同 Ping 本身，但 QUIC 为明文管道故风险一致）。

## 待办

- [x] `networks` JSON 注入工具：common `StatusNetworks.addNetworks`。
- [x] Ping 解析模型：common `Networks` / `StatusNetworks.parse`。
- [x] 模式决策：`TransportMode` / `TransportDecider`（TCP/QUIC/fallback）。
- [ ] Fabric `MinecraftServerMixin`（已编译进 jar，待实测）；NeoForge 状态 hook 待接入。
- [ ] 客户端模式 UI 与缓存（连接层接入）。
- [ ] 真实 QUIC 传输（Rust quinn-plaintext endpoint）驱动 Ping 端口参数。
