package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.translate.GlyphBorrowPolicy;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.RawGlyph;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.MissingGlyph;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>This Minecraft version keeps glyphs as {@link RawGlyph}s (metrics plus the pixels to upload)
 * that the owning font set stitches into its own texture; the vertical shift is therefore applied
 * to the raw glyph's top and bottom edges, and the donor glyph's pixels are uploaded unchanged.</p>
 */
public final class BorrowedGlyphs {

    private static final String HOOK_PROVIDE = "FontBorrow.provide";
    private static final String HOOK_SHIFT = "FontBorrow.shift";

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

    /** The donor's own glyph for {@code codePoint}, or {@code null} for a blank, missing or unknown one. */
    private static RawGlyph donorGlyph(FontSet donor, int codePoint) {
        GlyphInfo info = donor.getGlyphInfo(codePoint);
        if (info instanceof RawGlyph raw && raw != MissingGlyph.INSTANCE) {
            return raw;
        }
        return null;
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
        public RawGlyph getGlyph(int codePoint) {
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
                RawGlyph glyph = donorGlyph(donor, codePoint);
                return glyph == null ? null : new Borrowed(this, donor, glyph);
            } catch (Throwable guardError) {
                HookGuard.fail(HOOK_PROVIDE, guardError);
                return null;
            }
        }

        /** Top of the custom font's own glyph for {@code codePoint}; NaN when it has none. */
        float ownTop(int codePoint) {
            for (GlyphProvider provider : own) {
                RawGlyph glyph = provider.getGlyph(codePoint);
                if (glyph != null) {
                    return glyph.getUp();
                }
            }
            return Float.NaN;
        }

        float shiftAgainst(FontSet donor) {
            return SHIFTS.shiftFor(fontId, this::ownTop, codePoint -> {
                RawGlyph glyph = donorGlyph(donor, codePoint);
                return glyph == null ? Float.NaN : glyph.getUp();
            });
        }
    }

    /** A donor glyph seen from the custom font: donor metrics, donor image, moved vertically. */
    private static final class Borrowed implements RawGlyph {
        private final Provider provider;
        private final FontSet donor;
        private final RawGlyph glyph;

        Borrowed(Provider provider, FontSet donor, RawGlyph glyph) {
            this.provider = provider;
            this.donor = donor;
            this.glyph = glyph;
        }

        /** Vertical move (negative = up); 0 when the measurement fails, which leaves the donor glyph in place. */
        private float shift() {
            if (HookGuard.enter(HOOK_SHIFT)) {
                try {
                    return provider.shiftAgainst(donor);
                } catch (Throwable guardError) {
                    HookGuard.fail(HOOK_SHIFT, guardError);
                }
            }
            return 0.0F;
        }

        @Override
        public int getPixelWidth() {
            return glyph.getPixelWidth();
        }

        @Override
        public int getPixelHeight() {
            return glyph.getPixelHeight();
        }

        @Override
        public void upload(int x, int y) {
            glyph.upload(x, y);
        }

        @Override
        public boolean isColored() {
            return glyph.isColored();
        }

        @Override
        public float getOversample() {
            return glyph.getOversample();
        }

        @Override
        public float getAdvance() {
            return glyph.getAdvance();
        }

        @Override
        public float getAdvance(boolean bold) {
            return glyph.getAdvance(bold);
        }

        @Override
        public float getBoldOffset() {
            return glyph.getBoldOffset();
        }

        @Override
        public float getShadowOffset() {
            return glyph.getShadowOffset();
        }

        @Override
        public float getBearingX() {
            return glyph.getBearingX();
        }

        @Override
        public float getBearingY() {
            return glyph.getBearingY() + shift();
        }

        @Override
        public float getLeft() {
            return glyph.getLeft();
        }

        @Override
        public float getRight() {
            return glyph.getRight();
        }

        @Override
        public float getUp() {
            return glyph.getUp() + shift();
        }

        @Override
        public float getDown() {
            return glyph.getDown() + shift();
        }
    }
}
