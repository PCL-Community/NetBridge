package top.tangge233.netbridge.ability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器列表 Ping 响应中 net-bridge 的传输能力模型。
 *
 * <p>不可变。数据模型与 wire 格式解耦：JSON 注入/解析见
 * {@link StatusNetworksCodec}；顶层 networks 表为平级扩展位，
 * 每个已知传输（quic/kcp）一个条目，未知条目原样忽略。
 */
public final class NetworksAbility {
    public static final String KEY_NETWORKS = "networks";
    public static final String KEY_QUIC = "quic";
    public static final String KEY_KCP = "kcp";

    private final Map<String, NetworksEntry> entries;

    /** 共享空实例：本类不可变，empty() 高频 miss 路径免重复分配。 */
    private static final NetworksAbility EMPTY = new NetworksAbility(Map.of());

    private NetworksAbility(Map<String, NetworksEntry> entries) {
        this.entries =
                entries == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static NetworksAbility empty() {
        return EMPTY;
    }

    /** 以给定条目构造能力模型（null、未知协议的条目被丢弃）。 */
    public static NetworksAbility of(NetworksEntry... entries) {
        Map<String, NetworksEntry> map = new LinkedHashMap<>();
        for (NetworksEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            String name = nameOf(entry);
            if (name != null) {
                map.put(name, entry);
            }
        }
        return new NetworksAbility(map);
    }

    /** 按协议版本串反查条目键名；未知协议返回 null（调用方丢弃）。 */
    private static String nameOf(NetworksEntry entry) {
        if (TransportProtocol.QUIC_V1.equals(entry.protocol())) {
            return KEY_QUIC;
        }
        if (TransportProtocol.KCP_V1.equals(entry.protocol())) {
            return KEY_KCP;
        }
        return null;
    }

    /** 全部条目（含未启用/不支持的，供诊断展示）。 */
    public Map<String, NetworksEntry> entries() {
        return entries;
    }

    /**
     * 按传输名取条目（{@value #KEY_QUIC} / {@value #KEY_KCP}）；未宣告返回 null。
     */
    public NetworksEntry entry(String name) {
        return entries.get(name);
    }

    /**
     * 该传输名是否可用：已宣告且 {@link NetworksEntry#usable()}。
     */
    public boolean usable(String name) {
        NetworksEntry entry = entries.get(name);
        return entry != null && entry.usable();
    }

    /** 是否存在任一可用的加速传输（服务器列表 tooltip 标注用）。 */
    public boolean hasUsableAccelerated() {
        return usable(KEY_QUIC) || usable(KEY_KCP);
    }
}
