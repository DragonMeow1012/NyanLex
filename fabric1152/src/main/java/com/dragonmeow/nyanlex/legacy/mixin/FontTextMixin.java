package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class FontTextMixin {
    @ModifyVariable(method = "draw(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanlex$draw(String text) {
        if (!HookGuard.enter("FontText.draw")) return text;
        try {
     return LegacyTranslatorMod.translateVisibleString(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.draw", guardError);
            return text;
        }
    }
    @ModifyVariable(method = "drawShadow(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanlex$shadow(String text) {
        if (!HookGuard.enter("FontText.shadow")) return text;
        try {
     return LegacyTranslatorMod.translateVisibleString(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.shadow", guardError);
            return text;
        }
    }
    @ModifyVariable(method = "split(Ljava/lang/String;I)Ljava/util/List;", at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanlex$split(String text) {
        if (!HookGuard.enter("FontText.split")) return text;
        try {
     return LegacyTranslatorMod.translateVisibleString(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontText.split", guardError);
            return text;
        }
    }
}
