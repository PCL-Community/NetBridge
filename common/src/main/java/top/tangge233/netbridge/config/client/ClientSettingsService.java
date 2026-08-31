package top.tangge233.netbridge.config.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

/**
 * 客户端配置运行时服务，拥有当前的有效配置状态并自动持久化。
 */
public final class ClientSettingsService {

    public static final String PROP_MODE = "netbridge.transport";
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSettingsService.class);

    private final ClientConfigStore store;
    private volatile ClientSettings current;

    public ClientSettingsService(
            ClientConfigStore store,
            ClientSettings initial
    ) {
        this.store = store;
        this.current = initial;
    }

    public static ClientSettingsService create(ClientConfigStore store) {
        var loaded = store.load();
        var sysProp = System.getProperty(PROP_MODE);
        if (sysProp != null && !sysProp.isBlank()) {
            var propMode = TransportMode.parse(sysProp);
            if (propMode != null) {
                LOGGER.info("Transport mode from system property {}: {}", PROP_MODE, propMode);
                loaded = new ClientSettings(propMode, loaded.kcpProfile());
            }
        }
        return new ClientSettingsService(store, loaded);
    }

    public ClientSettings current() {
        return current;
    }

    public void updateMode(TransportMode mode) {
        current = new ClientSettings(mode, current.kcpProfile());
        LOGGER.info("Transport mode updated to {}", mode);
        store.save(current);
    }

    public void updateKcpProfile(KcpProfile profile) {
        current = new ClientSettings(current.mode(), profile);
        LOGGER.info("KCP profile updated to {}", profile);
        store.save(current);
    }

}
