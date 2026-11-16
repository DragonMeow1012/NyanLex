package com.dragonmeow.nyanlex.neoforge26.mixin;

import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to a baked glyph's render types, texture rectangle and quad edges, so a glyph
 * borrowed from another font can be re-created with a vertical offset (the fields are private and
 * final). Re-creating it (instead of only moving where it is drawn) keeps italic slant, which is
 * computed from the quad's own vertical position, identical to the font's own letters.
 *
 * <p>The texture view field has no accessor on purpose: its type is in a different package in
 * 26.2 and 26.3, and {@code BorrowedGlyphs} reads the same view from a drawn instance instead.</p>
 */
@Mixin(BakedSheetGlyph.class)
public interface BakedSheetGlyphAccessor {

    @Accessor("renderTypes")
    GlyphRenderTypes nyanlex$renderTypes();

    @Accessor("u0")
    float nyanlex$u0();

    @Accessor("u1")
    float nyanlex$u1();

    @Accessor("v0")
    float nyanlex$v0();

    @Accessor("v1")
    float nyanlex$v1();

    @Accessor("left")
    float nyanlex$left();

    @Accessor("right")
    float nyanlex$right();

    @Accessor("up")
    float nyanlex$up();

    @Accessor("down")
    float nyanlex$down();
}
