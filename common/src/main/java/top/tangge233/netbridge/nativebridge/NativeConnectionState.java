package top.tangge233.netbridge.nativebridge;

public enum NativeConnectionState {

    CONNECTING(1),
    CONNECTED(2),
    CLOSED(3),
    FAILED(4);

    private final int abiValue;

    NativeConnectionState(int abiValue) {
        this.abiValue = abiValue;
    }

    public static NativeConnectionState fromAbi(int abiValue) {
        return switch (abiValue) {
            case 1 -> CONNECTING;
            case 2 -> CONNECTED;
            case 3 -> CLOSED;
            case 4 -> FAILED;
            default -> throw new IllegalArgumentException(
                    "Unknown native connection state: " + abiValue
            );
        };
    }

    public int abiValue() {
        return abiValue;
    }

}
