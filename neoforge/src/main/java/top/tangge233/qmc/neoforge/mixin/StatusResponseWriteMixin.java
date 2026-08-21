package top.tangge233.qmc.neoforge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.qmc.jni.QuicNative;
import top.tangge233.qmc.net.StatusNetworks;
import top.tangge233.qmc.server.QuicServer;

/**
 * 在 status 包编码时向 JSON 注入 networks 能力声明（ADR-0002）。
 *
 * 1.21.1 的 MinecraftServer 已无 getStatusJson（历史注入点失效），
 * 改为在 ClientboundStatusResponsePacket.write 的 writeJsonWithCodec 处
 * 重定向：先序列化 ServerStatus，再追加 networks 后直接写 buf。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseWriteMixin {

    @Redirect(
            method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private <T> void qmc$injectNetworks(FriendlyByteBuf buf, Codec<T> codec, T value) {
        if (QuicServer.isRunning() && value instanceof ServerStatus status) {
            @SuppressWarnings("unchecked")
            Codec<ServerStatus> statusCodec = (Codec<ServerStatus>) (Codec<?>) codec;
            String json = statusCodec.encodeStart(JsonOps.INSTANCE, status)
                    .getOrThrow(msg -> new IllegalStateException("Failed to encode status: " + msg))
                    .toString();
            buf.writeUtf(StatusNetworks.addNetworks(json, QuicServer.port(), QuicNative.RAW_FEATURE), 262144);
            return;
        }
        buf.writeJsonWithCodec(codec, value);
    }
}
