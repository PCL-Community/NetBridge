package top.tangge233.qmc.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import top.tangge233.qmc.net.StatusNetworks;
import top.tangge233.qmc.server.QuicServer;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "getStatusJson", at = @At("RETURN"), cancellable = true)
    private void qmc$advertiseQuic(CallbackInfoReturnable<String> cir) {
        String json = cir.getReturnValue();
        if (json != null && QuicServer.isRunning()) {
            cir.setReturnValue(StatusNetworks.addNetworks(json, QuicServer.port(),
                    top.tangge233.qmc.jni.QuicNative.RAW_FEATURE));
        }
    }
}
