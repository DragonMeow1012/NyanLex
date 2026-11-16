package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.BorrowedGlyphs;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Wiring only. When a custom (non-vanilla) font set has loaded its glyph providers, one extra
 * provider is appended last so that characters the font does not define are drawn (and measured)
 * with the default font's glyph instead of the missing-glyph box; see {@link BorrowedGlyphs}.
 * Vanilla fonts and every character a font defines itself behave exactly as before.
 *
 * <p>1.20.1 keeps the active providers in a field that {@code reload(List)} fills last (it keeps
 * only providers that win at least one glyph, so an empty-handed provider would be dropped by the
 * game's own filter); the provider is therefore added after {@code reload} has finished, while the
 * glyph caches are still empty.</p>
 */
@Mixin(FontSet.class)
public abstract class FontSetBorrowMixin {

    @Shadow @Final
    private ResourceLocation name;

    @Shadow @Final
    private List<GlyphProvider> providers;

    @Inject(method = "reload(Ljava/util/List;)V", at = @At("RETURN"), require = 0)
    private void nyanlex$appendBorrowProvider(List<GlyphProvider> list, CallbackInfo ci) {
        if (!HookGuard.enter("FontSet.appendBorrowProvider")) return;
        try {
            FontSet self = (FontSet) (Object) this;
            List<GlyphProvider> own = List.copyOf(this.providers);
            List<GlyphProvider> withBorrowing = BorrowedGlyphs.withBorrowing(self, this.name, own);
            if (withBorrowing != own) {
                this.providers.add(withBorrowing.get(withBorrowing.size() - 1));
            }
        } catch (Throwable guardError) {
            HookGuard.fail("FontSet.appendBorrowProvider", guardError);
        }
    }

    @Inject(method = "reload(Ljava/util/List;)V", at = @At("RETURN"), require = 0)
    private void nyanlex$fontReloaded(List<GlyphProvider> list, CallbackInfo ci) {
        if (!HookGuard.enter("FontSet.fontReloaded")) return;
        try {
            FontSet self = (FontSet) (Object) this;
            BorrowedGlyphs.fontReloaded(self, this.name);
        } catch (Throwable guardError) {
            HookGuard.fail("FontSet.fontReloaded", guardError);
        }
    }
}
