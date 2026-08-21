package top.tangge233.qmc.net;

/**
 * 客户端 QUIC 连接目标：服务端宣告的 QUIC 端口 + 是否允许 TCP 回退。
 */
public record QuicTarget(int quicPort, boolean allowTcpFallback) {}
