package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * StatusNetworksCapture 线程级暂存语义：同线程可见可取走，跨线程不可见。
 */
class StatusNetworksCaptureTest {
    @Test
    void sameThreadCaptureThenTake() {
        Networks n = Networks.withQuic(25565, "quic-raw");
        StatusNetworksCapture.capture(n);
        assertSame(n, StatusNetworksCapture.take());
        // 取走后清空（Networks 无值 equals，用行为断言）。
        assertTrue(StatusNetworksCapture.take().quicInfo().isEmpty());
    }

    @Test
    void emptyCaptureIsInvisible() {
        StatusNetworksCapture.capture(Networks.empty());
        assertTrue(StatusNetworksCapture.take().quicInfo().isEmpty());
    }

    @Test
    void notVisibleAcrossThreads() throws Exception {
        Networks n = Networks.withQuic(25565, "quic-raw");
        StatusNetworksCapture.capture(n);
        AtomicReference<Networks> seen = new AtomicReference<>();
        Thread other = new Thread(() -> seen.set(StatusNetworksCapture.take()));
        other.start();
        other.join();
        assertTrue(seen.get().quicInfo().isEmpty(), "跨线程不应看到暂存值");
        // 主线程的暂存值不受影响，清理避免污染其他测试。
        assertSame(n, StatusNetworksCapture.take());
    }
}
