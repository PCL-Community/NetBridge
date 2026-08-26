package top.tangge233.netbridge.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import top.tangge233.netbridge.channel.NativeChannel;

/**
 * 握手看门狗：定时任务与连接 promise 竞速。
 *
 * <p>两个 native 栈都无法在目标时限内自行终结黑洞握手（QUIC 零响应时
 * 永不失败、KCP 无握手概念），Java 侧定时器是唯一可行方案。超时路径经
 * {@link NativeChannel#abortConnect} 关闭通道——close 触发 native
 * closeConnection 清理注册表句柄，防止 PTO 空转任务泄漏。
 *
 * <p>超时时限：单次连接流程内首次尝试 {@value #FIRST_ATTEMPT_MILLIS}ms、
 * 第二次 {@code SECOND_ATTEMPT_MILLIS}ms；native 报 FAILED 视同当次
 * 立即失败，不等超时。
 */
public final class HandshakeWatchdog {
    /** 第一次握手超时（冷启动，含 DNS 与建连）。 */
    public static final long FIRST_ATTEMPT_MILLIS = 10_000L;
    /** 第二次握手超时。 */
    public static final long SECOND_ATTEMPT_MILLIS = 20_000L;

    private HandshakeWatchdog() {}

    /**
     * 为一次连接尝试布防：{@code timeoutMillis} 内未完成即在 channel 的
     * EventLoop 上执行超时收口；连接提前完成/失败则自动撤防。
     *
     * @param future {@link Bootstrap#connect} 返回的 future
     * @param attempt 第几次尝试（1 起）；1 → 10s，其余 → 20s
     */
    public static void arm(ChannelFuture future, int attempt) {
        Channel channel = future.channel();
        long timeout = attempt <= 1 ? FIRST_ATTEMPT_MILLIS : SECOND_ATTEMPT_MILLIS;
        Runnable fire = () -> {
            // 未完成才收口：abortConnect 失败 promise 并经 doClose 清理 native 句柄。
            if (!future.isDone()) {
                ((NativeChannel) channel)
                        .abortConnect(new ConnectException("handshake timeout after " + timeout + " ms"));
            }
        };
        var scheduled =
                channel.eventLoop().schedule(fire, timeout, TimeUnit.MILLISECONDS);
        future.addListener(done -> scheduled.cancel(false));
    }
}
