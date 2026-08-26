package top.tangge233.netbridge.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import top.tangge233.netbridge.fabric.mc.NativeServerTransport;
import top.tangge233.netbridge.server.NativeAcceptor;

/**
 * 服务端生命周期：runServer 开始时启动 QUIC/KCP acceptor，close 时停止。
 * （不依赖 fabric-api，纯 mixin 实现。）
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerLifecycleMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void netbridge$startAcceptors(CallbackInfo ci) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        // 仅专用服务器启动 acceptor：集成端（单人/LAN）的 getPort() 为 -1，
        // QUIC 会绑定失败刷错误日志；KCP 随机端口会在玩家本机开 UDP 监听。
        if (!self.isDedicatedServer()) {
            return;
        }
        NativeAcceptor.setConnectionHandler(connId -> NativeServerTransport.adopt(self, connId));
        NativeAcceptor.start(self.getPort(), self.getLocalIp());
    }

    /**
     * close() 是 AutoCloseable 接口方法，intermediary 中保持原名，
     * 关闭注解处理器的重映射检查（remap = false）。
     */
    @Inject(method = "close()V", remap = false, at = @At("HEAD"))
    private void netbridge$stopAcceptors(CallbackInfo ci) {
        NativeAcceptor.setConnectionHandler(null);
        NativeAcceptor.stop();
    }
}
