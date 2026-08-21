package top.tangge233.qmc.neoforge;

import java.util.logging.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.neoforge.mc.QuicServerTransport;
import top.tangge233.qmc.server.QuicServer;

@Mod(QmcNeoForgeMod.MOD_ID)
public class QmcNeoForgeMod {
    public static final String MOD_ID = "qmc";
    public static final Logger LOGGER = Logger.getLogger(MOD_ID);

    public QmcNeoForgeMod(IEventBus modBus) {
        NativeLoader.load();
        LOGGER.info("quic-mc NeoForge loaded: native bridge " + QuicNative.version()
                + ", feature " + QuicNative.rawFeature());
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
