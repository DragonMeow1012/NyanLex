package com.dragonmeow.nyanlex.fabric.mixin;

import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A font set does not keep its own id; the glyph stitcher created for it does (it names the
 * font's textures after it), so custom fonts are told apart from the game's own through it.
 */
@Mixin(GlyphStitcher.class)
public interface GlyphStitcherAccessor {

    @Accessor("texturePrefix")
    Identifier nyanlex$texturePrefix();
}
