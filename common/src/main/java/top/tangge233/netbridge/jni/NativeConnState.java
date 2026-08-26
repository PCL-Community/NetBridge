package top.tangge233.netbridge.jni;

/**
 * 连接状态码，与 Rust bridge 常量一致。
 *
 * <p>状态迁移语义：
 * <ul>
 *   <li>{@link #CONNECTING} → {@link #CONNECTED}：QUIC 为明文握手完成；
 *       KCP 为首个入站数据帧送达（存活判定）。</li>
 *   <li>→ {@link #CLOSED}：本端或对端正常关闭。</li>
 *   <li>→ {@link #FAILED}：握手或传输错误；连接随后从注册表移除，
 *       查询转为 {@link #UNKNOWN}。</li>
 * </ul>
 */
public enum NativeConnState {
    CONNECTING(0),
    CONNECTED(1),
    CLOSED(2),
    FAILED(3),
    /** 连接不存在（已移除或从未建立）。 */
    UNKNOWN(-1);

    /** native 层状态码。 */
    public final int code;

    NativeConnState(int code) {
        this.code = code;
    }

    /**
     * native 状态码转枚举；未知码映射为 {@link #UNKNOWN}（前向兼容）。
     */
    public static NativeConnState fromCode(int code) {
        for (NativeConnState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
