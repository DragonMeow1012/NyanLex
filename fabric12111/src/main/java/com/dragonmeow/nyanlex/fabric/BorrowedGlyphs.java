package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.fabric.mixin.BakedSheetGlyphAccessor;
import com.dragonmeow.nyanlex.fabric.mixin.GlyphStitcherAccessor;
import com.dragonmeow.nyanlex.translate.GlyphBorrowPolicy;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets a custom (non-vanilla) font show characters it has no glyph for by borrowing the vanilla
 * default font's glyph, moved vertically so it lines up with the custom font's own Latin letters.
 *
 * <p>Wiring: {@code FontSetBorrowMixin} appends one {@link Provider} as the LAST glyph provider
 * of every custom font set. A font set only asks its last provider for a character when none of
 * its own providers has it, so characters the font defines are never affected. The borrowed
 * glyph reports the donor's metrics through {@code info()} (the value layout and width
 * measuring read) and draws the donor's image through the same object, shifted vertically, so
 * width and drawing always agree. Vanilla fonts get no provider at all. Every entry point is
 * crash-guarded: a failure falls back to the vanilla "missing glyph" behaviour.</p>
 */
public final class BorrowedGlyphs {

    private static final String HOOK_PROVIDE = "FontBorrow.provide";
    private static final String HOOK_BAKE = "FontBorrow.bake";

    private static final GlyphBorrowPolicy.ShiftCache SHIFTS = new GlyphBorrowPolicy.ShiftCache();

    /** The currently loaded {@code minecraft:default} font set, the donor of borrowed glyphs. */
    private static volatile FontSet defaultSet;

    private BorrowedGlyphs() {
    }

    /** The font id a font set was created for (it is not stored on the set itself). */
    public static Identifier fontId(GlyphStitcher stitcher) {
        return ((GlyphStitcherAccessor) stitcher).nyanlex$texturePrefix();
    }

    /** A font set finished (re)loading: forget measured shifts and remember the donor font. */
    public static void fontReloaded(FontSet set, Identifier id) {
        SHIFTS.clear();
        if (GlyphBorrowPolicy.isDefaultFont(id.getNamespace(), id.getPath())) {
            defaultSet = set;
        }
    }

    /**
     * The provider list to use for {@code owner}: {@code own} unchanged for vanilla fonts,
     * otherwise {@code own} plus the borrowing provider at the very end.
     */
    public static List<GlyphProvider> withBorrowing(FontSet owner, Identifier id, List<GlyphProvider> own) {
        if (GlyphBorrowPolicy.isVanillaFont(id.getNamespace(), id.getPath())) {
            return own;
        }
        List<GlyphProvider> all = new ArrayList<>(own.size() + 1);
        all.addAll(own);
        all.add(new Provider(owner, id.toString(), List.copyOf(own)));
        return List.copyOf(all);
    }

    /** Top edge of a drawn glyph relative to the text origin, NaN for blank (space) glyphs. */
    private static float topOf(BakedGlyph glyph) {
        if (glyph == null) {
            return Float.NaN;
        }
        TextRenderable.Styled probe = glyph.createGlyph(0.0F, 0.0F, 0xFFFFFFFF, 0, Style.EMPTY, 0.0F, 0.0F);
        return probe == null ? Float.NaN : probe.top();
    }

    /**
     * The donor glyph moved down by {@code shift}. An atlas-backed glyph is re-created with its
     * quad moved (same result as the font's own letters, including italic slant); any other kind
     * is wrapped so that it is merely drawn at a shifted position.
     */
    private static BakedGlyph shifted(BakedGlyph base, float shift) {
        if (base instanceof BakedSheetGlyph && base instanceof BakedSheetGlyphAccessor glyph) {
            return new BakedSheetGlyph(
                    base.info(), glyph.nyanlex$renderTypes(), glyph.nyanlex$textureView(),
                    glyph.nyanlex$u0(), glyph.nyanlex$u1(), glyph.nyanlex$v0(), glyph.nyanlex$v1(),
                    glyph.nyanlex$left(), glyph.nyanlex$right(),
                    glyph.nyanlex$up() + shift, glyph.nyanlex$down() + shift);
        }
        return new Shifted(base, shift);
    }

