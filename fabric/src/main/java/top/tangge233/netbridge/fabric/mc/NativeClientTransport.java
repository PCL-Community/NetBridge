package top.tangge233.netbridge.fabric.mc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.runtime.NetBridgeServices;
import top.tangge233.netbridge.transport.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeClientTransport {

    private NativeClientTransport() {
    }

    public static ChannelFuture connectWithFallback(
            InetSocketAddress tcpAddress,
            boolean useEpoll,
            Connection connection,
            TransportTarget target
    ) {
        var pipelineAttached = new AtomicBoolean(false);
        ConnectionDisplay.clear();
        Throwable last = null;

        for (var attempt = 1; attempt <= 2; attempt++) {
            ConnectStatus.connecting(target.mode());
            var future = tryConnect(
                    tcpAddress,
                    useEpoll,
                    connection,
                    target.endpoint(),
                    pipelineAttached
            );
            HandshakeWatchdog.arm(future, attempt);
            try {
                future.syncUninterruptibly();
                ConnectStatus.clear();
                FallbackTracker.record(tcpAddress, target);
                ConnectionDisplay.set(
                        target.mode().name(),
                        target.endpoint().getHostString() + ":" + target.endpoint().getPort()
                );
                return future;
            } catch (Throwable t) {
                last = t;
                closeQuietly(future.channel());
                NetBridge.LOGGER.warn(
                        "Handshake to {} via {} failed (attempt {}/2): {}",
                        tcpAddress, target.mode(), attempt, t.getMessage()
                );
            }
        }

        NetBridge.LOGGER.warn(
                "Transport {} to {} failed twice ({}), falling back to TCP",
                target.mode(),
                tcpAddress,
                last != null
                        ? last.getMessage()
                        : "unknown error"
        );
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
            AtomicBoolean pipelineAttached
    ) {
        var kcp = NetBridgeServices.clientSettings().current().mode() == TransportMode.KCP;
        EventLoopGroup group = useEpoll && Epoll.isAvailable()
                ? Connection.NETWORK_EPOLL_WORKER_GROUP.get()
                : Connection.NETWORK_WORKER_GROUP.get();
        var bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(() -> new NativeChannel(kcp))
                .handler(new ChannelInitializer<NativeChannel>() {
                    @Override
                    protected void initChannel(NativeChannel channel) {
                        channel.pipeline().addLast(
                                "timeout",
                                new ReadTimeoutHandler(30)
                        );
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                if (pipelineAttached.compareAndSet(false, true)) {
                                    var pipeline = ctx.pipeline();
                                    Connection.configureSerialization(
                                            pipeline,
                                            PacketFlow.CLIENTBOUND,
                                            false,
                                            null
                                    );
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
        try {
            channel.close().syncUninterruptibly();
        } catch (Throwable _) {
        }
    }

}
