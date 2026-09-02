package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * 已绑定的 NbApiV1 函数表下调用句柄集合。
 */
public record FfmApiV1(
        MethodHandle contextCreate,
        MethodHandle contextShutdown,
        MethodHandle contextDestroy,
        MethodHandle connect,
        MethodHandle connectionState,
        MethodHandle connectionRemoteAddress,
        MethodHandle connectionWrite,
        MethodHandle connectionRead,
        MethodHandle connectionClose,
        MethodHandle serverStart,
        MethodHandle serverPort,
        MethodHandle serverStop,
        int abiMajor,
        int abiMinor,
        int structSize,
        long featureBits
) {

    private FfmApiV1(
            int abiMajor,
            int abiMinor,
            int structSize,
            long featureBits,
            MethodHandle contextCreate,
            MethodHandle contextShutdown,
            MethodHandle contextDestroy,
            MethodHandle connect,
            MethodHandle connectionState,
            MethodHandle connectionRemoteAddress,
            MethodHandle connectionWrite,
            MethodHandle connectionRead,
            MethodHandle connectionClose,
            MethodHandle serverStart,
            MethodHandle serverPort,
            MethodHandle serverStop
    ) {
        this(
                contextCreate,
                contextShutdown,
                contextDestroy,
                connect,
                connectionState,
                connectionRemoteAddress,
                connectionWrite,
                connectionRead,
                connectionClose,
                serverStart,
                serverPort,
                serverStop,
                abiMajor,
                abiMinor,
                structSize,
                featureBits
        );
    }

    public static FfmApiV1 fromSegment(MemorySegment tableSegment) {
        var linker = Linker.nativeLinker();

        var major = tableSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement("abi_major"))
        );
        var minor = tableSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement("abi_minor"))
        );
        var size = tableSegment.get(
                ValueLayout.JAVA_INT,
                FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement("struct_size"))
        );
        var features = tableSegment.get(
                ValueLayout.JAVA_LONG,
                FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement("feature_bits"))
        );

        var contextCreatePtr = getFnPtr(tableSegment, "context_create");
        var contextShutdownPtr = getFnPtr(tableSegment, "context_shutdown");
        var contextDestroyPtr = getFnPtr(tableSegment, "context_destroy");

        var connectPtr = getFnPtr(tableSegment, "connect");
        var connectionStatePtr = getFnPtr(tableSegment, "connection_state");
        var connectionRemoteAddressPtr = getFnPtr(tableSegment, "connection_remote_address");
        var connectionWritePtr = getFnPtr(tableSegment, "connection_write");
        var connectionReadPtr = getFnPtr(tableSegment, "connection_read");
        var connectionClosePtr = getFnPtr(tableSegment, "connection_close");

        var serverStartPtr = getFnPtr(tableSegment, "server_start");
        var serverPortPtr = getFnPtr(tableSegment, "server_port");
        var serverStopPtr = getFnPtr(tableSegment, "server_stop");

        return new FfmApiV1(
                major,
                minor,
                size,
                features,
                linker.downcallHandle(
                        contextCreatePtr,
                        FfmApiLayouts.CONTEXT_CREATE_DESC
                ),
                linker.downcallHandle(
                        contextShutdownPtr,
                        FfmApiLayouts.CONTEXT_SHUTDOWN_DESC
                ),
                linker.downcallHandle(
                        contextDestroyPtr,
                        FfmApiLayouts.CONTEXT_DESTROY_DESC
                ),
                linker.downcallHandle(
                        connectPtr,
                        FfmApiLayouts.CONNECT_DESC
                ),
                linker.downcallHandle(
                        connectionStatePtr,
                        FfmApiLayouts.CONNECTION_STATE_DESC
                ),
                linker.downcallHandle(
                        connectionRemoteAddressPtr,
                        FfmApiLayouts.CONNECTION_REMOTE_ADDRESS_DESC
                ),
                linker.downcallHandle(
                        connectionWritePtr,
                        FfmApiLayouts.CONNECTION_WRITE_DESC
                ),
                linker.downcallHandle(
                        connectionReadPtr,
                        FfmApiLayouts.CONNECTION_READ_DESC
                ),
                linker.downcallHandle(
                        connectionClosePtr,
                        FfmApiLayouts.CONNECTION_CLOSE_DESC
                ),
                linker.downcallHandle(
                        serverStartPtr,
                        FfmApiLayouts.SERVER_START_DESC
                ),
                linker.downcallHandle(
                        serverPortPtr,
                        FfmApiLayouts.SERVER_PORT_DESC
                ),
                linker.downcallHandle(
                        serverStopPtr,
                        FfmApiLayouts.SERVER_STOP_DESC
                )
        );
    }

    private static MemorySegment getFnPtr(
            MemorySegment segment,
            String name
    ) {
        var offset = FfmApiLayouts.API_V1.byteOffset(MemoryLayout.PathElement.groupElement(name));
        var addr = segment.get(ValueLayout.ADDRESS, offset);
        if (addr.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Function pointer in API table is null: " + name);
        }
        return addr;
    }

}
