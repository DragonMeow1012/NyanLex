package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class FontTextMixin {
    @ModifyVariable(method = "draw(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String mctranslator$draw(String text) { return LegacyTranslatorMod.translateVisibleString(text); }
    @ModifyVariable(method = "drawShadow(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String mctranslator$shadow(String text) { return LegacyTranslatorMod.translateVisibleString(text); }
    @ModifyVariable(method = "split(Ljava/lang/String;I)Ljava/util/List;", at = @At("HEAD"), argsOnly = true, require = 0)
    private String mctranslator$split(String text) { return LegacyTranslatorMod.translateVisibleString(text); }
}
