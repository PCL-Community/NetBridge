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
import top.tangge233.netbridge.net.QuicChannel;
import top.tangge233.netbridge.server.QuicServer;

/**
 * 服务端 QUIC 连接收养：把 acceptor 收到的新 QUIC 连接接入 Minecraft
 * 服务端协议管线，复刻原版 {@code ServerConnectionListener} 的 initChannel
 * 流程（timeout → configureSerialization(SERVERBOUND) → Connection →
 * configurePacketHandler → handshake listener），并加入 connections 列表
 * 使其随服务器 tick（登录超时、play 阶段 tick 等依赖此列表）。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006），
 * 修改时必须同步两处。
 */
public final class QuicServerTransport {
    private QuicServerTransport() {}

    /** 收养一条服务端已握手的 QUIC 连接并接入 MC 协议处理。 */
    public static void adopt(MinecraftServer server, long connId) {
        QuicChannel channel = QuicChannel.adopt(connId);
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
        // channelActive，随后 QuicChannel 轮询器开始把 QUIC 字节推入管线。
        EventLoopGroup group = ServerConnectionListener.SERVER_EVENT_GROUP.get();
        group.register(channel).syncUninterruptibly();
        // 纳入 ServerConnectionListener 的 tick 列表（getConnections 返回内部可变列表）。
        server.getConnection().getConnections().add(connection);
        QuicServer.LOGGER.info(
                "QUIC connection " + connId + " adopted into server pipeline (channel " + channel.connId() + ")");
    }
}
