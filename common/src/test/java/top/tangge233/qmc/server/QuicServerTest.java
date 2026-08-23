package top.tangge233.qmc.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.QuicClient;

/**
 * QuicServer accept 循环集成测试（真实 JNI）：handler 回调、stop 幂等。
 */
class QuicServerTest {
    private final BlockingQueue<Long> accepted = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() {
        QuicClient.useConfigFile(null);
        QuicServer.setConnectionHandler(null);
        QuicServer.stop();
        QuicServer.stop(); // 幂等
        assertFalse(QuicServer.isRunning());
        assertEquals(-1, QuicServer.port());
    }

    /** 首次启动场景：server.toml 缺失时从内置模板自动生成（含注释），值可解析。 */
    @Test
    void createsDefaultConfigFileWhenMissing(@TempDir Path dir) throws IOException {
        QuicClient.useConfigFile(dir.resolve("client.toml"));
        assertFalse(Files.exists(dir.resolve("server.toml")));

        QuicServer.ServerConfig config = QuicServer.loadServerConfig();

        Path file = dir.resolve("server.toml");
        assertTrue(Files.exists(file), "server.toml should be auto-created");
        String content = Files.readString(file);
        assertTrue(content.contains("# QUIC 监听端口"), "template comments preserved");
        assertEquals(Integer.valueOf(-1), config.port(), "default port follows TCP");
        assertEquals(Integer.valueOf(256), config.maxConnection());
    }

    /** 已有配置不被覆盖：用户改动保留，值原样读出。 */
    @Test
    void keepsExistingConfigFile(@TempDir Path dir) throws IOException {
        QuicClient.useConfigFile(dir.resolve("client.toml"));
        Files.writeString(dir.resolve("server.toml"),
                "# my tuning\nport = 30000\nmax_connection = 8\n");

        QuicServer.ServerConfig config = QuicServer.loadServerConfig();

        String content = Files.readString(dir.resolve("server.toml"));
        assertTrue(content.startsWith("# my tuning"), "user file untouched");
        assertEquals(Integer.valueOf(30000), config.port());
        assertEquals(Integer.valueOf(8), config.maxConnection());
    }

    @Test
    void handlerReceivesNewConnections() throws Exception {
        NativeLoader.load();
        QuicServer.setConnectionHandler(accepted::add);
        assertTrue(QuicServer.start(0));
        int port = QuicServer.port();
        assertTrue(port > 0);

        long client = QuicNative.connect("127.0.0.1", port);
        assertTrue(client > 0, "connect failed");
        Long connId = accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(connId, "handler never invoked");
        assertEquals(QuicNative.STATE_CONNECTED, QuicNative.connectionState(connId));
        QuicNative.closeConnection(client);
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
