package top.tangge233.netbridge.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.jni.QuicNative;

/**
 * 服务端 QUIC acceptor 生命周期管理（ADR-0001）。
 *
 * 由 mod 入口在 Minecraft 服务器启动/停止时调用；Ping 扩展通过
 * {@link #port()} 宣告真实 UDP 端口。配置装载与参数解析见
 * {@link QuicServerConfig}。
 */
public final class QuicServer {
    public static final Logger LOGGER = LoggerFactory.getLogger("net-bridge");

    private static volatile long serverHandle = -1;
    private static volatile int port = -1;
    private static volatile LongConsumer connectionHandler;

    /**
     * 连接收养执行器（单线程串行）：handler 内的 channel 注册可能阻塞等待
     * 服务端 EventLoop，移出 accept 线程以免拖慢后续连接接纳；单线程同时
     * 保证收养顺序与连接到达顺序一致。daemon 线程随 JVM 退出。
     */
    private static final ExecutorService ADOPT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "net-bridge-quic-adopt");
        thread.setDaemon(true);
        return thread;
    });

    private QuicServer() {}

    /**
     * 注册新 QUIC 连接处理器（mod 层在 start 前调用）：acceptor 收到新连接时
     * 以连接 id 回调，由 mod 层收养并接入 Minecraft 协议管线。
     * 未注册时新连接会被直接关闭，避免字节流无人消费导致客户端挂起。
     */
    public static void setConnectionHandler(LongConsumer handler) {
        connectionHandler = handler;
    }

    /**
     * 启动 QUIC acceptor；已运行时幂等返回 true，失败记录日志并返回 false。
     *
     * 监听端口按 {@link #resolveListenPort} 的优先级解析，preferredPort
     * （服务器 TCP 端口）作为 -1（跟随 TCP 端口）与非法值的落点。
     */
    public static synchronized boolean start(int preferredPort) {
        return start(preferredPort, null);
    }

    /**
     * 启动 QUIC acceptor 并指定监听地址（{@code bindIp} 为 Minecraft
     * {@code server-ip}；空则回退 server.toml 的 {@code bind}，再回退全部网卡）。
     * 绑定地址仅限 IP 字面量，避免 QUIC 端口暴露到非预期网卡。
     */
    public static synchronized boolean start(int preferredPort, String bindIp) {
        if (serverHandle != -1) {
            return true;
        }
        NativeLoader.load();
        QuicServerConfig.ServerConfig config = QuicServerConfig.load();
        long handle = QuicNative.startServer(
                QuicServerConfig.resolveListenPort(config.port(), preferredPort),
                QuicServerConfig.resolveMaxConnections(config.maxConnection()),
                QuicServerConfig.resolveBind(config.bind(), bindIp));
        if (handle < 0) {
            LOGGER.warn("QUIC acceptor start failed (see net-bridge-native log)");
            return false;
        }
        int actual = QuicNative.serverPort(handle);
        serverHandle = handle;
        port = actual;
        startAcceptLoop(handle);
        LOGGER.info("QUIC acceptor listening on udp/{}", actual);
        return true;
    }

    /**
     * 后台轮询 acceptConnections，把新连接交给 mod 层处理器。
     *
     * 以 {@code serverHandle == handle} 为唯一存活条件：stop() 置空 handle
     * 后线程自行退出，重启产生的新线程与旧线程靠 handle 不匹配互斥，无需
     * 显式取消；配合 start() 的 synchronized + 幂等检查保证每个 handle
     * 只有一个 accept 线程。
     */
    private static void startAcceptLoop(long handle) {
        Thread thread = new Thread(() -> {
            try {
                while (serverHandle == handle) {
                    long[] ids = QuicNative.acceptConnections(handle);
                    LongConsumer handler = connectionHandler;
                    for (long id : ids) {
                        if (handler != null) {
                            ADOPT_EXECUTOR.execute(() -> {
                                try {
                                    handler.accept(id);
                                } catch (Throwable t) {
                                    LOGGER.warn("QUIC connection handler failed for conn {}", id, t);
                                    QuicNative.closeConnection(id);
                                }
                            });
                        } else {
                            LOGGER.warn("QUIC connection {} rejected: no connection handler registered", id);
                            QuicNative.closeConnection(id);
                        }
                    }
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "net-bridge-quic-accept");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止 QUIC acceptor 并关闭其全部连接；未运行时幂等。 */
    public static synchronized void stop() {
        if (serverHandle == -1) {
            return;
        }
        QuicNative.stopServer(serverHandle);
        serverHandle = -1;
        port = -1;
        LOGGER.info("QUIC acceptor stopped");
    }

    public static boolean isRunning() {
        return serverHandle != -1;
    }

    /** 当前 acceptor 实际端口；未运行时返回 -1。 */
    public static int port() {
        return port;
    }
}
