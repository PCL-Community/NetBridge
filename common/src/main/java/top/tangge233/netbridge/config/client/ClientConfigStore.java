package top.tangge233.netbridge.config.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 客户端配置磁盘读写（client.toml），基于 NightConfig。
 *
 * <p>纯 IO 职责，不持有运行时有效状态。
 */
public record ClientConfigStore(
        Path file
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfigStore.class);

    /**
     * 从磁盘加载 client.toml；如果文件不存在或解析失败，返回默认配置。
     */
    public ClientSettings load() {
        if (!Files.exists(file)) {
            return ClientSettings.defaults();
        }

        try (var config = CommentedFileConfig.builder(file).build()) {
            config.load();
            String modeStr = config.get("mode");
            var mode = TransportMode.parse(modeStr);
            if (mode == null) {
                mode = TransportMode.TCP;
            }

            String profileStr = null;
            var kcpObj = config.get("kcp");
            if (kcpObj instanceof UnmodifiableConfig sub) {
                profileStr = sub.get("profile");
            }
            if (profileStr == null) {
                profileStr = config.get(List.of("kcp", "profile"));
            }
            if (profileStr == null) {
                profileStr = config.get("kcp.profile");
            }

            var profile = KcpProfile.parse(profileStr);
            if (profile == null) {
                if (profileStr != null && !profileStr.isBlank()) {
                    LOGGER.warn("Unknown kcp profile '{}' in {}: using default", profileStr, file);
                }
                profile = KcpProfile.BALANCE;
            }

            return new ClientSettings(mode, profile);
        } catch (Exception e) {
            LOGGER.warn("Failed to read client config from {}: {}", file, e.toString());
            return ClientSettings.defaults();
        }
    }

    /**
     * 保存配置到 client.toml。
     */
    public void save(ClientSettings settings) {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var content = "mode = \"%s\"\n\n[kcp]\nprofile = \"%s\"\n".formatted(
                    settings.mode().configValue(),
                    settings.kcpProfile().configValue()
            );
            Files.writeString(file, content);
        } catch (Exception e) {
            LOGGER.warn("Failed to save client config to {}: {}", file, e.toString());
        }
    }

}
