package top.tangge233.qmc.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.QuicClient;

/**
 * 服务端 QUIC acceptor 生命周期管理（ADR-0001）。
 *
 * 由 mod 入口在 Minecraft 服务器启动/停止时调用；Ping 扩展通过
 * {@link #port()} 宣告真实 UDP 端口。
 */
public final class QuicServer {
    public static final Logger LOGGER = LoggerFactory.getLogger("qmc");

    /** 可选系统属性：覆盖 QUIC 监听端口（优先级高于配置文件）。 */
    public static final String PROP_QUIC_PORT = "qmc.quicPort";

    private static volatile long serverHandle = -1;
    private static volatile int port = -1;
    private static volatile LongConsumer connectionHandler;

    /**
     * 连接收养执行器（单线程串行）：handler 内的 channel 注册可能阻塞等待
     * 服务端 EventLoop，移出 accept 线程以免拖慢后续连接接纳；单线程同时
     * 保证收养顺序与连接到达顺序一致。daemon 线程随 JVM 退出。
     */
    private static final ExecutorService ADOPT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "qmc-quic-adopt");
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
     * （服务器 TCP 端口）仅作为最终回退值。
     */
    public static synchronized boolean start(int preferredPort) {
        if (serverHandle != -1) {
            return true;
        }
        NativeLoader.load();
        long handle = QuicNative.startServer(resolveListenPort(preferredPort));
        if (handle < 0) {
            LOGGER.warn("QUIC acceptor start failed: {}", QuicNative.lastError());
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
     * 解析 QUIC 监听端口（可选配置项）：优先级为系统属性 {@code qmc.quicPort} >
     * 配置目录下 {@code quic-mc/server.toml} 的 {@code port = <0-65535>}（0 表示
     * 自动分配）> 回退到服务器 TCP 端口。非法/越界值告警并忽略，不阻断启动。
     */
    private static int resolveListenPort(int tcpPort) {
        String sys = System.getProperty(PROP_QUIC_PORT);
        if (sys != null && !sys.isBlank()) {
            Integer parsed = parsePort(sys.trim(), "system property " + PROP_QUIC_PORT);
            if (parsed != null) {
                LOGGER.info("QUIC listen port {} from system property {}", parsed, PROP_QUIC_PORT);
                return parsed;
            }
        }
        Path configDir = QuicClient.configDir();
        if (configDir != null) {
            Path file = configDir.resolve("server.toml");
            try {
                if (Files.exists(file)) {
                    Long value = new Toml().read(file.toFile()).getLong("port");
                    if (value != null) {
                        int parsed = value.intValue();
                        if (parsed >= 0 && parsed <= 65535) {
                            LOGGER.info("QUIC listen port {} from {}", parsed, file);
                            return parsed;
                        }
                        LOGGER.warn("Invalid QUIC listen port {} from {}: out of range 0-65535", parsed, file);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to read QUIC listen port from {}: {}", file, e.toString());
            }
        }
        return tcpPort;
    }

    /** 解析并校验端口（0-65535，0 表示自动分配）；非法返回 null 并告警。 */
    private static Integer parsePort(String value, String source) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65535) {
                return port;
            }
            LOGGER.warn("Invalid QUIC listen port {} from {}: out of range 0-65535", value, source);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid QUIC listen port '{}' from {}: not a number", value, source);
        }
        return null;
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
        }, "qmc-quic-accept");
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
