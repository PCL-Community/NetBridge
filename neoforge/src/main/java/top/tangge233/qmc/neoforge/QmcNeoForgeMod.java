package top.tangge233.qmc.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.neoforge.mc.QuicServerTransport;
import top.tangge233.qmc.server.QuicServer;

@Mod(QmcNeoForgeMod.MOD_ID)
public class QmcNeoForgeMod {
    public static final String MOD_ID = "qmc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public QmcNeoForgeMod(IEventBus modBus) {
        NativeLoader.load();
        QuicClient.useConfigFile(
                FMLPaths.CONFIGDIR.get().resolve("quic-mc/client.properties"));
        LOGGER.info("quic-mc NeoForge loaded: native bridge {}, feature {}",
                QuicNative.version(), QuicNative.rawFeature());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            var server = e.getServer();
            QuicServer.setConnectionHandler(connId -> QuicServerTransport.adopt(server, connId));
            QuicServer.start(server.getPort());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            QuicServer.setConnectionHandler(null);
            QuicServer.stop();
        });
    }
}
