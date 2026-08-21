package top.tangge233.qmc.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

/**
 * QuicServer accept 循环集成测试（真实 JNI）：handler 回调、无 handler 拒绝、stop 幂等。
 */
class QuicServerTest {
    private final BlockingQueue<Long> accepted = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() {
        QuicServer.setConnectionHandler(null);
        QuicServer.stop();
        QuicServer.stop(); // 幂等
        assertFalse(QuicServer.isRunning());
        assertEquals(-1, QuicServer.port());
    }

    @Test
    void handlerReceivesNewConnections() throws Exception {
        NativeLoader.load();
        QuicServer.setConnectionHandler(accepted::add);
        assertTrue(QuicServer.start(0));
        int port = QuicServer.port();
        assertTrue(port > 0);

        long client = QuicNative.connect("127.0.0.1", port);
        assertTrue(client > 0, "connect failed: " + QuicNative.lastError());
        Long connId = accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(connId, "handler never invoked");
        assertEquals(QuicNative.STATE_CONNECTED, QuicNative.connectionState(connId));
        QuicNative.closeConnection(client);
    }

    @Test
    void rejectsWhenNoHandlerRegistered() throws Exception {
        NativeLoader.load();
        assertTrue(QuicServer.start(0));
        long client = QuicNative.connect("127.0.0.1", QuicServer.port());
        assertTrue(client > 0);
        // 无 handler：新连接应被关闭而非静默挂起。服务端收尾时 store(CLOSED)
        // 与 registry 移除几乎同时发生，客户端可能直接观察到 UNKNOWN(-1)——
        // 与生产判定（QuicChannel.poll 把 CLOSED/UNKNOWN 都视为关闭）一致。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            int s = QuicNative.connectionState(client);
            if (s != QuicNative.STATE_CONNECTED) {
                return;
            }
            Thread.sleep(5);
        }
        fail("connection without handler was not closed, state="
                + QuicNative.connectionState(client));
    }

    @Test
    void restartUsesFreshAcceptor() throws Exception {
        NativeLoader.load();
        assertTrue(QuicServer.start(0));
        int firstPort = QuicServer.port();
        QuicServer.stop();
        QuicServer.setConnectionHandler(accepted::add);
        assertTrue(QuicServer.start(0));
        // stop→start 后 accept 线程仍工作（回归：旧实现 accepting 标志会卡死新线程）。
        long client = QuicNative.connect("127.0.0.1", QuicServer.port());
        assertTrue(client > 0);
        Long connId = accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(connId, "accept loop dead after restart");
        QuicNative.closeConnection(client);
        assertTrue(firstPort > 0); // 抑制未用告警
    }
}
