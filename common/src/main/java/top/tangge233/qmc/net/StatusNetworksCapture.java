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
    private static final ConcurrentHashMap<Thread, Networks> LAST = new ConcurrentHashMap<>();

    private StatusNetworksCapture() {}

    /** 解码线程上记录最近一次解析出的 networks。 */
    public static void capture(Networks networks) {
        if (networks != null && !networks.quicInfo().isEmpty()) {
            LAST.put(Thread.currentThread(), networks);
        }
    }

    /** 消费方在包处理时取走当前线程的暂存值；无则 empty。 */
    public static Networks take() {
        Networks n = LAST.remove(Thread.currentThread());
        return n == null ? Networks.empty() : n;
    }
}
