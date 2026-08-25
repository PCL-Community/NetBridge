package top.tangge233.netbridge.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import top.tangge233.netbridge.fabric.mc.QuicServerTransport;
import top.tangge233.netbridge.server.QuicServer;

/**
 * 服务端生命周期：runServer 开始时启动 QUIC acceptor，close 时停止。
 * （不依赖 fabric-api，纯 mixin 实现。）
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerLifecycleMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void netbridge$startAcceptor(CallbackInfo ci) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        QuicServer.setConnectionHandler(connId -> QuicServerTransport.adopt(self, connId));
        QuicServer.start(self.getPort(), self.getLocalIp());
    }

    /**
     * close() 是 AutoCloseable 接口方法，intermediary 中保持原名，
     * 关闭注解处理器的重映射检查（remap = false）。
     */
    @Inject(method = "close()V", remap = false, at = @At("HEAD"))
    private void netbridge$stopAcceptor(CallbackInfo ci) {
        QuicServer.setConnectionHandler(null);
        QuicServer.stop();
    }
}
