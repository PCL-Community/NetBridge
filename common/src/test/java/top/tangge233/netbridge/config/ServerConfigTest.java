package top.tangge233.netbridge.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.server.ServerConfigStore;
import top.tangge233.netbridge.config.server.ServerSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.transport.KcpProfile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务端配置加载与网络地址解析测试。
 */
class ServerConfigTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(ServerSettingsResolver.PROP_QUIC_PORT);
    }

    @Test
    void loadsDefaultsWhenFileDoesNotExist(@TempDir Path dir) {
        var serverFile = dir.resolve("server.toml");
        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(-1, settings.quic().port());
        assertNull(settings.quic().bindHost());
        assertEquals(256, settings.quic().maxConnections());

        assertFalse(settings.kcp().enabled(), "KCP 默认关闭");
        assertEquals(-1, settings.kcp().port());
        assertEquals(KcpProfile.BALANCE, settings.kcp().kcpProfile());

        assertTrue(Files.exists(serverFile), "加载时应自动从模板释放生成 server.toml");
    }

    @Test
    void readsCustomConfigurationCorrectly(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(
                serverFile,
                """
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
                        bind = "192.168.1.100"
                        max_connection = 8
                        profile = "aggressive"
                        """
        );

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(0, settings.quic().port());
        assertEquals("127.0.0.1", settings.quic().bindHost());
        assertEquals("quic.example.org", settings.quic().advertisedHost());
        assertEquals(64, settings.quic().maxConnections());

        assertTrue(settings.kcp().enabled());
        assertEquals(30000, settings.kcp().port());
        assertNull(settings.kcp().advertisedHost(), "空串归一化为 null");
        assertEquals("192.168.1.100", settings.kcp().bindHost());
        assertEquals(8, settings.kcp().maxConnections());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcp().kcpProfile());
    }

    @Test
    void defaultsApplyForMissingFields(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(serverFile, "[quic]\n[kcp]\n");

        var store = new ServerConfigStore(serverFile);
        var settings = store.load();

        assertTrue(settings.quic().enabled());
        assertEquals(256, settings.quic().maxConnections());
        assertFalse(settings.kcp().enabled(), "缺 enable 默认 false");
        assertEquals(KcpProfile.BALANCE, settings.kcp().kcpProfile());
    }

    @Test
    void brokenConfigFallsBackToDefaults(@TempDir Path dir) throws Exception {
        var serverFile = dir.resolve("server.toml");
        Files.writeString(serverFile, "this is not valid toml [[[[");

        var store = new ServerConfigStore(serverFile);
        assertDoesNotThrow(store::load);
        assertTrue(store.load().quic().enabled());
    }

    @Test
    void serverSettingsResolverResolvesPortsAndBindIp() {
        var settings = ServerSettings.defaults();
        var resolved = ServerSettingsResolver.resolve(
                settings,
                25565,
                "127.0.0.1"
        );

        // QUIC: enabled, port=-1 -> follows 25565
        assertTrue(resolved.quic().enabled());
        assertEquals(25565, resolved.quic().listenPort());
        assertEquals("127.0.0.1", resolved.quic().bindHost());

        // KCP: disabled by default
        assertFalse(resolved.kcp().enabled());

        // System property override for QUIC port
        System.setProperty(ServerSettingsResolver.PROP_QUIC_PORT, "28888");
        var overridden = ServerSettingsResolver.resolve(
                settings,
                25565,
                null
        );
        assertEquals(28888, overridden.quic().listenPort());
        assertNull(overridden.quic().bindHost());
        System.clearProperty(ServerSettingsResolver.PROP_QUIC_PORT);

        // Test invalid mcPort with -1 follow
        var invalidMcPort = ServerSettingsResolver.resolve(
                settings,
                70000,
                null
        );
        assertFalse(invalidMcPort.quic().enabled(), "MC 端口越界时应禁用该传输");
    }

}
