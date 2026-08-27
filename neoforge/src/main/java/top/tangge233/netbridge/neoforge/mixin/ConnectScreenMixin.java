package top.tangge233.netbridge.neoforge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.transport.ConnectStatus;
import top.tangge233.netbridge.transport.TransportMode;

/**
 * 连接屏幕另起一行渲染实际状态机文案：「正在建立 X 连接」→
 * 「正在回退 TCP 连接」。由 {@link ConnectStatus} 驱动，非配置回显。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {

    protected ConnectScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void netbridge$drawStatusLine(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        var phase = ConnectStatus.phase();
        if (phase == ConnectStatus.Phase.IDLE) {
            return;
        }
        Component text;
        if (phase == ConnectStatus.Phase.CONNECTING) {
            TransportMode mode = ConnectStatus.mode();
            String modeKey = mode == null ? TransportMode.TCP.configValue() : mode.configValue();
            text = Component.translatable(
                    "netbridge.connect.connecting",
                    Component.translatable("netbridge.transport." + modeKey));
        } else {
            text = Component.translatable("netbridge.connect.falling_back");
        }
        graphics.drawCenteredString(this.font, text, this.width / 2, this.height / 2 - 65, 0xFFFFFF);
    }
}
