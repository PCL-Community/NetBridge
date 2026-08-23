package top.tangge233.qmc.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
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

    /** 服务端活跃连接数默认上限（配置项 {@code max_connection} 可覆盖）。 */
    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    /** 默认监听端口语义值：跟随 Minecraft TCP 端口。 */
    public static final int DEFAULT_PORT = -1;

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
     * （服务器 TCP 端口）作为 -1（跟随 TCP 端口）与非法值的落点。
     */
    public static synchronized boolean start(int preferredPort) {
        if (serverHandle != -1) {
            return true;
        }
        NativeLoader.load();
        ServerConfig config = loadServerConfig();
        long handle = QuicNative.startServer(
                resolveListenPort(config.port(), preferredPort),
                resolveMaxConnections(config.maxConnection()));
        if (handle < 0) {
            LOGGER.warn("QUIC acceptor start failed (see qmc-native log)");
            return false;
        }
        int actual = QuicNative.serverPort(handle);
        serverHandle = handle;
        port = actual;
        startAcceptLoop(handle);
        LOGGER.info("QUIC acceptor listening on udp/{}", actual);
        return true;
    }

    /** {@code server.toml} 解析结果；null 字段表示未配置/非法，走内置默认。 */
    record ServerConfig(Integer port, Integer maxConnection) {}

    /**
     * 读取 {@code quic-mc/server.toml}；文件不存在时从 jar 内置模板
     * {@code server-default.toml} 自动生成（注释即文档，可直接编辑），
     * 存在则原样读取不回写。解析失败告警并返回空配置（全部走内置
     * 默认），不阻断启动。
     */
    static ServerConfig loadServerConfig() {
        Path dir = QuicClient.configDir();
        if (dir == null) {
            return new ServerConfig(null, null);
        }
        Path file = dir.resolve("server.toml");
        try {
            Files.createDirectories(dir);
            CommentedFileConfig config = CommentedFileConfig.builder(file)
                    .defaultResource("/quic-mc/server-default.toml")
                    .build();
            config.load();
            Integer port = config.get("port");
            Integer max = config.get("max_connection");
            config.close();
            return new ServerConfig(port, max);
        } catch (Exception e) {
            LOGGER.warn("Failed to load QUIC server config {}: {}", file, e.toString());
            return new ServerConfig(null, null);
        }
    }

    /**
     * 解析 QUIC 监听端口：优先级为系统属性 {@code qmc.quicPort} >
     * {@code server.toml} 的 {@code port}。端口语义：
     * {@code -1} 跟随 Minecraft TCP 端口；{@code 0} 启动时随机分配；
     * 1-65535 直接使用；其余非法值告警并按 -1（跟随 TCP 端口）处理。
     */
    private static int resolveListenPort(Integer configuredPort, int tcpPort) {
        String sys = System.getProperty(PROP_QUIC_PORT);
        if (sys != null && !sys.isBlank()) {
            Integer parsed = parsePort(sys.trim(), "system property " + PROP_QUIC_PORT);
            if (parsed != null) {
                return applyPortSemantics(parsed, tcpPort, PROP_QUIC_PORT);
            }
        }
        if (configuredPort != null) {
            int parsed = configuredPort;
            if (parsed >= -1 && parsed <= 65535) {
                return applyPortSemantics(parsed, tcpPort, "server.toml");
            }
            LOGGER.warn("Invalid QUIC listen port {} from server.toml: not -1/0/1-65535", parsed);
        }
        LOGGER.info("QUIC listen port unset; following Minecraft TCP port {}", tcpPort);
        return tcpPort;
    }

    /** 应用端口语义：-1 跟随 TCP 端口，0 随机，1-65535 原样使用。 */
    private static int applyPortSemantics(int parsed, int tcpPort, String source) {
        if (parsed == -1) {
            LOGGER.info("QUIC listen port -1 from {}: following Minecraft TCP port {}", source, tcpPort);
            return tcpPort;
        }
        LOGGER.info("QUIC listen port {} from {}", parsed, source);
        return parsed;
    }

    /** 解析并校验端口（-1 跟随 TCP、0 自动分配、1-65535 固定）；非法返回 null 并告警。 */
    private static Integer parsePort(String value, String source) {
        try {
            int port = Integer.parseInt(value);
            if (port >= -1 && port <= 65535) {
                return port;
            }
            LOGGER.warn("Invalid QUIC listen port {} from {}: not -1/0/1-65535", value, source);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid QUIC listen port '{}' from {}: not a number", value, source);
        }
        return null;
    }

    /**
     * 解析服务端连接上限：{@code server.toml} 的 {@code max_connection}，
     * 默认 {@value #DEFAULT_MAX_CONNECTIONS}；非法值（&lt;1）告警并回退默认。
     */
    private static int resolveMaxConnections(Integer configured) {
        if (configured != null) {
            if (configured >= 1) {
                LOGGER.info("QUIC max connections {} from server.toml", configured);
                return configured;
            }
            LOGGER.warn("Invalid QUIC max connections {} from server.toml: must be >= 1", configured);
        }
        return DEFAULT_MAX_CONNECTIONS;
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
