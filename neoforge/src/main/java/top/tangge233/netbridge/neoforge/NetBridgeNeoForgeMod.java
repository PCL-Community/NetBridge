package top.tangge233.netbridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.neoforge.mc.NativeServerTransport;
import top.tangge233.netbridge.server.NativeAcceptor;
import top.tangge233.netbridge.transport.ClientConfig;

/**
 * NeoForge 入口：加载原生库（含 ABI 校验）、注册客户端 TOML 配置文件，并在
 * ServerStarted/ServerStopping 事件上启停 QUIC/KCP acceptor。
 */
@Mod(NetBridgeNeoForgeMod.MOD_ID)
public class NetBridgeNeoForgeMod {
    public static final String MOD_ID = "net_bridge";

    public NetBridgeNeoForgeMod(IEventBus modBus) {
        if (!NativeLoader.load()) {
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled (TCP fallback)");
        } else {
            ClientConfig.useConfigFile(FMLPaths.CONFIGDIR.get().resolve("net-bridge/client.toml"));
            NetBridge.LOGGER.info("net-bridge NeoForge loaded: native ABI {}", NativeBridge.version());
        }
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            var server = e.getServer();
            NativeAcceptor.setConnectionHandler(connId -> NativeServerTransport.adopt(server, connId));
            NativeAcceptor.start(server.getPort(), server.getLocalIp());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            NativeAcceptor.setConnectionHandler(null);
            NativeAcceptor.stop();
        });
    }
}
