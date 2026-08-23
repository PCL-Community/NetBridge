package top.tangge233.qmc.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Objects;

/**
 * 将 quic-mc 的传输能力（networks）注入 Minecraft status JSON。
 *
 * 保持原字段不变，只在顶层追加 networks 对象（原版客户端忽略未知字段）。
 */
public final class StatusNetworks {
    private StatusNetworks() {}

    /**
     * 给服务器列表 Ping 响应 JSON 注入 networks 能力声明，quic 表写入
     * {@code enable/port/features/protocol} 四个字段。
     *
     * @param statusJson 原版 MinecraftServer#getStatusJson 输出
     * @param quicPort   QUIC 端口（acceptor 实际绑定的 UDP 端口）
     * @param features   至少应包含 QuicNative.RAW_FEATURE
     * @return 注入后的 JSON；statusJson 非有效对象或已含 networks 时原样返回
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
        if (root.has(NetworksAbility.KEY_NETWORKS)) {
            return statusJson;
        }
        JsonObject quic = new JsonObject();
        quic.addProperty(NetworksAbility.KEY_ENABLE, true);
        var featureArr = new JsonArray();
        for (String f : features) {
            featureArr.add(f);
        }
        quic.add(NetworksAbility.KEY_FEATURES, featureArr);
        quic.addProperty(NetworksAbility.KEY_PORT, quicPort);
        quic.addProperty(NetworksAbility.KEY_PROTOCOL, NetworksAbility.PROTOCOL_V1);
        JsonObject networks = new JsonObject();
        networks.add(NetworksAbility.KEY_QUIC, quic);
        root.add(NetworksAbility.KEY_NETWORKS, networks);
        return root.toString();
    }

    /**
     * 从 status JSON 解析 networks 模型（客户端解码侧使用）。
     *
     * @return 解析结果；JSON 缺失/损坏、缺 port 或 {@code enable} 显式为
     *         false 时返回 empty，调用方应视同服务端不支持 QUIC。
     */
    public static NetworksAbility parse(String statusJson) {
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            return parse(root);
        } catch (RuntimeException | StackOverflowError e) {
            // StackOverflowError：恶意构造的深嵌套 JSON 会让 Gson 递归爆栈，
            // 它是 Error 不走 RuntimeException 分支；吞掉降级为「未宣告」，
            // 不能让远程包杀死 netty 解码线程。
            return NetworksAbility.empty();
        }
    }

    /**
     * {@link #parse(String)} 的已解析形态：调用方已有 JsonElement 时复用，
     * 避免同一份 status JSON 被解析两遍（解码路径每 ping 省一次全量解析）。
     */
    public static NetworksAbility parse(JsonElement parsed) {
        try {
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has(NetworksAbility.KEY_NETWORKS)) {
                return NetworksAbility.empty();
            }
            JsonObject networks = root.getAsJsonObject(NetworksAbility.KEY_NETWORKS);
            if (!networks.has(NetworksAbility.KEY_QUIC)) {
                return NetworksAbility.empty();
            }
            JsonObject quic = networks.getAsJsonObject(NetworksAbility.KEY_QUIC);
            // enable 显式为 false：服务端声明 QUIC 不可用，等同未宣告。
            if (quic.has(NetworksAbility.KEY_ENABLE) && !quic.get(NetworksAbility.KEY_ENABLE).getAsBoolean()) {
                return NetworksAbility.empty();
            }
            if (!quic.has(NetworksAbility.KEY_PORT)) {
                return NetworksAbility.empty();
            }
            int port = quic.get(NetworksAbility.KEY_PORT).getAsInt();
            var featureArr = new ArrayList<String>();
            if (quic.has(NetworksAbility.KEY_FEATURES)) {
                for (var el : quic.getAsJsonArray(NetworksAbility.KEY_FEATURES)) {
                    featureArr.add(el.getAsString());
                }
            }
            String protocol = quic.has(NetworksAbility.KEY_PROTOCOL) ? quic.get(NetworksAbility.KEY_PROTOCOL).getAsString() : NetworksAbility.PROTOCOL_V1;
            return NetworksAbility.withQuic(port, featureArr.toArray(new String[0]));
        } catch (RuntimeException | StackOverflowError e) {
            return NetworksAbility.empty();
        }
    }
}
