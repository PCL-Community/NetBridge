package top.tangge233.qmc.fabric.mc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import top.tangge233.qmc.net.QuicChannel;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.QuicTarget;

/**
 * 客户端 QUIC 连接 glue：用 {@link QuicChannel} + 原版管线替换 TCP 连接
 * （ADR-0001 架构层 2，对 Connection 之上的逻辑透明）。
 *
 * fallback 语义（ADR-0002）：QUIC 握手失败且允许回退时，本次连接改走 TCP；
 * Connection 未被污染（管线挂载延迟到握手成功后），可安全复用。
 * 失败不拉黑地址——下次连接会重新尝试 QUIC。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006），
 * 修改时必须同步两处。
 */
public final class QuicClientTransport {
    private QuicClientTransport() {}

    /**
     * 尝试 QUIC 连接；握手失败且允许 fallback 时自动回退到原版 TCP。
     * 已由调用方确认 QUIC 可用（{@link QuicClient#quicTargetFor}）。
     */
    public static ChannelFuture connectWithFallback(
            InetSocketAddress tcpAddress, boolean useEpoll, Connection connection, QuicTarget target) {
        AtomicBoolean pipelineAttached = new AtomicBoolean(false);
        ChannelFuture quicFuture = tryConnect(tcpAddress, useEpoll, connection, target.quicPort(), pipelineAttached);
        try {
            quicFuture.syncUninterruptibly();
            return quicFuture;
        } catch (Throwable t) {
            closeQuietly(quicFuture.channel());
            if (target.allowTcpFallback() && !pipelineAttached.get()) {
                // Connection 尚未挂载到任何管线，可安全走原版 TCP。
                QuicClient.LOGGER.warn("QUIC handshake to {} failed ({}), falling back to TCP for this connection",
                        tcpAddress, t.getMessage());
                return Connection.connect(tcpAddress, useEpoll, connection);
            }
            if (pipelineAttached.get()) {
                QuicClient.LOGGER.warn("QUIC connection to {} failed after pipeline attach; cannot fall back", tcpAddress);
            }
            return quicFuture;
        }
    }

    private static ChannelFuture tryConnect(
            InetSocketAddress tcpAddress,
            boolean useEpoll,
            Connection connection,
            int quicPort,
            AtomicBoolean pipelineAttached) {
        EventLoopGroup group =
                useEpoll && Epoll.isAvailable()
                        ? Connection.NETWORK_EPOLL_WORKER_GROUP.get()
                        : Connection.NETWORK_WORKER_GROUP.get();
        InetSocketAddress quicAddress = new InetSocketAddress(tcpAddress.getAddress(), quicPort);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(QuicChannel::new)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel channel) {
                        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
                        // 关键：管线挂载延迟到握手成功（channelActive）之后，
                        // 保证失败路径上 Connection 未被消费，可安全回退 TCP。
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                if (pipelineAttached.compareAndSet(false, true)) {
                                    ChannelPipeline pipeline = ctx.pipeline();
                                    Connection.configureSerialization(
                                            pipeline, PacketFlow.CLIENTBOUND, false, null);
                                    connection.configurePacketHandler(pipeline);
                                }
                                ctx.fireChannelActive();
                            }
                        });
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
