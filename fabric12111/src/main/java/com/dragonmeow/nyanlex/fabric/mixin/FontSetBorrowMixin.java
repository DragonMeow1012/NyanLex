package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.BorrowedGlyphs;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * Wiring only. When a custom (non-vanilla) font set picks its active glyph providers, one extra
 * provider is appended last so that characters the font does not define are drawn (and measured)
 * with the default font's glyph instead of the missing-glyph box; see {@link BorrowedGlyphs}.
 * Vanilla fonts and every character a font defines itself behave exactly as before.
 */
@Mixin(FontSet.class)
public abstract class FontSetBorrowMixin {

    @Shadow
    @Final
    private GlyphStitcher stitcher;

    @Inject(
            method = "selectProviders(Ljava/util/List;Ljava/util/Set;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void nyanlex$appendBorrowProvider(List<GlyphProvider.Conditional> all, Set<FontOption> options,
                                              CallbackInfoReturnable<List<GlyphProvider>> cir) {
        if (!HookGuard.enter("FontSet.appendBorrowProvider")) return;
        try {
            List<GlyphProvider> own = cir.getReturnValue();
            List<GlyphProvider> withBorrowing = BorrowedGlyphs.withBorrowing(
                    (FontSet) (Object) this, BorrowedGlyphs.fontId(this.stitcher), own);
            if (withBorrowing != own) {
                cir.setReturnValue(withBorrowing);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("FontSet.appendBorrowProvider", guardError);
        }
    }

    @Inject(method = "reload(Ljava/util/Set;)V", at = @At("RETURN"), require = 0)
    private void nyanlex$fontReloaded(Set<FontOption> options, CallbackInfo ci) {
        if (!HookGuard.enter("FontSet.fontReloaded")) return;
        try {
            BorrowedGlyphs.fontReloaded((FontSet) (Object) this, BorrowedGlyphs.fontId(this.stitcher));
        } catch (Throwable guardError) {
            HookGuard.fail("FontSet.fontReloaded", guardError);
        }
    }
}
