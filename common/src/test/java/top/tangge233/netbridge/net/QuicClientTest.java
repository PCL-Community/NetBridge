package top.tangge233.netbridge.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.jni.QuicNative;

/**
 * QuicClient 传输决策测试。
 *
 * 注意：模式用 {@link QuicClient#setMode} 显式设置而非系统属性——
 * {@code QuicClient.mode} 只在类初始化时读一次属性，跨测试的属性
 * 污染会因类初始化时机不同而产生顺序依赖的假失败。
 */
class QuicClientTest {
    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void resetMode() {
        QuicClient.setMode(TransportMode.TCP_ONLY);
        QuicClient.useConfigFile(null); // 注销配置文件，避免污染后续测试
        System.clearProperty(QuicClient.PROP_MODE);
    }

    @Test
    void tcpOnlyNeverUsesQuic() {
        InetSocketAddress addr = addr();
        QuicClient.setMode(TransportMode.TCP_ONLY);
        assertNull(QuicClient.quicTargetFor(addr));
    }

    @Test
    void quicModeRequiresAdvertisedCapability() {
        InetSocketAddress addr = addr();
        QuicClient.setMode(TransportMode.QUIC_ONLY);
        // 未 Ping 过：无能力 -> 不走 QUIC
        assertNull(QuicClient.quicTargetFor(addr));

        QuicClient.record(addr, NetworksAbility.withQuic(25565, QuicNative.RAW_FEATURE));
        QuicTarget target = QuicClient.quicTargetFor(addr);
        assertNotNull(target);
        assertEquals(25565, target.quicPort());
        assertFalse(target.allowTcpFallback(), "quic-only 不应允许回退");
    }

    @Test
    void fallbackModeAllowsTcp() {
        InetSocketAddress addr = addr();
        QuicClient.setMode(TransportMode.QUIC_WITH_TCP_FALLBACK);
        QuicClient.record(addr, NetworksAbility.withQuic(25565, QuicNative.RAW_FEATURE));
        QuicTarget target = QuicClient.quicTargetFor(addr);
        assertNotNull(target);
        assertTrue(target.allowTcpFallback());
    }

    @Test
    void failureDoesNotBlacklistAddress() {
        // ADR-0002：失败仅影响本次连接，不拉黑地址，下次仍尝试 QUIC。
        InetSocketAddress addr = addr();
        QuicClient.setMode(TransportMode.QUIC_ONLY);
        QuicClient.record(addr, NetworksAbility.withQuic(25565, QuicNative.RAW_FEATURE));
        assertNotNull(QuicClient.quicTargetFor(addr), "应始终可重试 QUIC（无拉黑机制）");
    }

    @Test
    void unknownServerAdvertisesNothing() {
        InetSocketAddress addr = addr();
        QuicClient.setMode(TransportMode.QUIC_ONLY);
        assertFalse(QuicClient.networksFor(addr).supportsQuicRaw());
    }

    @Test
    void configFileRoundTrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 加载：预置配置文件应生效（模拟上次退出时保存的模式）。
        java.nio.file.Path file = dir.resolve("net-bridge/client.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mode = \"quic-fallback\"\n");
        QuicClient.useConfigFile(file);
        assertEquals(TransportMode.QUIC_WITH_TCP_FALLBACK, QuicClient.mode());

        // 保存：切换模式即写回，内容可被下次启动加载（TOML 回读校验，不锁格式）。
        QuicClient.setMode(TransportMode.QUIC_ONLY);
        assertEquals(
                "quic",
                new com.moandjiezana.toml.Toml().read(file.toFile()).getString("mode"));
    }

    private InetSocketAddress addr() {
        return new InetSocketAddress("127.0.0.1", 25565 + SEQ.incrementAndGet() * 7);
    }
}
