package top.tangge233.netbridge.neoforge.mc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.transport.ClientConfig;
import top.tangge233.netbridge.transport.ConnectionDisplay;
import top.tangge233.netbridge.transport.ConnectStatus;
import top.tangge233.netbridge.transport.FallbackTracker;
import top.tangge233.netbridge.transport.HandshakeWatchdog;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

/**
 * 客户端加速连接 glue：用 {@link NativeChannel} + 原版管线替换 TCP 连接，
 * 对 Connection 之上的逻辑透明。
 *
 * <p>尝试序列：目标传输至多 2 次（看门狗 10s/20s 竞速），两次均失败
 * 记入降级记忆并回退原版 TCP。管线挂载延迟到握手成功（channelActive）
 * 之后——失败路径上 Connection 未被消费，可安全复用。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
public final class NativeClientTransport {
    private NativeClientTransport() {}

    /**
     * 执行带降级的连接流程。已由调用方确认存在可用的加速目标；
     * TCP 直连不经过本类。
     */
    public static ChannelFuture connectWithFallback(
            InetSocketAddress tcpAddress, boolean useEpoll, Connection connection, TransportTarget target) {
        AtomicBoolean pipelineAttached = new AtomicBoolean(false);
        ConnectionDisplay.clear();
        Throwable last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            ConnectStatus.connecting(target.mode());
            ChannelFuture future =
                    tryConnect(tcpAddress, useEpoll, connection, target.endpoint(), pipelineAttached);
            HandshakeWatchdog.arm(future, attempt);
            try {
                future.syncUninterruptibly();
                ConnectStatus.clear();
                FallbackTracker.record(tcpAddress, target);
                ConnectionDisplay.set(
                        target.mode().name(), target.endpoint().getHostString() + ":" + target.endpoint().getPort());
                return future;
            } catch (Throwable t) {
                last = t;
                closeQuietly(future.channel());
                NetBridge.LOGGER.warn("Handshake to {} via {} failed (attempt {}/2): {}",
                        tcpAddress, target.mode(), attempt, t.getMessage());
            }
        }
        // 两次均失败：本次走原版 TCP。
        NetBridge.LOGGER.warn("Transport {} to {} failed twice ({}), falling back to TCP",
                target.mode(), tcpAddress, last == null ? "unknown" : last.getMessage());
        ConnectStatus.fallingBack();
        try {
            return Connection.connect(tcpAddress, useEpoll, connection);
        } finally {
            ConnectStatus.clear();
        }
    }

    private static ChannelFuture tryConnect(
            InetSocketAddress tcpAddress,
            boolean useEpoll,
            Connection connection,
            InetSocketAddress endpoint,
            AtomicBoolean pipelineAttached) {
        boolean kcp = ClientConfig.mode() == TransportMode.KCP;
        EventLoopGroup group = useEpoll && Epoll.isAvailable()
                ? Connection.NETWORK_EPOLL_WORKER_GROUP.get()
                : Connection.NETWORK_WORKER_GROUP.get();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(() -> new NativeChannel(kcp))
                .handler(new ChannelInitializer<NativeChannel>() {
                    @Override
                    protected void initChannel(NativeChannel channel) {
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
        return bootstrap.connect(endpoint);
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
