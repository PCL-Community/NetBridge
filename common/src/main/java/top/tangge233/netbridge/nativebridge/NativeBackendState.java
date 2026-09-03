package top.tangge233.netbridge.nativebridge;

/**
 * Native backend 可用性状态（替代旧 boolean loaded 的状态机）。
 */
public enum NativeBackendState {

    NEW,
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
    INCOMPATIBLE,
    CLOSING,
    CLOSED

}
