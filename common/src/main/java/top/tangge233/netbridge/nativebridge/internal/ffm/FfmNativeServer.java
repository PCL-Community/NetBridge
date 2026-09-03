package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class FfmNativeServer implements NativeServer {

    private final FfmNativeTransportBackend owner;
    private final long id;
    private final NativeTransportKind transport;
    private final Set<NativeConnection> accepted = ConcurrentHashMap.newKeySet();

    private volatile @Nullable NativeServerListener listener;
    private volatile boolean closed;

    FfmNativeServer(
            FfmNativeTransportBackend owner,
            long id,
            NativeTransportKind transport
    ) {
        this.owner = owner;
        this.id = id;
        this.transport = transport;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public NativeTransportKind transport() {
        return transport;
    }

    @Override
    public int localPort() {
        ensureOpen();
        return owner.context().serverPort(id);
    }

    @Override
    public void setListener(NativeServerListener listener) {
        this.listener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        listener = null;

        try {
            owner.context().serverStop(id);
        } catch (RuntimeException e) {
            throw new NativeException("failed to stop native server " + id, e);
        } finally {
            for (var conn : accepted) {
                try {
                    conn.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Error closing accepted connection {}: {}",
                            conn.id(),
                            e.getMessage()
                    );
                }
            }
            accepted.clear();
            owner.unregisterServer(id);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new NativeException("NativeServer " + id + " is closed");
        }
    }

    void handleAccepted(NativeConnection connection) {
        accepted.add(connection);
        var l = listener;
        if (l != null) {
            l.onAccepted(connection);
        }
    }

    void handleStateChanged() {
        var l = listener;
        if (l != null) {
            l.onStateChanged(NativeServerState.RUNNING);
        }
    }

}
