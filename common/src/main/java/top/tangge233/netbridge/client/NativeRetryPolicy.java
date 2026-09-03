package top.tangge233.netbridge.client;

/**
 * 加速尝试重试策略。
 */
public record NativeRetryPolicy(
        int maxAttempts,
        long firstAttemptTimeoutMillis,
        long subsequentAttemptTimeoutMillis
) {

    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final long FIRST_ATTEMPT_TIMEOUT_MILLIS = 10_000L;
    public static final long SUBSEQUENT_ATTEMPT_TIMEOUT_MILLIS = 20_000L;

    public static NativeRetryPolicy defaults() {
        return new NativeRetryPolicy(
                DEFAULT_MAX_ATTEMPTS,
                FIRST_ATTEMPT_TIMEOUT_MILLIS,
                SUBSEQUENT_ATTEMPT_TIMEOUT_MILLIS
        );
    }

    public long timeoutMillisForAttempt(int attempt) {
        return attempt <= 1
                ? firstAttemptTimeoutMillis
                : subsequentAttemptTimeoutMillis;
    }

}
