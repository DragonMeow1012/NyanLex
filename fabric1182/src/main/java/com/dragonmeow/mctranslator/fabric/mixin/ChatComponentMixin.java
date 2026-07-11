package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes the 1.19.4 chat history so an asynchronous translation can replace its source row. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage> mctranslator$getAllMessages();

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void mctranslator$translateLegacyChat(Component message, CallbackInfo ci) {
        if (MctranslatorFabric.interceptLegacyChat(message)) ci.cancel();
    }
}
