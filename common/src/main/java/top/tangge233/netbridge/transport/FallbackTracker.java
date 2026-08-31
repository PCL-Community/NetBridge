package top.tangge233.netbridge.transport;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * 加速连接地址缓存：记录最近成功建立的加速（KCP/QUIC）连接目标。
 *
 * <p>命中期内同传输重连直接复用缓存端点，跳过宣告协商；换传输或过期
 * 后重新走完整宣告序列。失败不再写记忆——UDP 环境变化不应长期阻断 某一传输，切换模式立即生效。
 */
public final class FallbackTracker {

    /** 记忆保留时长（毫秒）。 */
    public static final long TTL_MILLIS = 5 * 60 * 1000L;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private FallbackTracker() {
    }

    /**
     * 该地址在 TTL 内是否成功建立过加速连接；顺带清理过期条目。
     *
     * @return 成功连接的传输目标；无记录或已过期返回 empty
     */
    public static Optional<TransportTarget> lookup(@Nullable InetSocketAddress address) {
        if (address == null) {
            return Optional.empty();
        }

        var key = key(address);
        var entry = ENTRIES.get(key);
        if (entry == null) {
            return Optional.empty();
        }

        if (entry.expiry() - System.currentTimeMillis() <= 0) {
            ENTRIES.remove(key);
            return Optional.empty();
        }

        return Optional.of(entry.target());
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /**
     * 记录一次成功建立的加速连接目标（按 host:port 键），刷新其 TTL。
     */
    public static void record(
            @Nullable InetSocketAddress address,
            @Nullable TransportTarget target
    ) {
        if (address == null || target == null) {
            return;
        }

        var now = System.currentTimeMillis();
        ENTRIES.put(key(address), new Entry(target, now + TTL_MILLIS));
        if (ENTRIES.size() > 256) {
            evictExpired(now);
            // 过期清理后仍超限：全为有效期内的条目，强制淘汰最近过期的。
            while (ENTRIES.size() > 256) {
                var oldest = ENTRIES.entrySet().stream()
                        .min(Map.Entry.comparingByValue(
                                Comparator.comparingLong(Entry::expiry)
                        ))
                        .orElse(null);
                if (oldest == null) {
                    break;
                }

                ENTRIES.remove(oldest.getKey());
            }
        }
    }

    private static void evictExpired(long now) {
        ENTRIES.entrySet().removeIf(stringEntryEntry ->
                stringEntryEntry.getValue().expiry() - now <= 0
        );
    }

    /** 清除全部缓存（测试用）。 */
    public static void clear() {
        ENTRIES.clear();
    }

    private record Entry(
            TransportTarget target,
            long expiry
    ) {

    }

}
