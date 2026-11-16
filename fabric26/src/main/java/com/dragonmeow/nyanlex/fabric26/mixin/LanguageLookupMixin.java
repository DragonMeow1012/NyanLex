package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.fabric26.OriginalLanguage;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code I18n.get} formats the text of the current language table; while the show-original key is
 * on it answers from the English-first table (see {@link OriginalLanguage}) and is untouched
 * otherwise. This game version has no {@code I18n.exists}, so there is no second entry point.
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
}
