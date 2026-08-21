package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.tangge233.qmc.jni.QuicNative;

class QuicClientTest {
    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void clearPropertiesAndCache() {
        System.clearProperty(QuicClient.PROP_MODE);
    }

    @Test
    void defaultsToTcp() {
        InetSocketAddress addr = addr();
        assertEquals(TransportMode.TCP_ONLY, QuicClient.mode());
        assertNull(QuicClient.quicTargetFor(addr));
    }

    @Test
    void quicModeRequiresAdvertisedCapability() {
        InetSocketAddress addr = addr();
        System.setProperty(QuicClient.PROP_MODE, "quic");
        // 未 Ping 过：无能力 -> 不走 QUIC
        assertNull(QuicClient.quicTargetFor(addr));

        QuicClient.record(addr, Networks.withQuic(25565, QuicNative.RAW_FEATURE));
        QuicTarget target = QuicClient.quicTargetFor(addr);
        assertNotNull(target);
        assertEquals(25565, target.quicPort());
        assertFalse(target.allowTcpFallback(), "quic-only 不应允许回退");
    }

    @Test
    void fallbackModeAllowsTcp() {
        InetSocketAddress addr = addr();
        System.setProperty(QuicClient.PROP_MODE, "quic_fallback");
        QuicClient.record(addr, Networks.withQuic(25565, QuicNative.RAW_FEATURE));
        QuicTarget target = QuicClient.quicTargetFor(addr);
        assertNotNull(target);
        assertTrue(target.allowTcpFallback());
    }

    @Test
    void failedAddressIsExcluded() {
        InetSocketAddress addr = addr();
        System.setProperty(QuicClient.PROP_MODE, "quic");
        QuicClient.record(addr, Networks.withQuic(25565, QuicNative.RAW_FEATURE));
        assertNotNull(QuicClient.quicTargetFor(addr));

        QuicClient.markQuicFailed(addr);
        assertNull(QuicClient.quicTargetFor(addr));
    }

    @Test
    void unknownServerAdvertisesNothing() {
        InetSocketAddress addr = addr();
        System.setProperty(QuicClient.PROP_MODE, "quic");
        assertFalse(QuicClient.networksFor(addr).supportsQuicRaw());
    }

    private InetSocketAddress addr() {
        return new InetSocketAddress("127.0.0.1", 25565 + SEQ.incrementAndGet() * 7);
    }
}
