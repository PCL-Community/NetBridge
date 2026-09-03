package top.tangge233.netbridge.neoforge.mc;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.nativebridge.NativeConnection;

import java.net.InetSocketAddress;

public final class NativeServerTransport {

    private NativeServerTransport() {
    }

    public static void adopt(
            MinecraftServer server,
            NativeConnection connection
    ) {
        var channel = new NativeChannel(connection);
        try {
            channel.setRemoteAddress(connection.remoteAddress());
        } catch (RuntimeException e) {
            channel.setRemoteAddress(new InetSocketAddress("0.0.0.0", 0));
        }

        var pipeline = channel.pipeline();
        pipeline.addLast("timeout", new ReadTimeoutHandler(30));

        Connection.configureSerialization(
                pipeline,
                PacketFlow.SERVERBOUND,
                false,
                null
        );

        var rateLimit = server.getRateLimitPacketsPerSecond();
        var mcConnection = rateLimit > 0
                ? new RateKickingConnection(rateLimit)
                : new Connection(PacketFlow.SERVERBOUND);
        mcConnection.configurePacketHandler(pipeline);
        mcConnection.setListenerForServerboundHandshake(
                new ServerHandshakePacketListenerImpl(server, mcConnection)
        );

        EventLoopGroup group = ServerConnectionListener.SERVER_EVENT_GROUP.get();
        group.register(channel).syncUninterruptibly();

        server.getConnection().getConnections().add(mcConnection);
        NetBridge.LOGGER.info(
                "Connection {} adopted into server pipeline (channel {})",
                connection.id(),
                channel.connId()
        );
    }

}
