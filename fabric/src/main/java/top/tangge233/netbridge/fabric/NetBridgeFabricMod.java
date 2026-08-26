package top.tangge233.netbridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.transport.ClientConfig;

/**
 * Fabric 入口：加载原生库（含 ABI 校验）、注册客户端 TOML 配置文件。
 * 服务端 acceptor 的启停由 {@code MinecraftServerLifecycleMixin} 驱动。
 */
public class NetBridgeFabricMod implements ModInitializer {
    public static final String MOD_ID = "net_bridge";

    @Override
    public void onInitialize() {
        NativeLoader.load();
        ClientConfig.useConfigFile(
                FabricLoader.getInstance().getConfigDir().resolve("net-bridge/client.toml"));
        NetBridge.LOGGER.info(
                "net-bridge Fabric loaded: native ABI {}", NativeBridge.version());
    }
}
