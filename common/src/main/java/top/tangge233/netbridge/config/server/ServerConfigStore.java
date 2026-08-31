package top.tangge233.netbridge.config.server;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 服务端配置磁盘读写与模板生成（server.toml），基于 NightConfig。
 */
public record ServerConfigStore(
        Path file
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfigStore.class);
    private static final String TEMPLATE_RESOURCE = "/net-bridge/server-default.toml";

    /**
     * 加载 server.toml，如果文件不存在则先从资源模板释放生成默认文件。
     */
    public ServerSettings load() {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ensureTemplateExists();
            try (var config = CommentedFileConfig.builder(file).build()) {
                config.load();
                return new ServerSettings(
                        readSection(config, "quic", ServerTransportSettings.defaultQuic()),
                        readSection(config, "kcp", ServerTransportSettings.defaultKcp())
                );
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load server config {}: {}", file, e.toString());
            return ServerSettings.defaults();
        }
    }

    public void ensureTemplateExists() {
        if (Files.exists(file)) {
            return;
        }

        try (var in = ServerConfigStore.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                LOGGER.warn(
                        "Built-in server config template {} not found on mod classpath",
                        TEMPLATE_RESOURCE
                );
                return;
            }
            Files.copy(in, file);
            LOGGER.info("Generated default server config {}", file);
        } catch (Exception e) {
            LOGGER.warn("Failed to write default server config {}: {}", file, e.toString());
        }
    }

    private static ServerTransportSettings readSection(
            CommentedFileConfig config,
            String name,
            ServerTransportSettings defaults
    ) {
        var enable = (Boolean) config.get(List.of(name, "enable"));
        var bind = (String) config.get(List.of(name, "bind"));
        var host = (String) config.get(List.of(name, "host"));
        var port = (Integer) config.get(List.of(name, "port"));
        var maxConnection = (Integer) config.get(List.of(name, "max_connection"));
        var profileStr = (String) config.get(List.of(name, "profile"));

        var profile = KcpProfile.parse(profileStr);
        if (profile == null && profileStr != null && !profileStr.isBlank()) {
            LOGGER.warn(
                    "Unknown kcp profile '{}' in section '{}', using default",
                    profileStr,
                    name
            );
        }

        return new ServerTransportSettings(
                enable != null
                        ? enable
                        : defaults.enabled(),
                bind != null && !bind.isBlank()
                        ? bind
                        : null,
                host != null && !host.isBlank()
                        ? host
                        : null,
                port != null
                        ? port
                        : defaults.port(),
                maxConnection != null && maxConnection >= 1
                        ? maxConnection
                        : defaults.maxConnections(),
                profile != null
                        ? profile
                        : defaults.kcpProfile()
        );
    }

}
