package top.tangge233.netbridge.transport;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 客户端传输模式三档。
 *
 * <p>quic/kcp 内置 TCP 降级（握手两次失败自动回退）；tcp 即纯 TCP，
 * 无降级概念。不存在独立的 *-fallback-tcp 档。
 */
public enum TransportMode {

    TCP,
    QUIC,
    KCP;

    /**
     * 解析配置串；未知值返回 null（调用方回退默认 TCP）。 旧取值（quic-only/quic-fallback 等）不迁移，一律视为未配置。
     */
    public static @Nullable TransportMode parse(@Nullable String value) {
        return value == null
                ? null
                : switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "tcp" -> TCP;
                    case "quic" -> QUIC;
                    case "kcp" -> KCP;
                    default -> null;
                };
    }

    /** 配置文件与系统属性中的小写标识。 */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

}
