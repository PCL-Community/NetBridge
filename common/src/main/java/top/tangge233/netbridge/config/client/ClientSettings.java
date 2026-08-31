package top.tangge233.netbridge.config.client;

import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

/**
 * 客户端不可变配置模型。
 *
 * @param mode       客户端加速模式（TCP / QUIC / KCP）
 * @param kcpProfile KCP 参数档（BALANCE / AGGRESSIVE）
 */
public record ClientSettings(
        TransportMode mode,
        KcpProfile kcpProfile
) {

    public static ClientSettings defaults() {
        return new ClientSettings(TransportMode.TCP, KcpProfile.BALANCE);
    }

}
