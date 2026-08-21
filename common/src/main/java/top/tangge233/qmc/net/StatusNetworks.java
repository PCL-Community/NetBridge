package top.tangge233.qmc.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;

/**
 * 将 quic-mc 的传输能力（networks）注入 Minecraft status JSON。
 *
 * 保持原字段不变，只在顶层追加 networks 对象（原版客户端忽略未知字段）。
 */
public final class StatusNetworks {
    private StatusNetworks() {}

    /**
     * 给服务器列表 Ping 响应 JSON 注入 networks 能力声明。
     *
     * @param statusJson 原版 MinecraftServer#getStatusJson 输出
     * @param quicPort   QUIC 端口（服务端 UDP acceptor 使用）
     * @param features   至少应包含 QuicNative.RAW_FEATURE
     * @return 带 networks 的 JSON 字符串；若 statusJson 非有效对象则原样返回
     */
    public static String addNetworks(String statusJson, int quicPort, String... features) {
        Objects.requireNonNull(statusJson, "statusJson");
        JsonObject root;
        try {
            root = JsonParser.parseString(statusJson).getAsJsonObject();
        } catch (RuntimeException e) {
            // 非有效 JSON 对象：原样返回，不阻断 status 编码。
            return statusJson;
        }
        if (root.has(Networks.KEY_NETWORKS)) {
            return statusJson;
        }
        JsonObject quic = new JsonObject();
        var featureArr = new com.google.gson.JsonArray();
        for (String f : features) {
            featureArr.add(f);
        }
        quic.add(Networks.KEY_FEATURES, featureArr);
        quic.addProperty(Networks.KEY_PORT, quicPort);
        quic.addProperty(Networks.KEY_PROTOCOL, Networks.PROTOCOL_V1);
        JsonObject networks = new JsonObject();
        networks.add(Networks.KEY_QUIC, quic);
        root.add(Networks.KEY_NETWORKS, networks);
        return root.toString();
    }

    /** 从 status JSON 中解析 networks 模型；缺失/无效时返回 empty。 */
    public static Networks parse(String statusJson) {
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (!root.has(Networks.KEY_NETWORKS)) {
                return Networks.empty();
            }
            JsonObject networks = root.getAsJsonObject(Networks.KEY_NETWORKS);
            if (!networks.has(Networks.KEY_QUIC)) {
                return Networks.empty();
            }
            JsonObject quic = networks.getAsJsonObject(Networks.KEY_QUIC);
            int port = quic.get(Networks.KEY_PORT).getAsInt();
            var featureArr = new java.util.ArrayList<String>();
            if (quic.has(Networks.KEY_FEATURES)) {
                for (var el : quic.getAsJsonArray(Networks.KEY_FEATURES)) {
                    featureArr.add(el.getAsString());
                }
            }
            String protocol = quic.has(Networks.KEY_PROTOCOL) ? quic.get(Networks.KEY_PROTOCOL).getAsString() : Networks.PROTOCOL_V1;
            return Networks.withQuic(port, featureArr.toArray(new String[0]));
        } catch (RuntimeException e) {
            return Networks.empty();
        }
    }
}
