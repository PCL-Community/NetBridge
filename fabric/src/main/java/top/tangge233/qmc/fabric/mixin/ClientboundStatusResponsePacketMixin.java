package top.tangge233.qmc.fabric.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.qmc.net.StatusNetworks;
import top.tangge233.qmc.net.StatusNetworksCapture;

/**
 * 在 status 包解码时从原始 JSON 捕获 networks 能力（原版 ServerStatus codec
 * 会丢弃未知顶层字段，ADR-0002）。
 *
 * ClientboundStatusResponsePacket 是 record：解码构造器中 readJsonWithCodec
 * 发生在 this() 之前，@Redirect handler 必须为 static。解析结果写入
 * {@link StatusNetworksCapture} 的线程级暂存槽，由消费方在包处理时取走。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class ClientboundStatusResponsePacketMixin {

    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;readJsonWithCodec(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;"))
    private static <T> T qmc$captureNetworks(FriendlyByteBuf buf, Codec<T> codec) {
        String json = buf.readUtf();
        StatusNetworksCapture.capture(StatusNetworks.parse(json));
        JsonElement element = JsonParser.parseString(json);
        return codec.parse(JsonOps.INSTANCE, element)
                .getOrThrow(message -> new DecoderException("Failed to decode json: " + message));
    }
}
