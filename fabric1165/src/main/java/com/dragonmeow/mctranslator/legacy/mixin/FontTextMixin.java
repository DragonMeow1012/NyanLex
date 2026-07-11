package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
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
    private Component mctranslator$draw(Component text) { return LegacyTranslatorMod.translateVisible(text); }

    @ModifyVariable(method = "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component mctranslator$shadow(Component text) { return LegacyTranslatorMod.translateVisible(text); }

    @ModifyVariable(method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedText mctranslator$split(FormattedText text) {
        return text instanceof Component ? LegacyTranslatorMod.translateVisible((Component) text) : text;
    }
}
