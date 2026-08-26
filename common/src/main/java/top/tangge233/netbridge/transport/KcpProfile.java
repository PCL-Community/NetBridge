package top.tangge233.netbridge.transport;

import java.util.Locale;

/**
 * KCP 参数档（预设二档，不支持自定义）。
 *
 * <ul>
 *   <li>{@link #BALANCE}：nodelay=0/interval=40/resend=0/nc=0，带宽友好。</li>
 *   <li>{@link #AGGRESSIVE}：nodelay=1/interval=10/resend=2/nc=1，高丢包链路换延迟。</li>
 * </ul>
 */
public enum KcpProfile {
    BALANCE,
    AGGRESSIVE;

    /** 配置文件中的规范字符串。 */
    public String configValue() {
        return this == AGGRESSIVE ? "aggressive" : "balance";
    }

    /**
     * 解析配置串；规范名 {@code balance}/{@code aggressive}，
     * 历史别名 {@code balanced} 兼容接受。非法值返回 null（调用方告警回退默认）。
     */
    public static KcpProfile parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "balance", "balanced" -> BALANCE;
            case "aggressive" -> AGGRESSIVE;
            default -> null;
        };
    }
}
