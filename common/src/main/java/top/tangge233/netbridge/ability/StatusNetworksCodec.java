package top.tangge233.netbridge.ability;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * networks 能力的 wire v2 编解码：注入与解析 Minecraft status JSON。
 *
 * <p>保持原字段不变，只在顶层追加/读取 networks 对象
 * （原版客户端忽略未知字段）。wire 上只出现解析后的具体端口。
 */
public final class StatusNetworksCodec {

    private StatusNetworksCodec() {
    }

    /**
     * 由服务端宣告条目构建 networks JSON 对象。
     *
     * @param entries 已解析的传输条目（host=null 的条目省略 host 字段）
     */
    public static JsonObject buildNetworks(Map<String, NetworksEntry> entries) {
        var networks = new JsonObject();
        entries.forEach((name, entry) -> {
            if (entry != null && name != null) {
                networks.add(name, entry.toJson(true));
            }
        });
        return networks;
    }

    /**
     * 给服务器列表 Ping 响应 JSON 注入 networks 能力声明。
     *
     * @param statusJson 原版 status 编码输出
     * @param networks   注入对象；无任何条目时不注入（保持原 JSON）
     *
     * @return 注入后的 JSON；statusJson 非有效对象或已含 networks 时原样返回
     */
    public static String addNetworks(
            String statusJson,
            @Nullable JsonObject networks
    ) {
        if (networks == null || networks.entrySet().isEmpty()) {
            return statusJson;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(statusJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return statusJson;
        }

        if (root.has(NetworksAbility.KEY_NETWORKS)) {
            return statusJson;
        }

        root.add(NetworksAbility.KEY_NETWORKS, networks);
        return root.toString();
    }

    /**
     * 从 status JSON 解析 networks 模型（客户端解码侧使用）。
     *
     * @return 解析结果；JSON 缺失/损坏时返回 empty，调用方视同服务端未加速
     */
    public static NetworksAbility parse(String statusJson) {
        try {
            var root = JsonParser.parseString(statusJson).getAsJsonObject();
            return parse(root);
        } catch (RuntimeException | StackOverflowError e) {
            // StackOverflowError：恶意深嵌套 JSON 会让 Gson 递归爆栈；
            // 吞掉降级为「未宣告」，不能让远程包杀死 netty 解码线程。
            return NetworksAbility.empty();
        }
    }

    /**
     * {@link #parse(String)} 的已解析形态：调用方已有 JsonElement 时复用， 避免同一份 status JSON 被解析两遍。
     */
    public static NetworksAbility parse(JsonElement parsed) {
        try {
            var root = parsed.getAsJsonObject();
            if (!root.has(NetworksAbility.KEY_NETWORKS)) {
                return NetworksAbility.empty();
            }

            var networks = root.getAsJsonObject(NetworksAbility.KEY_NETWORKS);
            Map<String, NetworksEntry> entries = new LinkedHashMap<>();
            networks.entrySet().stream()
                    .filter(e ->
                            e.getValue() != null && e.getValue().isJsonObject()
                    )
                    .forEach(e -> {
                        var entry = NetworksEntry.fromJson(
                                e.getValue().getAsJsonObject()
                        );
                        if (entry != null) {
                            entries.put(e.getKey(), entry);
                        }
                    });

            return entries.isEmpty()
                    ? NetworksAbility.empty()
                    : NetworksAbility.of(entries.values().toArray(new NetworksEntry[0]));
        } catch (RuntimeException | StackOverflowError e) {
            return NetworksAbility.empty();
        }
    }

}
