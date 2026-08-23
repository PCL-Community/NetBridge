package top.tangge233.qmc.neoforge.mixin;

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
import top.tangge233.qmc.net.NetworksAbility;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.StatusNetworksCapture;

/**
 * Ping 响应到达时：记录 networks 能力到地址缓存；若服务端支持 quic-raw，
 * 在延迟栏文本下方追加「支持 QUIC 连接」标注。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerResponseMixin {
    @Shadow(remap = false)
    private Connection val$connection;

    @Shadow(remap = false)
    private ServerData val$data;

    @Inject(method = "handleStatusResponse", at = @At("RETURN"))
    private void qmc$recordNetworks(CallbackInfo ci) {
        NetworksAbility networks = StatusNetworksCapture.take();
        SocketAddress remote = this.val$connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            QuicClient.record(inetSocketAddress, networks);
        }
        if (networks.supportsQuicRaw() && this.val$data != null) {
            Component tag = Component.literal(" [QUIC]").withStyle(ChatFormatting.AQUA);
            this.val$data.motd = this.val$data.motd == null
                    ? tag
                    : Component.empty().append(this.val$data.motd).append(tag);
        }
    }
}
