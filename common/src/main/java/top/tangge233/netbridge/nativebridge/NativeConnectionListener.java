package top.tangge233.netbridge.nativebridge;

public interface NativeConnectionListener {

    default void onStateChanged(NativeConnectionState state) {
    }

    default void onDataAvailable() {
    }

    default void onWritable() {
    }

}
