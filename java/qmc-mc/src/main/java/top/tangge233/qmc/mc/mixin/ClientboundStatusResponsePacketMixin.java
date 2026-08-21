package top.tangge233.qmc.mc.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.qmc.net.Networks;
import top.tangge233.qmc.net.QuicStatusPacket;
import top.tangge233.qmc.net.StatusNetworks;

/**
 * 在 status 包解码时从原始 JSON 捕获 networks 能力（原版 ServerStatus codec
 * 会丢弃未知顶层字段，ADR-0002）。
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class ClientboundStatusResponsePacketMixin implements QuicStatusPacket {
    @Unique
    private Networks qmc$networks = Networks.empty();

    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;readJsonWithCodec(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;"))
    private <T> T qmc$captureNetworks(FriendlyByteBuf buf, Codec<T> codec) {
        String json = buf.readUtf();
        this.qmc$networks = StatusNetworks.parse(json);
        JsonElement element = JsonParser.parseString(json);
        return codec.parse(JsonOps.INSTANCE, element)
                .getOrThrow(message -> new DecoderException("Failed to decode json: " + message));
    }

    @Override
    public Networks qmcNetworks() {
        return this.qmc$networks;
    }
}
