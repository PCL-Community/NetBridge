package top.tangge233.netbridge.neoforge.mixin;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.neoforge.mc.NativeClientTransport;
import top.tangge233.netbridge.runtime.NetBridgeServices;
import top.tangge233.netbridge.transport.ConnectionDisplay;
import top.tangge233.netbridge.transport.TransportSelector;

import java.net.InetSocketAddress;

/**
 * 客户端出站连接拦截：目标宣告了可用加速传输且模式开启时改用 native 通道； 握手两次失败自动回退原版 TCP。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Unique
    private static final ThreadLocal<Boolean> NETBRIDGE_IN_PROGRESS =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
            method = "connect",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void netbridge$tryAccelerated(
            InetSocketAddress address,
            boolean useEpoll,
            Connection connection,
            CallbackInfoReturnable<ChannelFuture> cir
    ) {
        if (NETBRIDGE_IN_PROGRESS.get()) {
            return;
        }

        ConnectionDisplay.clear();
        var mode = NetBridgeServices.clientSettings().current().mode();
        var targetOpt = TransportSelector.decide(address);
        if (targetOpt.isEmpty()) {
            NetBridge.LOGGER.info("Transport for {}: TCP (mode={})", address, mode);
            return;
        }

        var target = targetOpt.get();
        NetBridge.LOGGER.info(
                "Transport for {}: {} endpoint {}",
                address,
                target.mode(),
                target.endpoint()
        );

        NETBRIDGE_IN_PROGRESS.set(Boolean.TRUE);

        try {
            cir.setReturnValue(
                    NativeClientTransport.connectWithFallback(
                            address,
                            useEpoll,
                            connection,
                            target
                    )
            );
        } finally {
            NETBRIDGE_IN_PROGRESS.remove();
        }
    }

}
