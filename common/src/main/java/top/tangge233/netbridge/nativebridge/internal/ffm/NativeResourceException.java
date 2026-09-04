package top.tangge233.netbridge.nativebridge.internal.ffm;

public final class NativeResourceException extends RuntimeException {

    private final String code;

    public NativeResourceException(String codeAndMessage) {
        super(codeAndMessage);
        this.code = firstToken(codeAndMessage);
    }

    private static String firstToken(String message) {
        var idx = message.indexOf(' ');
        return idx <= 0
                ? message
                : message.substring(0, idx);
    }

    public NativeResourceException(
            String codeAndMessage,
            Throwable cause
    ) {
        super(codeAndMessage, cause);
        this.code = firstToken(codeAndMessage);
    }

    public String code() {
        return code;
    }

}
