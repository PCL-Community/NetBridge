package top.tangge233.qmc.jni;

/**
 * QUIC 连接状态（与 Rust bridge 状态码一致）。
 */
public enum QuicConnectionState {
    CONNECTING(QuicNative.STATE_CONNECTING),
    CONNECTED(QuicNative.STATE_CONNECTED),
    CLOSED(QuicNative.STATE_CLOSED),
    FAILED(QuicNative.STATE_FAILED),
    UNKNOWN(QuicNative.STATE_UNKNOWN);

    private final int code;

    QuicConnectionState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static QuicConnectionState fromCode(int code) {
        return switch (code) {
            case QuicNative.STATE_CONNECTING -> CONNECTING;
            case QuicNative.STATE_CONNECTED -> CONNECTED;
            case QuicNative.STATE_CLOSED -> CLOSED;
            case QuicNative.STATE_FAILED -> FAILED;
            default -> UNKNOWN;
        };
    }
}
