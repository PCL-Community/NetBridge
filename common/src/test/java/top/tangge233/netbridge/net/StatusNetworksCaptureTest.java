package top.tangge233.netbridge.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * StatusNetworksCapture 线程级暂存语义：同线程可见可取走，跨线程不可见。
 */
class StatusNetworksCaptureTest {
    @Test
    void sameThreadCaptureThenTake() {
        NetworksAbility n = NetworksAbility.withQuic(25565, "quic-raw");
        StatusNetworksCapture.capture(n);
        assertSame(n, StatusNetworksCapture.take());
        // 取走后清空（NetworksAbility 无值 equals，用行为断言）。
        assertTrue(StatusNetworksCapture.take().quicInfo().isEmpty());
    }

    @Test
    void emptyCaptureIsInvisible() {
        StatusNetworksCapture.capture(NetworksAbility.empty());
        assertTrue(StatusNetworksCapture.take().quicInfo().isEmpty());
    }

    @Test
    void notVisibleAcrossThreads() throws Exception {
        NetworksAbility n = NetworksAbility.withQuic(25565, "quic-raw");
        StatusNetworksCapture.capture(n);
        AtomicReference<NetworksAbility> seen = new AtomicReference<>();
        Thread other = new Thread(() -> seen.set(StatusNetworksCapture.take()));
        other.start();
        other.join();
        assertTrue(seen.get().quicInfo().isEmpty(), "跨线程不应看到暂存值");
        // 主线程的暂存值不受影响，清理避免污染其他测试。
        assertSame(n, StatusNetworksCapture.take());
    }
}
