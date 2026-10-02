package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void nyanslate$translate(Component message, CallbackInfo ci) {
        if (LegacyTranslatorMod.interceptChat(message)) ci.cancel();
    }
}
