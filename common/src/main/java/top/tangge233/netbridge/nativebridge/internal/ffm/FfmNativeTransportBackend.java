package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Java 25 FFM 的 NativeTransportBackend 实现。
 *
 * <p>拥有 FfmNativeLibrary（含 shared Arena / upcall stub）与 NativeContext。
 * 事件从 Rust Tokio worker 线程经 upcall 到达 {@link #onEvent(NativeEvent)}， 在此按 object id 路由到 typed
 * connection/server wrapper。
 */
public final class FfmNativeTransportBackend
        implements NativeTransportBackend, NativeEventListener {

    private final FfmNativeLibrary library;
    private final FfmNativeContext context;
    private final Map<Long, FfmNativeConnection> connections = new ConcurrentHashMap<>();
    private final Map<Long, FfmNativeServer> servers = new ConcurrentHashMap<>();

    private volatile NativeBackendState state = NativeBackendState.NEW;

    private FfmNativeTransportBackend(
            FfmNativeLibrary library,
            FfmNativeContext context
    ) {
        this.library = library;
        this.context = context;
    }

    public static FfmNativeTransportBackend load(Path libraryPath, int workerThreads) {
        var library = FfmNativeLibrary.load(libraryPath);
        FfmNativeContext context;
        try {
            context = library.createContext(workerThreads);
        } catch (Throwable t) {
            library.close();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to create native context", t);
        }
        var backend = new FfmNativeTransportBackend(library, context);
        backend.state = NativeBackendState.AVAILABLE;
        library.dispatcher().addListener(backend);
        return backend;
    }

    public FfmNativeContext context() {
        return context;
    }

    @Override
    public NativeBackendAvailability availability() {
        return switch (state) {
            case AVAILABLE -> new NativeBackendAvailability(
                    NativeBackendState.AVAILABLE,
                    null
            );
            case CLOSED -> new NativeBackendAvailability(
                    NativeBackendState.CLOSED,
                    "backend closed"
            );
            case CLOSING -> new NativeBackendAvailability(
                    NativeBackendState.CLOSING,
                    "backend closing"
            );
            default -> new NativeBackendAvailability(
                    NativeBackendState.UNAVAILABLE,
                    "backend not available"
            );
        };
    }

    @Override
    public NativeConnection connect(NativeConnectRequest request) {
        ensureOpen();
        var kind = request.transport();
        var connId = context.connect(
                kind.abiValue(),
                request.host(),
                request.port(),
                request.kcpProfile().abiValue()
        );
        var conn = new FfmNativeConnection(
                this,
                connId,
                kind,
                NativeConnectionState.CONNECTING
        );
        connections.put(connId, conn);
        return conn;
    }

    @Override
    public NativeServer startServer(NativeServerRequest request) {
        ensureOpen();
        var kind = request.transport();
        var serverId = context.serverStart(
                kind.abiValue(),
                request.bindHost(),
                request.port(),
                request.maxConnections(),
                request.kcpProfile().abiValue()
        );
        var server = new FfmNativeServer(
                this,
                serverId,
                kind
        );
        servers.put(serverId, server);
        return server;
    }

    @Override
    public void close() {
        var prev = state;
        if (prev == NativeBackendState.CLOSED || prev == NativeBackendState.CLOSING) {
            return;
        }

        state = NativeBackendState.CLOSING;
        library.dispatcher().removeListener(this);

        try {
            for (var server : servers.values()) {
                try {
                    server.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Error closing native server {}: {}",
                            server.id(),
                            e.getMessage()
                    );
                }
            }

            servers.clear();

            for (var conn : connections.values()) {
                try {
                    conn.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Error closing native connection {}: {}",
                            conn.id(),
                            e.getMessage()
                    );
                }
            }

            connections.clear();
            context.shutdown(2000);
            context.destroy();
        } finally {
            library.close();
            state = NativeBackendState.CLOSED;
        }
    }

    private void ensureOpen() {
        var st = state;
        if (st != NativeBackendState.AVAILABLE) {
            throw new NativeException("Native backend is " + st);
        }
    }

    void unregisterConnection(long connId) {
        connections.remove(connId);
    }

    void unregisterServer(long serverId) {
        servers.remove(serverId);
    }

    /**
     * 事件路由入口（在 Rust Tokio worker 线程上被 upcall 调用，必须轻量且不阻塞）。
     */
    @Override
    public void onEvent(NativeEvent event) {
        if (state == NativeBackendState.CLOSED || state == NativeBackendState.CLOSING) {
            return;
        }

        try {
            switch (event.eventKind()) {
                case NativeEvent.KIND_CONNECTION_STATE -> {
                    var conn = connections.get(event.objectId());
                    if (conn != null) {
                        conn.handleStateChanged(NativeConnectionState.fromAbi((int) event.arg0()));
                    }
                }
                case NativeEvent.KIND_DATA_AVAILABLE -> {
                    var conn = connections.get(event.objectId());
                    if (conn != null) {
                        conn.handleDataAvailable();
                    }
                }
                case NativeEvent.KIND_WRITABLE -> {
                    var conn = connections.get(event.objectId());
                    if (conn != null) {
                        conn.handleWritable();
                    }
                }
                case NativeEvent.KIND_ACCEPTED -> {
                    var server = servers.get(event.objectId());
                    if (server != null) {
                        var accepted = new FfmNativeConnection(
                                this,
                                event.arg0(),
                                server.transport(),
                                NativeConnectionState.CONNECTED
                        );
                        connections.put(event.arg0(), accepted);
                        server.handleAccepted(accepted);
                    }
                }
                case NativeEvent.KIND_SERVER_STATE -> {
                    var server = servers.get(event.objectId());
                    if (server != null) {
                        server.handleStateChanged((int) event.arg0());
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            NetBridge.LOGGER.error(
                    "Error routing native event {}: {}",
                    event,
                    t.getMessage(),
                    t
            );
        }
    }

}
