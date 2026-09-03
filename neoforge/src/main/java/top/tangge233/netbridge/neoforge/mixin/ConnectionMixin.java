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

import java.net.InetSocketAddress;

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

        if (!NetBridgeServices.clientRuntime().acceleratedRequested()) {
            NetBridge.LOGGER.info("Transport for {}: TCP (mode=tcp)", address);
            return;
        }

        NETBRIDGE_IN_PROGRESS.set(Boolean.TRUE);

        try {
            cir.setReturnValue(
                    NativeClientTransport.connectWithFallback(
                            address,
                            useEpoll,
                            connection
                    )
            );
        } finally {
            NETBRIDGE_IN_PROGRESS.remove();
        }
    }

}
