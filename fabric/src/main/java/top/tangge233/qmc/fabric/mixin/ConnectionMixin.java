package top.tangge233.qmc.fabric.mixin;

import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.qmc.fabric.mc.QuicClientTransport;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.QuicTarget;

/**
 * 客户端出站连接拦截：目标地址宣告 QUIC 能力且模式开启时，改用 QUIC 通道；
 * QUIC 失败且允许 fallback 时自动回退原版 TCP（ADR-0002）。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    /** fallback 递归防护：内部调用原版 connect 时不再触发本 mixin。 */
    @Unique
    private static final ThreadLocal<Boolean> QUIC_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void qmc$tryQuic(
            InetSocketAddress address,
            boolean useEpoll,
            Connection connection,
            CallbackInfoReturnable<ChannelFuture> cir) {
        if (!QuicClient.quicEnabled() || Boolean.TRUE.equals(QUIC_IN_PROGRESS.get())) {
            return;
        }
        QuicTarget target = QuicClient.quicTargetFor(address);
        if (target == null) {
            return;
        }
        QUIC_IN_PROGRESS.set(Boolean.TRUE);
        try {
            cir.setReturnValue(QuicClientTransport.connectWithFallback(address, useEpoll, connection, target));
        } finally {
            QUIC_IN_PROGRESS.remove();
        }
    }
}
