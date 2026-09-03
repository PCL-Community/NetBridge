package top.tangge233.netbridge.client;

import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

public final class SuccessfulEndpointCache {

    public static final long DEFAULT_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_ENTRIES = 256;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long ttlMillis;

    public SuccessfulEndpointCache() {
        this(
                System::currentTimeMillis,
                DEFAULT_TTL_MILLIS
        );
    }

    public SuccessfulEndpointCache(
            LongSupplier clock,
            long ttlMillis
    ) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public Optional<TransportTarget> lookup(
            @Nullable InetSocketAddress address,
            TransportMode mode
    ) {
        if (address == null) {
            return Optional.empty();
        }

        var key = key(address);
        var entry = entries.get(key);

        if (entry == null) {
            return Optional.empty();
        }

        if (entry.expiry() - clock.getAsLong() <= 0) {
            entries.remove(key);
            return Optional.empty();
        }

        if (entry.target().mode() != mode) {
            return Optional.empty();
        }

        return Optional.of(entry.target());
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    public void record(
            @Nullable InetSocketAddress address,
            @Nullable TransportTarget target
    ) {
        if (address == null || target == null) {
            return;
        }

        var now = clock.getAsLong();
        var key = key(address);

        entries.put(
                key,
                new Entry(target, now + ttlMillis)
        );

        if (entries.size() > MAX_ENTRIES) {
            evictExpired(now);
            while (entries.size() > MAX_ENTRIES) {
                var oldest = entries.entrySet().stream()
                        .min(
                                Map.Entry.comparingByValue(
                                        Comparator.comparingLong(Entry::expiry)
                                )
                        )
                        .orElse(null);
                if (oldest == null) {
                    break;
                }
                entries.remove(oldest.getKey());
            }
        }
    }

    private void evictExpired(long now) {
        entries.entrySet().removeIf(e ->
                e.getValue().expiry() - now <= 0
        );
    }

    public void invalidate(@Nullable InetSocketAddress address) {
        if (address != null) {
            entries.remove(key(address));
        }
    }

    private record Entry(
            TransportTarget target,
            long expiry
    ) {

    }

}
