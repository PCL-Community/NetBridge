package top.tangge233.netbridge.config.server;

import top.tangge233.netbridge.transport.KcpProfile;

import org.jspecify.annotations.Nullable;

/**
 * 服务端单个传输协议配置（未解析状态，端口允许 -1 或 0）。
 *
 * @param enabled        是否启用
 * @param bindHost       监听 IP 字面量（null 或空表示跟随 server-ip / 全部网卡）
 * @param advertisedHost 下发 Ping 响应的主机名/IP（null 表示跟随连接主机）
 * @param port           配置端口（-1 跟随 MC 端口，0 随机分配，1..65535 固定端口）
 * @param maxConnections 最大活跃连接数（>= 1）
 * @param kcpProfile     KCP 参数档（仅 KCP 有效）
 */
public record ServerTransportSettings(
        boolean enabled,
        @Nullable String bindHost,
        @Nullable String advertisedHost,
        int port,
        int maxConnections,
        @Nullable KcpProfile kcpProfile
) {

    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    public static ServerTransportSettings defaultQuic() {
        return new ServerTransportSettings(
                true,
                null,
                null,
                -1,
                DEFAULT_MAX_CONNECTIONS,
                null
        );
    }

    public static ServerTransportSettings defaultKcp() {
        return new ServerTransportSettings(
                false,
                null,
                null,
                -1,
                DEFAULT_MAX_CONNECTIONS,
                KcpProfile.BALANCE
        );
    }

}
