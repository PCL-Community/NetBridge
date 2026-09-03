package top.tangge233.netbridge.server;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.ability.TransportProtocol;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.runtime.NetBridgeNative;
import top.tangge233.netbridge.runtime.NetBridgeServices;
import top.tangge233.netbridge.transport.KcpProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * 服务端 acceptor：经 FFM backend 启动 QUIC/KCP，ACCEPTED 事件驱动收养。
 */
public final class NativeAcceptor {

    private static final ExecutorService ADOPT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "net-bridge-adopt");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile @Nullable NativeServer quicServer;
    private static volatile @Nullable NativeServer kcpServer;
    private static volatile NetworksAbility announcement = NetworksAbility.empty();
    private static volatile @Nullable Consumer<NativeConnection> connectionHandler;

    private NativeAcceptor() {
    }

    public static void setConnectionHandler(
            @Nullable Consumer<NativeConnection> handler
    ) {
        connectionHandler = handler;
    }

    public static synchronized boolean start(
            int mcPort,
            @Nullable String mcBindIp
    ) {
        if (quicServer != null || kcpServer != null) {
            return true;
        }

        var backend = NetBridgeNative.backend();
        if (backend == null) {
            NetBridge.LOGGER.warn(
                    "net-bridge native unavailable; accelerated transports disabled, only TCP will be served"
            );
            return false;
        }

        var configStore = NetBridgeServices.serverConfigStore();
        var settings = configStore.load();
        var resolved = ServerSettingsResolver.resolve(
                settings,
                mcPort,
                mcBindIp
        );

        Map<String, NetworksEntry> entries = new LinkedHashMap<>();

        var quic = startTransport(backend, "quic", resolved.quic());
        quicServer = quic;
        collectAnnouncement(entries, "quic", resolved.quic(), quic);

        var kcp = startTransport(backend, "kcp", resolved.kcp());
        kcpServer = kcp;
        collectAnnouncement(entries, "kcp", resolved.kcp(), kcp);

        announcement = NetworksAbility.of(entries.values().toArray(new NetworksEntry[0]));
        var any = quic != null || kcp != null;
        if (!any) {
            NetBridge.LOGGER.warn("No accelerated transport started; only TCP will be served");
        }
        return any;
    }

    private static @Nullable NativeServer startTransport(
            NativeTransportBackend backend,
            String name,
            ServerSettingsResolver.ResolvedTransport transport
    ) {
        if (!transport.enabled()) {
            return null;
        }

        NativeServerRequest request;
        if (name.equals("kcp")) {
            request = new NativeServerRequest(
                    NativeTransportKind.KCP,
                    transport.bindHost(),
                    transport.listenPort(),
                    transport.maxConnections(),
                    toKcpProfile(transport.kcpProfile())
            );
        } else {
            request = new NativeServerRequest(
                    NativeTransportKind.QUIC,
                    transport.bindHost(),
                    transport.listenPort(),
                    transport.maxConnections(),
                    NativeConnectRequest.KcpProfileValue.BALANCED
            );
        }

        final NativeServer server;
        try {
            server = backend.startServer(request);
        } catch (RuntimeException e) {
            NetBridge.LOGGER.error(
                    "{} transport failed to bind udp/{}: transport disabled",
                    name,
                    transport.listenPort()
            );
            return null;
        }

        server.setListener(new NativeServerListener() {
            @Override
            public void onAccepted(NativeConnection connection) {
                dispatch(server, connection);
            }
        });
        NetBridge.LOGGER.info(
                "{} acceptor listening on udp/{}",
                name,
                server.localPort()
        );
        return server;
    }

    private static void collectAnnouncement(
            Map<String, NetworksEntry> entries,
            String name,
            ServerSettingsResolver.ResolvedTransport transport,
            @Nullable NativeServer server
    ) {
        if (server == null) {
            return;
        }

        var actual = server.localPort();
        if (actual <= 0) {
            return;
        }

        var protocol = name.equals("kcp")
                ? TransportProtocol.KCP_V1
                : TransportProtocol.QUIC_V1;
        entries.put(
                name,
                new NetworksEntry(
                        true,
                        transport.advertisedHost(),
                        actual,
                        protocol
                )
        );
    }

    private static NativeConnectRequest.KcpProfileValue toKcpProfile(
            @Nullable KcpProfile profile
    ) {
        return profile == KcpProfile.AGGRESSIVE
                ? NativeConnectRequest.KcpProfileValue.AGGRESSIVE
                : NativeConnectRequest.KcpProfileValue.BALANCED;
    }

    private static void dispatch(
            NativeServer server,
            NativeConnection connection
    ) {
        var handler = connectionHandler;
        if (handler == null) {
            NetBridge.LOGGER.warn(
                    "Connection {} rejected: no connection handler registered",
                    connection.id()
            );
            try {
                connection.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn(
                        "Failed to close rejected connection {}",
                        connection.id(),
                        e
                );
            }
            return;
        }
        ADOPT_EXECUTOR.execute(() -> {
            try {
                handler.accept(connection);
            } catch (Throwable t) {
                NetBridge.LOGGER.warn(
                        "Connection handler failed for conn {}",
                        connection.id(),
                        t
                );
                try {
                    connection.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Failed to close failed connection {}",
                            connection.id(),
                            e
                    );
                }
            }
        });
    }

    public static NetworksAbility announcement() {
        return announcement;
    }

    public static synchronized void stop() {
        if (quicServer != null) {
            try {
                quicServer.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn("Error stopping quic acceptor: {}", e.getMessage());
            }
            quicServer = null;
        }
        if (kcpServer != null) {
            try {
                kcpServer.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn("Error stopping kcp acceptor: {}", e.getMessage());
            }
            kcpServer = null;
        }
        announcement = NetworksAbility.empty();
        NetBridge.LOGGER.info("Acceptors stopped");
    }

    public static boolean isRunning() {
        return quicServer != null || kcpServer != null;
    }

}
