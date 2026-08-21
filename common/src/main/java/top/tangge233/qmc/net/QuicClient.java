package top.tangge233.qmc.net;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 客户端 QUIC 传输决策与能力缓存（ADR-0002 / ADR-0005）。
 *
 * 传输模式：游戏内设置界面切换（多人游戏屏幕底部按钮），
 * 初始值取系统属性 {@code qmc.transport}（可选），默认 TCP。
 */
public final class QuicClient {
    public static final String PROP_MODE = "qmc.transport";
    public static final Logger LOGGER = Logger.getLogger("qmc.client");

    private static final Map<String, Networks> NETWORKS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private static volatile TransportMode mode = parseMode(System.getProperty(PROP_MODE, "tcp"));

    private QuicClient() {}

    private static TransportMode parseMode(String value) {
        return switch (value == null ? "tcp" : value.trim().toLowerCase(Locale.ROOT)) {
            case "quic", "quic_only", "quic-only" -> TransportMode.QUIC_ONLY;
            case "quic_fallback", "quic-fallback", "fallback" -> TransportMode.QUIC_WITH_TCP_FALLBACK;
            default -> TransportMode.TCP_ONLY;
        };
    }

    /** 当前传输模式（ADR-0002）。 */
    public static TransportMode mode() {
        return mode;
    }

    /** 运行时切换传输模式（游戏内设置界面调用）。 */
    public static void setMode(TransportMode newMode) {
        mode = newMode == null ? TransportMode.TCP_ONLY : newMode;
        LOGGER.info("Transport mode set to " + mode);
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
        // 注意：不因上次失败拉黑地址（ADR-0002 fallback 语义：
        // 每次连接都重新尝试 QUIC，失败仅影响本次）。
        if (mode == TransportMode.TCP_ONLY || tcpAddress == null) {
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

    /** 记录连接传输决策（游戏日志可见，便于诊断）。 */
    public static void logTransportChoice(InetSocketAddress address, boolean useQuic, String reason) {
        LOGGER.info("Transport for " + address + ": " + (useQuic ? "QUIC" : "TCP") + " (" + reason + ")");
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
