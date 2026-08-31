package top.tangge233.netbridge.config.server;

/**
 * 服务端传输配置集合。
 *
 * @param quic QUIC 传输配置
 * @param kcp  KCP 传输配置
 */
public record ServerSettings(
        ServerTransportSettings quic,
        ServerTransportSettings kcp
) {

    public static ServerSettings defaults() {
        return new ServerSettings(
                ServerTransportSettings.defaultQuic(),
                ServerTransportSettings.defaultKcp()
        );
    }

}
