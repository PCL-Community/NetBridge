package top.tangge233.netbridge.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.config.client.ClientConfigStore;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端配置存储、持久化与运行时服务测试。
 */
class ClientConfigTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(ClientSettingsService.PROP_MODE);
    }

    @Test
    void loadsDefaultsWhenFileDoesNotExist(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.TCP, settings.mode());
        assertEquals(KcpProfile.BALANCE, settings.kcpProfile());
    }

    @Test
    void readsLegacyClientTomlFixture(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "kcp"
                        [kcp]
                        profile = "aggressive"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.KCP, settings.mode());
        assertEquals(KcpProfile.AGGRESSIVE, settings.kcpProfile());
    }

    @Test
    void saveAndLoadRoundtrip(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);

        var toSave = new ClientSettings(TransportMode.QUIC, KcpProfile.AGGRESSIVE);
        store.save(toSave);

        var loaded = store.load();
        assertEquals(TransportMode.QUIC, loaded.mode());
        assertEquals(KcpProfile.AGGRESSIVE, loaded.kcpProfile());
    }

    @Test
    void handlesMalformedConfigGracefully(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(clientFile, "not a valid toml = [[[[");

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(TransportMode.TCP, settings.mode());
        assertEquals(KcpProfile.BALANCE, settings.kcpProfile());
    }

    @Test
    void handlesUnknownKcpProfileGracefully(@TempDir Path dir) throws Exception {
        var clientFile = dir.resolve("client.toml");
        Files.writeString(
                clientFile,
                """
                        mode = "quic"
                        [kcp]
                        profile = "ultra_fast_nonexistent"
                        """
        );

        var store = new ClientConfigStore(clientFile);
        var settings = store.load();

        assertEquals(
                TransportMode.QUIC,
                settings.mode()
        );
        assertEquals(
                KcpProfile.BALANCE,
                settings.kcpProfile(),
                "未知 profile 应回退默认 BALANCE"
        );
    }

    @Test
    void clientSettingsServiceAppliesSystemPropertyAndPersistsUpdates(@TempDir Path dir) {
        var clientFile = dir.resolve("client.toml");
        var store = new ClientConfigStore(clientFile);

        System.setProperty(ClientSettingsService.PROP_MODE, "kcp");
        var service = ClientSettingsService.create(store);

        assertEquals(TransportMode.KCP, service.current().mode());

        // Runtime UI update
        service.updateMode(TransportMode.QUIC);
        assertEquals(TransportMode.QUIC, service.current().mode());

        // Verify persisted to disk
        var reloaded = store.load();
        assertEquals(TransportMode.QUIC, reloaded.mode());
    }

}
