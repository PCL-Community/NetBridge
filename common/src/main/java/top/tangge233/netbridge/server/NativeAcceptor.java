package top.tangge233.netbridge.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.ability.TransportProtocol;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeLoader;

/**
 * 服务端 acceptor 生命周期：并行管理 QUIC 与 KCP 两个传输实例。
 *
 * <p>端口语义（每段独立解析）：
 * <ul>
 *   <li>-1 跟随 Minecraft TCP 端口（kcp 为 MC 端口 +1）；</li>
 *   <li>0 系统随机分配，实际端口启动后日志输出；</li>
 *   <li>越界或 bind 失败 → 记错误日志并禁用该传输，不影响另一传输与 TCP 主服务。</li>
 * </ul>
 *
 * <p>Ping 宣告恒为解析后的具体端口；-1/0 绝不出现在 wire 上。
 */
public final class NativeAcceptor {
    /** 可选系统属性：覆盖 QUIC 监听端口（诊断/覆盖用，优先级高于配置文件）。 */
    public static final String PROP_QUIC_PORT = "netbridge.quicPort";

    private static volatile long quicHandle = -1;
    private static volatile long kcpHandle = -1;
    private static volatile NetworksAbility announcement = NetworksAbility.empty();
    private static volatile LongConsumer connectionHandler;

