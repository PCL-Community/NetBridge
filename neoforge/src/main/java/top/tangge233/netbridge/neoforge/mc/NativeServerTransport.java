package top.tangge233.netbridge.neoforge.mc;

import io.netty.channel.ChannelPipeline;
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

/**
 * 服务端连接收养：把 acceptor（QUIC/KCP）收到的新连接接入 Minecraft
 * 服务端协议管线，复刻原版 {@code ServerConnectionListener} 的 initChannel
 * 流程（timeout → configureSerialization(SERVERBOUND) → Connection →
 * configurePacketHandler → handshake listener），并加入 connections 列表
 * 使其随服务器 tick。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
public final class NativeServerTransport {
    private NativeServerTransport() {}

    /** 收养一条服务端已建立好的连接并接入 MC 协议处理。 */
    public static void adopt(MinecraftServer server, long connId) {
        NativeChannel channel = NativeChannel.adopt(connId);
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("timeout", new ReadTimeoutHandler(30));
        Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
        int rateLimit = server.getRateLimitPacketsPerSecond();
        Connection connection = rateLimit > 0
                ? new RateKickingConnection(rateLimit)
                : new Connection(PacketFlow.SERVERBOUND);
        connection.configurePacketHandler(pipeline);
        connection.setListenerForServerboundHandshake(
                new ServerHandshakePacketListenerImpl(server, connection));
        // 注册到原版服务端事件循环组；channel 已 active，注册完成即触发
        // channelActive，随后轮询器开始把 native 字节推入管线。
        EventLoopGroup group = ServerConnectionListener.SERVER_EVENT_GROUP.get();
        group.register(channel).syncUninterruptibly();
        // 纳入 ServerConnectionListener 的 tick 列表。
        server.getConnection().getConnections().add(connection);
        NetBridge.LOGGER.info(
                "Connection " + connId + " adopted into server pipeline (channel " + channel.connId() + ")");
    }
}
