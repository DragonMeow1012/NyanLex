package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class FontTextMixin {
    @ModifyVariable(method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$draw(Component text) {
        if (!HookGuard.enter("FontText.draw")) return text;
        try {
     return LegacyTranslatorMod.translateVisible(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.draw", guardError);
            return text;
        }
    }

    @ModifyVariable(method = "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$shadow(Component text) {
        if (!HookGuard.enter("FontText.shadow")) return text;
        try {
     return LegacyTranslatorMod.translateVisible(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.shadow", guardError);
            return text;
        }
    }

    @ModifyVariable(method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedText nyanslate$split(FormattedText text) {
        if (!HookGuard.enter("FontText.split")) return text;
        try {
            return text instanceof Component ? LegacyTranslatorMod.translateVisible((Component) text) : text;
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.split", guardError);
            return text;
        }
    }
}
