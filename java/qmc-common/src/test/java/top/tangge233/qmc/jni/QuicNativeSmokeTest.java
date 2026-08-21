package top.tangge233.qmc.jni;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * JNI 桥冒烟验证：加载 classpath 中的 .so，并做真实 QUIC 回环双向传输。
 */
class QuicNativeSmokeTest {
    private static void awaitState(long conn, int want) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int s = QuicNative.connectionState(conn);
            if (s == want) {
                return;
            }
            Thread.sleep(5);
        }
        fail("timeout waiting state " + want + ", got " + QuicNative.connectionState(conn)
                + " err=" + QuicNative.lastError());
    }

    private static byte[] awaitRead(long conn, int want) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            byte[] data = QuicNative.readChunk(conn, want);
            if (data != null && data.length >= want) {
                return data;
            }
            Thread.sleep(5);
        }
        fail("timeout waiting read, err=" + QuicNative.lastError());
        throw new AssertionError("unreachable");
    }

    private static long awaitServerConn(long server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long[] conns = QuicNative.acceptConnections(server);
            if (conns.length > 0) {
                return conns[0];
            }
            Thread.sleep(5);
        }
        fail("server never accepted connection, err=" + QuicNative.lastError());
        throw new AssertionError("unreachable");
    }

    @Test
    void nativeReturnsAbiVersion() {
        NativeLoader.load();
        assertEquals("0.1.0", QuicNative.version());
    }

    @Test
    void rawFeatureIsQuicRaw() {
        NativeLoader.load();
        assertEquals("quic-raw", QuicNative.rawFeature());
    }

    @Test
    void missingConnectionReadsNullAndStateUnknown() {
        NativeLoader.load();
        assertNull(QuicNative.readChunk(123456, 100));
        assertEquals(QuicNative.STATE_UNKNOWN, QuicNative.connectionState(123456));
    }

    @Test
    void quicBridgeLoopbackRoundtrip() throws Exception {
        NativeLoader.load();

        long server = QuicNative.startServer(0);
        assertTrue(server > 0, "startServer failed: " + QuicNative.lastError());
        try {
            int port = QuicNative.serverPort(server);
            assertTrue(port > 0, "server port not bound");

            long client = QuicNative.connect("127.0.0.1", port);
            assertTrue(client > 0, "connect failed: " + QuicNative.lastError());
            awaitState(client, QuicNative.STATE_CONNECTED);

            long serverConn = awaitServerConn(server);
            assertEquals(QuicNative.STATE_CONNECTED, QuicNative.connectionState(serverConn));

            // client -> server
            byte[] payload = "quic-mc hello over jni".getBytes(StandardCharsets.UTF_8);
            int n = QuicNative.writeChunk(client, payload);
            assertEquals(payload.length, n, "client write: " + QuicNative.lastError());
            assertArrayEquals(payload, awaitRead(serverConn, payload.length));

            // server -> client
            byte[] reply = "pong via jni".getBytes(StandardCharsets.UTF_8);
            assertEquals(reply.length, QuicNative.writeChunk(serverConn, reply),
                    "server write: " + QuicNative.lastError());
            assertArrayEquals(reply, awaitRead(client, reply.length));

            QuicNative.closeConnection(client);
            awaitState(client, QuicNative.STATE_CLOSED);
        } finally {
            QuicNative.stopServer(server);
        }
    }
}
