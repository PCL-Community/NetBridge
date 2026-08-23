package top.tangge233.qmc.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.FastThreadLocal;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

/**
 * 把 QUIC 流暴露为 Netty {@link Channel} 的适配器（ADR-0001 架构层 2）。
 *
 * 数据通路：
 * <ul>
 *   <li>写：{@link #doWrite} 把出站 ByteBuf 拷成 byte[]，经 {@link QuicNative#writeChunk} 入队；
 *       队列满时保留在 outbound buffer，由轮询器稍后重试（背压，不丢包）。</li>
 *   <li>读：事件循环上定时轮询 {@link QuicNative#readChunk}，数据到达后
 *       {@code fireChannelRead} 推入管道（配合 MC 的 FlowControlHandler 语义）。</li>
 *   <li>连接：{@link QuicNative#connect} 为异步握手，连接 promise 在握手成功/失败时完成。</li>
 * </ul>
 */
public class QuicChannel extends AbstractChannel {
    /** 单次 JNI 读取上限（与 Rust 侧 chunk 大小一致）。 */
    public static final int MAX_READ_BYTES = 65536;

    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final long POLL_INTERVAL_MS = 5;
    /** 单次 poll 的读取轮数上限：剩余数据留在原生队列下轮再取，防止持续灌流的远端独占 EventLoop。 */
    private static final int MAX_READS_PER_POLL = 16;
    /** 出站暂存区增长上限：更大的包（罕见）按需一次性精确分配，不常驻。 */
    private static final int MAX_SCRATCH_BYTES = 1024 * 1024;

