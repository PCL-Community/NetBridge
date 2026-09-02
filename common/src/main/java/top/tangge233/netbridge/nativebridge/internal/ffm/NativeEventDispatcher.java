package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.NativeEvent;
import top.tangge233.netbridge.nativebridge.NativeEventListener;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 接收 FFM Upcall 回调并将事件安全分发给已注册监听器的分发器。
 */
public final class NativeEventDispatcher {

    private final CopyOnWriteArrayList<NativeEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(NativeEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NativeEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 由 FFM upcall stub 调用的目标方法。
     *
     * <p>保证内部捕获所有 Throwable，绝对不让异常穿越 FFM boundary。
     */
    public void onNativeEvent(
            int eventKind,
            long objectId,
            long arg0,
            long arg1
    ) {
        try {
            var event = new NativeEvent(eventKind, objectId, arg0, arg1);
            listeners.forEach(listener -> {
                try {
                    listener.onEvent(event);
                } catch (Throwable t) {
                    NetBridge.LOGGER.error(
                            "Error in native event listener: {}",
                            t.getMessage(),
                            t
                    );
                }
            });
        } catch (Throwable t) {
            NetBridge.LOGGER.error(
                    "Fatal error in onNativeEvent upcall target: {}",
                    t.getMessage(),
                    t
            );
        }
    }

}
