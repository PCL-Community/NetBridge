package top.tangge233.netbridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.jni.QuicNative;
import top.tangge233.netbridge.net.QuicClient;
import top.tangge233.netbridge.neoforge.mc.QuicServerTransport;
import top.tangge233.netbridge.server.QuicServer;

/**
 * NeoForge 入口：加载原生库、注册客户端 TOML 配置文件，并在
 * ServerStarted/ServerStopping 事件上启停 QUIC acceptor。
 */
@Mod(NetBridgeNeoForgeMod.MOD_ID)
public class NetBridgeNeoForgeMod {
    public static final String MOD_ID = "net-bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public NetBridgeNeoForgeMod(IEventBus modBus) {
        NativeLoader.load();
        QuicClient.useConfigFile(
                FMLPaths.CONFIGDIR.get().resolve("net-bridge/client.toml"));
        LOGGER.info("net-bridge NeoForge loaded: native bridge {}, feature {}",
                QuicNative.version(), QuicNative.rawFeature());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            var server = e.getServer();
            QuicServer.setConnectionHandler(connId -> QuicServerTransport.adopt(server, connId));
            QuicServer.start(server.getPort(), server.getLocalIp());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            QuicServer.setConnectionHandler(null);
            QuicServer.stop();
        });
    }
}
