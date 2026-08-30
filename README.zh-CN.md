<div align="center">

# net-bridge

<p align="center">
  <strong>一个将游戏流量承载到更优网络协议上的 Minecraft 模组</strong>
</p>

[![License](https://img.shields.io/badge/license-AGPL--v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20macos-lightgrey.svg)]()

</div>

[English](README.md) | 简体中文

---

## 工作原理

模组接管了 Minecraft 的服务端连接流，使用 QUIC-Plaintext（或带 FEC 的 KCP）承载数据流。模组通过 JNI 调用由 Rust🦀 编写的连接管理组件。

服务端在 ping 响应的顶层 `networks` 对象中宣告其加速传输能力，每个传输一个条目：

```json
"networks": {
  "quic": {"enable": true, "port": 25565, "protocol": "net-bri-quic/1"},
  "kcp":  {"enable": true, "port": 25566, "protocol": "net-bri-kcp/1"}
}
```

如果服务器也安装了本模组，你会在其描述末尾看到 `[QUIC/KCP]` 标记。客户端在多人游戏界面通过传输按钮选择 QUIC 或 KCP。

> 若两次握手尝试均失败，将自动为该连接回退到 TCP，并记忆 5 分钟。

### 为什么禁用 QUIC 的默认加密？

Minecraft 本身已有加密流，本模组只想利用 QUIC 的可靠传输特性。额外的加密握手会带来不必要的开销。

## 配置

服务端：`config/net-bridge/server.toml`

```toml
[quic]
enable = true
# -1 = 跟随 Minecraft TCP 端口；0 = 随机；否则为固定端口
port = -1
bind = ""            # 留空 = 跟随 server.properties 的 server-ip
host = ""            # 宣告地址；留空 = 跟随服务器地址
max_connection = 256 # 超出上限的新连接被静默丢弃

[kcp]
enable = true
port = -1            # 跟随 Minecraft TCP 端口 + 1
bind = ""            # 留空 = 跟随 server.properties 的 server-ip
host = ""            # 宣告地址；留空 = 跟随服务器地址
max_connection = 256
profile = "balance"  # 或 "aggressive"
```

客户端：`config/net-bridge/client.toml`

```toml
mode = "tcp"        # tcp / quic / kcp

[kcp]
profile = "balance" # balance / aggressive
```

系统属性 `-Dnetbridge.transport=tcp|quic|kcp` 可覆盖 `mode`。

## 构建

依赖：

| 依赖 | 说明 |
| ---------- | ----- |
| Rust | 经 [rustup](https://rustup.rs/) 安装 |
| JDK 21+ | Gradle 经 `./gradlew` wrapper 运行（JDK 取自 PATH） |

发布模式构建：

```bash
make build
```

或调试模式：

```bash
make debug
```

清理全部产物：

```bash
make clean
```

## 许可证

本项目基于 [LGPL-v3](LICENSE) 授权。

> 在 v0.0.5 版本及之前使用 APGL-v3 授权。
