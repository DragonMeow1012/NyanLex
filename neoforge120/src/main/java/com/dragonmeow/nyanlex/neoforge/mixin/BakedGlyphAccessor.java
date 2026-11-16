package com.dragonmeow.nyanlex.neoforge.mixin;

import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to a baked glyph's texture rectangle and quad edges, so a glyph borrowed from
 * another font can be re-created with a vertical offset (the fields are private and final).
 */
@Mixin(BakedGlyph.class)
public interface BakedGlyphAccessor {

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
