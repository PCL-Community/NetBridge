# ADR-0001: networks 能力发现 wire 格式 v2

状态：已接受 · 日期：2026-08-25 · 取代：pre-refactor networks 格式（旧代码注释中的 ADR-0002）

## 背景

服务端经列表 ping 响应注入顶层 `networks` 对象宣告传输能力。旧格式为
`quic: {features: ["quic-raw"], port, protocol: "net-bridge/1"}`。KCP 加入后需统一多传输宣告，
且项目处于 Alpha，允许破坏性变更（硬切，不做双格式兼容期）。

## 决策

wire 格式 v2：

```json
"networks": {
  "quic": {"enable": true, "host": "1.1.1.1", "port": 25565, "protocol": "net-bri-quic/1"},
  "kcp":  {"enable": true, "host": null,     "port": 25566, "protocol": "net-bri-kcp/1"}
}
```

- 每传输一个平级条目；字段集相同：`enable` / `host` / `port` / `protocol`。
- **wire 上只出现解析后的具体值**：服务端 `[quic]/[kcp] port = -1/0` 仅是绑定便利，
  启动时即解析为实际监听端口再写入条目——wire 的 `port` 恒为 1..=65535，绝不出现 -1/0。
- `enable` 缺失视为 `false`。
- `host` 缺失或 null：客户端使用 ping 目标的服务器地址（此时端口仍取条目值；
  若条目整体缺失则该传输不存在，无回退拼接）。
- **`features` 字段废除**。未来新传输算法靠新增条目/扩展字段表达，版本演进只看 `protocol`。
- **协议协商**：客户端将条目 `protocol` 与自身支持集（`net-bri-quic/1`、`net-bri-kcp/1`）精确比对；
  不支持的协议 → 该传输本地禁用（视同未宣告）。
- 不做旧格式解析；老客户端连新服务端只会看到无能力，回落 TCP，属预期。

## 后果

- `NetworksAbility` 重构为按传输名取条目的通用模型，删除 features 逻辑。
- 协议串即版本门闩：后续不兼容改动升 `net-bri-quic/2` 等。
