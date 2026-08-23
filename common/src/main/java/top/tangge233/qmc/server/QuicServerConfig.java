package top.tangge233.qmc.server;

import java.nio.file.Files;
import java.nio.file.Path;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import top.tangge233.qmc.net.QuicClient;

/**
 * 服务端配置装载与参数解析（{@code quic-mc/server.toml}）。
 *
 * 从 {@link QuicServer} 拆出的纯配置层：文件读取、默认值生成、端口与
 * 连接上限的优先级解析；无状态，全部静态。
 */
final class QuicServerConfig {
    /** 可选系统属性：覆盖 QUIC 监听端口（优先级高于配置文件）。 */
    public static final String PROP_QUIC_PORT = "qmc.quicPort";

    /** 服务端活跃连接数默认上限（配置项 {@code max_connection} 可覆盖）。 */
    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    /** 默认监听端口语义值：跟随 Minecraft TCP 端口。 */
    public static final int DEFAULT_PORT = -1;

    private QuicServerConfig() {}

    /** {@code server.toml} 解析结果；null 字段表示未配置/非法，走内置默认。 */
    record ServerConfig(Integer port, Integer maxConnection) {}

    /**
     * 读取 {@code quic-mc/server.toml}；文件不存在时从 jar 内置模板
     * {@code server-default.toml} 自动生成（注释即文档，可直接编辑），
     * 存在则原样读取不回写。解析失败告警并返回空配置（全部走内置
     * 默认），不阻断启动。
     */
    static ServerConfig load() {
        Path dir = QuicClient.configDir();
        if (dir == null) {
            return new ServerConfig(null, null);
        }
        Path file = dir.resolve("server.toml");
        try {
            Files.createDirectories(dir);
            // try-with-resources：读取中途异常也能释放底层文件句柄。
            try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                    .defaultResource("/quic-mc/server-default.toml")
                    .build()) {
                config.load();
                Integer port = config.get("port");
                Integer max = config.get("max_connection");
                return new ServerConfig(port, max);
            }
        } catch (Exception e) {
            QuicServer.LOGGER.warn("Failed to load QUIC server config {}: {}", file, e.toString());
            return new ServerConfig(null, null);
        }
    }

    /**
     * 解析 QUIC 监听端口：优先级为系统属性 {@code qmc.quicPort} >
     * {@code server.toml} 的 {@code port}。端口语义：
     * {@code -1} 跟随 Minecraft TCP 端口；{@code 0} 启动时随机分配；
     * 1-65535 直接使用；其余非法值告警并按 -1（跟随 TCP 端口）处理。
     */
    static int resolveListenPort(Integer configuredPort, int tcpPort) {
        String sys = System.getProperty(PROP_QUIC_PORT);
        if (sys != null && !sys.isBlank()) {
            Integer parsed = parsePort(sys.trim(), "system property " + PROP_QUIC_PORT);
            if (parsed != null) {
                return applyPortSemantics(parsed, tcpPort, PROP_QUIC_PORT);
            }
        }
        if (configuredPort != null) {
            int parsed = configuredPort;
            if (parsed >= -1 && parsed <= 65535) {
                return applyPortSemantics(parsed, tcpPort, "server.toml");
            }
            QuicServer.LOGGER.warn("Invalid QUIC listen port {} from server.toml: not -1/0/1-65535", parsed);
        }
        QuicServer.LOGGER.info("QUIC listen port unset; following Minecraft TCP port {}", tcpPort);
        return tcpPort;
    }

    /** 应用端口语义：-1 跟随 TCP 端口，0 随机，1-65535 原样使用。 */
    private static int applyPortSemantics(int parsed, int tcpPort, String source) {
        if (parsed == -1) {
            QuicServer.LOGGER.info("QUIC listen port -1 from {}: following Minecraft TCP port {}", source, tcpPort);
            return tcpPort;
        }
        QuicServer.LOGGER.info("QUIC listen port {} from {}", parsed, source);
        return parsed;
    }

    /** 解析并校验端口（-1 跟随 TCP、0 自动分配、1-65535 固定）；非法返回 null 并告警。 */
    private static Integer parsePort(String value, String source) {
        try {
            int port = Integer.parseInt(value);
            if (port >= -1 && port <= 65535) {
                return port;
            }
            QuicServer.LOGGER.warn("Invalid QUIC listen port {} from {}: not -1/0/1-65535", value, source);
        } catch (NumberFormatException e) {
            QuicServer.LOGGER.warn("Invalid QUIC listen port '{}' from {}: not a number", value, source);
        }
        return null;
    }

    /**
     * 解析服务端连接上限：{@code server.toml} 的 {@code max_connection}，
     * 默认 {@value #DEFAULT_MAX_CONNECTIONS}；非法值（&lt;1）告警并回退默认。
     */
    static int resolveMaxConnections(Integer configured) {
        if (configured != null) {
            if (configured >= 1) {
                QuicServer.LOGGER.info("QUIC max connections {} from server.toml", configured);
                return configured;
            }
            QuicServer.LOGGER.warn("Invalid QUIC max connections {} from server.toml: must be >= 1", configured);
        }
        return DEFAULT_MAX_CONNECTIONS;
    }
}
