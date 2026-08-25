package top.tangge233.netbridge.net;

/**
 * status 包解码时捕获的 networks 能力的线程级暂存槽。
 *
 * ClientboundStatusResponsePacket 是 record，无法通过 mixin 挂载实例字段
 * （解码构造器中 readJsonWithCodec 发生在 this() 之前，handler 必须为 static）。
 * 因此解码时写入本槽（按解码线程隔离），包对象创建后由消费方在同一线程取走。
 *
 * 用 ThreadLocal 而非 ConcurrentHashMap&lt;Thread,…&gt;：同线程写入读取无需并发
 * map，且线程死亡后条目随之释放（CHM 强引用 key 会残留死线程条目）。
 */
public final class StatusNetworksCapture {
    private static final ThreadLocal<NetworksAbility> LAST = new ThreadLocal<>();

    private StatusNetworksCapture() {}

    /**
     * 解码线程上记录本次解析出的 networks。必须无条件写入（含 empty）：
     * 槽位以「最近一次解码」为准，若空结果跳过写入，上一条残留值会被
     * 误配到后续不宣告 QUIC 的服务器包上。
     */
    public static void capture(NetworksAbility networks) {
        LAST.set(networks == null ? NetworksAbility.empty() : networks);
    }

    /** 消费方在包处理时取走当前线程的暂存值并清槽；无则 empty。 */
    public static NetworksAbility take() {
        NetworksAbility n = LAST.get();
        LAST.remove();
        return n == null ? NetworksAbility.empty() : n;
    }
}
