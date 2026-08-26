package top.tangge233.netbridge.server;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.transport.ClientConfig;

/**
 * 服务端配置装载（{@code net-bridge/server.toml}）。
 *
 * <p>{@code [quic]} 与 {@code [kcp]} 两段字段一致：
 * {@code enable}/{@code bind}/{@code host}/{@code port}/{@code max_connection}
 * （kcp 另有 {@code profile}）。文件不存在时从 jar 内置模板
 * {@code server-default.toml} 自动生成；解析失败告警并按内置默认处理，
 * 不阻断启动。无旧配置迁移。
 */
public final class ServerConfig {
    /** 服务端活跃连接数默认上限（配置项 {@code max_connection} 可覆盖）。 */
    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    /** 单个传输段配置；null 字段表示未配置，走内置默认。 */
    public record Section(
            boolean enable, String bind, String host, Integer port, Integer maxConnection, String profile) {}

    private final Section quic;
    private final Section kcp;

    private ServerConfig(Section quic, Section kcp) {
        this.quic = quic;
        this.kcp = kcp;
    }

    public static ServerConfig empty() {
        return new ServerConfig(SectionDefaults.QUIC, SectionDefaults.KCP);
    }

    public Section quic() {
        return quic;
    }

    public Section kcp() {
        return kcp;
    }

    /** 未配置字段的默认值来源。kcp 默认关闭（按需启用）。 */
    private static final class SectionDefaults {
        static final Section QUIC = new Section(true, null, null, -1, null, null);
        static final Section KCP = new Section(false, null, null, -1, null, "balance");
    }

    /** 内置模板资源路径（随 mod jar 打包，须经本类 classloader 读取）。 */
    private static final String TEMPLATE_RESOURCE = "/net-bridge/server-default.toml";

    /**
     * 读取 {@code net-bridge/server.toml}。
     *
     * @return 解析结果；目录未注册或读取失败时返回全默认配置
     */
    public static ServerConfig load() {
        Path dir = ClientConfig.configDir();
        if (dir == null) {
            return empty();
        }
        Path file = dir.resolve("server.toml");
        try {
            Files.createDirectories(dir);
            ensureTemplateExists(file);
            // 不用 defaultResource：NeoForge 的 mod jar 不在系统 classpath 上，
            // NightConfig 经自身 loader 取不到模板资源；模板已由
            // {@link #ensureTemplateExists} 落盘。
            try (CommentedFileConfig config = CommentedFileConfig.builder(file).build()) {
                config.load();
                return new ServerConfig(
                        readSection(config, "quic", SectionDefaults.QUIC.enable(), null),
                        readSection(config, "kcp",
                                SectionDefaults.KCP.enable(), SectionDefaults.KCP.profile()));
            }
        } catch (Exception e) {
            NetBridge.LOGGER.warn("Failed to load server config {}: {}", file, e.toString());
            return empty();
        }
    }

    /**
     * 首次启动时把 jar 内置模板写到配置文件位置（注释即文档，可直接编辑）；
     * 已存在则不动。模板经本类的 classloader 读取——mod 资源只在 mod 层可见。
     */
    private static void ensureTemplateExists(Path file) {
        if (Files.exists(file)) {
            return;
        }
        try (var in = ServerConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                NetBridge.LOGGER.warn(
                        "Built-in server config template {} not found on mod classpath", TEMPLATE_RESOURCE);
                return;
            }
            Files.copy(in, file);
            NetBridge.LOGGER.info("Generated default server config {}", file);
        } catch (Exception e) {
            NetBridge.LOGGER.warn("Failed to write default server config {}: {}", file, e.toString());
        }
    }

    /**
     * 读取单个传输段；段整体缺失时以默认值构造。
     *
     * @param defaultEnable 段缺 {@code enable} 字段时的默认值
     *                      （quic=true，kcp=false——与模板文档一致，
     *                      缺整段不等于意外开启监听）
     */
    private static Section readSection(
            CommentedFileConfig config, String name, boolean defaultEnable, String defaultProfile) {
        Boolean enable = config.get(name + ".enable");
        String bind = config.get(name + ".bind");
        String host = config.get(name + ".host");
        Integer port = config.get(name + ".port");
        Integer maxConnection = config.get(name + ".max_connection");
        String profile = config.<String>get(name + ".profile");
        return new Section(
                enable == null ? defaultEnable : enable,
                bind,
                host != null && !host.isBlank() ? host : null,
                port,
                maxConnection,
                profile != null ? profile : defaultProfile);
    }
}
