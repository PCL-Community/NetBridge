package top.tangge233.netbridge.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.concurrent.FastThreadLocal;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.jni.NativeBridge;
import top.tangge233.netbridge.jni.NativeConnState;
import top.tangge233.netbridge.jni.NativeLoader;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * 把 native 连接（QUIC/KCP）暴露为 Netty {@link Channel} 的协议无关适配器。
 *
 * <p>数据通路：
 * <ul>
 *   <li>写：{@link #doWrite} 把出站 ByteBuf 拷进复用 byte[]，经
 *       {@link NativeBridge#writeChunk} 入队；队列满时消息保留在 outbound buffer，
 *       由轮询器稍后重试（背压，不丢包）。</li>
 *   <li>读：Rust 数据到达经 JNI 反向通知（{@code NativeBridge.onDataAvailable}），
 *       在 EventLoop 上立即拉取推送管线；轮询器降级为兜底（通知丢失/状态检测/
 *       写重试），退避见 {@link #BACKOFF_STEPS_MS}。读取优先经
 *       {@link NativeBridge#readChunkInto} 直写池化 direct buffer，
 *       {@code fireChannelRead} 推入管道。</li>
 *   <li>连接：{@link NativeBridge#connect} 异步建立，连接 promise 在
 *       CONNECTED/FAILED 时完成；超时由外部看门狗经 {@link #abortConnect} 收尸。</li>
 * </ul>
 *
 * <p>轮询节奏：握手期固定 5ms；已连接后活跃保持 5ms，连续空转按
 * 5→10→20→40ms 步进退避，任何数据到达或写排队立即复位。
 */
public class NativeChannel extends AbstractChannel {

    /** 单次 JNI 读取上限（与 Rust 侧 chunk 大小一致）。 */
    public static final int MAX_READ_BYTES = 65536;

    private static final ChannelMetadata METADATA = new ChannelMetadata(
            false,
            16
    );
    /** 基础轮询间隔与退避步进序列（毫秒）；末值为上限。 */
    private static final long[] BACKOFF_STEPS_MS = {20, 50, 100};
    /** 单次 poll 的读取轮数上限：剩余数据留在原生队列下轮再取，防止持续灌流的远端独占 EventLoop。 */
    private static final int MAX_READS_PER_POLL = 16;
    /** drainRead 单次唤醒的最大读取轮数：洪峰时让出 EventLoop 重新调度，避免独占。 */
    private static final int MAX_DRAIN_ROUNDS = 4;
    /** 出站暂存区增长上限：更大的包（罕见）按需一次性精确分配，不常驻。 */
    private static final int MAX_SCRATCH_BYTES = 1024 * 1024;

    /**
     * connId → channel 注册表：Rust 数据到达通知（onDataAvailable）按 id 定位 channel 并在其 EventLoop
     * 上触发立即读。连接关闭时移除。
     */
    private static final ConcurrentHashMap<Long, NativeChannel> CHANNELS = new ConcurrentHashMap<>();
    // InetSocketAddress 不可变，全通道共享常量，避免每次连接分配。
    private static final InetSocketAddress ADOPT_REMOTE_ADDRESS = new InetSocketAddress(
            "0.0.0.0",
            0
    );
    private static final InetSocketAddress LOCAL_ADDRESS = new InetSocketAddress(0);
    /**
     * 出站写暂存区：JNI 边界必须连续 byte[]（安全值拷贝）。按 EventLoop 线程共享复用（doWrite 只在 channel 所属 EventLoop 上执行，多通道
     * 天然串行），消除每消息堆分配；倍增扩容，超过 {@link #MAX_SCRATCH_BYTES} 的大包改用一次性精确分配。
     */
    private static final FastThreadLocal<byte[]> WRITE_SCRATCH = new FastThreadLocal<>() {
        @Override
        protected byte[] initialValue() {
            return new byte[4096];
        }
    };
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * 连接期即可写：KCP 客户端无握手应答，出站不等待 CONNECTED （native 侧同样放行），否则与服务端"按首包建会话"互等死锁。
     */
    private final boolean earlyWrite;
    private final AtomicBoolean pendingWake = new AtomicBoolean();
    private volatile long connId = -1;
    private volatile boolean connected;
    private volatile boolean readRequested;
    private volatile @Nullable InetSocketAddress remoteAddress;
    private volatile @Nullable ScheduledFuture<?> pollTask;
    private volatile long pollDelayMs = BACKOFF_STEPS_MS[0];
    private volatile @Nullable ChannelPromise connectPromise;

    public NativeChannel() {
        this(false);
    }

    /**
     * @param earlyWrite true = 连接期即允许写出站缓冲（KCP 客户端语义）
     */
    public NativeChannel(boolean earlyWrite) {
        super(null);
        this.earlyWrite = earlyWrite;
    }

    /** 按连接 id 取 channel；未知（未注册/已关闭）返回 null。 */
    public static @Nullable NativeChannel channelFor(long connId) {
        return CHANNELS.get(connId);
    }

    /**
     * 收养一条已由服务端 acceptor 建立好的连接（服务端侧使用）。
     *
     * <p>注意：adopt 后 channel 处于 active 状态但轮询器尚未启动；需将 channel
     * 注册到 EventLoopGroup——注册完成触发 channelActive，autoRead 由此 调用 doBeginRead 启动轮询器，数据才开始流入管线（见平台侧服务端
     * transport）。
     */
    public static NativeChannel adopt(long connId) {
        var channel = new NativeChannel(false);
        channel.connId = connId;
        channel.connected = true;
        channel.remoteAddress = resolveRemoteAddress(connId);
        CHANNELS.put(connId, channel);
        return channel;
    }

    /**
     * 取连接真实对端地址（IP 管控：ban-ip/限速/审计）； 取不到或非法时回退 0.0.0.0 哨兵。
     */
    private static InetSocketAddress resolveRemoteAddress(long connId) {
        var s = NativeBridge.remoteAddress(connId);
        if (s == null) {
            return ADOPT_REMOTE_ADDRESS;
        }

        var idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) {
            return ADOPT_REMOTE_ADDRESS;
        }

        try {
            var host = s.substring(0, idx);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }

            var port = Integer.parseInt(s.substring(idx + 1));
            // 字面量 IP，getByName 不触发 DNS。
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (IOException | IllegalArgumentException e) {
            NetBridge.LOGGER.warn(
                    "Invalid native remote address '{}' from native (conn {})",
                    s,
                    connId
            );
            return ADOPT_REMOTE_ADDRESS;
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new NativeUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    /** 无本地绑定：客户端端口由 native 分派，恒返回占位地址。 */
    @Override
    protected SocketAddress localAddress0() {
        return LOCAL_ADDRESS;
    }

    /** 真实对端地址：连接前为传入目标，adopt 后为 native 上报地址。 */
    @Override
    protected @Nullable SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        // 客户端端口由 native 侧自行分配，无本地 bind。
    }

    /** 与 close 等价：无独立半开状态。 */
    @Override
    protected void doDisconnect() {
        doClose();
    }

    /** 幂等关闭：停轮询并向 native 发送 Close（清理注册表句柄）。 */
    @Override
    protected void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        stopPoller();
        var id = connId;
        connId = -1;
        if (id >= 0) {
            CHANNELS.remove(id);
            NativeBridge.closeConnection(id);
        }
        var p = connectPromise;
        if (p != null && !p.isDone()) {
            p.tryFailure(new ClosedChannelException());
        }
    }

    /** 标记读就绪并首次启动轮询器（无 AutoRead 时的单次读触发）。 */
    @Override
    protected void doBeginRead() {
        readRequested = true;
        if (pollTask == null && connId >= 0) {
            startPoller(null);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (connId < 0 || !(connected || earlyWrite)) {
            return; // 连接未就绪且不允许提前写：消息保留在 outbound buffer。
        }

        var maxMessages = maxMessagesPerWrite();
        var written = 0;
        while (written < maxMessages && in.current() != null) {
            var r = writeMessage(in, in.current());
            if (r != 1) {
                break; // 保留待重试（0）或通道已关闭（-1）。
            }
            written++;
        }
        // 队列仍有剩余时无需另排重试任务：轮询器必调 unsafe().flush()（见 poll()）。
    }

    /**
     * 写出单条 outbound 消息（所有权仍归 outbound buffer，按结果移除）。
     *
     * @return 1 = 已完整写走（消息已移除）；0 = 队列满/未就绪，保留重试； -1 = 连接已不存在，通道已关闭（收尾竞态，静默移除不失败写）
     */
    private int writeMessage(ChannelOutboundBuffer in, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            in.remove(new UnsupportedOperationException(
                    "unsupported message type: " + msg.getClass()));
            return 1;
        }

        var readable = buf.readableBytes();
        if (readable == 0) {
            in.remove();
            return 1;
        }

        // 拷进线程共享的 WRITE_SCRATCH（JNI 边界需连续 byte[]，见字段注释）。
        byte[] data;
        if (readable <= MAX_SCRATCH_BYTES) {
            var scratch = WRITE_SCRATCH.get();
            if (scratch.length < readable) {
                scratch = new byte[Math.max(readable, scratch.length << 1)];
                WRITE_SCRATCH.set(scratch);
            }
            data = scratch;
        } else {
            data = new byte[readable];
        }

        buf.getBytes(buf.readerIndex(), data, 0, readable);
        var accepted = NativeBridge.writeChunk(connId, data, readable);
        if (accepted < 0) {
            // 连接已在 native 侧移除/关闭（收尾竞态）：静默移除消息并关闭。
            // 不得失败 write future——否则 close future 带 cause，MC 会刷
            // "Exception caught in connection" ERROR；连接已死，写不出去无损失。
            in.remove();
            unsafe().close(voidPromise());
            return -1;
        }

        if (accepted < readable) {
            // 队列满(0)或未就绪：保留消息稍后重试（writeChunk 全收或全拒，保守处理）。
            return 0;
        }

        in.remove();
        return 1;
    }

    /** 只接受 ByteBuf 消息，其余类型直接拒绝。 */
    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }
        throw new UnsupportedOperationException("unsupported message type: " + msg.getClass());
    }

    /** 取消当前待执行轮询（已在跑的在 event loop 内天然串行）。 */
    private void stopPoller() {
        var task = pollTask;
        pollTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Rust 数据到达唤醒（任意线程）：去抖后在 EventLoop 上立即读，替代轮询等待。 未注册到 EventLoop 时放弃——数据留在 native 队列，轮询兜底读取。
     */
    public void wakeupRead() {
        if (!pendingWake.compareAndSet(false, true)) {
            return;
        }

        var loop = eventLoop();
        if (loop == null) {
            pendingWake.set(false);
            return;
        }

        try {
            loop.execute(this::drainRead);
        } catch (RejectedExecutionException e) {
            // EventLoop 已关闭：复位唤醒令牌，交由轮询兜底（连接即将收尾）。
            pendingWake.set(false);
        }
    }

    /**
     * EventLoop 上立即读：循环读到无数据为止，期间新通知就地消费； 洪峰超上限让出重排，不独占 EventLoop。
     */
    private void drainRead() {
        pendingWake.set(false);
        if (closed.get() || connId < 0 || !connected) {
            return;
        }

        var any = true;
        var rounds = 0;
        while (any && rounds < MAX_DRAIN_ROUNDS) {
            if (!(config().isAutoRead() || readRequested)) {
                break;
            }
            readRequested = false;
            any = readNow();
            rounds++;
            if (pendingWake.get()) {
                pendingWake.set(false);
            }
        }

        if (any || pendingWake.get()) {
            // 数据未完或新通知又至：让出 EventLoop 后继续，避免霸占。
            try {
                eventLoop().execute(this::drainRead);
            } catch (RejectedExecutionException e) {
                // EventLoop 已关闭：复位令牌，轮询兜底。
                pendingWake.set(false);
            }
        }
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    /** 连接建立且未关闭视为 active。 */
    @Override
    public boolean isActive() {
        return connected && isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    /** 当前连接 id；未连接时为 -1。 */
    public long connId() {
        return connId;
    }

    /**
     * 看门狗超时收口：以给定原因失败连接 promise 并关闭通道。 close 触发 {@link #doClose} → native closeConnection 清理句柄 （黑洞下
     * native 任务仍在重传，必须主动清理防泄漏）。
     */
    public void abortConnect(Throwable cause) {
        failConnect(cause);
        unsafe().close(voidPromise());
    }

    /** 握手失败/关闭收尾：以给定原因完成（或失败）connect promise。 */
    private void failConnect(Throwable cause) {
        var p = connectPromise;
        if (p != null && !p.isDone()) {
            p.tryFailure(cause);
        }
    }

    /** 事件循环上驱动：状态感知、读取可用数据 + 重试 pending 写。 */
    private void poll() {
        if (connId < 0) {
            return;
        }

        var state = NativeBridge.connectionState(connId);
        if (state == NativeConnState.CONNECTED.code) {
            if (!connected) {
                completeConnect();
            }
        } else if (state == NativeConnState.FAILED.code) {
            stopPoller();
            failConnect(new ConnectException(
                    "handshake failed (conn %d, see net-bridge-native log)".formatted(connId)
            ));
            pipeline().fireExceptionCaught(new IOException("connection failed"));
            unsafe().close(voidPromise());
            return;
        } else if (state == NativeConnState.CLOSED.code
                || state == NativeConnState.UNKNOWN.code
        ) {
            stopPoller();
            failConnect(new ClosedChannelException());
            // 不手动 fireChannelInactive：connected 仍为 true，close() 会恰好触发一次。
            unsafe().close(voidPromise());
            return;
        }

        var activity = false;
        if (connected || earlyWrite) {
            if (config().isAutoRead() || readRequested) {
                readRequested = false;
                activity |= readNow();
            }
            var outbound = unsafe().outboundBuffer();
            if (outbound != null && !outbound.isEmpty()) {
                activity = true;
                unsafe().flush();
            }
        }
        reschedule(activity);
    }

    /** 握手成功收尾：完成 connect promise、激活管线、放行连接前排队的写。 */
    private void completeConnect() {
        connected = true;
        var p = connectPromise;
        if (p != null && !p.isDone()) {
            p.trySuccess();
        }
        pipeline().fireChannelActive();
        // 连接前排队等待的写（如握手包）现在可以发出。
        unsafe().flush();
    }

    /**
     * 读取当前全部可用字节并推入管道。
     *
     * @return 本轮是否读到任何数据（退避复位依据）
     */
    private boolean readNow() {
        if (connId < 0 || !connected) {
            return false;
        }
        var any = false;
        for (var reads = 0; reads < MAX_READS_PER_POLL; reads++) {
            // 池化 direct buffer：JNI 直写后整包移交管线，读路径稳态零堆分配。
            var buf = alloc().ioBuffer(MAX_READ_BYTES);
            try {
                var n = readInto(buf);
                if (n < 0) {
                    // 连接已在 native 侧移除（收尾竞态）：静默关闭，不 fire 异常。
                    unsafe().close(voidPromise());
                    break;
                }

                if (n == 0) {
                    break;
                }

                buf.writerIndex(n);
                pipeline().fireChannelRead(buf);
                buf = null; // 所有权移交管线，下游负责 release。
                any = true;
                if (n < MAX_READ_BYTES) {
                    break;
                }
            } finally {
                if (buf != null) {
                    buf.release();
                }
            }
        }

        if (any) {
            pipeline().fireChannelReadComplete();
        }

        return any;
    }

    /**
     * 单次 JNI 读入 {@code buf}。优先走 {@link NativeBridge#readChunkInto} 直接写池化 direct 内存；非
     * direct/复合视图（nioBuffer 为 null）退化到 byte[] 路径。返回 -1 表示连接级错误，0 表示暂无数据。
     */
    private int readInto(ByteBuf buf) {
        var nio = buf.nioBuffer(buf.writerIndex(), buf.writableBytes());
        if (nio != null && nio.isDirect()) {
            return NativeBridge.readChunkInto(
                    connId,
                    nio,
                    Math.min(buf.writableBytes(), MAX_READ_BYTES)
            );
        }

        var data = NativeBridge.readChunk(connId, MAX_READ_BYTES);
        if (data == null) {
            return -1;
        }

        if (data.length > 0) {
            buf.writeBytes(data);
        }
        return data.length;
    }

    /** 首次启动轮询器（异步连接立即返回；握手完成由 poll 感知）。 */
    private void startPoller(@Nullable ChannelPromise promise) {
        this.connectPromise = promise;
        pollDelayMs = BACKOFF_STEPS_MS[0];
        pollTask = eventLoop().schedule(this::poll, 0, TimeUnit.MILLISECONDS);
    }

    /** 按本轮活跃度计算下一轮间隔并重新调度（自续任务，非固定速率）。 */
    private void reschedule(boolean activity) {
        if (closed.get()) {
            return;
        }

        long next;
        if (!connected || activity) {
            // 握手期固定最快档；活跃轮复位退避。
            next = BACKOFF_STEPS_MS[0];
        } else {
            var current = pollDelayMs;
            long stepped = 0;
            for (var i = 0; i < BACKOFF_STEPS_MS.length - 1; i++) {
                if (BACKOFF_STEPS_MS[i] == current) {
                    stepped = BACKOFF_STEPS_MS[i + 1];
                    break;
                }
            }

            next = stepped == 0
                    ? BACKOFF_STEPS_MS[BACKOFF_STEPS_MS.length - 1]
                    : stepped;
        }
        pollDelayMs = next;
        pollTask = eventLoop().schedule(
                this::poll,
                next,
                TimeUnit.MILLISECONDS
        );
    }

    private final class NativeUnsafe extends AbstractUnsafe {

        /**
         * 加载 native 库并发起异步连接；连接 promise 由轮询器在 CONNECTED/FAILED/CLOSED 时完成，失败才回退 TCP。
         */
        @Override
        public void connect(
                SocketAddress remoteAddress,
                @Nullable SocketAddress localAddress,
                ChannelPromise promise
        ) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!isOpen()) {
                promise.tryFailure(new ClosedChannelException());
                return;
            }

            if (isActive()) {
                promise.tryFailure(new ConnectException("already connected"));
                return;
            }

            try {
                var target = (InetSocketAddress) remoteAddress;
                NativeChannel.this.remoteAddress = target;
                if (!NativeLoader.load()) {
                    promise.tryFailure(
                            new ConnectException("net-bridge native library unavailable"));
                    return;
                }

                var kind = earlyWrite
                        ? NativeBridge.KIND_KCP
                        : NativeBridge.KIND_QUIC;
                var profile = kind == NativeBridge.KIND_KCP
                        ?
                        NetBridgeServices.clientSettings()
                                .current()
                                .kcpProfile()
                                .configValue()
                        : null;
                var id = NativeBridge.connect(
                        kind,
                        target.getHostString(),
                        target.getPort(),
                        profile
                );
                if (id < 0) {
                    promise.tryFailure(new ConnectException(
                            "connect failed, see net-bridge-native log"));
                    return;
                }

                connId = id;
                CHANNELS.put(id, NativeChannel.this);
                startPoller(promise);
            } catch (Throwable t) {
                promise.tryFailure(t);
            }
        }

    }

}
