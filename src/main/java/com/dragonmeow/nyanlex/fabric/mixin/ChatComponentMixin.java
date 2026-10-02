package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.translate.InternalRenderGuard;
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
public abstract class ChatComponentMixin implements com.dragonmeow.nyanlex.fabric.ChatComponentAccess {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage> nyanlex$getAllMessages();

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("HEAD"), require = 0)
    private void nyanlex$enterChatRender(GuiGraphics graphics, int tickCount,
                                               int mouseX, int mouseY, boolean focused,
                                               CallbackInfo ci) {
        HookGuard.enterSticky("ChatComponent.enterChatRender");
        try {
            InternalRenderGuard.enter();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComponent.enterChatRender", guardError);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("RETURN"), require = 0)
    private void nyanlex$exitChatRender(GuiGraphics graphics, int tickCount,
                                              int mouseX, int mouseY, boolean focused,
                                              CallbackInfo ci) {
        HookGuard.enterSticky("ChatComponent.exitChatRender");
        try {
            InternalRenderGuard.exit();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComponent.exitChatRender", guardError);
        }
    }
}
