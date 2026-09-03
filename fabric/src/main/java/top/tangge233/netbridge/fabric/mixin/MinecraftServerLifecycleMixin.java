package top.tangge233.netbridge.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import top.tangge233.netbridge.fabric.mc.NativeServerTransport;
import top.tangge233.netbridge.server.NativeAcceptor;

@Mixin(MinecraftServer.class)
public class MinecraftServerLifecycleMixin {

    @Inject(
            method = "runServer",
            at = @At("HEAD")
    )
    private void netbridge$startAcceptors(CallbackInfo ci) {
        var self = (MinecraftServer) (Object) this;
        if (!self.isDedicatedServer()) {
            return;
        }

        NativeAcceptor.setConnectionHandler(connection ->
                NativeServerTransport.adopt(self, connection)
        );
        NativeAcceptor.start(
                self.getPort(),
                self.getLocalIp()
        );
    }

    @Inject(
            method = "close()V",
            remap = false,
            at = @At("HEAD")
    )
    private void netbridge$stopAcceptors(CallbackInfo ci) {
        NativeAcceptor.setConnectionHandler(null);
        NativeAcceptor.stop();
    }

}
