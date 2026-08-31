package top.tangge233.netbridge.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.config.client.ClientSettingsService;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端决策状态机测试：超时×2 降级记忆 / TTL 过期重试 / 未宣告直降 TCP / 端点合成（host 缺省跟随）。
 */
class TransportSelectorTest {

    /** 每测试唯一地址：能力缓存按地址键存取，复用地址会跨测试污染。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void resetState(@TempDir Path tempDir) {
        FallbackTracker.clear();
        System.clearProperty(ClientSettingsService.PROP_MODE);
        NetBridgeServices.bootstrap(new ConfigPaths(tempDir));
    }

    @AfterEach
    void cleanup() {
        FallbackTracker.clear();
    }

    @Test
    void tcpModeNeverSelectsAccelerated() {
        var addr = addr();
        assertFalse(TransportSelector.decide(addr).isPresent());
    }

    private static InetSocketAddress addr() {
        return new InetSocketAddress(
                "203.0.113." + (SEQ.incrementAndGet() % 250 + 1),
                25565
        );
    }

    @Test
    void unadvertisedServerFallsStraightToTcp() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.QUIC);
        // 未 Ping 过：无任何宣告。
        assertFalse(TransportSelector.decide(addr).isPresent());

        // 已宣告但 protocol 不支持。
        TransportSelector.record(
                addr,
                NetworksAbility.of(new NetworksEntry(
                        true,
                        null,
                        25565,
                        "net-bri-quic/99"
                ))
        );
        assertFalse(TransportSelector.decide(addr).isPresent());
    }

    @Test
    void advertisedServerYieldsEndpointFollowingAddressWhenHostMissing() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.QUIC);
        TransportSelector.record(
                addr,
                NetworksAbility.of(new NetworksEntry(
                        true,
                        null,
                        2443,
                        "net-bri-quic/1"
                ))
        );
        var target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(TransportMode.QUIC, target.get().mode());
        assertTrue(
                target.get().endpoint().getHostString().startsWith("203.0.113."),
                "host 缺省跟随 ping 目标"
        );
        assertEquals(2443, target.get().endpoint().getPort());
    }

    @Test
    void kcpModeUsesKcpEntryOnly() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.KCP);
        TransportSelector.record(
                addr,
                NetworksAbility.of(
                        new NetworksEntry(
                                true,
                                null,
                                2443,
                                "net-bri-quic/1"
                        ),
                        new NetworksEntry(
                                true,
                                null,
                                2444,
                                "net-bri-kcp/1"
                        )
                )
        );
        var target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(2444, target.get().endpoint().getPort());
    }

    @Test
    void successCacheTakesPriorityOverAdvertisement() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.QUIC);
        TransportSelector.record(
                addr,
                NetworksAbility.of(new NetworksEntry(
                        true,
                        null,
                        2443,
                        "net-bri-quic/1"
                ))
        );
        // 模拟成功建连后的缓存：端点与宣告不同，缓存应优先。
        FallbackTracker.record(
                addr,
                new TransportTarget(
                        TransportMode.QUIC,
                        new InetSocketAddress("1.2.3.4", 9999)
                )
        );

        var target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(
                9999,
                target.get().endpoint().getPort(),
                "TTL 内同传输缓存端点优先于宣告"
        );
        assertEquals("1.2.3.4", target.get().endpoint().getHostString());
    }

    @Test
    void staleCacheFallsBackToAdvertisement() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.QUIC);
        TransportSelector.record(
                addr,
                NetworksAbility.of(new NetworksEntry(
                        true,
                        null,
                        2443,
                        "net-bri-quic/1"
                ))
        );
        FallbackTracker.record(
                addr,
                new TransportTarget(
                        TransportMode.QUIC,
                        new InetSocketAddress("1.2.3.4", 9999)
                )
        );
        FallbackTracker.clear(); // 模拟 TTL 过期

        var target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(
                2443,
                target.get().endpoint().getPort(),
                "缓存过期后重新走宣告协商"
        );
    }

    @Test
    void cacheIgnoredWhenModeDiffers() {
        var addr = addr();
        NetBridgeServices.clientSettings().updateMode(TransportMode.KCP);
        // 缓存是 QUIC（上次成功），当前切到 KCP：不得复用缓存，走 KCP 宣告。
        FallbackTracker.record(
                addr,
                new TransportTarget(
                        TransportMode.QUIC,
                        new InetSocketAddress("1.2.3.4", 9999)
                )
        );
        TransportSelector.record(
                addr,
                NetworksAbility.of(new NetworksEntry(
                        true,
                        null,
                        2444,
                        "net-bri-kcp/1"
                ))
        );

        var target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(TransportMode.KCP, target.get().mode());
        assertEquals(
                2444,
                target.get().endpoint().getPort(),
                "换传输后立即生效，不被旧缓存锁死"
        );
    }

    @Test
    void modeParsingAcceptsThreeValuesOnly() {
        assertEquals(TransportMode.TCP, TransportMode.parse("tcp"));
        assertEquals(TransportMode.QUIC, TransportMode.parse("QUIC"));
        assertEquals(TransportMode.KCP, TransportMode.parse(" kcp "));
        assertNull(TransportMode.parse("quic-fallback"), "旧取值不迁移");
        assertNull(TransportMode.parse(null));
    }

    @Test
    void profileParsingWithAlias() {
        assertEquals(KcpProfile.BALANCE, KcpProfile.parse("balance"));
        assertEquals(KcpProfile.BALANCE, KcpProfile.parse("balanced"), "历史别名兼容");
        assertEquals(KcpProfile.AGGRESSIVE, KcpProfile.parse(" Aggressive "));
        assertNull(KcpProfile.parse("turbo"));
        assertEquals("balance", KcpProfile.BALANCE.configValue());
    }

}
