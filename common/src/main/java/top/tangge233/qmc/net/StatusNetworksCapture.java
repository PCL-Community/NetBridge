package top.tangge233.qmc.net;

import java.util.concurrent.ConcurrentHashMap;

/**
 * status 包解码时捕获的 networks 能力的线程级暂存槽。
 *
 * ClientboundStatusResponsePacket 是 record，无法通过 mixin 挂载实例字段
 * （解码构造器中 readJsonWithCodec 发生在 this() 之前，handler 必须为 static）。
 * 因此解码时写入本槽（按解码线程隔离），包对象创建后由消费方在同一线程取走。
 */
public final class StatusNetworksCapture {
    private static final ConcurrentHashMap<Thread, NetworksAbility> LAST = new ConcurrentHashMap<>();

    private StatusNetworksCapture() {}

    /**
     * 解码线程上记录本次解析出的 networks。必须无条件写入（含 empty）：
     * 槽位以「最近一次解码」为准，若空结果跳过写入，上一条残留值会被
     * 误配到后续不宣告 QUIC 的服务器包上。
     */
    public static void capture(NetworksAbility networks) {
        LAST.put(Thread.currentThread(), networks == null ? NetworksAbility.empty() : networks);
    }

    /** 消费方在包处理时取走当前线程的暂存值；无则 empty。 */
    public static NetworksAbility take() {
        NetworksAbility n = LAST.remove(Thread.currentThread());
        return n == null ? NetworksAbility.empty() : n;
    }
}
