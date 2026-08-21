package top.tangge233.qmc.net;

/**
 * 客户端传输模式（ADR-0002）。
 */
public enum TransportMode {
    TCP_ONLY,
    QUIC_ONLY,
    QUIC_WITH_TCP_FALLBACK;
}
