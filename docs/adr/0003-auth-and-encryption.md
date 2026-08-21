# ADR-0003: 认证与加密（已否决——无 TLS、无加密解耦、无指纹）

日期：2026-08-20  
状态：**已否决（Superseded by ADR-0001 重写）**  
影响范围：历史记录

## 上一版内容摘要（不再适用）

上一版曾计划：
- 保留原版密钥交换 + 可跳过 AES 流加密（`encryption_skip`）；
- 在线模式保留流加密、离线/自定义鉴权可跳过；
- QUIC TLS + TOFU 指纹信任模型。

## 为什么否决

- 用户方向明确：**初期不引入任何额外加密/压缩层**，QUIC 仅作为透明管道替换。
- `encryption_skip`/指纹/zstd 协商会改变字节流语义（AES 可观察、压缩可观察），破坏“对其它 mod 透明”目标。
- quinn-plaintext 无 TLS，无需证书/指纹/TOFU。
- 这意味着：**本项目初期不修改原版认证与加密路径**——原版 `enableEncryption`/AES 照常运行于 QUIC 流之上（在线与离线模式行为与 TCP 一致）。

因此本 ADR 的内容（认证仪式 vs 会话加密解耦、指纹 TOFU）不作为本项目实现，仅保留为历史决策记录。

## 现状

- 传输层：quinn-plaintext（无加密），见 ADR-0001。
- 应用层：原版认证/加密原封不动（`setEncryption` 不被跳过），见 ADR-0001 字节流模型与 ADR-0005。
- 无 `encryption_skip` 能力、无指纹、无 zstd。
