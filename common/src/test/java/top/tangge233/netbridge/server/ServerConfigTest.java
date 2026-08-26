package top.tangge233.netbridge.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.transport.ClientConfig;

/**
 * 服务端配置边界值测试：-1/0/65535/65536/null bind/host、双段独立读取。
 */
class ServerConfigTest {

    @Test
    void emptyWhenConfigDirUnregistered() {
        ClientConfig.useConfigFile(null);
        ServerConfig config = ServerConfig.load();
        assertTrue(config.quic().enable());
        assertEquals(-1, config.quic().port());
        assertNull(config.quic().bind());
        assertEquals(-1, config.kcp().port(), "kcp 默认 -1（解析为 MC 端口+1）");
    }

    @Test
    void readsBothSectionsIndependently(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("client.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mode = \"tcp\"\n");
        ClientConfig.useConfigFile(file);

        // server.toml 与 client.toml 同目录。
        Path serverFile = dir.resolve("server.toml");
        Files.writeString(serverFile, """
                [quic]
                enable = true
                port = 0
                bind = "127.0.0.1"
                host = "quic.example.org"
                max_connection = 64

                [kcp]
                enable = true
                port = 30000
                host = ""
                max_connection = 8
                profile = "aggressive"
                """);

        ServerConfig config = ServerConfig.load();
        assertTrue(config.quic().enable());
        assertEquals(0, config.quic().port());
        assertEquals("127.0.0.1", config.quic().bind());
        assertEquals("quic.example.org", config.quic().host());
        assertEquals(64, config.quic().maxConnection());

        assertEquals(30000, config.kcp().port());
        assertNull(config.kcp().host(), "空串归一为 null（跟随服务器地址）");
        assertEquals(8, config.kcp().maxConnection());
        assertEquals("aggressive", config.kcp().profile());
    }

    @Test
    void defaultsApplyForMissingFields(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("client.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        ClientConfig.useConfigFile(file);

        Files.writeString(dir.resolve("server.toml"), "[quic]\n[kcp]\n");
        ServerConfig config = ServerConfig.load();
        assertTrue(config.quic().enable(), "缺 enable 默认 true");
        assertNull(config.quic().maxConnection(), "未配置字段保留 null，由 acceptor 解析默认值");
        assertTrue(config.kcp().enable());
        assertEquals("balance", config.kcp().profile(), "段缺 profile 时由模板默认补齐 balance");
    }

    @Test
    void brokenConfigFallsBackToDefaults(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("client.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        ClientConfig.useConfigFile(file);

        Files.writeString(dir.resolve("server.toml"), "this is not valid toml [[[");
        assertDoesNotThrow(ServerConfig::load);
        assertTrue(ServerConfig.load().quic().enable());
    }
}
