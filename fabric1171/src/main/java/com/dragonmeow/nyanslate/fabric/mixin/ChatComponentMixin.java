package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.fabric.NyanslateFabric;
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
public abstract class ChatComponentMixin {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage<Component>> nyanslate$getAllMessages();

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void nyanslate$translateLegacyChat(Component message, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComponent.translateLegacyChat")) return;
        try {
            if (NyanslateFabric.interceptLegacyChat(message)) ci.cancel();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComponent.translateLegacyChat", guardError);
        }
    }
}
