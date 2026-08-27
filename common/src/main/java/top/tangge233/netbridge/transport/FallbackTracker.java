package top.tangge233.netbridge.transport;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加速连接地址缓存：记录最近成功建立的加速（KCP/QUIC）连接目标。
 *
 * <p>命中期内同传输重连直接复用缓存端点，跳过宣告协商；换传输或过期
 * 后重新走完整宣告序列。失败不再写记忆——UDP 环境变化不应长期阻断
 * 某一传输，切换模式立即生效。
 */
public final class FallbackTracker {
    /** 记忆保留时长（毫秒）。 */
    public static final long TTL_MILLIS = 5 * 60 * 1000L;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private record Entry(TransportTarget target, long expiry) {}

    private FallbackTracker() {}

    /**
     * 该地址在 TTL 内是否成功建立过加速连接；顺带清理过期条目。
     *
     * @return 成功连接的传输目标；无记录或已过期返回 empty
     */
    public static Optional<TransportTarget> lookup(InetSocketAddress address) {
        if (address == null) {
            return Optional.empty();
        }
        String key = key(address);
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiry() - System.currentTimeMillis() <= 0) {
            ENTRIES.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.target());
    }

    /**
     * 记录一次成功建立的加速连接目标（按 host:port 键），刷新其 TTL。
     */
    public static void record(InetSocketAddress address, TransportTarget target) {
        if (address == null || target == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ENTRIES.put(key(address), new Entry(target, now + TTL_MILLIS));
        if (ENTRIES.size() > 256) {
            evictExpired(now);
            // 过期清理后仍超限：全为有效期内的条目，强制淘汰最近过期的。
            while (ENTRIES.size() > 256) {
                Map.Entry<String, Entry> oldest =
                        ENTRIES.entrySet().stream()
                                .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiry(), b.expiry())))
                                .orElse(null);
                if (oldest == null) {
                    break;
                }
                ENTRIES.remove(oldest.getKey());
            }
        }
    }

    /** 清除全部缓存（测试用）。 */
    public static void clear() {
        ENTRIES.clear();
    }

    private static void evictExpired(long now) {
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiry() - now <= 0) {
                it.remove();
            }
        }
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
