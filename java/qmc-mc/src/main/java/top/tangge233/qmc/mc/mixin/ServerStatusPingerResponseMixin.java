package top.tangge233.qmc.mc.mixin;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.QuicStatusPacket;

/**
 * Ping 响应到达时，把包上携带的 networks 能力绑定到对应服务器地址
 * （客户端据此在后续连接时选择 QUIC）。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerResponseMixin {
    @Shadow(remap = false)
    private Connection val$connection;

    @Inject(method = "handleStatusResponse", at = @At("HEAD"))
    private void qmc$recordNetworks(ClientboundStatusResponsePacket packet, CallbackInfo ci) {
        if ((Object) packet instanceof QuicStatusPacket quicStatusPacket) {
            SocketAddress remote = this.val$connection.channel().remoteAddress();
            if (remote instanceof InetSocketAddress inetSocketAddress) {
                QuicClient.record(inetSocketAddress, quicStatusPacket.qmcNetworks());
            }
        }
    }
}
