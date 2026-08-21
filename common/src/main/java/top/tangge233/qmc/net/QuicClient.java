package top.tangge233.qmc.net;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 QUIC 传输决策与能力缓存（ADR-0002 / ADR-0005）。
 *
 * 全局开关：系统属性 {@code qmc.transport}，取值
 * {@code tcp}（默认）、{@code quic}、{@code quic_fallback}。
 */
public final class QuicClient {
    public static final String PROP_MODE = "qmc.transport";

    private static final Map<String, Networks> NETWORKS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private QuicClient() {}

    /** 当前传输模式（ADR-0002）。 */
    public static TransportMode mode() {
        String value = System.getProperty(PROP_MODE, "tcp").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "quic", "quic_only", "quic-only" -> TransportMode.QUIC_ONLY;
            case "quic_fallback", "quic-fallback", "fallback" -> TransportMode.QUIC_WITH_TCP_FALLBACK;
            default -> TransportMode.TCP_ONLY;
        };
    }

    public static boolean quicEnabled() {
        return mode() != TransportMode.TCP_ONLY;
    }

    /** 记录一次 Ping 解析出的能力（按解析后的 IP:port 缓存）。 */
    public static void record(InetSocketAddress address, Networks networks) {
        if (address == null || networks == null) {
            return;
        }
        NETWORKS.put(key(address), networks);
    }

    /** 查询某地址的 QUIC 能力；未 Ping 过返回 empty。 */
    public static Networks networksFor(InetSocketAddress address) {
        if (address == null) {
            return Networks.empty();
        }
        return NETWORKS.getOrDefault(key(address), Networks.empty());
    }

    /** 标记某地址 QUIC 连接失败（本会话内不再重试，用于 fallback 防递归）。 */
    public static void markQuicFailed(InetSocketAddress address) {
        if (address != null) {
            FAILED.add(key(address));
        }
    }

    public static boolean isQuicFailed(InetSocketAddress address) {
        return address != null && FAILED.contains(key(address));
    }

    /**
     * 基于当前模式与能力缓存生成 QUIC 目标。
     *
     * @return 可用 QUIC 目标；未启用/未宣告/已失败时返回 null（走 TCP）。
     */
    public static QuicTarget quicTargetFor(InetSocketAddress tcpAddress) {
        TransportMode mode = mode();
        if (mode == TransportMode.TCP_ONLY || tcpAddress == null || isQuicFailed(tcpAddress)) {
            return null;
        }
        Networks networks = networksFor(tcpAddress);
        if (!networks.supportsQuicRaw()) {
            return null;
        }
        int quicPort = networks.quic().port();
        if (quicPort <= 0) {
            return null;
        }
        return new QuicTarget(quicPort, mode == TransportMode.QUIC_WITH_TCP_FALLBACK);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
