package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * 唯一的进程级服务入口 / Composition Root。
 *
 * <p>供 Loader / Mixin 边界定位对象图，内部各服务均为单实例对象。
 */
public final class NetBridgeServices {

    private static volatile @Nullable ConfigPaths configPaths;
    private static volatile @Nullable ClientSettingsService clientSettings;
    private static volatile @Nullable ServerConfigStore serverConfigStore;

    private NetBridgeServices() {
    }

    /**
     * 由 Loader 入口（Fabric / NeoForge）在启动阶段调用，完成配置和运行时初始化。
     */
    public static synchronized void bootstrap(ConfigPaths paths) {
        configPaths = paths;
        var clientStore = new ClientConfigStore(paths.clientFile());
        clientSettings = ClientSettingsService.create(clientStore);
        serverConfigStore = new ServerConfigStore(paths.serverFile());
    }

    public static @Nullable ConfigPaths configPaths() {
        return configPaths;
    }

    public static ClientSettingsService clientSettings() {
        var s = clientSettings;
        if (s == null) {
            synchronized (NetBridgeServices.class) {
                s = clientSettings;
                if (s == null) {
                    var clientStore = new ClientConfigStore(Path.of(
                            "config/net-bridge/client.toml"));
                    s = ClientSettingsService.create(clientStore);
                    clientSettings = s;
                }
            }
        }
        return s;
    }

    public static ServerConfigStore serverConfigStore() {
        var s = serverConfigStore;
        if (s == null) {
            synchronized (NetBridgeServices.class) {
                s = serverConfigStore;
                if (s == null) {
                    s = new ServerConfigStore(Path.of("config/net-bridge/server.toml"));
                    serverConfigStore = s;
                }
            }
        }
        return s;
    }

}
