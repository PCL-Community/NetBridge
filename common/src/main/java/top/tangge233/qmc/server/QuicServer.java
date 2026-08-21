package top.tangge233.qmc.server;

import java.util.logging.Logger;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

/**
 * 服务端 QUIC acceptor 生命周期管理（ADR-0001）。
 *
 * 由 mod 入口在 Minecraft 服务器启动/停止时调用；Ping 扩展通过
 * {@link #port()} 宣告真实 UDP 端口。
 */
public final class QuicServer {
    public static final Logger LOGGER = Logger.getLogger("qmc.server");

    private static volatile long serverHandle = -1;
    private static volatile int port = -1;

    private QuicServer() {}

    /** 启动 QUIC acceptor；已运行时幂等返回 true。失败记录日志并返回 false。 */
    public static synchronized boolean start(int preferredPort) {
        if (serverHandle != -1) {
            return true;
        }
        NativeLoader.load();
        long handle = QuicNative.startServer(preferredPort);
        if (handle < 0) {
            LOGGER.warning("QUIC acceptor start failed: " + QuicNative.lastError());
            return false;
        }
        int actual = QuicNative.serverPort(handle);
        serverHandle = handle;
        port = actual;
        LOGGER.info("QUIC acceptor listening on udp/" + actual);
        return true;
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
