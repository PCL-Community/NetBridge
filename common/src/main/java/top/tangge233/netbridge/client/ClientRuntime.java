package top.tangge233.netbridge.client;

import io.netty.channel.ChannelFuture;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.transport.TransportMode;

import java.net.InetSocketAddress;

import org.jspecify.annotations.Nullable;

public final class ClientRuntime {

    private final ClientSettingsService settings;
    private final @Nullable NativeTransportBackend backend;
    private final ServerCapabilityCache capabilities;
    private final SuccessfulEndpointCache successfulEndpoints;
    private final ConnectionStateStore stateStore;
    private final ConnectionPlanner planner;
    private final ConnectionExecutor executor;

    public ClientRuntime(
            ClientSettingsService settings,
            @Nullable NativeTransportBackend backend
    ) {
        this.settings = settings;
        this.backend = backend;
        this.capabilities = new ServerCapabilityCache();
        this.successfulEndpoints = new SuccessfulEndpointCache();
        this.stateStore = new ConnectionStateStore();
        this.planner = new ConnectionPlanner();
        this.executor = new ConnectionExecutor(
                successfulEndpoints,
                stateStore,
                NativeRetryPolicy.defaults()
        );
    }

    public ChannelFuture connect(
            InetSocketAddress tcpAddress,
            ConnectionExecutorAdapter adapter
    ) {
        var current = settings.current();
        var plan = planner.plan(
                tcpAddress,
                current,
                capabilities.get(tcpAddress),
                successfulEndpoints.lookup(tcpAddress, current.mode()),
                nativeAvailable()
        );
        return executor.execute(plan, backend, adapter);
    }

    public boolean nativeAvailable() {
        return backend != null && backend.availability().available();
    }

    public void recordServerCapabilities(
            InetSocketAddress address,
            NetworksAbility ability
    ) {
        capabilities.record(address, ability);
    }

    public boolean acceleratedRequested() {
        return settings.current().mode() != TransportMode.TCP;
    }

    public ConnectionSnapshot snapshot() {
        return stateStore.snapshot();
    }

}
