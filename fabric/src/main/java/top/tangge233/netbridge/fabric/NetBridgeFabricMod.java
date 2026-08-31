package top.tangge233.netbridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.runtime.NetBridgeServices;

/**
 * Fabric 入口：初始化配置并尝试加载原生库（含 ABI 校验）。 服务端 acceptor 的启停由 {@code MinecraftServerLifecycleMixin} 驱动。
 */
public class NetBridgeFabricMod implements ModInitializer {

    public static final String MOD_ID = "net_bridge";

    @Override
    public void onInitialize() {
        var paths = new ConfigPaths(FabricLoader.getInstance()
                .getConfigDir()
                .resolve("net-bridge"));
        NetBridgeServices.bootstrap(paths);

        if (!NativeLoader.load()) {
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled (TCP fallback)"
            );
        } else {
            NetBridge.LOGGER.info(
                    "net-bridge Fabric loaded: native ABI {}",
                    NativeBridge.version()
            );
        }
    }

}
