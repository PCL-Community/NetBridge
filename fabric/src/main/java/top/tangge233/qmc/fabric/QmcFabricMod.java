package top.tangge233.qmc.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.QuicClient;

/**
 * Fabric 入口：加载原生库、注册客户端 TOML 配置文件。
 * 服务端 QUIC acceptor 的启停由 {@code MinecraftServerLifecycleMixin} 驱动。
 */
public class QmcFabricMod implements ModInitializer {
    public static final String MOD_ID = "qmc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NativeLoader.load();
        QuicClient.useConfigFile(
                FabricLoader.getInstance().getConfigDir().resolve("quic-mc/client.toml"));
        LOGGER.info("quic-mc Fabric loaded: native bridge {}, feature {}",
                QuicNative.version(), QuicNative.rawFeature());
    }
}
