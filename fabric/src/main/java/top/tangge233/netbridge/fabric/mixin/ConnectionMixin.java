package top.tangge233.netbridge.fabric.mixin;

import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.fabric.mc.NativeClientTransport;
import top.tangge233.netbridge.transport.ClientConfig;
import top.tangge233.netbridge.transport.ConnectionDisplay;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportSelector;
import top.tangge233.netbridge.transport.TransportTarget;

/**
 * 客户端出站连接拦截：目标宣告了可用加速传输且模式开启时改用 native 通道；
 * 握手两次失败自动回退原版 TCP。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    /** 回退递归防护：内部调用原版 connect 时不再触发本 mixin。 */
    @Unique
    private static final ThreadLocal<Boolean> NETBRIDGE_IN_PROGRESS =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void netbridge$tryAccelerated(
            InetSocketAddress address,
            boolean useEpoll,
            Connection connection,
            CallbackInfoReturnable<ChannelFuture> cir) {
        if (Boolean.TRUE.equals(NETBRIDGE_IN_PROGRESS.get())) {
            return;
        }
        // 新连接开始即清除旧显示：TCP 直连路径不 set 不覆盖，残留会让 F3 显示上次传输。
        ConnectionDisplay.clear();
        TransportMode mode = ClientConfig.mode();
        var targetOpt = TransportSelector.decide(address);
        if (targetOpt.isEmpty()) {
            NetBridge.LOGGER.info("Transport for {}: TCP (mode={})", address, mode);
            return;
        }
        TransportTarget target = targetOpt.get();
        NetBridge.LOGGER.info("Transport for {}: {} endpoint {}", address, target.mode(), target.endpoint());
        NETBRIDGE_IN_PROGRESS.set(Boolean.TRUE);
        try {
            cir.setReturnValue(
                    NativeClientTransport.connectWithFallback(address, useEpoll, connection, target));
        } finally {
            NETBRIDGE_IN_PROGRESS.remove();
        }
    }
}
