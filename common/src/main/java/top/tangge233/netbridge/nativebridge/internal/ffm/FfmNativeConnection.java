package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

public final class FfmNativeConnection implements NativeConnection {

    private final FfmNativeTransportBackend owner;
    private final long id;
    private final @Nullable FfmNativeServer ownerServer;
    private final NativeTransportKind transport;

    private volatile NativeConnectionState state;
    private volatile @Nullable NativeConnectionListener listener;
    private volatile boolean closed;

    FfmNativeConnection(
            FfmNativeTransportBackend owner,
            long id,
            @Nullable FfmNativeServer ownerServer,
            NativeTransportKind transport,
            NativeConnectionState initialState
    ) {
        this.owner = owner;
        this.id = id;
        this.ownerServer = ownerServer;
        this.transport = transport;
        this.state = initialState;
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
    public NativeConnectionState state() {
        var st = state;
        if (closed) {
            return NativeConnectionState.CLOSED;
        }

        if (st == NativeConnectionState.CONNECTING) {
            var abi = owner.context().connectionState(id);
            if (abi == -1) {
                markClosedByQuery();
                return NativeConnectionState.CLOSED;
            }

            var mapped = NativeConnectionState.fromAbi(abi);
            state = mapped;
            return mapped;
        }

        return st;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        ensureOpen();
        try {
            return owner.context().connectionRemoteAddress(id);
        } catch (RuntimeException e) {
            throw new NativeException("remote address unavailable for connection " + id, e);
        }
    }

    @Override
    public NativeIoResult write(ByteBuffer source) {
        ensureOpen();
        var remaining = source.remaining();

        if (remaining == 0) {
            return NativeIoResult.progressed(0);
        }

        if (source.isDirect()) {
            var segment = MemorySegment.ofBuffer(source.slice());
            var result = owner.context().connectionWrite(id, segment, remaining);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            source.position(source.position() + n);
            return NativeIoResult.progressed(n);
        }

        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(remaining, 1);
            segment.copyFrom(MemorySegment.ofBuffer(source.slice()));
            var result = owner.context().connectionWrite(id, segment, remaining);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            source.position(source.position() + n);
            return NativeIoResult.progressed(n);
        }
    }

    @Override
    public NativeIoResult read(ByteBuffer target) {
        ensureOpen();
        var capacity = target.remaining();
        if (capacity == 0) {
            return NativeIoResult.progressed(0);
        }

        if (target.isDirect()) {
            var segment = MemorySegment.ofBuffer(target.slice());
            var result = owner.context().connectionRead(id, segment, capacity);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            if (n == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }

            target.position(target.position() + n);
            return NativeIoResult.progressed(n);
        }

        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(capacity, 1);
            var result = owner.context().connectionRead(id, segment, capacity);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            if (n == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            target.put(bytes, 0, n);
            return NativeIoResult.progressed(n);
        }
    }

    @Override
    public void setListener(NativeConnectionListener listener) {
        this.listener = listener;
        var st = state();
        if (st != NativeConnectionState.CONNECTING) {
            listener.onStateChanged(st);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        listener = null;

        try {
            owner.context().connectionClose(id);
        } catch (RuntimeException e) {
            throw new NativeException("failed to close native connection " + id, e);
        } finally {
            owner.unregisterConnection(id);
            releaseServerOwnership();
        }
    }

    private void releaseServerOwnership() {
        if (ownerServer != null) {
            ownerServer.removeChild(this);
        }
    }

    private void markClosedByQuery() {
        var prev = state;
        state = NativeConnectionState.CLOSED;
        owner.unregisterConnection(id);
        var l = listener;
        if (l != null && prev != NativeConnectionState.CLOSED) {
            l.onStateChanged(NativeConnectionState.CLOSED);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new NativeException("NativeConnection " + id + " is closed");
        }
    }

    void handleStateChanged(NativeConnectionState newState) {
        var prev = state;
        if (prev == newState) {
            return;
        }

        state = newState;
        var l = listener;
        if (l != null) {
            l.onStateChanged(newState);
        }

        if (newState == NativeConnectionState.CLOSED || newState == NativeConnectionState.FAILED) {
            owner.unregisterConnection(id);
        }
    }

    void handleDataAvailable() {
        var l = listener;
        if (l != null) {
            l.onDataAvailable();
        }
    }

    void handleWritable() {
        var l = listener;
        if (l != null) {
            l.onWritable();
        }
    }

}
