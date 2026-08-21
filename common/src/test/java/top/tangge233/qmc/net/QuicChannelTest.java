package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.qmc.jni.NativeLoader;
import top.tangge233.qmc.jni.QuicNative;

/**
 * QuicChannel（Netty 适配器）回环集成测试：真实 JNI QUIC 桥 + Netty 管道双向读写。
 */
class QuicChannelTest {
    private static NioEventLoopGroup group;
    private static long server;
    private static int port;

    @BeforeAll
    static void setUp() {
        NativeLoader.load();
        server = QuicNative.startServer(0);
        assertTrue(server > 0, "startServer failed: " + QuicNative.lastError());
        port = QuicNative.serverPort(server);
        assertTrue(port > 0);
        group = new NioEventLoopGroup(2);
    }

    @AfterAll
    static void tearDown() {
        QuicNative.stopServer(server);
        group.shutdownGracefully();
    }

    private static long awaitServerConn() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long[] conns = QuicNative.acceptConnections(server);
            if (conns.length > 0) {
                return conns[0];
            }
            Thread.sleep(5);
        }
        throw new AssertionError("server never accepted connection");
    }

    private static void awaitActive(QuicChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!channel.isActive()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("channel never became active");
            }
            Thread.sleep(5);
        }
    }

    /** 收集管道收到的字节块，供测试断言。 */
    static final class Collector extends ChannelInboundHandlerAdapter {
        final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            buf.release();
            chunks.add(data);
        }
    }

    private static byte[] awaitData(Collector collector, int want) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < want) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("timeout: got " + out.size() + " of " + want + " bytes");
            }
            byte[] chunk = collector.chunks.poll(remaining, TimeUnit.NANOSECONDS);
            if (chunk != null) {
                out.write(chunk, 0, chunk.length);
            }
        }
        return out.toByteArray();
    }

    @Test
    void clientServerBidirectional() throws Exception {
        // 客户端 QuicChannel
        Collector clientCollector = new Collector();
        Bootstrap bootstrap = new Bootstrap().group(group).channel(QuicChannel.class)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel channel) {
                        channel.pipeline().addLast(clientCollector);
                    }
                });
        ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress("127.0.0.1", port));
        connectFuture.syncUninterruptibly();
        QuicChannel client = (QuicChannel) connectFuture.channel();
        assertTrue(client.isActive());
        assertEquals(QuicNative.STATE_CONNECTED, QuicNative.connectionState(client.connId()));

        // 服务端收养连接
        long serverConnId = awaitServerConn();
        Collector serverCollector = new Collector();
        QuicChannel serverChannel = QuicChannel.adopt(serverConnId);
        group.register(serverChannel).syncUninterruptibly();
        serverChannel.pipeline().addLast(serverCollector);
        awaitActive(serverChannel);

        // client -> server
        byte[] payload = "hello from client over quic channel".getBytes(StandardCharsets.UTF_8);
        client.writeAndFlush(Unpooled.wrappedBuffer(payload)).syncUninterruptibly();
        assertArrayEquals(payload, awaitData(serverCollector, payload.length));

        // server -> client
        byte[] reply = "hello from server".getBytes(StandardCharsets.UTF_8);
        serverChannel.writeAndFlush(Unpooled.wrappedBuffer(reply)).syncUninterruptibly();
        assertArrayEquals(reply, awaitData(clientCollector, reply.length));

        // 大数据块（跨多个 Rust 读块，验证拼接）
        byte[] big = new byte[200_000];
        new Random(42).nextBytes(big);
        client.writeAndFlush(Unpooled.wrappedBuffer(big)).syncUninterruptibly();
        assertArrayEquals(big, awaitData(serverCollector, big.length));

        // 关闭语义：client 关闭后，服务端侧应观察到通道非激活。
        client.close().syncUninterruptibly();
        assertFalse(client.isActive());
        serverChannel.close().syncUninterruptibly();
        QuicNative.stopServer(server);
    }
}
