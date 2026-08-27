<div align="center">

# net-bridge

<p align="center">
  <strong>A Minecraft mod that transports your game traffic over better network protocol</strong>
</p>

[![License](https://img.shields.io/badge/license-AGPL--v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20macos-lightgrey.svg)]()

</div>

English | [简体中文](README.zh-CN.md)

---

## How it works?

The mod took over Minecraft's server connection stream and used QUIC-Plaintext (or KCP with FEC) to transport data streams. The mod uses JNI to call the connection manage component written by Rust🦀.

The server announces its accelerated transports in the ping response under a top-level `networks` object, one entry per transport:

```json
"networks": {
  "quic": {"enable": true, "port": 25565, "protocol": "net-bri-quic/1"},
  "kcp":  {"enable": true, "port": 25566, "protocol": "net-bri-kcp/1"}
}
```

You will see `[QUIC/KCP]` at the end of its description, if server also installs the mod. The client picks QUIC or KCP from the transport button in the multiplayer screen.

> If two handshake attempts fail, it falls back to TCP automatically for that connection and remembers it for 5 minutes.

### Why disable QUIC's default encryption?

Minecraft already has its own encrypted streams, and this mod just wants to take advantage of QUIC's reliable transmission features. Additional cryptographic handshakes cause additional overhead, which is unnecessary.

## Configuration

Server: `config/net-bridge/server.toml`

```toml
[quic]
enable = true
# -1 = follow the Minecraft TCP port; 0 = random; otherwise a fixed port
port = -1
bind = ""            # empty = follow server.properties server-ip
host = ""            # advertised address; empty = follow the server address
max_connection = 256 # excess connections are silently dropped

[kcp]
enable = true        # Disable by defualt
port = -1            # follows Minecraft TCP port + 1
bind = ""            # empty = follow server.properties server-ip
host = ""            # advertised address; empty = follow the server address
max_connection = 256
profile = "balance"  # or "aggressive"
```

Client: `config/net-bridge/client.toml`

```toml
mode = "tcp"        # tcp / quic / kcp

[kcp]
profile = "balance" # balance / aggressive
```

System property `-Dnetbridge.transport=tcp|quic|kcp` overrides `mode`.

## Building

Requirements:

| Dependency | Notes |
| ---------- | ----- |
| Rust | via [rustup](https://rustup.rs/) |
| JDK 21+ | Gradle runs through the `./gradlew` wrapper (JDK from PATH) |

Build in release mode:

```bash
make build
```

Or debug mode:

```bash
make debug
```

Clean everything:

```bash
make clean
```

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
