package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.translate.InternalRenderGuard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent the broad screen-text hook from translating the chat HUD while another screen is open. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements com.dragonmeow.mctranslator.fabric.ChatComponentAccess {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage> mctranslator$getAllMessages();

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("HEAD"), require = 0)
    private void mctranslator$enterChatRender(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                               int tickCount, int mouseX, int mouseY,
                                               boolean focused, boolean showBackground,
                                               CallbackInfo ci) {
        InternalRenderGuard.enter();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("RETURN"), require = 0)
    private void mctranslator$exitChatRender(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                              int tickCount, int mouseX, int mouseY,
                                              boolean focused, boolean showBackground,
                                              CallbackInfo ci) {
        InternalRenderGuard.exit();
    }
}
