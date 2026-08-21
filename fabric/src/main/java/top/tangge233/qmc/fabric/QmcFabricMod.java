package top.tangge233.qmc.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

public class QmcFabricMod implements ModInitializer {
    public static final String MOD_ID = "qmc";
    public static final Logger LOGGER = Logger.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NativeLoader.load();
        LOGGER.info("quic-mc Fabric loaded: native bridge " + QuicNative.version()
                + ", feature " + QuicNative.rawFeature());
    }
}
