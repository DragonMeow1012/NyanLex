package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.fabric.mixin.BakedGlyphAccessor;
import com.dragonmeow.nyanlex.translate.GlyphBorrowPolicy;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Lets a custom (non-vanilla) font show characters it has no glyph for by borrowing the vanilla
 * default font's glyph, moved vertically so it lines up with the custom font's own Latin letters.
 *
 * <p>Wiring: {@code FontSetBorrowMixin} appends one {@link Provider} as the LAST glyph provider
 * of every custom font set. A font set only asks its last provider for a character when none of
 * its own providers has it, so characters the font defines are never affected, and both the
 * advance used for layout (width) and the glyph used for drawing come from the same
 * {@link Borrowed} object. Vanilla fonts get no provider at all. Every entry point is
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

    /** A font set finished (re)loading: forget measured shifts and remember the donor font. */
    public static void fontReloaded(FontSet set, ResourceLocation id) {
        SHIFTS.clear();
        if (GlyphBorrowPolicy.isDefaultFont(id.getNamespace(), id.getPath())) {
            defaultSet = set;
        }
    }

    /**
     * The provider list to use for {@code owner}: {@code own} unchanged for vanilla fonts,
     * otherwise {@code own} plus the borrowing provider at the very end.
     */
    public static List<GlyphProvider> withBorrowing(FontSet owner, ResourceLocation id, List<GlyphProvider> own) {
        if (GlyphBorrowPolicy.isVanillaFont(id.getNamespace(), id.getPath())) {
            return own;
        }
        List<GlyphProvider> all = new ArrayList<>(own.size() + 1);
        all.addAll(own);
        all.add(new Provider(owner, id.toString(), List.copyOf(own)));
        return List.copyOf(all);
    }

    /** Top edge of a baked glyph relative to the text origin, NaN for blank (space) glyphs. */
    private static float topOf(BakedGlyph glyph) {
        if (glyph == null || glyph instanceof EmptyGlyph) {
            return Float.NaN;
        }
        return ((BakedGlyphAccessor) glyph).nyanlex$up();
    }

    /** Copy of {@code base} moved down by {@code shift} (negative = up); same texture and size. */
    private static BakedGlyph shifted(BakedGlyph base, float shift) {
        if (shift == 0.0F || base instanceof EmptyGlyph) {
            return base;
        }
        BakedGlyphAccessor glyph = (BakedGlyphAccessor) base;
        return new BakedGlyph(
                glyph.nyanlex$normalType(), glyph.nyanlex$seeThroughType(), glyph.nyanlex$polygonOffsetType(),
                glyph.nyanlex$u0(), glyph.nyanlex$u1(), glyph.nyanlex$v0(), glyph.nyanlex$v1(),
                glyph.nyanlex$left(), glyph.nyanlex$right(),
                glyph.nyanlex$up() + shift, glyph.nyanlex$down() + shift);
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
        public GlyphInfo getGlyph(int codePoint) {
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
                GlyphInfo info = donor.getGlyphInfo(codePoint, false);
                if (info == null || info == SpecialGlyphs.MISSING) {
                    return null;
                }
                return new Borrowed(this, donor, codePoint, info);
            } catch (Throwable guardError) {
                HookGuard.fail(HOOK_PROVIDE, guardError);
                return null;
            }
        }

        /** Top of the custom font's own glyph for {@code codePoint}; NaN when it has none. */
        float ownTop(int codePoint) {
            for (GlyphProvider provider : own) {
                if (provider.getGlyph(codePoint) != null) {
                    return topOf(owner.getGlyph(codePoint));
                }
            }
            return Float.NaN;
        }

        float shiftAgainst(FontSet donor) {
            return SHIFTS.shiftFor(fontId, this::ownTop, codePoint -> {
                GlyphInfo info = donor.getGlyphInfo(codePoint, false);
                return info == null || info == SpecialGlyphs.MISSING ? Float.NaN : topOf(donor.getGlyph(codePoint));
            });
        }
    }

    /** A donor glyph seen from the custom font: donor metrics, donor image, moved vertically. */
    private static final class Borrowed implements GlyphInfo {
        private final Provider provider;
        private final FontSet donor;
        private final int codePoint;
        private final GlyphInfo info;

        Borrowed(Provider provider, FontSet donor, int codePoint, GlyphInfo info) {
            this.provider = provider;
            this.donor = donor;
            this.codePoint = codePoint;
            this.info = info;
        }

        @Override
        public float getAdvance() {
            return info.getAdvance();
        }

        @Override
        public float getAdvance(boolean bold) {
            return info.getAdvance(bold);
        }

        @Override
        public float getBoldOffset() {
            return info.getBoldOffset();
        }

        @Override
        public float getShadowOffset() {
            return info.getShadowOffset();
        }

        @Override
        public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> stitch) {
            if (HookGuard.enter(HOOK_BAKE)) {
                try {
                    return shifted(donor.getGlyph(codePoint), provider.shiftAgainst(donor));
                } catch (Throwable guardError) {
                    HookGuard.fail(HOOK_BAKE, guardError);
                }
            }
            // Same result the font would have produced without borrowing.
            return SpecialGlyphs.MISSING.bake(stitch);
        }
    }
}
