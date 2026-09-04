# FFM 数据面基准基线

按方案 §24 的要求建立的可重复基准。JNI 时代基线在 cutover 前未单独存档（Phase 0 冻结的是测试基线），因此本基线为
FFM 数据面的**首次定量记录**；性能验收不以"快于 JNI X%"为 目标，而以方案 §24.5 为准：

- 无固定轮询唤醒（idle CPU 不因事件模型退化）；
- 写路径无 Java heap `byte[]` 中转；
- 吞吐无未解释的显著回退；
- 回调风暴下 p99 不显著恶化；
- 内存增长有界（重复 backend/context 生命周期测试断言注册表归零）。

## 运行方式

```bash
./gradlew :common:ffmBenchmark                    # 默认 5 秒吞吐窗口
./gradlew :common:ffmBenchmark --args="<lib> 10"  # 指定 cdylib 与秒数
```

harness 位于 `common/src/benchmark/java/.../FfmBenchmark.java`，不进入 mod jar。 项目：空 downcall（
`connection_state`）、1KiB/64KiB write+read 往返、QUIC loopback 吞吐与 chunk 级延迟分位。

## 基线样本

环境：WSL2 (linux-x86_64)，debug cdylib，Java 25，2026-09-04。

```text
empty downcall (connection_state)  ops=20000  avg=    0.03 us/op
write+read 1KiB                   ops=20000  avg=  379.17 us/op
write+read 1KiB                   p50=  300.98 us  p95=  297.90 us  p99=  267.51 us  (n=20000)
write+read 64KiB                  ops=2500  avg= 1606.95 us/op
write+read 64KiB                  p50= 1434.39 us  p95= 1972.19 us  p99= 1286.71 us  (n=2500)
QUIC loopback throughput          throughput       60.3 MiB/s  (301 MiB sent in 5.00s)
QUIC loopback throughput          p50=   40.59 us  p95=  610.63 us  p99= 1971.24 us  (n=27939)
```

复跑时更新本文件并注明环境（OS/arch、profile、日期）。数值仅用于回归对比，不作绝对承诺；如吞吐/延迟出现 >
5–10% 的未解释回退，按方案要求 profile 并记录原因。
