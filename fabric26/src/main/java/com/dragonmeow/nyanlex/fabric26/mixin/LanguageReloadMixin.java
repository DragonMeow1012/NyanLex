package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.fabric26.OriginalLanguage;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rebuilds the English-first table used by the show-original key every time the game reloads its
 * language (start-up, resource-pack change, language change, F3+T). Runs after the game has set up
 * its own table, so a reload that happens while the key is on switches straight to the new table.
 */
@Mixin(LanguageManager.class)
public abstract class LanguageReloadMixin {

    @Shadow
    private String currentCode;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"), require = 0)
    private void nyanlex$loadOriginalTable(ResourceManager manager, CallbackInfo ci) {
        if (!HookGuard.enter("LanguageReload.loadOriginalTable")) return;
        try {
            OriginalLanguage.reload(manager, this.currentCode);
        } catch (Throwable guardError) {
            HookGuard.fail("LanguageReload.loadOriginalTable", guardError);
        }
    }
}
