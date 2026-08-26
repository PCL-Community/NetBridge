package top.tangge233.netbridge.ability;

import java.util.Locale;
import java.util.Set;

/**
 * 传输协议版本串常量与支持集比对。
 *
 * <p>版本串是 wire 层的协商门闩：客户端将宣告条目的 protocol 与自身
 * 支持集精确比对，不支持的协议视同该传输未宣告。未来新传输或破坏性
 * 演进通过新增版本串表达，不做兼容回退。
 */
public final class TransportProtocol {
    /** QUIC 明文传输 v1。 */
    public static final String QUIC_V1 = "net-bri-quic/1";
    /** KCP 传输 v1。 */
    public static final String KCP_V1 = "net-bri-kcp/1";

    /** 客户端支持的协议集合（精确比对，无通配）。 */
    public static final Set<String> SUPPORTED = Set.of(QUIC_V1, KCP_V1);

    private TransportProtocol() {}

    /**
     * 判断宣告的 protocol 是否在支持集内。
     *
     * @param protocol 宣告条目的 protocol 字段；null/缺失 = 不支持
     */
    public static boolean isSupported(String protocol) {
        return protocol != null && SUPPORTED.contains(protocol.trim().toLowerCase(Locale.ROOT));
    }
}
