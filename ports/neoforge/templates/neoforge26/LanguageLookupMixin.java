package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.neoforge26.OriginalLanguage;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * {@code I18n} keeps its own reference to the language table instead of asking
 * {@code Language.getInstance()} in this game version, so it needs its own hook for the show-original
 * key. Both entry points answer from the English-first table while the key is on and are untouched
 * otherwise. (The 26.2 and 26.3 {@code I18n} reads {@code Language.getInstance()} and needs no hook.)
 */
@Mixin(I18n.class)
public abstract class LanguageLookupMixin {

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nyanlex$originalText(String key, Object[] args, CallbackInfoReturnable<String> cir) {
        Language english = OriginalLanguage.effective();
        if (english == null) return;
        if (!HookGuard.enter("LanguageLookup.originalText")) return;
        try {
            cir.setReturnValue(nyanlex$format(english, key, args));
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

    /** {@code I18n.get} semantics against {@code table}, including its format-error fallback text. */
    private static String nyanlex$format(Language table, String key, Object[] args) {
        String text = table.getOrDefault(key);
        try {
            return String.format(Locale.ROOT, text, args);
        } catch (IllegalFormatException e) {
            return "Format error: " + text;
        }
    }
}
