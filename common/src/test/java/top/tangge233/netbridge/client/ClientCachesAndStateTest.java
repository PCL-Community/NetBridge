package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientCachesAndStateTest {

    @Test
    void capabilityCacheEvictsLru() {
        var cache = new ServerCapabilityCache();
        var advertised = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                2443,
                "net-bri-quic/1"
        ));
        IntStream.rangeClosed(1, 300)
                .forEachOrdered(i -> cache.record(
                        addr(1000 + i),
                        advertised
                ));
        assertTrue(
                cache.get(addr(1001)).entries().isEmpty(),
                "LRU 逐出最久未用"
        );
        assertFalse(cache.get(addr(1300)).entries().isEmpty());
        cache.clear();
        assertTrue(cache.get(addr(1300)).entries().isEmpty());
    }

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("203.0.113.7", port);
    }

    @Test
    void successCacheModeScopedAndExpiring() {
        var now = new AtomicLong(1000);
        var cache = new SuccessfulEndpointCache(now::get, 5000);
        var key = addr(25565);
        var target = new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        );

        assertTrue(cache.lookup(key, TransportMode.QUIC).isEmpty());
        cache.record(key, target);
        assertEquals(
                9999,
                cache.lookup(key, TransportMode.QUIC).orElseThrow().endpoint().getPort()
        );

        assertTrue(
                cache.lookup(key, TransportMode.KCP).isEmpty(),
                "mode 不同不得命中"
        );

        now.addAndGet(5001);
        assertTrue(
                cache.lookup(key, TransportMode.QUIC).isEmpty(),
                "TTL 过期后命中失效"
        );
    }

    @Test
    void successCacheOverwriteAndInvalidate() {
        var cache = new SuccessfulEndpointCache();
        var key = addr(25565);
        var first = new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("1.2.3.4", 1111)
        );
        var second = new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("5.6.7.8", 2222)
        );
        cache.record(
                key,
                first
        );
        cache.record(
                key,
                second
        );
        assertEquals(
                2222,
                cache.lookup(key, TransportMode.QUIC).orElseThrow().endpoint().getPort(),
                "后写覆盖先写"
        );
        cache.invalidate(key);
        assertTrue(cache.lookup(key, TransportMode.QUIC).isEmpty());
    }

    @Test
    void successCacheBounded() {
        var cache = new SuccessfulEndpointCache();
        var target = new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        );
        IntStream.rangeClosed(1, 1000)
                .forEachOrdered(i -> cache.record(
                        addr(i),
                        target
                ));
        assertTrue(cache.lookup(addr(1000), TransportMode.QUIC).isPresent());
    }

    @Test
    void stateStoreTransitions() {
        var store = new ConnectionStateStore();
        assertEquals(
                ConnectionSnapshot.Phase.IDLE,
                store.snapshot().phase()
        );
        store.connecting(TransportMode.QUIC);
        assertEquals(
                ConnectionSnapshot.Phase.CONNECTING,
                store.snapshot().phase()
        );
        assertEquals(
                TransportMode.QUIC,
                store.snapshot().requestedMode()
        );
        assertNull(store.snapshot().transportLine());
        store.connected(
                TransportMode.QUIC,
                "QUIC 1.2.3.4:25565"
        );
        assertEquals(
                ConnectionSnapshot.Phase.CONNECTED,
                store.snapshot().phase()
        );
        assertEquals(
                "QUIC 1.2.3.4:25565",
                store.transportLine()
        );
        store.fallingBack();
        assertEquals(
                ConnectionSnapshot.Phase.FALLING_BACK,
                store.snapshot().phase()
        );
        store.idle();
        assertEquals(
                ConnectionSnapshot.Phase.IDLE,
                store.snapshot().phase()
        );
    }

}
