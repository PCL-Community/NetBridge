package top.tangge233.netbridge.neoforge.mixin;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.ability.StatusNetworksCapture;
import top.tangge233.netbridge.transport.TransportSelector;

/**
 * Ping 响应到达时：记录 networks 能力到地址缓存；若存在任一可用的加速传输，
 * 在延迟栏文本下方追加「支持 QUIC/KCP 连接」标注。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerResponseMixin {
    @Shadow(remap = false)
    private Connection val$connection;

    @Shadow(remap = false)
    private ServerData val$data;

    @Inject(method = "handleStatusResponse", at = @At("RETURN"))
    private void netbridge$recordNetworks(CallbackInfo ci) {
        var networks = StatusNetworksCapture.take();
        SocketAddress remote = this.val$connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            TransportSelector.record(inetSocketAddress, networks);
        }
        if (networks.hasUsableAccelerated() && this.val$data != null) {
            Component tag = Component.translatable("netbridge.status.accelerated_tag")
                    .withStyle(ChatFormatting.AQUA);
            this.val$data.motd = this.val$data.motd == null
                    ? tag
                    : Component.empty().append(this.val$data.motd).append(tag);
        }
    }
}
