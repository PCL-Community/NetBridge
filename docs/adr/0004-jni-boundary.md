# ADR-0004: JNI 数据面边界策略

状态：已被 ADR-0009 取代 · 日期：2026-08-25 · 取代：pre-refactor 注释中 ADR-0001 的零拷贝期望

> **本 ADR 描述 JNI 时代的边界策略。Java 25 + FFM 重构后由 ADR-0009
> （Java 25 / FFM / C ABI）整体取代，本文件仅保留为历史记录。**

## 背景

数据面要求低开销，但 JNI 边界的直接内存访问引入指针生命周期与安全复杂度。
项目 Alpha，优先正确与安全。

## 决策

- **零拷贝只在各语言内部成立**：Rust 内 `Bytes` 零成本接管；Java 内避免中间分配。
- **JNI 边界显式允许拷贝**，换取内存安全；现阶段不做跨语言直接内存访问。
- 接口形态维持批量字节桥：`writeChunk(byte[], length)` / `readChunk(maxBytes)` /
  `connectionState` / 句柄式注册表。写方向保持 `byte[]` 拷入，不新增直缓冲写变体。
- panic 不穿越 native 边界（catch_unwind 包裹），错误记 stderr 返回默认值——沿用现有约定。
- 传输泛化后 JNI 表面应与具体协议解耦（句柄即传输无关连接 id），函数命名去 QUIC 化。

## 后果

- 每 chunk 每方向恰一次边界拷贝；性能优化留待 profile 数据驱动。
- 已存在的 `readChunkInto(direct ByteBuffer)` 为唯一豁免的直接内存路径（SAFETY 论证完备，
  保留）；不新增其他直接内存变体。