    private static boolean isMissing(BakedGlyph glyph) {
        return glyph == null || glyph.info() == SpecialGlyphs.MISSING;
    }

    private static final class Provider implements GlyphProvider {
        private final FontSet owner;
        private final String fontId;
        /** The custom font's own providers (this provider excluded). */
        private final List<GlyphProvider> own;

        Provider(FontSet owner, String fontId, List<GlyphProvider> own) {
            this.owner = owner;
            this.fontId = fontId;
            this.own = own;
        }

        @Override
        public IntSet getSupportedGlyphs() {
            // Nothing is registered up front, so the font's width table and obfuscation
            // glyph pool stay exactly what the font itself defines.
            return IntSets.EMPTY_SET;
        }

        @Override
        public UnbakedGlyph getGlyph(int codePoint) {
            if (!GlyphBorrowPolicy.isBorrowable(codePoint)) {
                return null;
            }
            FontSet donor = defaultSet;
            if (donor == null || donor == owner) {
                return null;
            }
            if (!HookGuard.enter(HOOK_PROVIDE)) {
                return null;
            }
            try {
                BakedGlyph glyph = donor.source(false).getGlyph(codePoint);
                if (isMissing(glyph)) {
                    return null;
                }
                return new Borrowed(this, donor, codePoint, glyph);
            } catch (Throwable guardError) {
                HookGuard.fail(HOOK_PROVIDE, guardError);
                return null;
            }
        }

        /** Top of the custom font's own glyph for {@code codePoint}; NaN when it has none. */
        float ownTop(int codePoint) {
            for (GlyphProvider provider : own) {
                if (provider.getGlyph(codePoint) != null) {
                    return topOf(owner.source(false).getGlyph(codePoint));
                }
            }
            return Float.NaN;
        }

        float shiftAgainst(FontSet donor) {
            return SHIFTS.shiftFor(fontId, this::ownTop, codePoint -> {
                BakedGlyph glyph = donor.source(false).getGlyph(codePoint);
                return isMissing(glyph) ? Float.NaN : topOf(glyph);
            });
        }
    }

    /** A donor glyph as seen from the custom font: donor metrics, donor image, moved vertically. */
    private static final class Borrowed implements UnbakedGlyph {
        private final Provider provider;
        private final FontSet donor;
        private final int codePoint;
        private final BakedGlyph donorGlyph;

        Borrowed(Provider provider, FontSet donor, int codePoint, BakedGlyph donorGlyph) {
            this.provider = provider;
            this.donor = donor;
            this.codePoint = codePoint;
            this.donorGlyph = donorGlyph;
        }

        @Override
        public GlyphInfo info() {
            return donorGlyph.info();
        }

        @Override
        public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
            if (HookGuard.enter(HOOK_BAKE)) {
                try {
                    float shift = provider.shiftAgainst(donor);
                    return shift == 0.0F ? donorGlyph : shifted(donorGlyph, shift);
                } catch (Throwable guardError) {
                    HookGuard.fail(HOOK_BAKE, guardError);
                }
            }
            // Same result the font would have produced without borrowing.
            return stitcher.getMissing();
        }
    }

    /** Fallback for glyph kinds that cannot be re-created: drawn {@code shift} pixels lower, same metrics. */
    private static final class Shifted implements BakedGlyph {
        private final BakedGlyph base;
        private final float shift;

        Shifted(BakedGlyph base, float shift) {
            this.base = base;
            this.shift = shift;
        }

        @Override
        public GlyphInfo info() {
            return base.info();
        }

        @Override
        public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style,
                                                 float boldOffset, float shadowOffset) {
            return base.createGlyph(x, y + shift, color, shadowColor, style, boldOffset, shadowOffset);
        }
    }
}
