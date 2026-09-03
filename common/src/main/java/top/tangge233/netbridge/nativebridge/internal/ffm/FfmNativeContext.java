package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

public final class FfmNativeContext implements AutoCloseable {

    private final FfmApiV1 api;
    private final MemorySegment contextPtr;
    private volatile boolean closed;

    public FfmNativeContext(
            FfmApiV1 api,
            MemorySegment contextPtr
    ) {
        this.api = api;
        this.contextPtr = contextPtr;
    }

    public MemorySegment rawPointer() {
        return contextPtr;
    }

    public long connect(
            int transportKind,
            String host,
            int port,
            int kcpProfile
    ) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var hostBytes = host.getBytes(StandardCharsets.UTF_8);
            var hostSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, hostBytes);

            var opts = arena.allocate(FfmApiLayouts.CONNECT_OPTIONS_V1);
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.CONNECT_OPTIONS_V1.byteSize()
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("transport_kind")
                    ),
                    transportKind
            );

            var hostOffset = FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                    MemoryLayout.PathElement.groupElement("host_utf8")
            );
            opts.set(
                    ValueLayout.ADDRESS,
                    hostOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("data")
                    ),
                    hostSegment
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    hostOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("length")
                    ),
                    hostBytes.length
            );

            opts.set(
                    ValueLayout.JAVA_SHORT,
                    FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(MemoryLayout.PathElement.groupElement(
                            "port")),
                    (short) port
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("kcp_profile")
                    ),
                    kcpProfile
            );

            var outConn = arena.allocate(ValueLayout.JAVA_LONG);
            var status = (int) api.connect().invokeExact(contextPtr, opts, outConn);
            FfmStatus.checkStatus(status, "connect");
            return outConn.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connect invocation failed", t);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NativeContext is closed");
        }
    }

    public int connectionState(long connectionId) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var outState = arena.allocate(ValueLayout.JAVA_INT);
            var status = (int) api.connectionState().invokeExact(
                    contextPtr,
                    connectionId,
                    outState
            );
            if (status == FfmStatus.NB_NOT_FOUND) {
                return -1;
            }
            FfmStatus.checkStatus(status, "connection_state");
            return outState.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connection_state invocation failed", t);
        }
    }

    public InetSocketAddress connectionRemoteAddress(long connectionId) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var outAddr = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
            var status = (int) api.connectionRemoteAddress().invokeExact(
                    contextPtr,
                    connectionId,
                    outAddr
            );
            FfmStatus.checkStatus(status, "connection_remote_address");

            var family = outAddr.get(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("family")
                    )
            );
            var port = Short.toUnsignedInt(outAddr.get(
                    ValueLayout.JAVA_SHORT,
                    FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("port")
                    )
            ));
            var addrOffset = FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                    MemoryLayout.PathElement.groupElement("address")
            );

            byte[] ipBytes;
            if (family == 4) {
                ipBytes = new byte[4];
                MemorySegment.copy(
                        outAddr,
                        addrOffset,
                        MemorySegment.ofArray(ipBytes),
                        0,
                        4
                );
            } else {
                ipBytes = new byte[16];
                MemorySegment.copy(
                        outAddr,
                        addrOffset,
                        MemorySegment.ofArray(ipBytes),
                        0,
                        16
                );
            }
            var inet = InetAddress.getByAddress(ipBytes);
            return new InetSocketAddress(inet, port);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connection_remote_address invocation failed", t);
        }
    }

    public FfmIoResult connectionWrite(
            long connectionId,
            MemorySegment data,
            int length
    ) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var outWritten = arena.allocate(ValueLayout.JAVA_INT);

            var status = (int) api.connectionWrite().invokeExact(
                    contextPtr,
                    connectionId,
                    data,
                    length,
                    outWritten
            );

            var written = outWritten.get(ValueLayout.JAVA_INT, 0);

            switch (status) {
                case FfmStatus.NB_CLOSED,
                     FfmStatus.NB_NOT_FOUND,
                     FfmStatus.NB_INVALID_ARGUMENT -> {
                    return new FfmIoResult(status, 0);
                }

                case FfmStatus.NB_OK,
                     FfmStatus.NB_WOULD_BLOCK -> {
                    return new FfmIoResult(status, written);
                }

                default -> throw new IllegalStateException(
                        "connection_write failed: " + FfmStatus.describe(status)
                );
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connection_write invocation failed", t);
        }
    }

    public FfmIoResult connectionRead(
            long connectionId,
            MemorySegment dst,
            int capacity
    ) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var outRead = arena.allocate(ValueLayout.JAVA_INT);

            var status = (int) api.connectionRead().invokeExact(
                    contextPtr,
                    connectionId,
                    dst,
                    capacity,
                    outRead
            );
            var read = outRead.get(ValueLayout.JAVA_INT, 0);

            switch (status) {
                case FfmStatus.NB_CLOSED,
                     FfmStatus.NB_NOT_FOUND,
                     FfmStatus.NB_INVALID_ARGUMENT -> {
                    return new FfmIoResult(status, 0);
                }

                case FfmStatus.NB_OK,
                     FfmStatus.NB_WOULD_BLOCK -> {
                    return new FfmIoResult(status, read);
                }

                default -> throw new IllegalStateException(
                        "connection_read failed: " + FfmStatus.describe(status)
                );
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connection_read invocation failed", t);
        }
    }

    public void connectionClose(long connectionId) {
        ensureOpen();
        try {
            var status = (int) api.connectionClose().invokeExact(
                    contextPtr,
                    connectionId
            );
            if (status != FfmStatus.NB_NOT_FOUND) {
                FfmStatus.checkStatus(status, "connection_close");
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("connection_close invocation failed", t);
        }
    }

    public long serverStart(
            int transportKind,
            @Nullable String bindHost,
            int port,
            int maxConnections,
            int kcpProfile
    ) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var opts = arena.allocate(FfmApiLayouts.SERVER_OPTIONS_V1);
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.SERVER_OPTIONS_V1.byteSize()
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("transport_kind")
                    ),
                    transportKind
            );

            var bindOffset = FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                    MemoryLayout.PathElement.groupElement("bind_host_utf8")
            );
            if (bindHost != null && !bindHost.isEmpty()) {
                var bytes = bindHost.getBytes(StandardCharsets.UTF_8);
                var seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                opts.set(
                        ValueLayout.ADDRESS,
                        bindOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("data")
                        ),
                        seg
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        bindOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("length")
                        ),
                        bytes.length
                );
            }

            opts.set(
                    ValueLayout.JAVA_SHORT,
                    FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("port")
                    ),
                    (short) port
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("max_connections")
                    ),
                    maxConnections
            );
            opts.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("kcp_profile")
                    ),
                    kcpProfile
            );

            var outServer = arena.allocate(ValueLayout.JAVA_LONG);
            var status = (int) api.serverStart().invokeExact(
                    contextPtr,
                    opts,
                    outServer
            );
            FfmStatus.checkStatus(status, "server_start");
            return outServer.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("server_start invocation failed", t);
        }
    }

    public int serverPort(long serverId) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var outPort = arena.allocate(ValueLayout.JAVA_SHORT);
            var status = (int) api.serverPort().invokeExact(
                    contextPtr,
                    serverId,
                    outPort
            );
            FfmStatus.checkStatus(status, "server_port");
            return Short.toUnsignedInt(outPort.get(ValueLayout.JAVA_SHORT, 0));
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("server_port invocation failed", t);
        }
    }

    public void serverStop(long serverId) {
        ensureOpen();
        try {
            var status = (int) api.serverStop().invokeExact(
                    contextPtr,
                    serverId
            );
            if (status != FfmStatus.NB_NOT_FOUND) {
                FfmStatus.checkStatus(status, "server_stop");
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("server_stop invocation failed", t);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            shutdown(2000);
            destroy();
        }
    }

    public void shutdown(int timeoutMillis) {
        if (closed) {
            return;
        }

        try {
            var status = (int) api.contextShutdown().invokeExact(contextPtr, timeoutMillis);
            FfmStatus.checkStatus(status, "context_shutdown");
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("context_shutdown invocation failed", t);
        }
    }

    public void destroy() {
        if (closed) {
            return;
        }

        closed = true;
        try {
            var status = (int) api.contextDestroy().invokeExact(contextPtr);
            FfmStatus.checkStatus(status, "context_destroy");
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("context_destroy invocation failed", t);
        }
    }

}
