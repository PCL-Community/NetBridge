package top.tangge233.netbridge.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;

/**
 * 客户端决策状态机测试：超时×2 降级记忆 / TTL 过期重试 / 未宣告直降 TCP /
 * 端点合成（host 缺省跟随）。
 */
class TransportSelectorTest {
    /** 每测试唯一地址：能力缓存按地址键存取，复用地址会跨测试污染。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static InetSocketAddress addr() {
        return new InetSocketAddress("203.0.113." + (SEQ.incrementAndGet() % 250 + 1), 25565);
    }

    @BeforeEach
    void resetState() {
        FallbackTracker.clear();
        ClientConfig.setMode(TransportMode.TCP);
        ClientConfig.useConfigFile(null);
        System.clearProperty(ClientConfig.PROP_MODE);
    }

    @AfterEach
    void cleanup() {
        FallbackTracker.clear();
    }

    @Test
    void tcpModeNeverSelectsAccelerated() {
        InetSocketAddress addr = addr();
        assertFalse(TransportSelector.decide(addr).isPresent());
    }

    @Test
    void unadvertisedServerFallsStraightToTcp() {
        InetSocketAddress addr = addr();
        ClientConfig.setMode(TransportMode.QUIC);
        // 未 Ping 过：无任何宣告。
        assertFalse(TransportSelector.decide(addr).isPresent());

        // 已宣告但 protocol 不支持。
        TransportSelector.record(addr, NetworksAbility.of(
                new NetworksEntry(true, null, 25565, "net-bri-quic/99")));
        assertFalse(TransportSelector.decide(addr).isPresent());
    }

    @Test
    void advertisedServerYieldsEndpointFollowingAddressWhenHostMissing() {
        InetSocketAddress addr = addr();
        ClientConfig.setMode(TransportMode.QUIC);
        TransportSelector.record(addr, NetworksAbility.of(
                new NetworksEntry(true, null, 2443, "net-bri-quic/1")));
        Optional<TransportTarget> target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(TransportMode.QUIC, target.get().mode());
        assertTrue(target.get().endpoint().getHostString().startsWith("203.0.113."), "host 缺省跟随 ping 目标");
        assertEquals(2443, target.get().endpoint().getPort());
    }

    @Test
    void kcpModeUsesKcpEntryOnly() {
        InetSocketAddress addr = addr();
        ClientConfig.setMode(TransportMode.KCP);
        TransportSelector.record(addr, NetworksAbility.of(
                new NetworksEntry(true, null, 2443, "net-bri-quic/1"),
                new NetworksEntry(true, null, 2444, "net-bri-kcp/1")));
        Optional<TransportTarget> target = TransportSelector.decide(addr);
        assertTrue(target.isPresent());
        assertEquals(2444, target.get().endpoint().getPort());
    }

    @Test
    void fallbackMemoryShortCircuitsAttempts() {
        InetSocketAddress addr = addr();
        ClientConfig.setMode(TransportMode.QUIC);
        TransportSelector.record(addr, NetworksAbility.of(
                new NetworksEntry(true, null, 2443, "net-bri-quic/1")));
        FallbackTracker.mark(addr);

        Optional<TransportTarget> target = TransportSelector.decide(addr);
        assertFalse(target.isPresent(), "TTL 命中应跳过全部加速尝试");

        // TTL 过期后重新执行完整序列。
        FallbackTracker.clear();
        assertTrue(TransportSelector.decide(addr).isPresent(), "过期后应重新尝试加速传输");
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
