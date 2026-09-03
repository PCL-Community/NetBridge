package top.tangge233.netbridge.nativebridge;

public record NativeIoResult(
        NativeIoStatus status,
        int bytes
) {

    public static final NativeIoResult WOULD_BLOCK = new NativeIoResult(
            NativeIoStatus.WOULD_BLOCK,
            0
    );
    public static final NativeIoResult CLOSED = new NativeIoResult(
            NativeIoStatus.CLOSED,
            0
    );

    public static NativeIoResult progressed(int bytes) {
        return new NativeIoResult(NativeIoStatus.PROGRESSED, bytes);
    }

    public boolean progressed() {
        return status == NativeIoStatus.PROGRESSED;
    }

    public boolean wouldBlock() {
        return status == NativeIoStatus.WOULD_BLOCK;
    }

    public boolean closed() {
        return status == NativeIoStatus.CLOSED;
    }

}
