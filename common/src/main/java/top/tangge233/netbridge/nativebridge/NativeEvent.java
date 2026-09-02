package top.tangge233.netbridge.nativebridge;

/**
 * 原生层事件数据载荷。
 */
public record NativeEvent(
        int eventKind,
        long objectId,
        long arg0,
        long arg1
) {

    public static final int KIND_CONNECTION_STATE = 1;
    public static final int KIND_DATA_AVAILABLE = 2;
    public static final int KIND_WRITABLE = 3;
    public static final int KIND_ACCEPTED = 4;
    public static final int KIND_SERVER_STATE = 5;

}
