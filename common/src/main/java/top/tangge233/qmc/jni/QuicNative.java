package top.tangge233.qmc.jni;

import java.nio.ByteBuffer;

/**
 * JNI 桥到 qmc-native（Rust）。
 *
 * Rust 侧（quinn-plaintext）持有 UDP endpoint 与 tokio runtime；
 * Java 通过同步批量接口读写字节（ADR-0001 JNI 桥）。
 * Rust 侧错误即时输出到 stderr（由启动器重定向进 logs/latest.log）。
 */
public final class QuicNative {
    public static final String RAW_FEATURE = "quic-raw";

    /** 连接状态码，与 Rust bridge 常量一致（见 QuicConnectionState）。 */
    public static final int STATE_CONNECTING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_CLOSED = 2;
    public static final int STATE_FAILED = 3;
    public static final int STATE_UNKNOWN = -1;

    private QuicNative() {}

    /** 返回 Rust 侧 ABI 版本字符串。 */
    public static native String version();

    /** 返回 QUIC 明文管道特性标记（quic-raw）。 */
    public static native String rawFeature();

    // ---- 服务端 ----

    /**
     * 启动 QUIC acceptor（端口 0 表示由系统分配）。返回服务端句柄；失败返回 -1。
     *
     * @param maxConnections 服务端活跃连接上限，超限的新连接被拒绝
     */
    public static native long startServer(int port, int maxConnections);

    /** 查询服务端实际绑定端口；未知返回 -1。 */
    public static native int serverPort(long server);

    /** 取回服务端尚未上报的新连接 id 列表（每次调用后不会重复返回）。 */
    public static native long[] acceptConnections(long server);

    /** 停止服务端并关闭其全部连接。 */
    public static native boolean stopServer(long server);

    // ---- 客户端 ----

    /** 发起 QUIC 连接（异步握手）。返回连接 id；立即失败返回 -1。 */
    public static native long connect(String host, int port);

    /** 查询连接状态码（QuicConnectionState 常量）。 */
    public static native int connectionState(long conn);

    /** 关闭连接。 */
    public static native boolean closeConnection(long conn);

    // ---- 数据 ----

    /**
     * 写入一段明文帧字节（批量桥）：取 {@code data[0..length)} 入队，
     * 调用方可复用暂存区避免每消息堆分配。
     *
     * @return 实际入队字节数：0 表示队列满/连接未就绪（调用方需缓冲重试，不可丢弃）；
     *         -1 表示连接不存在、已关闭，或 length 越界（&lt;0 或 &gt; data.length）。
     */
    public static native int writeChunk(long conn, byte[] data, int length);

    /** 读取最多 maxBytes 字节；无数据返回空数组；连接不存在返回 null。 */
    public static native byte[] readChunk(long conn, int maxBytes);

    /**
     * 读取最多 maxBytes 字节写入直接缓冲区（GC 友好路径，避免每次调用
     * 分配新 byte[]）：native 从 {@code buffer} 基址绝对偏移 0 开始写入，
     * 不读取也不修改其 position/limit。调用方须保证 buffer 为直接缓冲且
     * 容量 ≥ maxBytes。
     *
     * @return 实际读取字节数：0 表示暂无数据；
     *         -1 表示连接不存在、已关闭或 buffer/maxBytes 参数非法。
     */
    public static native int readChunkInto(long conn, ByteBuffer buffer, int maxBytes);
}
