package top.tangge233.netbridge.neoforge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.netbridge.ability.StatusNetworksCodec;
import top.tangge233.netbridge.server.NativeAcceptor;

/**
 * 在 status 包编码时向 JSON 注入 networks 能力声明。
 *
 * <p>1.21.1 的 MinecraftServer 已无 getStatusJson（历史注入点失效），
 * 改为在 ClientboundStatusResponsePacket.write 的 writeJsonWithCodec 处
 * 重定向：先序列化 ServerStatus，再追加 networks 后直接写 buf。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseWriteMixin {

    @Redirect(
            method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private <T> void netbridge$injectNetworks(FriendlyByteBuf buf, Codec<T> codec, T value) {
        if (value instanceof ServerStatus status && NativeAcceptor.isRunning()) {
            Codec<ServerStatus> statusCodec = (Codec<ServerStatus>) (Codec<?>) codec;
            String json = statusCodec
                    .encodeStart(JsonOps.INSTANCE, status)
                    .getOrThrow(msg -> new IllegalStateException("Failed to encode status: " + msg))
                    .toString();
            buf.writeUtf(
                    StatusNetworksCodec.addNetworks(json, StatusNetworksCodec.buildNetworks(
                            NativeAcceptor.announcement().entries())),
                    262144);
            return;
        }
        buf.writeJsonWithCodec(codec, value);
    }
}
