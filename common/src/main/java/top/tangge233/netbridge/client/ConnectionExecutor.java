package top.tangge233.netbridge.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.channel.NativeChannel;
import top.tangge233.netbridge.nativebridge.NativeConnectRequest;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.NativeTransportKind;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

public final class ConnectionExecutor {

    private final SuccessfulEndpointCache successCache;
    private final ConnectionStateStore stateStore;
    private final NativeRetryPolicy retryPolicy;

    public ConnectionExecutor(
            SuccessfulEndpointCache successCache,
            ConnectionStateStore stateStore,
            NativeRetryPolicy retryPolicy
    ) {
        this.successCache = successCache;
        this.stateStore = stateStore;
        this.retryPolicy = retryPolicy;
    }

    public ChannelFuture execute(
            ConnectionPlan plan,
            @Nullable NativeTransportBackend backend,
            ConnectionExecutorAdapter adapter
    ) {
        var attemptOpt = plan.nativeAttempt();
        if (backend == null || attemptOpt.isEmpty()) {
            return fallbackToTcp(plan, adapter);
        }
        var attempt = attemptOpt.get();

        Throwable last = null;
        for (var current = 1; current <= retryPolicy.maxAttempts(); current++) {
            stateStore.connecting(attempt.mode());
            ChannelFuture future;
            try {
                future = tryNativeAttempt(backend, attempt, adapter, current);
            } catch (Throwable t) {
                last = t;
                NetBridge.LOGGER.warn(
                        "Handshake to {} via {} failed (attempt {}/{}): {}",
                        plan.tcpAddress(),
                        attempt.mode(),
                        current,
                        retryPolicy.maxAttempts(),
                        t.getMessage()
                );
                continue;
            }
            try {
                future.syncUninterruptibly();
                stateStore.connected(attempt.mode(), transportLine(attempt));
                successCache.record(
                        plan.tcpAddress(), new TransportTarget(
                                attempt.mode(),
                                attempt.endpoint()
                        )
                );
                return future;
            } catch (Throwable t) {
                last = t;
                closeQuietly(future.channel());
                NetBridge.LOGGER.warn(
                        "Handshake to {} via {} failed (attempt {}/{}): {}",
                        plan.tcpAddress(),
                        attempt.mode(),
                        current,
                        retryPolicy.maxAttempts(),
                        t.getMessage()
                );
            }
        }

        NetBridge.LOGGER.warn(
                "Transport {} to {} failed ({}), falling back to TCP",
                attempt.mode(),
                plan.tcpAddress(),
                last != null
                        ? last.getMessage()
                        : "unknown error"
        );
        return fallbackToTcp(plan, adapter);
    }

    private ChannelFuture fallbackToTcp(
            ConnectionPlan plan,
            ConnectionExecutorAdapter adapter
    ) {
        stateStore.fallingBack();
        ChannelFuture tcp;
        try {
            tcp = adapter.openTcp(plan.tcpAddress());
        } finally {
            stateStore.idle();
        }
        return tcp;
    }

    private ChannelFuture tryNativeAttempt(
            NativeTransportBackend backend,
            ConnectionPlan.NativeAttemptPlan attempt,
            ConnectionExecutorAdapter adapter,
            int attemptNumber
    ) {
        var connection = backend.connect(buildRequest(attempt));
        var channel = new NativeChannel(connection);
        var bootstrap = new Bootstrap()
                .group(adapter.eventLoopGroup())
                .channelFactory(() -> channel)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        adapter.initNativeChannel(ch);
                    }
                });
        var future = bootstrap.connect(attempt.endpoint());
        var watchdog = future.channel().eventLoop().schedule(
                () -> {
                    if (!future.isDone()) {
                        channel.abortConnect(new ConnectException(
                                "handshake timeout after %d ms".formatted(
                                        retryPolicy.timeoutMillisForAttempt(attemptNumber)
                                )
                        ));
                    }
                },
                retryPolicy.timeoutMillisForAttempt(attemptNumber),
                TimeUnit.MILLISECONDS
        );
        future.addListener(ignored -> watchdog.cancel(false));
        return future;
    }

    private static String transportLine(ConnectionPlan.NativeAttemptPlan attempt) {
        return "%s %s:%d".formatted(
                attempt.mode().name(),
                attempt.endpoint().getHostString(),
                attempt.endpoint().getPort()
        );
    }

    private static void closeQuietly(Channel channel) {
        try {
            channel.close().syncUninterruptibly();
        } catch (Throwable e) {
            NetBridge.LOGGER.debug("Failed to close channel quietly", e);
        }
    }

    private static NativeConnectRequest buildRequest(ConnectionPlan.NativeAttemptPlan attempt) {
        var kind = switch (attempt.mode()) {
            case QUIC -> NativeTransportKind.QUIC;
            case KCP -> NativeTransportKind.KCP;
            case TCP -> throw new IllegalStateException("tcp mode has no native attempt");
        };
        var profile = attempt.kcpProfile() == KcpProfile.AGGRESSIVE
                ? NativeConnectRequest.KcpProfileValue.AGGRESSIVE
                : NativeConnectRequest.KcpProfileValue.BALANCED;
        return new NativeConnectRequest(
                kind,
                attempt.endpoint().getHostString(),
                attempt.endpoint().getPort(),
                profile
        );
    }

}
