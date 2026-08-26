package top.tangge233.netbridge.transport;

/**
 * 连接状态机文案钩子：ConnectScreen 轮询此处渲染实际状态行。
 *
 * <p>由客户端连接流程写入（尝试中/降级中），连接结束或开始新连接时覆盖；
 * 渲染层只读。state 为 volatile 单值——同一时刻只有一次连接流程在前台，
 * 后台并发连接以最后写入者为准（可接受的显示近似）。
 */
public final class ConnectStatus {
    /** 连接阶段：无 / 尝试加速传输 / 正在降级 TCP。 */
    public enum Phase {
        IDLE,
        CONNECTING,
        FALLING_BACK
    }

    private record State(Phase phase, TransportMode mode) {}

    private static volatile State current = new State(Phase.IDLE, null);

    private ConnectStatus() {}

    /** 写入「正在建立 X 连接」阶段。 */
    public static void connecting(TransportMode mode) {
        current = new State(Phase.CONNECTING, mode);
    }

    /** 写入「正在回退 TCP 连接」阶段。 */
    public static void fallingBack() {
        current = new State(Phase.FALLING_BACK, null);
    }

    /** 清除状态（连接完成/失败后）。 */
    public static void clear() {
        current = new State(Phase.IDLE, null);
    }

    /** 当前阶段（非 null）。 */
    public static Phase phase() {
        return current.phase();
    }

    /** 当前尝试的传输模式；仅 {@link Phase#CONNECTING} 下非 null。 */
    public static TransportMode mode() {
        return current.mode();
    }
}
