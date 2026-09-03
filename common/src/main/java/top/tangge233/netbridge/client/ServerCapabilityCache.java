package top.tangge233.netbridge.client;

import top.tangge233.netbridge.ability.NetworksAbility;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Ping 解析能力实例缓存（LRU）。
 */
public final class ServerCapabilityCache {

    private static final int MAX_ENTRIES = 256;

    private final Map<String, NetworksAbility> networks = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NetworksAbility> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    public void record(
            @Nullable InetSocketAddress address,
            @Nullable NetworksAbility ability
    ) {
        if (address == null || ability == null) {
            return;
        }
        networks.put(key(address), ability);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    public NetworksAbility get(@Nullable InetSocketAddress address) {
        return address == null
                ? NetworksAbility.empty()
                : networks.getOrDefault(
                        key(address),
                        NetworksAbility.empty()
                );
    }

    public void clear() {
        networks.clear();
    }

}
