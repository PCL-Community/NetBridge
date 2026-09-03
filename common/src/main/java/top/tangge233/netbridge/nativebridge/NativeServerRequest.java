package top.tangge233.netbridge.nativebridge;

import org.jspecify.annotations.Nullable;

public record NativeServerRequest(
        NativeTransportKind transport,
        @Nullable String bindHost,
        int port,
        int maxConnections,
        NativeConnectRequest.KcpProfileValue kcpProfile
) {

    public static NativeServerRequest quic(int port, int maxConnections) {
        return new NativeServerRequest(
                NativeTransportKind.QUIC,
                null,
                port,
                maxConnections,
                NativeConnectRequest.KcpProfileValue.BALANCED
        );
    }

    public static NativeServerRequest kcp(
            int port,
            int maxConnections,
            NativeConnectRequest.KcpProfileValue profile
    ) {
        return new NativeServerRequest(
                NativeTransportKind.KCP,
                null,
                port,
                maxConnections,
                profile
        );
    }

}
