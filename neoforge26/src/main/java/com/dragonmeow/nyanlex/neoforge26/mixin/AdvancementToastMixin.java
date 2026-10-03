package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge26.NyanLexNeoForge26;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Translates the advancement title before vanilla measures and wraps its toast text. */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {
    @ModifyArg(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            index = 0,
            require = 0)
    private FormattedText nyanlex$translateTitle(FormattedText text) {
        if (!(text instanceof Component component)
                || !HookGuard.enter("AdvancementToast.title")) return text;
        try {
            return NyanLexNeoForge26.advancementText(component);
        } catch (Throwable guardError) {
            HookGuard.fail("AdvancementToast.title", guardError);
            return text;
        }
    }
}
