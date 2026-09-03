package top.tangge233.netbridge.nativebridge;

public interface NativeServerListener {

    default void onAccepted(NativeConnection connection) {
    }

    default void onStateChanged(NativeServerState state) {
    }

}
