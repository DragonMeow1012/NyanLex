package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.neoforge.OriginalLanguage;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code I18n} keeps its own reference to the language table instead of asking
 * {@code Language.getInstance()}, so it needs its own hook for the show-original key. Both entry
 * points answer from the English-first table while the key is on and are untouched otherwise.
 */
@Mixin(I18n.class)
public abstract class LanguageLookupMixin {

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nyanlex$originalText(String key, Object[] args, CallbackInfoReturnable<String> cir) {
        Language english = OriginalLanguage.effective();
        if (english == null) return;
        if (!HookGuard.enter("LanguageLookup.originalText")) return;
        try {
            cir.setReturnValue(OriginalLanguage.format(english, key, args));
        } catch (Throwable guardError) {
            HookGuard.fail("LanguageLookup.originalText", guardError);
        }
    }

    @Inject(method = "exists", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nyanlex$originalExists(String key, CallbackInfoReturnable<Boolean> cir) {
        Language english = OriginalLanguage.effective();
        if (english == null) return;
        if (!HookGuard.enter("LanguageLookup.originalExists")) return;
        try {
            cir.setReturnValue(english.has(key));
        } catch (Throwable guardError) {
            HookGuard.fail("LanguageLookup.originalExists", guardError);
        }
    }
}
