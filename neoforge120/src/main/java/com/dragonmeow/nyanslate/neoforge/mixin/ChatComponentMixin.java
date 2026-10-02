package com.dragonmeow.nyanslate.neoforge.mixin;

import com.dragonmeow.nyanslate.translate.InternalRenderGuard;
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
public abstract class ChatComponentMixin implements com.dragonmeow.nyanslate.neoforge.ChatComponentAccess {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage> nyanslate$getAllMessages();

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V",
            at = @At("HEAD"), require = 0)
    private void nyanslate$enterChatRender(GuiGraphics graphics, int tickCount,
                                               int mouseX, int mouseY, CallbackInfo ci) {
        InternalRenderGuard.enter();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V",
            at = @At("RETURN"), require = 0)
    private void nyanslate$exitChatRender(GuiGraphics graphics, int tickCount,
                                              int mouseX, int mouseY, CallbackInfo ci) {
        InternalRenderGuard.exit();
    }
}
