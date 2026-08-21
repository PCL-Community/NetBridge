package top.tangge233.qmc.server;

import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

/**
 * 服务端 QUIC acceptor 生命周期管理（ADR-0001）。
 *
 * 由 mod 入口在 Minecraft 服务器启动/停止时调用；Ping 扩展通过
 * {@link #port()} 宣告真实 UDP 端口。
 */
public final class QuicServer {
    public static final Logger LOGGER = LoggerFactory.getLogger("qmc");

    private static volatile long serverHandle = -1;
    private static volatile int port = -1;
    private static volatile LongConsumer connectionHandler;

    private QuicServer() {}

    /**
     * 注册新 QUIC 连接处理器（mod 层在 start 前调用）：acceptor 收到新连接时
     * 以连接 id 回调，由 mod 层收养并接入 Minecraft 协议管线。
     * 未注册时新连接会被直接关闭，避免字节流无人消费导致客户端挂起。
     */
    public static void setConnectionHandler(LongConsumer handler) {
        connectionHandler = handler;
    }

    /** 启动 QUIC acceptor；已运行时幂等返回 true。失败记录日志并返回 false。 */
    public static synchronized boolean start(int preferredPort) {
        if (serverHandle != -1) {
            return true;
        }
        NativeLoader.load();
        long handle = QuicNative.startServer(preferredPort);
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
     * 后台轮询 acceptConnections，把新连接交给 mod 层处理器。
     *
     * 线程以 {@code serverHandle == handle} 为唯一存活条件：stop() 置空
     * handle 后线程自行退出；stop 后重新 start 会启动新线程，旧线程因
     * handle 不匹配退出，无需显式取消。start 由 synchronized + 幂等检查
     * 保护，每个 handle 只会启动一个 accept 线程。
     */
    private static void startAcceptLoop(long handle) {
        Thread thread = new Thread(() -> {
            try {
                while (serverHandle == handle) {
                    long[] ids = QuicNative.acceptConnections(handle);
                    LongConsumer handler = connectionHandler;
                    for (long id : ids) {
                        if (handler != null) {
                            try {
                                handler.accept(id);
                            } catch (Throwable t) {
                                LOGGER.warn("QUIC connection handler failed for conn {}", id, t);
                                QuicNative.closeConnection(id);
                            }
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
