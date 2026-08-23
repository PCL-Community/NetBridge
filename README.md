<div align="center">

# quic-mc

<p align="center">
  <strong>A Minecraft mod that transports your game traffic over QUIC</strong>
</p>

[![License](https://img.shields.io/badge/license-AGPL--v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20macos-lightgrey.svg)]()

</div>

---

## Why QUIC?

QUIC can bring you a better gaming experience, especially in networks where TCP often gets stuck.

But this doesn't work for everyone. Your firewall or ISP may block or slow down UDP, which can make the game feel worse than TCP. So, test it yourself instead of just complaining.

## How it works?

The mod took over Minecraft's server connection stream and used QUIC-Plaintext to transport data streams. The mod uses JNI to call the connection streaming and management component written by Rust.

The server will send the network ability during the ping. If server supports quic connection, you will see `[QUIC]` at the end of server description.

The project is planning to give you more protocol options in the future (e.g. kcp).

### Why disable QUIC's default encryption?

Minecraft already has its own encrypted streams, and this mod just wants to take advantage of QUIC's reliable transmission features. Additional cryptographic handshakes cause additional overhead, which is unnecessary.

## Configuration

Add/Edit the file at `config/quic-mc/server.toml`. The file only has one config section:

```toml
port = 25566
```

## Building

Requirements:

| Dependency | Notes |
| ---------- | ----- |
| Rust | via [rustup](https://rustup.rs/) |
| JDK & Gradle | managed by [mise](https://mise.jdx.dev/), see `mise.toml` |

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
