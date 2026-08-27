package top.tangge233.netbridge.neoforge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.StatusNetworksCodec;
import top.tangge233.netbridge.server.NativeAcceptor;

/**
 * 在 status 包编码时向 JSON 注入 networks 能力声明。
 *
 * <p>1.21.1 的 MinecraftServer 已无 getStatusJson（历史注入点失效），
 * 改为在 ClientboundStatusResponsePacket.write 的 writeJsonWithCodec 处
 * 重定向：先序列化 ServerStatus，再追加 networks 后直接写 buf。
 *
 * <p>注入后超出原版 256KiB writeUtf 上限（大图标 + 长 motd）时放弃注入，
 * 回退原版编码路径——ping 不因注入而失败。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseWriteMixin {

    /** 原版 status JSON 的 wire 上限（与 writeJsonWithCodec 内部一致）。 */
    private static final int MAX_STATUS_JSON = 262144;

    @Redirect(
            method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private <T> void netbridge$injectNetworks(FriendlyByteBuf buf, Codec<T> codec, T value) {
        String json = null;
        if (value instanceof ServerStatus status && NativeAcceptor.isRunning()) {
            Codec<ServerStatus> statusCodec = (Codec<ServerStatus>) (Codec<?>) codec;
            json = statusCodec
                    .encodeStart(JsonOps.INSTANCE, status)
                    .getOrThrow(msg -> new IllegalStateException("Failed to encode status: " + msg))
                    .toString();
        }
        if (json != null) {
            String injected = StatusNetworksCodec.addNetworks(json, StatusNetworksCodec.buildNetworks(
                    NativeAcceptor.announcement().entries()));
            // writeUtf 上限按 UTF-8 字节计，length() 为字符数（CJK/emoji 会低估）。
            if (injected.getBytes(StandardCharsets.UTF_8).length <= MAX_STATUS_JSON) {
                buf.writeUtf(injected, MAX_STATUS_JSON);
                return;
            }
            NetBridge.LOGGER.warn(
                    "networks injection dropped: status json {} chars + networks exceeds {} limit",
                    json.length(),
                    MAX_STATUS_JSON);
        }
        buf.writeJsonWithCodec(codec, value);
    }
}
