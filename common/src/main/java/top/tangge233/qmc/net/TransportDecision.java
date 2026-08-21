package top.tangge233.qmc.net;

/**
 * 客户端传输决策：基于 ping 结果与用户模式。
 */
public record TransportDecision(boolean useQuic, boolean allowTcpFallback, String reason) {
    public static TransportDecision useTcp(String reason) {
        return new TransportDecision(false, true, reason);
    }

    public static TransportDecision useQuic(String reason) {
        return new TransportDecision(true, false, reason);
    }

    public static TransportDecision fallbackToTcp(String reason) {
        return new TransportDecision(false, true, reason);
    }
}
