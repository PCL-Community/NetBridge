package top.tangge233.netbridge.neoforge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.transport.ClientConfig;
import top.tangge233.netbridge.transport.TransportMode;

/**
 * 多人游戏屏幕左上角「传输」循环按钮（TCP/QUIC/KCP 三档），切换即写回
 * client.toml；tooltip 说明自动降级策略。
 *
 * <p>mixin 继承目标类的父类 Screen，即可在注入方法内以 this 调用
 * protected 的 addRenderableWidget。
 *
 * <p>注意：本文件在 :neoforge 与 :fabric 各有一份源码副本，
 * 修改时必须同步两处。
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void netbridge$addTransportButton(CallbackInfo ci) {
        CycleButton<TransportMode> button = CycleButton.builder(
                        (TransportMode mode) -> Component.translatable("netbridge.transport." + mode.configValue())
                                .withStyle(mode == TransportMode.TCP ? ChatFormatting.WHITE : ChatFormatting.AQUA))
                .withValues(TransportMode.values())
                .withInitialValue(ClientConfig.mode())
                .displayOnlyValue()
                // 左上角：原版底部 64px 是两行居中按钮块（select/edit/refresh 等），
                // 放底部会与其重叠；顶部左侧仅居中标题，无控件。
                .create(5, 6, 110, 20,
                        Component.translatable("netbridge.transport.mode"),
                        (btn, mode) -> ClientConfig.setMode(mode));
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("netbridge.transport.tooltip")));
        this.addRenderableWidget(button);
    }
}
