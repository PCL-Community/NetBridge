package top.tangge233.netbridge.transport;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级记忆：按服务器地址记录近期发生过 TCP 回退的事实。
 *
 * <p>命中期内重连直接走 TCP、跳过全部加速尝试（UDP 被掐断的网络环境
 * 不会在几分钟内自愈）；TTL 过期后重新执行完整尝试序列。条目在命中
 * 或记录时惰性清理，无后台线程。
 */
public final class FallbackTracker {
    /** 记忆保留时长（毫秒）。 */
    public static final long TTL_MILLIS = 5 * 60 * 1000L;

    private static final Map<String, Long> EXPIRIES = new ConcurrentHashMap<>();

    private FallbackTracker() {}

    /**
     * 该地址是否处于降级记忆期内；顺带清理过期与陈旧条目。
     */
    public static boolean isTracked(InetSocketAddress address) {
        if (address == null) {
            return false;
        }
        String key = key(address);
        Long expiry = EXPIRIES.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry - System.currentTimeMillis() <= 0) {
            EXPIRIES.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 记录一次降级事件（按 host:port 键），刷新其 TTL。
     */
    public static void mark(InetSocketAddress address) {
        if (address == null) {
            return;
        }
        long now = System.currentTimeMillis();
        EXPIRIES.put(key(address), now + TTL_MILLIS);
        if (EXPIRIES.size() > 256) {
            evictExpired(now);
            // 过期清理后仍超限：全为有效期内的条目，强制淘汰最近过期的。
            while (EXPIRIES.size() > 256) {
                Map.Entry<String, Long> oldest =
                        EXPIRIES.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
                if (oldest == null) {
                    break;
                }
                EXPIRIES.remove(oldest.getKey());
            }
        }
    }

    /** 清除全部记忆（测试用）。 */
    public static void clear() {
        EXPIRIES.clear();
    }

    private static void evictExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = EXPIRIES.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() - now <= 0) {
                it.remove();
            }
        }
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
