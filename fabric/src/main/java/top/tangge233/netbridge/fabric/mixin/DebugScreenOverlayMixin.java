package top.tangge233.netbridge.fabric.mixin;

import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.netbridge.transport.ConnectionDisplay;

/**
 * F3 调试屏追加 net-bridge 单行：<code>[net-bridge] &lt;PROTOCOL&gt; &lt;addr&gt;</code>。
 * 未在任何 net-bridge 连接中时不显示。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void netbridge$appendProtocolLine(CallbackInfoReturnable<List<String>> cir) {
        String line = ConnectionDisplay.current();
        if (line != null) {
            cir.getReturnValue().add("[net-bridge] " + line);
        }
    }
}
