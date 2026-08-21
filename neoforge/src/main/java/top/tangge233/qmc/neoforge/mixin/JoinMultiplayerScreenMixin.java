package top.tangge233.qmc.neoforge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.qmc.net.QuicClient;
import top.tangge233.qmc.net.TransportMode;

/**
 * 多人游戏屏幕底部加入「传输：TCP/QUIC」循环按钮（ADR-0002 模式选择），
 * 替代 JVM 参数配置。
 *
 * mixin 继承目标类的父类 Screen，即可在注入方法内以 this 调用
 * protected 的 addRenderableWidget。
 *
 * 注意：本文件在 :neoforge 与 :fabric 各有一份源码副本（ADR-0006）。
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void qmc$addTransportButton(CallbackInfo ci) {
        CycleButton<TransportMode> button = CycleButton.builder((TransportMode mode) -> {
                    Component label = switch (mode) {
                        case TCP_ONLY -> Component.literal("传输: TCP");
                        case QUIC_ONLY -> Component.literal("传输: QUIC").withStyle(ChatFormatting.AQUA);
                        case QUIC_WITH_TCP_FALLBACK ->
                                Component.literal("传输: QUIC+回退").withStyle(ChatFormatting.AQUA);
                    };
                    return label;
                })
                .withValues(TransportMode.values())
                .withInitialValue(QuicClient.mode())
                .displayOnlyValue()
                .create(5, this.height - 24, 110, 20,
                        Component.literal("传输模式"),
                        (btn, mode) -> QuicClient.setMode(mode));
        this.addRenderableWidget(button);
    }
}
