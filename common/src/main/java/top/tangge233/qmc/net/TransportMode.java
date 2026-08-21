package top.tangge233.qmc.net;

/**
 * 客户端传输模式（ADR-0002）。
 */
public enum TransportMode {
    TCP_ONLY,
    QUIC_ONLY,
    QUIC_WITH_TCP_FALLBACK;

    public boolean prefersQuic() {
        return this != TCP_ONLY;
    }

    public boolean allowsTcp() {
        return this != QUIC_ONLY;
    }

    /** 根据服务端能力和模式，判断实际应优先尝试的传输。 */
    public static boolean shouldTryQuicFirst(TransportMode mode, Networks networks) {
        return mode.prefersQuic() && networks.supportsQuicRaw();
    }
}
