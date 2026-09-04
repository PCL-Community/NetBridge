package top.tangge233.netbridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.runtime.NetBridgeServices;

public class NetBridgeFabricMod implements ModInitializer {

    public static final String MOD_ID = "net_bridge";

    @Override
    public void onInitialize() {
        var paths = new ConfigPaths(
                FabricLoader.getInstance()
                        .getConfigDir()
                        .resolve("net-bridge")
        );
        NetBridgeServices.bootstrap(paths);

        if (!NetBridgeServices.nativeAvailable()) {
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled (TCP fallback)"
            );
        }
    }

}
