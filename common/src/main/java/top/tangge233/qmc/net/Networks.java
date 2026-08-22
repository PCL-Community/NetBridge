package top.tangge233.qmc.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import top.tangge233.qmc.jni.QuicNative;

/**
 * 服务器列表 Ping 响应中 quic-mc 的传输能力识别模型（ADR-0002）。
 *
 * 数据模型与 wire 格式解耦：JSON 注入/解析见 {@link StatusNetworks}；
 * 顶层 networks 表为未来 KCP 等其他传输预留平级扩展位。
 */
public final class Networks {
    public static final String KEY_NETWORKS = "networks";
    public static final String KEY_QUIC = "quic";
    /** 显式 {@code false} 表示服务端声明 QUIC 不可用，客户端应视同未宣告。 */
    public static final String KEY_ENABLE = "enable";
    public static final String KEY_FEATURES = "features";
    public static final String KEY_PORT = "port";
    public static final String KEY_PROTOCOL = "protocol";
    public static final String PROTOCOL_V1 = "quic-mc/1";

    private final Map<String, QuicInfo> quicInfo;

    private Networks(Map<String, QuicInfo> quicInfo) {
        this.quicInfo = quicInfo == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(quicInfo));
    }

    public static Networks empty() {
        return new Networks(Map.of());
    }

    /** 构造一个声明 QUIC 能力的 Networks（简化便捷方法）。 */
    public static Networks withQuic(int port, String... features) {
        Map<String, QuicInfo> map = new LinkedHashMap<>();
        map.put(KEY_QUIC, new QuicInfo(port, features.length == 0 ? null : java.util.List.of(features)));
        return new Networks(map);
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
        private final java.util.List<String> features;
        private final String protocol;

        public QuicInfo(int port, java.util.List<String> features) {
            this(port, features, PROTOCOL_V1);
        }

        public QuicInfo(int port, java.util.List<String> features, String protocol) {
            this.port = port;
            this.features = features == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(features);
            this.protocol = protocol == null ? PROTOCOL_V1 : protocol;
        }

        public int port() { return port; }
        public java.util.List<String> features() { return features; }
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
