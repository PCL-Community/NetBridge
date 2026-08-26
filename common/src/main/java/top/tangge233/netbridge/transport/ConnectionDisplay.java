package top.tangge233.netbridge.transport;

/**
 * 当前连接的展示信息（F3 单行）：协议名与实际使用的传输端点。
 *
 * <p>由客户端连接流程写入（成功后），新一次连接开始时清除；
 * TCP 直连显示 TCP 与原地址。渲染层只读。
 */
public final class ConnectionDisplay {
    private static volatile String line;

    private ConnectionDisplay() {}

    /** 写入当前生效的传输行（如 "QUIC 1.2.3.4:25565"）。 */
    public static void set(String protocolName, String endpoint) {
        line = protocolName + " " + endpoint;
    }

    /** 新一次连接开始前清除旧值。 */
    public static void clear() {
        line = null;
    }

    /**
     * 当前展示内容；未在加速/TCP 连接中返回 null（F3 不显示该行）。
     */
    public static String current() {
        return line;
    }
}
