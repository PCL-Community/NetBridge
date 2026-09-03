package top.tangge233.netbridge.nativebridge;

/**
 * 原生传输类别（ABI 数值映射仅在 FFM internal codec 中）。
 */
public enum NativeTransportKind {

    QUIC(1),
    KCP(2);

    private final int abiValue;

    NativeTransportKind(int abiValue) {
        this.abiValue = abiValue;
    }

    public static NativeTransportKind fromAbi(int abiValue) {
        return switch (abiValue) {
            case 1 -> QUIC;
            case 2 -> KCP;
            default -> throw new IllegalArgumentException(
                    "Unknown native transport kind: " + abiValue
            );
        };
    }

    public int abiValue() {
        return abiValue;
    }

}
