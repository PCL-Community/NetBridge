package top.tangge233.qmc.net;

/**
 * 基于 Ping 结果与模式生成传输决策，供客户端连接流程使用。
 */
public final class TransportDecider {
    private TransportDecider() {}

    public static TransportDecision decide(TransportMode mode, Networks networks) {
        if (mode == null) {
            mode = TransportMode.TCP_ONLY;
        }
        if (networks == null) {
            networks = Networks.empty();
        }
        switch (mode) {
            case QUIC_ONLY -> {
                if (networks.supportsQuicRaw()) {
                    return TransportDecision.useQuic("quic-only: server advertises quic-raw");
                }
                return new TransportDecision(false, false, "quic-only: server does not advertise quic-raw");
            }
            case QUIC_WITH_TCP_FALLBACK -> {
                if (networks.supportsQuicRaw()) {
                    return TransportDecision.useQuic("fallback: try quic first");
                }
                return TransportDecision.useTcp("fallback: server lacks quic-raw, use tcp");
            }
            case TCP_ONLY -> {
                return TransportDecision.useTcp("user selected tcp");
            }
            default -> throw new IllegalStateException("Unknown mode: " + mode);
        }
    }
}
