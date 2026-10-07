package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes the version-native chat history for a late rich-message replacement. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements com.dragonmeow.nyanlex.fabric.ChatComponentAccess {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage<Component>> nyanlex$getAllMessages();

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void nyanlex$translateLegacyChat(Component message, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComponent.translateLegacyChat")) return;
        try {
            if (NyanLexFabric.interceptLegacyChat(message)) ci.cancel();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComponent.translateLegacyChat", guardError);
        }
    }
}
