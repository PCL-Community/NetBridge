package top.tangge233.netbridge.nativebridge;

public record NativeConnectRequest(
        NativeTransportKind transport,
        String host,
        int port,
        KcpProfileValue kcpProfile
) {

    public static NativeConnectRequest quic(String host, int port) {
        return new NativeConnectRequest(
                NativeTransportKind.QUIC,
                host,
                port,
                KcpProfileValue.BALANCED
        );
    }

    public static NativeConnectRequest kcp(
            String host,
            int port,
            KcpProfileValue profile
    ) {
        return new NativeConnectRequest(
                NativeTransportKind.KCP,
                host,
                port,
                profile
        );
    }

    public enum KcpProfileValue {

        BALANCED(0),
        AGGRESSIVE(2);

        private final int abiValue;

        KcpProfileValue(int abiValue) {
            this.abiValue = abiValue;
        }

        public int abiValue() {
            return abiValue;
        }

    }

}
