package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.fabric26.OriginalLanguage;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the show-original key is on, the game's current language table is the English-first one
 * (see {@link OriginalLanguage}). Translatable components, tooltips, font splitting and every other
 * reader of {@code Language.getInstance()} then resolve keys against it; each of them re-resolves
 * when the table instance it last used differs from the current one, so no cache has to be cleared.
 *
 * <p>This is among the hottest calls in the game. With the key off the handler is one volatile read
 * and returns, before any bookkeeping.</p>
 */
@Mixin(Language.class)
public abstract class LanguageInstanceMixin {

    @Inject(method = "getInstance", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nyanlex$originalTable(CallbackInfoReturnable<Language> cir) {
        Language english = OriginalLanguage.effective();
        if (english == null) return;
        if (!HookGuard.enter("LanguageInstance.originalTable")) return;
        try {
            cir.setReturnValue(english);
        } catch (Throwable guardError) {
            HookGuard.fail("LanguageInstance.originalTable", guardError);
        }
    }
}
