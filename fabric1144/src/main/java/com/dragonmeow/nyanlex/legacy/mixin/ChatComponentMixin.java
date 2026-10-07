package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements com.dragonmeow.nyanlex.legacy.LegacyChatComponentAccess {
    @org.spongepowered.asm.mixin.gen.Accessor("allMessages")
    public abstract java.util.List<net.minecraft.client.GuiMessage> nyanlex$getAllMessages();

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void nyanlex$translate(Component message, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComponent.translate")) return;
        try {
            if (LegacyTranslatorMod.interceptChat(message)) ci.cancel();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComponent.translate", guardError);
        }
    }
}
