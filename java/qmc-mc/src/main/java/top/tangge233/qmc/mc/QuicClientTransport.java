package top.tangge233.qmc.mc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import top.tangge233.qmc.net.QuicChannel;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.QuicTarget;

/**
 * 客户端 QUIC 连接 glue：用 {@link QuicChannel} + 原版管线替换 TCP 连接
 * （ADR-0001 架构层 2，对 Connection 之上的逻辑透明）。
 */
public final class QuicClientTransport {
    private QuicClientTransport() {}

    /**
     * 尝试 QUIC 连接；失败且允许 fallback 时自动回退到原版 TCP。
     * 已由调用方确认 QUIC 可用（{@link QuicClient#quicTargetFor}）。
     */
    public static ChannelFuture connectWithFallback(
            InetSocketAddress tcpAddress, boolean useEpoll, Connection connection, QuicTarget target) {
        ChannelFuture quicFuture = tryConnect(tcpAddress, useEpoll, connection, target.quicPort());
        try {
            quicFuture.syncUninterruptibly();
            return quicFuture;
        } catch (Throwable t) {
            if (target.allowTcpFallback()) {
                QuicClient.markQuicFailed(tcpAddress);
                closeQuietly(quicFuture.channel());
                return Connection.connect(tcpAddress, useEpoll, connection);
            }
            return quicFuture;
        }
    }

    private static ChannelFuture tryConnect(
            InetSocketAddress tcpAddress, boolean useEpoll, Connection connection, int quicPort) {
        EventLoopGroup group =
                useEpoll ? Connection.NETWORK_EPOLL_WORKER_GROUP.get() : Connection.NETWORK_WORKER_GROUP.get();
        InetSocketAddress quicAddress = new InetSocketAddress(tcpAddress.getAddress(), quicPort);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(QuicChannel::new)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
                        Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, null);
                        connection.configurePacketHandler(pipeline);
                    }
                });
        return bootstrap.connect(quicAddress);
    }

    private static void closeQuietly(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close().syncUninterruptibly();
        } catch (Throwable ignored) {
            // 关闭失败可忽略。
        }
    }
}
