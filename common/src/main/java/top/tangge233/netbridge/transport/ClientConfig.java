package top.tangge233.netbridge.transport;

import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import top.tangge233.netbridge.NetBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 客户端配置：传输模式与 KCP 参数档的持久化（client.toml）。
 *
 * <p>初始值优先取系统属性 {@code netbridge.transport}（可选，覆盖配置文件），
 * 否则读平台注册的 TOML 配置文件，默认 TCP。运行时切换（游戏内按钮） 自动写回。文件形态：
 *
 * <pre>
 * mode = "quic"
 * [kcp]
 * profile = "balance"
 * </pre>
 */
public final class ClientConfig {

    /** 系统属性名：同名覆盖配置文件中的 mode；旧取值（quic-fallback 等）不迁移。 */
    public static final String PROP_MODE = "netbridge.transport";

    private static volatile TransportMode mode = initialMode();
    private static volatile KcpProfile kcpProfile = KcpProfile.BALANCE;
    /** 平台入口注册的配置文件路径；未注册（如单测）时不做任何磁盘读写。 */
    private static volatile @Nullable Path configPath;

    private ClientConfig() {
    }

    /** 类初始化时的模式解析：系统属性 > 默认 TCP。 */
    private static TransportMode initialMode() {
        var parsed = TransportMode.parse(System.getProperty(PROP_MODE));
        return parsed == null
                ? TransportMode.TCP
                : parsed;
    }

    /**
     * 注册配置文件路径并加载其中保存的模式与 profile（平台 mod 入口启动时 调用一次；传 {@code null} 注销注册）。文件缺失/损坏时保持当前值， 不视为错误。
     */
    public static synchronized void useConfigFile(@Nullable Path file) {
        configPath = file;
        if (file == null) {
            return;
        }

        if (System.getProperty(PROP_MODE) != null) {
            NetBridge.LOGGER.info(
                    "Transport mode from system property {}, ignoring config file {}",
                    mode(),
                    file
            );
            return;
        }

        try {
            if (!Files.exists(file)) {
                return;
            }

            var toml = new Toml().read(file.toFile());
            var loadedMode = TransportMode.parse(toml.getString("mode"));
            if (loadedMode != null) {
                mode = loadedMode; // 直接赋值，避免加载过程触发回写
                NetBridge.LOGGER.info("Transport mode loaded from {}: {}", file, loadedMode);
            }
            var profileValue = toml.getString("kcp.profile");
            var loadedProfile = KcpProfile.parse(profileValue);
            if (loadedProfile != null) {
                kcpProfile = loadedProfile;
            } else if (profileValue != null) {
                NetBridge.LOGGER.warn(
                        "Unknown kcp profile '{}' in {}: keeping {}",
                        profileValue,
                        file,
                        kcpProfile
                );
            }
        } catch (Exception e) {
            NetBridge.LOGGER.warn("Failed to read client config from {}: {}", file, e.toString());
        }
    }

    /** 当前传输模式。 */
    public static TransportMode mode() {
        return mode;
    }

    /**
     * 平台注册的配置文件所在目录（如 {@code config/net-bridge}）；未注册时返回
     * null。服务端配置（{@code server.toml}）约定放在同一目录下，因此平台 入口必须在服务器启动前完成注册。
     */
    public static @Nullable Path configDir() {
        var file = configPath;
        return file == null
                ? null
                : file.getParent();
    }

    /**
     * 运行时切换传输模式（游戏内设置界面调用）；已注册配置文件时自动持久化。
     */
    public static void setMode(@Nullable TransportMode newMode) {
        mode = newMode == null
                ? TransportMode.TCP
                : newMode;
        NetBridge.LOGGER.info("Transport mode set to {}", mode);
        save();
    }

    private static synchronized void save() {
        var file = configPath;
        if (file == null) {
            return;
        }

        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", mode().configValue());
            Map<String, Object> kcp = new LinkedHashMap<>();
            kcp.put("profile", kcpProfile().configValue());
            data.put("kcp", kcp);
            Files.writeString(file, new TomlWriter().write(data));
        } catch (Exception e) {
            NetBridge.LOGGER.warn("Failed to save client config to {}: {}", file, e.toString());
        }
    }

    /** 当前 KCP 参数档。 */
    public static KcpProfile kcpProfile() {
        return kcpProfile;
    }

    /** 运行时切换 KCP 参数档；已注册配置文件时自动持久化。 */
    public static void setKcpProfile(@Nullable KcpProfile profile) {
        kcpProfile = profile == null
                ? KcpProfile.BALANCE
                : profile;
        save();
    }

}