    /**
     * 连接收养执行器（单线程串行）：handler 内的 channel 注册可能阻塞等待
     * 服务端 EventLoop，移出 accept 线程以免拖慢后续连接接纳；单线程同时
     * 保证收养顺序与连接到达顺序一致。daemon 线程随 JVM 退出。
     */
    private static final ExecutorService ADOPT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "net-bridge-adopt");
        thread.setDaemon(true);
        return thread;
    });

    private NativeAcceptor() {}

    /**
     * 注册新连接处理器（mod 层在 start 前调用）：任一传输收到新连接时
     * 以连接 id 回调，由 mod 层收养并接入协议管线。未注册时新连接会被
     * 直接关闭，避免字节流无人消费导致客户端挂起。
     */
    public static void setConnectionHandler(LongConsumer handler) {
        connectionHandler = handler;
    }

    /**
     * 启动全部启用的传输；幂等（已运行时直接返回 true）。
     * 单个传输失败只禁用自身，不阻断其余传输。
     *
     * @param mcPort  Minecraft TCP 端口（port=-1 的落点）
     * @param mcBindIp server.properties 的 server-ip（配置未设 bind 时的回退）
     * @return 至少一个传输启动成功即 true
     */
    public static synchronized boolean start(int mcPort, String mcBindIp) {
        if (quicHandle != -1 || kcpHandle != -1) {
            return true;
        }
        if (!NativeLoader.load()) {
            NetBridge.LOGGER.warn(
                    "net-bridge native unavailable; accelerated transports disabled, only TCP will be served");
            return false;
        }
        ServerConfig config = ServerConfig.load();
        Map<String, NetworksEntry> entries = new LinkedHashMap<>();

        long qh = startTransport(
                NativeBridge.KIND_QUIC,
                "quic",
                config.quic(),
                resolveConfiguredQuicPort(config.quic().port()),
                mcPort,
                mcBindIp,
                null);
        quicHandle = qh;
        collectAnnouncement(entries, "quic", config.quic(), qh);

        long kh = startTransport(
                NativeBridge.KIND_KCP,
                "kcp",
                config.kcp(),
                config.kcp().port(),
                mcPort,
                mcBindIp,
                config.kcp().profile());
        kcpHandle = kh;
        collectAnnouncement(entries, "kcp", config.kcp(), kh);

        announcement = NetworksAbility.of(entries.values().toArray(new NetworksEntry[0]));
        boolean any = qh != -1 || kh != -1;
        if (!any) {
            NetBridge.LOGGER.warn("No accelerated transport started; only TCP will be served");
        }
        if (any) {
            startAcceptLoop();
        }
        return any;
    }

    /** 解析 QUIC 配置端口（含系统属性覆盖）；非法值告警回退配置默认。 */
    private static int resolveConfiguredQuicPort(Integer configured) {
        String sys = System.getProperty(PROP_QUIC_PORT);
        if (sys != null && !sys.isBlank()) {
            try {
                return Integer.parseInt(sys.trim());
            } catch (NumberFormatException e) {
                NetBridge.LOGGER.warn(
                        "Invalid {} '{}': not a number; using configured value", PROP_QUIC_PORT, sys);
            }
        }
        return configured == null ? -1 : configured;
    }

    /**
     * 应用"跟随"语义：配置 -1 时落到 Minecraft TCP 端口（kcp 为 +1），
     * 其余值原样返回；MC 端口本身不可用则报错并返回越界值以禁用该传输。
     */
    private static int applyFollowSemantics(String name, Integer configured, int mcPort) {
        int preferred = configured == null ? -1 : configured;
        if (preferred != -1) {
            return preferred;
        }
        int target = name.equals("kcp") ? mcPort + 1 : mcPort;
        if (target < 1 || target > 65535) {
            NetBridge.LOGGER.error("{} listen port -1 cannot follow minecraft tcp port {}: transport disabled",
                    name, mcPort);
            return -2;
        }
        NetBridge.LOGGER.info("{} listen port -1: following minecraft tcp port {}", name, target);
        return target;
    }

    /**
     * 启动单个传输实例；返回句柄，失败（含被禁用）返回 -1 并记录日志。
     *
     * @param configuredPort 配置端口（未配置为 null）；-1 语义在此解析
     */
    private static long startTransport(
            int kind,
            String name,
            ServerConfig.Section section,
            Integer configuredPort,
            int mcPort,
            String mcBindIp,
            String profile) {
        if (!section.enable()) {
            NetBridge.LOGGER.info("{} transport disabled by config", name);
            return -1;
        }
        int port = applyFollowSemantics(name, configuredPort, mcPort);
        if (port < 0 || port > 65535) {
            NetBridge.LOGGER.error("{} listen port {} out of range (-1/0/1..=65535): transport disabled",
                    name, configuredPort == null ? -1 : configuredPort);
            return -1;
        }
        if (port == 0) {
            NetBridge.LOGGER.info("{} listen port 0: random assignment", name);
        } else {
            NetBridge.LOGGER.info("{} listen port {} (minecraft tcp port {})", name, port, mcPort);
        }
        String bind = section.bind() != null && !section.bind().isBlank()
                ? section.bind()
                : (mcBindIp != null && !mcBindIp.isBlank() ? mcBindIp : null);
        long handle = NativeBridge.startServer(kind, port, maxConnections(section), bind, profile);
        if (handle < 0) {
            NetBridge.LOGGER.error(
                    "{} transport failed to bind udp/{}: transport disabled (see net-bridge-native log)",
                    name,
                    port);
            return -1;
        }
        int actual = NativeBridge.serverPort(handle);
        NetBridge.LOGGER.info("{} acceptor listening on udp/{}", name, actual);
        return handle;
    }

    private static int maxConnections(ServerConfig.Section section) {
        Integer configured = section.maxConnection();
        if (configured != null && configured >= 1) {
            return configured;
        }
        if (configured != null) {
            NetBridge.LOGGER.warn("Invalid max_connection {}: must be >= 1; using default", configured);
        }
        return ServerConfig.DEFAULT_MAX_CONNECTIONS;
    }

    /** 把成功启动的传输写入宣告条目（host=null → 省略字段，客户端跟随服务器地址）。 */
    private static void collectAnnouncement(
            Map<String, NetworksEntry> entries, String name, ServerConfig.Section section, long handle) {
        if (handle < 0) {
            return;
        }
        int actual = NativeBridge.serverPort(handle);
        if (actual <= 0) {
            return;
        }
        String protocol = name.equals("kcp") ? TransportProtocol.KCP_V1 : TransportProtocol.QUIC_V1;
        entries.put(name, new NetworksEntry(true, section.host(), actual, protocol));
    }

    /** 当前 ping 注入用的宣告模型。 */
    public static NetworksAbility announcement() {
        return announcement;
    }

    /** 以当前句柄为起始值派生后台 accept 线程（daemon，随 JVM 退出）。 */
    private static void startAcceptLoop() {
        long qh = quicHandle;
        long kh = kcpHandle;
        Thread thread = new Thread(() -> acceptLoop(qh, kh), "net-bridge-accept");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 后台轮询两个实例的 acceptConnections，把新连接交给 mod 层处理器。
     *
     * <p>以「对应 handle 仍等于启动值」为存活条件：stop() 置空后线程自行
     * 退出，重启靠 handle 不匹配互斥，无需显式取消。
     */
    private static void acceptLoop(long qh, long kh) {
        while ((qh != -1 && quicHandle == qh) || (kh != -1 && kcpHandle == kh)) {
            try {
                drain(qh, quicHandle);
                drain(kh, kcpHandle);
            } catch (Throwable t) {
                // 防御兜底：单轮异常不得杀死 accept 线程（新连接会在 native 侧排队）。
                NetBridge.LOGGER.warn("Accept drain failed", t);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 取出单个实例的新连接并派发；handle 已被替换则跳过。 */
    private static void drain(long expectedHandle, long currentHandle) {
        if (expectedHandle < 0 || currentHandle != expectedHandle) {
            return;
        }
        try {
            long[] ids = NativeBridge.acceptConnections(expectedHandle);
            // JNI 出错时按 ABI 契约返回 null：接受循环必须存活，下轮重试。
            if (ids == null) {
                return;
            }
            LongConsumer handler = connectionHandler;
            for (long id : ids) {
                if (handler != null) {
                    ADOPT_EXECUTOR.execute(() -> {
                        try {
                            handler.accept(id);
                        } catch (Throwable t) {
                            NetBridge.LOGGER.warn("Connection handler failed for conn {}", id, t);
                            NativeBridge.closeConnection(id);
                        }
                    });
                } else {
                    NetBridge.LOGGER.warn("Connection {} rejected: no connection handler registered", id);
                    NativeBridge.closeConnection(id);
                }
            }
        } catch (Throwable t) {
            // 单次 drain 失败不杀死 accept 线程：新连接在 native 侧排队，下轮继续。
            NetBridge.LOGGER.warn("Accept drain failed for server {}", expectedHandle, t);
        }
    }

    /** 停止全部传输并关闭其连接；未运行时幂等。 */
    public static synchronized void stop() {
        if (quicHandle != -1) {
            NativeBridge.stopServer(quicHandle);
            quicHandle = -1;
        }
        if (kcpHandle != -1) {
            NativeBridge.stopServer(kcpHandle);
            kcpHandle = -1;
        }
        announcement = NetworksAbility.empty();
        NetBridge.LOGGER.info("Acceptors stopped");
    }

    /** 是否有任一传输在运行。 */
    public static boolean isRunning() {
        return quicHandle != -1 || kcpHandle != -1;
    }
}
