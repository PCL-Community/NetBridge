package top.tangge233.netbridge.ability;

import com.google.gson.JsonObject;

import org.jspecify.annotations.Nullable;

/**
 * networks 能力对象中单个传输条目：{@code {enable, host, port, protocol}}。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code enable}：wire 缺失视为 false（服务端显式声明不可用等同未宣告）。</li>
 *   <li>{@code host}：null/缺失 = 客户端跟随 ping 目标的服务器地址；
 *       非 null 为具体地址（IP 或可解析主机名）。</li>
 *   <li>{@code port}：必填且恒为解析后的具体端口（1..=65535），
 *       服务端配置的 -1/0 绝不出现在 wire 上；缺失或非法 → 条目无效。</li>
 *   <li>{@code protocol}：版本串，经 {@link TransportProtocol#isSupported} 精确比对。</li>
 * </ul>
 */
public record NetworksEntry(
        boolean enable,
        @Nullable String host,
        int port,
        @Nullable String protocol
) {

    public static final String KEY_ENABLE = "enable";
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_PROTOCOL = "protocol";

    /**
     * 从 JSON 对象解析条目。
     *
     * @return 解析结果；结构非法（缺 port/port 越界）返回 null，调用方应丢弃该条目
     */
    public static @Nullable NetworksEntry fromJson(@Nullable JsonObject object) {
        if (object == null || !object.has(KEY_PORT)) {
            return null;
        }

        try {
            var port = object.get(KEY_PORT).getAsInt();
            if (port < 1 || port > 65535) {
                return null;
            }

            var enable = object.has(KEY_ENABLE)
                    && !object.get(KEY_ENABLE).isJsonNull()
                    && object.get(KEY_ENABLE).getAsBoolean();
            var host = object.has(KEY_HOST) && !object.get(KEY_HOST).isJsonNull()
                    ? object.get(KEY_HOST).getAsString()
                    : null;
            var protocol = object.has(KEY_PROTOCOL) && !object.get(KEY_PROTOCOL).isJsonNull()
                    ? object.get(KEY_PROTOCOL).getAsString()
                    : null;
            return new NetworksEntry(
                    enable,
                    host,
                    port,
                    protocol
            );
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /**
     * 序列化为 wire JSON 对象。
     *
     * @param includeHost false 时省略 host 字段（语义 = 跟随服务器地址）
     */
    public JsonObject toJson(boolean includeHost) {
        var object = new JsonObject();
        object.addProperty(KEY_ENABLE, enable);
        if (includeHost && host != null && !host.isBlank()) {
            object.addProperty(KEY_HOST, host);
        }
        object.addProperty(KEY_PORT, port);
        if (protocol != null) {
            object.addProperty(KEY_PROTOCOL, protocol);
        }
        return object;
    }

    /**
     * 该条目对客户端是否可用：已启用、端口在 1..=65535、protocol 在支持集内。
     */
    public boolean usable() {
        return enable
                && port >= 1
                && port <= 65535
                && TransportProtocol.isSupported(protocol);
    }

}
