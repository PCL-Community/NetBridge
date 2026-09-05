package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.client.ClientRuntime;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.UnavailableNativeTransportBackend;
import top.tangge233.netbridge.server.ServerRuntime;

public final class NetBridgeRuntime implements AutoCloseable {

    private final ConfigPaths configPaths;
    private final ClientSettingsService clientSettings;
    private final ServerConfigStore serverConfigStore;
    private final NativeTransportBackend nativeBackend;
    private final ClientRuntime clientRuntime;
    private final ServerRuntime serverRuntime;
    private volatile boolean closed;

    public NetBridgeRuntime(
            ConfigPaths configPaths,
            ClientSettingsService clientSettings,
            ServerConfigStore serverConfigStore,
            NativeTransportBackend nativeBackend
    ) {
        this.configPaths = configPaths;
        this.clientSettings = clientSettings;
        this.serverConfigStore = serverConfigStore;
        this.nativeBackend = nativeBackend;
        this.clientRuntime = new ClientRuntime(clientSettings, nativeBackend);
        this.serverRuntime = new ServerRuntime(nativeBackend, serverConfigStore);
    }

    public ConfigPaths configPaths() {
        return configPaths;
    }

    public ClientSettingsService clientSettings() {
        return clientSettings;
    }

    public ServerConfigStore serverConfigStore() {
        return serverConfigStore;
    }

    public boolean nativeAvailable() {
        return !(nativeBackend instanceof UnavailableNativeTransportBackend)
                && nativeBackend.availability().available();
    }

    public ClientRuntime clientRuntime() {
        return clientRuntime;
    }

    public ServerRuntime serverRuntime() {
        return serverRuntime;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        serverRuntime.close();
        clientRuntime.close();
        try {
            nativeBackend.close();
        } catch (RuntimeException e) {
            NetBridge.LOGGER.warn("Error closing native backend: {}", e.getMessage());
        }
    }

}
