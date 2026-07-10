package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.translate.InternalRenderGuard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent the broad screen-text hook from translating the chat HUD while another screen is open. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("HEAD"), require = 0)
    private void mctranslator$enterChatRender(GuiGraphics graphics, int tickCount,
                                               int mouseX, int mouseY, boolean focused,
                                               CallbackInfo ci) {
        InternalRenderGuard.enter();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("RETURN"), require = 0)
    private void mctranslator$exitChatRender(GuiGraphics graphics, int tickCount,
                                              int mouseX, int mouseY, boolean focused,
                                              CallbackInfo ci) {
        InternalRenderGuard.exit();
    }
}
