package top.tangge233.netbridge.transport;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 客户端传输决策入口：模式 × 能力缓存 → 加速连接目标。
 *
 * <p>决策规则（顺序即优先级）：
 * <ol>
 *   <li>mode = tcp → 直接 TCP；</li>
 *   <li>成功缓存命中（TTL 内同传输成功建连）→ 直接复用缓存端点；</li>
 *   <li>目标传输未宣告 / 协议不支持 → 直接 TCP（不做其他加速传输的替代协商）；</li>
 *   <li>否则返回合成端点（host 缺省跟随 ping 目标地址）。</li>
 * </ol>
 */
public final class TransportSelector {

    /** 能力缓存上限：按使用热度淘汰（LRU），超出即逐出最久未命中项。 */
    private static final int MAX_CACHED_NETWORKS = 256;

    /**
     * Ping 解析出的能力缓存：access-order {@link LinkedHashMap} 保证 get/put 命中即移到队首，超限自动淘汰队尾（最久未用）。
     */
    private static final Map<String, NetworksAbility> NETWORKS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NetworksAbility> eldest) {
                    return size() > MAX_CACHED_NETWORKS;
                }
            }
    );

    private TransportSelector() {
    }

    /** 记录一次 Ping 解析出的能力（按解析后的 host:port 缓存）。 */
    public static void record(
            @Nullable InetSocketAddress address,
            @Nullable NetworksAbility networks
    ) {
        if (address == null || networks == null) {
            return;
        }
        NETWORKS.put(key(address), networks);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /**
     * 决策本次连接使用的传输。
     *
     * @return 加速目标；空表示直接走原版 TCP
     */
    public static Optional<TransportTarget> decide(@Nullable InetSocketAddress tcpAddress) {
        var mode = NetBridgeServices.clientSettings().current().mode();
        if (mode == TransportMode.TCP || tcpAddress == null) {
            return Optional.empty();
        }

        // 5 分钟内同一传输成功建连过：直接复用缓存端点，跳过宣告协商。
        var cached = FallbackTracker.lookup(tcpAddress);
        if (cached.isPresent() && cached.get().mode() == mode) {
            NetBridge.LOGGER.info("Transport for {}: {} (cached endpoint)", tcpAddress, mode);
            return cached;
        }

        var networks = networksFor(tcpAddress);
        var name = mode == TransportMode.QUIC
                ? NetworksAbility.KEY_QUIC
                : NetworksAbility.KEY_KCP;
        var entry = networks.entry(name);
        if (entry == null || !entry.usable()) {
            var reason = entry == null
                    ?
                    (
                            networks.entries().isEmpty()
                                    ? "server did not advertise"
                                    : "not advertised"
                    )
                    : "unsupported protocol or disabled";
            NetBridge.LOGGER.info(
                    "Transport for {}: TCP (mode={}, {})",
                    tcpAddress,
                    mode,
                    reason
            );
            return Optional.empty();
        }

        var host = entry.host() != null
                ? entry.host()
                : tcpAddress.getHostString();
        var endpoint = InetSocketAddress.createUnresolved(host, entry.port());
        return Optional.of(new TransportTarget(mode, endpoint));
    }

    /** 查询某地址的能力；未 Ping 过返回 empty。 */
    public static NetworksAbility networksFor(@Nullable InetSocketAddress address) {
        return address == null
                ? NetworksAbility.empty()
                : NETWORKS.getOrDefault(key(address), NetworksAbility.empty());
    }

}
