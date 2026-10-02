package com.dragonmeow.nyanslate.neoforge.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.translate.InternalRenderGuard;
import com.dragonmeow.nyanslate.neoforge.ChatComponentAccess;
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
public abstract class ChatComponentMixin implements ChatComponentAccess {
    @Accessor("allMessages")
    @Override
    public abstract java.util.List<GuiMessage> nyanslate$getAllMessages();

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("HEAD"), require = 0)
    private void nyanslate$enterChatRender(GuiGraphics graphics, int tickCount,
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
    private void nyanslate$exitChatRender(GuiGraphics graphics, int tickCount,
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
