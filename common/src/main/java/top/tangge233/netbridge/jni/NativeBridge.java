package top.tangge233.netbridge.jni;

import java.nio.ByteBuffer;

/**
 * JNI 桥到 net-bridge-native（Rust）。
 *
 * <p>Rust 侧持有 tokio runtime 与传输端点，按传输类别分派 QUIC/KCP 实现；
 * Java 通过同步批量接口读写字节。Rust 侧错误即时输出到 stderr
 * （由启动器重定向进 logs/latest.log）。
 *
 * <p>传输类别标签：{@link #KIND_QUIC} = 0、{@link #KIND_KCP} = 1。
 */
public final class NativeBridge {
    /** Java 侧期望的 native ABI 版本；加载后经 {@link NativeLoader} 校验，不匹配即拒绝。 */
    public static final String EXPECTED_ABI_VERSION = "0.2.0";

    /** 传输类别标签：QUIC（quinn-plaintext 明文握手）。 */
    public static final int KIND_QUIC = 0;
    /** 传输类别标签：KCP（tokio-kcp + FEC 帧化）。 */
    public static final int KIND_KCP = 1;

    private NativeBridge() {}

    /** 返回 Rust 侧 ABI 版本字符串。 */
    public static native String version();

    // ---- 服务端 ----

    /**
     * 启动指定传输的 acceptor（端口 0 表示由系统分配）。返回服务端句柄；失败返回 -1。
     *
     * @param kind           传输类别：{@link #KIND_QUIC} 或 {@link #KIND_KCP}
     * @param maxConnections 该实例活跃连接上限，超限的新连接被静默丢弃
     * @param bindHost       监听地址 IP 字面量；null/空 = 默认所有网卡
     * @param kcpProfile     KCP 参数档（"balance"/"aggressive"，兼容别名 "balanced"）；
     *                       QUIC 忽略此参数，可传 null
     */
    public static native long startServer(
            int kind, int port, int maxConnections, String bindHost, String kcpProfile);

    /** 查询服务端实际绑定端口；未知返回 -1。 */
    public static native int serverPort(long server);

    /** 查询服务端收养连接的对端地址（"ip:port"）；客户端连接或不存在返回 null。 */
    public static native String remoteAddress(long conn);

    /** 取回服务端尚未上报的新连接 id 列表（每次调用后不会重复返回）。 */
    public static native long[] acceptConnections(long server);

    /** 停止服务端并关闭其全部连接。 */
    public static native boolean stopServer(long server);

    // ---- 客户端 ----

    /**
     * 发起指定传输的连接（异步建立）。返回连接 id；立即失败返回 -1。
     *
     * @param kind       传输类别：{@link #KIND_QUIC} 或 {@link #KIND_KCP}
     * @param kcpProfile KCP 参数档；QUIC 忽略，可传 null
     */
    public static native long connect(int kind, String host, int port, String kcpProfile);

    /**
     * 查询连接状态码。
     *
     * @return {@link NativeConnState} 的 code；连接不存在返回 -1（UNKNOWN）
     */
    public static native int connectionState(long conn);

    /** 关闭连接并清理注册表条目。 */
    public static native boolean closeConnection(long conn);

    // ---- 数据 ----

    /**
     * 写入一段字节（批量桥）：取 {@code data[0..length)} 入队，
     * 调用方可复用暂存区避免每消息堆分配。
     *
     * @return 实际入队字节数：0 表示队列满/连接未就绪（调用方需缓冲重试，不可丢弃）；
     *         -1 表示连接不存在、已关闭，或 length 越界（&lt;0 或 &gt; data.length）
     */
    public static native int writeChunk(long conn, byte[] data, int length);

    /** 读取最多 maxBytes 字节；无数据返回空数组；连接不存在返回 null。 */
    public static native byte[] readChunk(long conn, int maxBytes);

    /**
     * 读取最多 maxBytes 字节写入直接缓冲区（{@link #readChunk} 的零分配
     * 变体）：native 从 {@code buffer} 基址绝对偏移 0 开始写入，不读取也
     * 不修改其 position/limit。调用方须保证 buffer 为直接缓冲且容量 ≥ maxBytes。
     *
     * @return 实际读取字节数：0 表示暂无数据；
     *         -1 表示连接不存在、已关闭或 buffer/maxBytes 参数非法
     */
    public static native int readChunkInto(long conn, ByteBuffer buffer, int maxBytes);
}
