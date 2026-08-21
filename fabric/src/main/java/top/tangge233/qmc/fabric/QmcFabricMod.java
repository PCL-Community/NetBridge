package top.tangge233.qmc.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.QuicClient;

public class QmcFabricMod implements ModInitializer {
    public static final String MOD_ID = "qmc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NativeLoader.load();
        QuicClient.useConfigFile(
                FabricLoader.getInstance().getConfigDir().resolve("quic-mc/client.properties"));
        LOGGER.info("quic-mc Fabric loaded: native bridge {}, feature {}",
                QuicNative.version(), QuicNative.rawFeature());
    }
}
