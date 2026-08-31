package top.tangge233.netbridge.server;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.ability.TransportProtocol;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

public final class NativeAcceptor {

    private static final ExecutorService ADOPT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "net-bridge-adopt");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile long quicHandle = -1;
    private static volatile long kcpHandle = -1;
    private static volatile NetworksAbility announcement = NetworksAbility.empty();
    private static volatile @Nullable LongConsumer connectionHandler;

    private NativeAcceptor() {
    }

    public static void setConnectionHandler(@Nullable LongConsumer handler) {
        connectionHandler = handler;
    }

    public static synchronized boolean start(int mcPort, @Nullable String mcBindIp) {
        if (quicHandle != -1 || kcpHandle != -1) {
            return true;
        }

        if (!NativeLoader.load()) {
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

        var qh = startTransport(NativeBridge.KIND_QUIC, "quic", resolved.quic());
        quicHandle = qh;
        collectAnnouncement(entries, "quic", resolved.quic(), qh);

        var kh = startTransport(NativeBridge.KIND_KCP, "kcp", resolved.kcp());
        kcpHandle = kh;
        collectAnnouncement(entries, "kcp", resolved.kcp(), kh);

        announcement = NetworksAbility.of(entries.values().toArray(new NetworksEntry[0]));
        var any = qh != -1 || kh != -1;
        if (!any) {
            NetBridge.LOGGER.warn("No accelerated transport started; only TCP will be served");
        }
        if (any) {
            startAcceptLoop();
        }
        return any;
    }

    private static long startTransport(
            int kind,
            String name,
            ServerSettingsResolver.ResolvedTransport transport
    ) {
        if (!transport.enabled()) {
            return -1;
        }

        var profile = transport.kcpProfile() != null
                ? transport.kcpProfile().configValue()
                : null;
        var handle = NativeBridge.startServer(
                kind,
                transport.listenPort(),
                transport.maxConnections(),
                transport.bindHost(),
                profile
        );
        if (handle < 0) {
            NetBridge.LOGGER.error(
                    "{} transport failed to bind udp/{}: transport disabled (see net-bridge-native log)",
                    name,
                    transport.listenPort()
            );
            return -1;
        }

        var actual = NativeBridge.serverPort(handle);
        NetBridge.LOGGER.info("{} acceptor listening on udp/{}", name, actual);
        return handle;
    }

    private static void collectAnnouncement(
            Map<String, NetworksEntry> entries,
            String name,
            ServerSettingsResolver.ResolvedTransport transport,
            long handle
    ) {
        if (handle < 0) {
            return;
        }

        var actual = NativeBridge.serverPort(handle);
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

    private static void startAcceptLoop() {
        var qh = quicHandle;
        var kh = kcpHandle;
        var thread = new Thread(() -> acceptLoop(qh, kh), "net-bridge-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private static void acceptLoop(long qh, long kh) {
        while ((qh != -1 && quicHandle == qh) || (kh != -1 && kcpHandle == kh)) {
            try {
                drain(qh, quicHandle);
                drain(kh, kcpHandle);
            } catch (Throwable t) {
                NetBridge.LOGGER.warn("Accept drain failed", t);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void drain(long expectedHandle, long currentHandle) {
        if (expectedHandle < 0 || currentHandle != expectedHandle) {
            return;
        }

        try {
            var ids = NativeBridge.acceptConnections(expectedHandle);
            if (ids == null) {
                return;
            }

            var handler = connectionHandler;
            for (var id : ids) {
                if (handler != null) {
                    ADOPT_EXECUTOR.execute(() -> {
                        try {
                            handler.accept(id);
                        } catch (Throwable t) {
                            NetBridge.LOGGER.warn(
                                    "Connection handler failed for conn {}",
                                    id,
                                    t
                            );
                            NativeBridge.closeConnection(id);
                        }
                    });
                } else {
                    NetBridge.LOGGER.warn(
                            "Connection {} rejected: no connection handler registered",
                            id
                    );
                    NativeBridge.closeConnection(id);
                }
            }
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("Accept drain failed for server {}", expectedHandle, t);
        }
    }

    public static NetworksAbility announcement() {
        return announcement;
    }

    public static synchronized void stop() {
        if (quicHandle != -1) {
            NativeBridge.stopServer(quicHandle);
            quicHandle = -1;
        }
        if (kcpHandle != -1) {
            NativeBridge.stopServer(kcpHandle);
            kcpHandle = -1;
        }
        announcement = NetworksAbility.empty();
        NetBridge.LOGGER.info("Acceptors stopped");
    }

    public static boolean isRunning() {
        return quicHandle != -1 || kcpHandle != -1;
    }

}
