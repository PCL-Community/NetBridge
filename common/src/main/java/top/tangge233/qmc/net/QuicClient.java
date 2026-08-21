package top.tangge233.qmc.net;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 QUIC 传输决策与能力缓存（ADR-0002 / ADR-0005）。
 *
 * 传输模式：游戏内设置界面切换（多人游戏屏幕底部按钮），
 * 初始值取系统属性 {@code qmc.transport}（可选），否则读平台注册的
 * 配置文件（{@link #useConfigFile}），默认 TCP。切换时自动写回配置文件。
 */
public final class QuicClient {
    public static final String PROP_MODE = "qmc.transport";
    public static final Logger LOGGER = LoggerFactory.getLogger("qmc");

    private static final Map<String, Networks> NETWORKS = new ConcurrentHashMap<>();

    private static volatile TransportMode mode = parseMode(System.getProperty(PROP_MODE, "tcp"));
    /** 平台入口注册的配置文件路径；未注册（如单测）时不做任何磁盘读写。 */
    private static volatile Path configPath;

    private QuicClient() {}

    private static TransportMode parseMode(String value) {
        return switch (value == null ? "tcp" : value.trim().toLowerCase(Locale.ROOT)) {
            case "quic", "quic_only", "quic-only" -> TransportMode.QUIC_ONLY;
            case "quic_fallback", "quic-fallback", "fallback" -> TransportMode.QUIC_WITH_TCP_FALLBACK;
            default -> TransportMode.TCP_ONLY;
        };
    }

    private static String modeValue(TransportMode mode) {
        return switch (mode) {
            case TCP_ONLY -> "tcp";
            case QUIC_ONLY -> "quic";
            case QUIC_WITH_TCP_FALLBACK -> "quic-fallback";
        };
    }

    /**
     * 注册配置文件路径并立即加载其中保存的模式（平台 mod 入口启动时调用一次；
     * 传 {@code null} 注销注册）。文件为 properties 风格的单行
     * {@code mode=<tcp|quic|quic-fallback>}；缺失/损坏时保持当前模式，不视为错误。
     */
    public static synchronized void useConfigFile(Path file) {
        configPath = file;
        if (file == null) {
            return;
        }
        if (System.getProperty(PROP_MODE) != null) {
            LOGGER.info("Transport mode from system property {}, ignoring config file {}", mode(), file);
            return;
        }
        try {
            if (!Files.exists(file)) {
                return;
            }
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("mode=")) {
                    TransportMode loaded = parseMode(trimmed.substring("mode=".length()));
                    mode = loaded; // 直接赋值，避免加载过程触发回写
                    LOGGER.info("Transport mode loaded from {}: {}", file, loaded);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read transport mode from {}: {}", file, e.toString());
        }
    }

    /** 当前传输模式（ADR-0002）。 */
    public static TransportMode mode() {
        return mode;
    }

    /** 运行时切换传输模式（游戏内设置界面调用）；已注册配置文件时自动持久化。 */
    public static void setMode(TransportMode newMode) {
        mode = newMode == null ? TransportMode.TCP_ONLY : newMode;
        LOGGER.info("Transport mode set to {}", mode);
        saveMode();
    }

    private static synchronized void saveMode() {
        Path file = configPath;
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "mode=" + modeValue(mode()) + System.lineSeparator());
        } catch (Exception e) {
            LOGGER.warn("Failed to save transport mode to {}: {}", file, e.toString());
        }
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

    /**
     * 基于当前模式与能力缓存生成 QUIC 目标。
     *
     * @return 可用 QUIC 目标；未启用/未宣告时返回 null（走 TCP）。
     *         注意：不因上次失败拉黑地址（ADR-0002 fallback 语义：
     *         每次连接都重新尝试 QUIC，失败仅影响本次）。
     */
    public static QuicTarget quicTargetFor(InetSocketAddress tcpAddress) {
        TransportMode mode = mode();
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
        LOGGER.info("Transport for {}: {} ({})", address, useQuic ? "QUIC" : "TCP", reason);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