    // 地址常量：InetSocketAddress 不可变，全通道共享（GC 零分配）。
    private static final InetSocketAddress ADOPT_REMOTE_ADDRESS = new InetSocketAddress("0.0.0.0", 0);
    private static final InetSocketAddress LOCAL_ADDRESS = new InetSocketAddress(0);

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * 出站写暂存区：JNI 边界必须连续 byte[]（安全值拷贝）。按 EventLoop
     * 线程共享复用（doWrite 只在 channel 所属 EventLoop 上执行，多通道
     * 天然串行），消除每消息堆分配；倍增扩容，超过 {@link #MAX_SCRATCH_BYTES}
     * 的大包改用一次性精确分配。驻留成本 = 每 worker 线程至多一个数组，
     * 而非每通道一个。
     */
    private static final FastThreadLocal<byte[]> WRITE_SCRATCH = new FastThreadLocal<>() {
        @Override
        protected byte[] initialValue() {
            return new byte[4096];
        }
    };

    private volatile long connId = -1;
    private volatile boolean connected;
    private volatile boolean readRequested;
    private volatile InetSocketAddress remoteAddress;
    private volatile ScheduledFuture<?> pollTask;
    private volatile ChannelPromise connectPromise;

    public QuicChannel() {
        super(null);
    }

    /**
     * 收养一条已由服务端 acceptor 建立好的 QUIC 连接（服务端侧使用）。
     *
     * 注意：adopt 后 channel 处于 active 状态但轮询器尚未启动；需将 channel
     * 注册到 EventLoopGroup——注册完成触发 channelActive，autoRead 由此
     * 调用 doBeginRead 启动轮询器，数据才开始流入管线（见平台侧
     * QuicServerTransport）。
     */
    public static QuicChannel adopt(long connId) {
        QuicChannel channel = new QuicChannel();
        channel.connId = connId;
        channel.connected = true;
        channel.remoteAddress = ADOPT_REMOTE_ADDRESS;
        return channel;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new QuicUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return LOCAL_ADDRESS;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        // QUIC 客户端端口由 Rust endpoint 自行分配，无本地 bind。
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopPoller();
        long id = connId;
        connId = -1;
        if (id >= 0) {
            QuicNative.closeConnection(id);
        }
        ChannelPromise p = connectPromise;
        if (p != null && !p.isDone()) {
            p.tryFailure(new ClosedChannelException());
        }
    }

    @Override
    protected void doBeginRead() {
        readRequested = true;
        if (pollTask == null && connId >= 0) {
            startPoller(null);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (connId < 0 || !connected) {
            return; // 连接未就绪：消息保留在 outbound buffer，连接成功后由轮询器 flush。
        }
        int maxMessages = maxMessagesPerWrite();
        int written = 0;
        while (written < maxMessages) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            if (!(msg instanceof ByteBuf buf)) {
                in.remove(new UnsupportedOperationException("unsupported message type: " + msg.getClass()));
                continue;
            }
            int readable = buf.readableBytes();
            if (readable == 0) {
                in.remove();
                continue;
            }
            // JNI 边界说明：Rust 侧不直接操作 JVM 内存（安全边界，ADR-0001），
            // 出站必须拷进连续 byte[]。GC 优化：复用 EventLoop 线程共享的
            // WRITE_SCRATCH（倍增扩容），仅超大包一次性精确分配。
            byte[] data;
            if (readable <= MAX_SCRATCH_BYTES) {
                byte[] scratch = WRITE_SCRATCH.get();
                if (scratch.length < readable) {
                    scratch = new byte[Math.max(readable, scratch.length << 1)];
                    WRITE_SCRATCH.set(scratch);
                }
                data = scratch;
            } else {
                data = new byte[readable];
            }
            buf.getBytes(buf.readerIndex(), data, 0, readable);
            int accepted = QuicNative.writeChunk(connId, data, readable);
            if (accepted < 0) {
                in.remove(new IOException("quic write failed (conn " + connId + ", see qmc-native log)"));
                // 统一经 close() 触发 channelInactive（此刻 connected 仍为 true，
                // close 会正确发一次 inactive 并失败其余出站消息）。
                unsafe().close(voidPromise());
                return;
            }
            if (accepted == 0) {
                // 队列满：保留消息，稍后重试。
                break;
            }
            if (accepted < readable) {
                // 理论上 writeChunk 全收或全拒；保守起见保留未完成部分。
                break;
            }
            in.remove();
            written++;
        }
        // 队列仍有剩余时无需另排重试任务：轮询器每 5ms 必调
        // unsafe().flush()（见 poll()），独立 schedule 任务只会重复。
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }
        throw new UnsupportedOperationException("unsupported message type: " + msg.getClass());
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public boolean isActive() {
        return connected && isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    /** 当前 QUIC 连接 id；未连接时为 -1。 */
    public long connId() {
        return connId;
    }

    /** 事件循环上驱动：读取可用数据 + 重试 pending 写。 */
    private void poll() {
        if (connId < 0) {
            return;
        }
        int state = QuicNative.connectionState(connId);
        if (state == QuicNative.STATE_CONNECTED) {
            if (!connected) {
                completeConnect();
            }
        } else if (state == QuicNative.STATE_FAILED) {
            stopPoller();
            failConnect(new ConnectException(
                    "quic handshake failed (conn " + connId + ", see qmc-native log)"));
            pipeline().fireExceptionCaught(new IOException("quic connection failed"));
            unsafe().close(voidPromise());
            return;
        } else if (state == QuicNative.STATE_CLOSED || state == QuicNative.STATE_UNKNOWN) {
            stopPoller();
            failConnect(new ClosedChannelException());
            // 不手动 fireChannelInactive：connected 仍为 true，close() 会恰好触发一次。
            unsafe().close(voidPromise());
            return;
        }

        if (connected) {
            if (config().isAutoRead() || readRequested) {
                readRequested = false;
                readNow();
            }
            if (connId >= 0) {
                unsafe().flush();
            }
        }
    }

    /** 握手成功收尾：完成 connect promise、激活管线、放行连接前排队的写。 */
    private void completeConnect() {
        connected = true;
        ChannelPromise p = connectPromise;
        if (p != null && !p.isDone()) {
            p.trySuccess();
        }
        pipeline().fireChannelActive();
        // 连接前排队等待的写（如握手包）现在可以发出。
        unsafe().flush();
    }

    /** 握手失败/关闭收尾：以给定原因完成（或失败）connect promise。 */
    private void failConnect(Exception cause) {
        ChannelPromise p = connectPromise;
        if (p != null && !p.isDone()) {
            p.tryFailure(cause);
        }
    }

    /** 读取当前全部可用字节并推入管道。 */
    private void readNow() {
        if (connId < 0 || !connected) {
            return;
        }
        boolean any = false;
        for (int reads = 0; reads < MAX_READS_PER_POLL; reads++) {
            // 池化 direct buffer：JNI 直写后整包移交管线，读路径稳态零堆分配
            // （旧 byte[] 路径每次调用新分配 ≤64KB，是 GC 压力主源）。
            ByteBuf buf = alloc().ioBuffer(MAX_READ_BYTES);
            try {
                int n = readInto(buf);
                if (n < 0) {
                    pipeline().fireExceptionCaught(new IOException(
                            "quic read failed (conn " + connId + ", see qmc-native log)"));
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
    }

    /**
     * 单次 JNI 读入 {@code buf}。优先走 {@link QuicNative#readChunkInto}
     * 直接写池化 direct 内存；非 direct/复合视图（nioBuffer 为 null）退化到
     * 旧 byte[] 路径。返回 -1 表示连接级错误，0 表示暂无数据。
     */
    private int readInto(ByteBuf buf) {
        ByteBuffer nio = buf.nioBuffer(buf.writerIndex(), buf.writableBytes());
        if (nio != null && nio.isDirect()) {
            return QuicNative.readChunkInto(connId, nio, Math.min(buf.writableBytes(), MAX_READ_BYTES));
        }
        byte[] data = QuicNative.readChunk(connId, MAX_READ_BYTES);
        if (data == null) {
            return -1;
        }
        if (data.length > 0) {
            buf.writeBytes(data);
        }
        return data.length;
    }

    private void startPoller(ChannelPromise promise) {
        this.connectPromise = promise;
        pollTask = eventLoop().scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPoller() {
        ScheduledFuture<?> task = pollTask;
        pollTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private final class QuicUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
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
                InetSocketAddress target = (InetSocketAddress) remoteAddress;
                QuicChannel.this.remoteAddress = target;
                NativeLoader.load();
                long id = QuicNative.connect(target.getHostString(), target.getPort());
                if (id < 0) {
                    promise.tryFailure(new ConnectException("quic connect failed, see qmc-native log"));
                    return;
                }
                connId = id;
                startPoller(promise);
            } catch (Throwable t) {
                promise.tryFailure(t);
            }
        }
    }
}
