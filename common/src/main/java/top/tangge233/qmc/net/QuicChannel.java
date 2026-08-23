package top.tangge233.qmc.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final AtomicBoolean closed = new AtomicBoolean();

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
        channel.remoteAddress = new InetSocketAddress("0.0.0.0", 0);
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
        return new InetSocketAddress(0);
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
            byte[] data = new byte[readable];
            buf.getBytes(buf.readerIndex(), data);
            int accepted = QuicNative.writeChunk(connId, data);
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
        if (in.current() != null) {
            scheduleFlushRetry();
        }
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

    private void scheduleFlushRetry() {
        eventLoop().schedule(() -> {
            if (isOpen() && connected) {
                unsafe().flush();
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 事件循环上驱动：读取可用数据 + 重试 pending 写。 */
    private void poll(ChannelPromise promise) {
        if (connId < 0) {
            return;
        }
        int state = QuicNative.connectionState(connId);
        if (state == QuicNative.STATE_CONNECTED) {
            if (!connected) {
                connected = true;
                ChannelPromise p = this.connectPromise;
                if (p != null && !p.isDone()) {
                    p.trySuccess();
                }
                pipeline().fireChannelActive();
                // 连接前排队等待的写（如握手包）现在可以发出。
                unsafe().flush();
            }
        } else if (state == QuicNative.STATE_FAILED) {
            stopPoller();
            ChannelPromise p = this.connectPromise;
            if (p != null && !p.isDone()) {
                p.tryFailure(new ConnectException("quic handshake failed (conn " + connId + ", see qmc-native log)"));
            }
            pipeline().fireExceptionCaught(new IOException("quic connection failed"));
            unsafe().close(voidPromise());
            return;
        } else if (state == QuicNative.STATE_CLOSED || state == QuicNative.STATE_UNKNOWN) {
            stopPoller();
            ChannelPromise p = this.connectPromise;
            if (p != null && !p.isDone()) {
                p.tryFailure(new ClosedChannelException());
            }
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

    /** 读取当前全部可用字节并推入管道。 */
    private void readNow() {
        if (connId < 0 || !connected) {
            return;
        }
        boolean any = false;
        while (true) {
            byte[] data = QuicNative.readChunk(connId, MAX_READ_BYTES);
            if (data == null) {
                pipeline().fireExceptionCaught(new IOException("quic read failed (conn " + connId + ", see qmc-native log)"));
                break;
            }
            if (data.length == 0) {
                break;
            }
            ByteBuf buf = alloc().buffer(data.length, data.length);
            buf.writeBytes(data);
            pipeline().fireChannelRead(buf);
            any = true;
            if (data.length < MAX_READ_BYTES) {
                break;
            }
        }
        if (any) {
            pipeline().fireChannelReadComplete();
        }
    }

    private void startPoller(ChannelPromise promise) {
        this.connectPromise = promise;
        pollTask = eventLoop().scheduleAtFixedRate(() -> poll(promise), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPoller() {
        ScheduledFuture<?> task = pollTask;
        pollTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private final class QuicUnsafe extends AbstractUnsafe {
        QuicUnsafe() {
            super();
        }

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
