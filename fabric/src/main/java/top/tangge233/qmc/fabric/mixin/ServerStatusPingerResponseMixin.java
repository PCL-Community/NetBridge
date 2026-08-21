package top.tangge233.qmc.fabric.mixin;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.StatusNetworksCapture;

/**
 * Ping 响应到达时，把解码线程暂存的 networks 能力绑定到对应服务器地址
 * （客户端据此在后续连接时选择 QUIC）。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerResponseMixin {
    @Shadow(remap = false)
    private Connection val$connection;

    @Inject(method = "handleStatusResponse", at = @At("HEAD"))
    private void qmc$recordNetworks(CallbackInfo ci) {
        SocketAddress remote = this.val$connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            QuicClient.record(inetSocketAddress, StatusNetworksCapture.take());
        }
    }
}
