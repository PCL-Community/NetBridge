package top.tangge233.netbridge.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.tangge233.netbridge.jni.QuicNative;

/**
 * 服务器列表 Ping 响应中 net-bridge 的传输能力识别模型（ADR-0002）。
 *
 * 数据模型与 wire 格式解耦：JSON 注入/解析见 {@link StatusNetworks}；
 * 顶层 networks 表为未来 KCP 等其他传输预留平级扩展位。
 */
public final class NetworksAbility {
    public static final String KEY_NETWORKS = "networks";
    public static final String KEY_QUIC = "quic";
    /** 显式 {@code false} 表示服务端声明 QUIC 不可用，客户端应视同未宣告。 */
    public static final String KEY_ENABLE = "enable";
    public static final String KEY_FEATURES = "features";
    public static final String KEY_PORT = "port";
    public static final String KEY_PROTOCOL = "protocol";
    public static final String PROTOCOL_V1 = "net-bridge/1";

    private final Map<String, QuicInfo> quicInfo;

    /** 共享空实例：本类不可变，empty() 高频 miss 路径免重复分配。 */
    private static final NetworksAbility EMPTY = new NetworksAbility(Map.of());

    private NetworksAbility(Map<String, QuicInfo> quicInfo) {
        this.quicInfo = quicInfo == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(quicInfo));
    }

    public static NetworksAbility empty() {
        return EMPTY;
    }

    /** 构造一个声明 QUIC 能力的 NetworksAbility（简化便捷方法）。 */
    public static NetworksAbility withQuic(int port, String... features) {
        Map<String, QuicInfo> map = new LinkedHashMap<>();
        map.put(KEY_QUIC, new QuicInfo(port, features.length == 0 ? null : List.of(features)));
        return new NetworksAbility(map);
    }

    public Map<String, QuicInfo> quicInfo() {
        return quicInfo;
    }

    public QuicInfo quic() {
        return quicInfo.get(KEY_QUIC);
    }

    public boolean supportsQuicRaw() {
        QuicInfo q = quic();
        return q != null && q.features().contains(QuicNative.RAW_FEATURE);
    }

    /** QUIC 能力信息。 */
    public static final class QuicInfo {
        private final int port;
        private final List<String> features;
        private final String protocol;

        public QuicInfo(int port, List<String> features) {
            this(port, features, PROTOCOL_V1);
        }

        public QuicInfo(int port, List<String> features, String protocol) {
            this.port = port;
            this.features = features == null
                    ? List.of()
                    : List.copyOf(features);
            this.protocol = protocol == null ? PROTOCOL_V1 : protocol;
        }

        public int port() { return port; }
        public List<String> features() { return features; }
        public String protocol() { return protocol; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof QuicInfo other)) return false;
            return port == other.port
                    && features.equals(other.features)
                    && Objects.equals(protocol, other.protocol);
        }

        @Override
        public int hashCode() {
            return Objects.hash(port, features, protocol);
        }

        @Override
        public String toString() {
            return "QuicInfo{port=" + port + ", features=" + features + ", protocol='" + protocol + "'}";
        }
    }
}
